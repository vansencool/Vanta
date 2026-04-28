package net.vansencool.vanta.classpath;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Manages the classpath for resolving external class references.
 */
@SuppressWarnings("unused")
public final class ClasspathManager {

    private static final Method[] EMPTY_METHODS = new Method[0];
    private static final @NotNull Object FIELD_MISS = new Object();
    private final @NotNull List<Path> classpathEntries;
    private final @NotNull Map<String, ClassInfo> cache;
    private final @NotNull Map<String, Class<?>> classCache = new ConcurrentHashMap<>();
    private final @NotNull Set<String> missCache = ConcurrentHashMap.newKeySet();
    private final @NotNull AtomicBoolean skeletonsRegistered = new AtomicBoolean(false);
    private final @NotNull Map<String, byte[]> inMemoryClasses = new ConcurrentHashMap<>();
    private final @NotNull Map<Class<?>, Method[]> methodsCache = new ConcurrentHashMap<>();
    private final @NotNull Map<Class<?>, Map<String, Method[]>> methodsByNameCache = new ConcurrentHashMap<>();
    private final @NotNull Map<Method, String> methodDescriptorCache = new ConcurrentHashMap<>();
    private final @NotNull Map<Class<?>, String> classDescriptorCache = new ConcurrentHashMap<>();
    private final @NotNull Map<Class<?>, Map<String, Object>> fieldCache = new ConcurrentHashMap<>();
    private final @NotNull Map<String, Type[]> argumentTypesCache = new ConcurrentHashMap<>();
    private final @NotNull Map<Class<?>, Constructor<?>[]> ctorsCache = new ConcurrentHashMap<>();
    private final @NotNull Map<Long, String> commonSuperCache = new ConcurrentHashMap<>();
    private final @NotNull Map<String, AsmClassInfo> asmInfoCache = new ConcurrentHashMap<>();
    private final @NotNull Set<String> asmInfoMiss = ConcurrentHashMap.newKeySet();
    private volatile @Nullable ExecutorService sharedFilePool;
    private int sharedFilePoolSize;
    private volatile @Nullable ExecutorService sharedMethodPool;
    private int sharedMethodPoolSize;
    private @Nullable URLClassLoader userClassLoader;
    private @Nullable InMemoryClassLoader inMemoryLoaderInstance;

    /**
     * Creates a classpath manager with no entries.
     */
    public ClasspathManager() {
        this.classpathEntries = new ArrayList<>();
        this.cache = new ConcurrentHashMap<>();
    }

    /**
     * Returns public methods of the given class, or empty array if reflection triggers a LinkageError
     * (e.g. from transitively missing classes on the classpath).
     */
    public static Method @NotNull [] safeGetMethods(@NotNull Class<?> clazz) {
        try {
            return clazz.getMethods();
        } catch (LinkageError e) {
            return new Method[0];
        }
    }

    /**
     * Returns constructors of the given class, or empty array on LinkageError.
     */
    public static Constructor<?> @NotNull [] safeGetDeclaredConstructors(@NotNull Class<?> clazz) {
        try {
            return clazz.getDeclaredConstructors();
        } catch (LinkageError e) {
            return new Constructor<?>[0];
        }
    }

    /**
     * Gets a public field by name, returning null on missing or LinkageError.
     */
    public static @Nullable Field safeGetField(@NotNull Class<?> clazz, @NotNull String name) {
        try {
            return clazz.getField(name);
        } catch (NoSuchFieldException | LinkageError e) {
            return null;
        }
    }

    public @NotNull Map<Long, String> commonSuperCache() {
        return commonSuperCache;
    }

    /**
     * Pre-touches classes by internal name to warm classloader and reflection caches, and
     * populates the common-super cache so frame computation can avoid cold lookups.
     *
     * @param internalNames internal names to pre-load
     */
    public void prewarm(@NotNull String... internalNames) {
        for (String name : internalNames) {
            Class<?> c = loadClass(name);
            if (c != null) {
                cachedMethods(c);
                cachedDeclaredConstructors(c);
                precomputeSuperChain(c);
            }
        }
    }

    /**
     * Walks the full superclass chain for the given class and populates the common-super cache with
     * all pairs (class, ancestor) and (ancestor, class) entries.
     *
     * @param c the class whose ancestor chain to materialise
     */
    private void precomputeSuperChain(@NotNull Class<?> c) {
        String cInternal = c.getName().replace('.', '/');
        Class<?> cur = c.getSuperclass();
        while (cur != null) {
            String ancestor = cur.getName().replace('.', '/');
            long k1 = ((long) cInternal.hashCode() << 32) ^ (ancestor.hashCode() & 0xffffffffL);
            long k2 = ((long) ancestor.hashCode() << 32) ^ (cInternal.hashCode() & 0xffffffffL);
            commonSuperCache.putIfAbsent(k1, ancestor);
            commonSuperCache.putIfAbsent(k2, ancestor);
            cur = cur.getSuperclass();
        }
    }

    /**
     * Adds a classpath entry (jar or directory).
     *
     * @param path the path to add
     */
    public void addEntry(@NotNull Path path) {
        classpathEntries.add(path);
        userClassLoader = null;
    }

    /**
     * Adds a classpath entry from a file path.
     *
     * @param file the file (jar or directory) to add
     */
    public void addEntry(@NotNull File file) {
        addEntry(file.toPath());
    }

    /**
     * Adds a classpath entry from a URL pointing at a local file or directory.
     *
     * @param url the url to add
     */
    public void addEntry(@NotNull URL url) {
        try {
            addEntry(Paths.get(url.toURI()));
        } catch (Exception e) {
            addEntry(Paths.get(url.getPath()));
        }
    }

    /**
     * Walks a ClassLoader hierarchy and harvests URLs from any {@link URLClassLoader}
     * ancestors, adding them as classpath entries. This mirrors how the system
     * JavaCompiler discovers the caller's classpath from a parent ClassLoader.
     *
     * @param classLoader the starting class loader, or null for a no-op
     */
    public void addClassLoader(@Nullable ClassLoader classLoader) {
        for (ClassLoader cl = classLoader; cl != null; cl = cl.getParent()) {
            if (cl instanceof URLClassLoader ucl) {
                for (URL url : ucl.getURLs()) addEntry(url);
            }
        }
    }

    /**
     * Adds all entries from a classpath string using the platform path separator.
     *
     * @param classpath the classpath string (e.g. "foo.jar:bar/classes")
     */
    public void addClasspath(@NotNull String classpath) {
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isEmpty()) addEntry(Paths.get(entry));
        }
    }

    /**
     * @return an immutable snapshot of the current classpath entries
     */
    public @NotNull List<Path> entries() {
        return List.copyOf(classpathEntries);
    }

    /**
     * Resolves basic class information by internal name.
     * First checks the cache, then tries the system classloader,
     * then the user classpath.
     *
     * @param internalName the internal class name (e.g., "java/lang/String")
     * @return the class info, or null if not found
     */
    public @Nullable ClassInfo resolve(@NotNull String internalName) {
        ClassInfo cached = cache.get(internalName);
        if (cached != null) return cached;

        String className = internalName.replace('/', '.');
        try {
            Class<?> clazz = Class.forName(className, false, ClassLoader.getSystemClassLoader());
            ClassInfo info = ClassInfo.fromReflection(clazz);
            cache.put(internalName, info);
            return info;
        } catch (ClassNotFoundException | LinkageError ignored) {
        }

        try {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            if (contextLoader != null) {
                Class<?> clazz = Class.forName(className, false, contextLoader);
                ClassInfo info = ClassInfo.fromReflection(clazz);
                cache.put(internalName, info);
                return info;
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
        }

        try {
            URLClassLoader loader = userClassLoader();
            if (loader != null) {
                Class<?> clazz = Class.forName(className, false, loader);
                ClassInfo info = ClassInfo.fromReflection(clazz);
                cache.put(internalName, info);
                return info;
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
        }

        return null;
    }

    /**
     * Checks if a class exists on the classpath.
     *
     * @param internalName the internal name
     * @return true if the class can be resolved
     */
    public boolean exists(@NotNull String internalName) {
        return loadClass(internalName) != null;
    }

    public Method @NotNull [] cachedMethods(@NotNull Class<?> clazz) {
        Method[] m = methodsCache.get(clazz);
        if (m != null) return m;
        m = safeGetMethods(clazz);
        methodsCache.put(clazz, m);
        return m;
    }

    /**
     * Returns all methods on {@code clazz} that match {@code name}. Result is cached
     * per (class, name) so repeated lookups skip the full method-array scan.
     */
    public Method @NotNull [] methodsNamed(@NotNull Class<?> clazz, @NotNull String name) {
        Map<String, Method[]> byName = methodsByNameCache.get(clazz);
        if (byName == null) {
            byName = new ConcurrentHashMap<>();
            Map<String, Method[]> existing = methodsByNameCache.putIfAbsent(clazz, byName);
            if (existing != null) byName = existing;
        }
        Method[] hit = byName.get(name);
        if (hit != null) return hit;
        Method[] all = cachedMethods(clazz);
        int count = 0;
        for (Method m : all) if (m.getName().equals(name)) count++;
        if (count == 0) {
            byName.put(name, EMPTY_METHODS);
            return EMPTY_METHODS;
        }
        Method[] out = new Method[count];
        int idx = 0;
        for (Method m : all) if (m.getName().equals(name)) out[idx++] = m;
        byName.put(name, out);
        return out;
    }

    /**
     * Returns the JVM method descriptor for {@code m}, cached. Avoids the per-call
     * StringBuilder allocation in {@link Type#getMethodDescriptor}.
     */
    public @NotNull String methodDescriptor(@NotNull Method m) {
        String d = methodDescriptorCache.get(m);
        if (d != null) return d;
        d = Type.getMethodDescriptor(m);
        methodDescriptorCache.put(m, d);
        return d;
    }

    /**
     * Looks up a field by name, walking the public-field hierarchy then falling
     * back to declared-field search across the superclass chain. Caches both
     * hits and misses so repeated lookups skip the {@link NoSuchFieldException}
     * path that otherwise dominates CPU when hot call sites reference fields
     * declared on a non-public ancestor.
     */
    public @Nullable Field fieldByName(@NotNull Class<?> clazz, @NotNull String name) {
        Map<String, Object> byName = fieldCache.get(clazz);
        if (byName == null) {
            byName = new ConcurrentHashMap<>();
            Map<String, Object> existing = fieldCache.putIfAbsent(clazz, byName);
            if (existing != null) byName = existing;
        }
        Object cached = byName.get(name);
        if (cached == FIELD_MISS) return null;
        if (cached != null) return (Field) cached;
        Field f = null;
        try {
            f = clazz.getField(name);
        } catch (NoSuchFieldException | LinkageError ignored) {
        }
        if (f == null) {
            for (Class<?> c = clazz; c != null && f == null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField(name);
                } catch (NoSuchFieldException | LinkageError ignored) {
                }
            }
        }
        byName.put(name, f != null ? f : FIELD_MISS);
        return f;
    }

    /**
     * Returns the argument {@link Type}s of a method descriptor, cached so that
     * repeated overload-resolution and argument-emission passes avoid the
     * descriptor re-parse in {@link Type#getArgumentTypes(String)}.
     */
    public Type @NotNull [] argumentTypes(@NotNull String methodDescriptor) {
        Type[] cached = argumentTypesCache.get(methodDescriptor);
        if (cached != null) return cached;
        Type[] parsed = Type.getArgumentTypes(methodDescriptor);
        argumentTypesCache.put(methodDescriptor, parsed);
        return parsed;
    }

    /**
     * Returns the JVM type descriptor for a {@link Class}, cached to avoid the
     * {@link Type#getDescriptor(Class)} StringBuilder walk on hot paths.
     */
    public @NotNull String classDescriptor(@NotNull Class<?> c) {
        String d = classDescriptorCache.get(c);
        if (d != null) return d;
        d = Type.getDescriptor(c);
        classDescriptorCache.put(c, d);
        return d;
    }

    public Constructor<?> @NotNull [] cachedDeclaredConstructors(@NotNull Class<?> clazz) {
        Constructor<?>[] c = ctorsCache.get(clazz);
        if (c != null) return c;
        c = safeGetDeclaredConstructors(clazz);
        ctorsCache.put(clazz, c);
        return c;
    }

    public @Nullable Class<?> loadClass(@NotNull String internalName) {
        Class<?> cached = classCache.get(internalName);
        if (cached != null) return cached;
        if (missCache.contains(internalName)) return null;
        String resourceName = internalName + ".class";
        String className = internalName.replace('/', '.');
        Class<?> result = null;
        ClassLoader system = ClassLoader.getSystemClassLoader();
        if (system != null && system.getResource(resourceName) != null) {
            try {
                result = Class.forName(className, false, system);
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
        if (result == null) {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            if (context != null && context != system && context.getResource(resourceName) != null) {
                try {
                    result = Class.forName(className, false, context);
                } catch (ClassNotFoundException | LinkageError ignored) {
                }
            }
        }
        if (result == null) {
            URLClassLoader user = userClassLoader();
            if (user != null && user.getResource(resourceName) != null) {
                try {
                    result = Class.forName(className, false, user);
                } catch (ClassNotFoundException | LinkageError ignored) {
                }
            }
        }
        if (result == null && inMemoryClasses.containsKey(internalName)) {
            try {
                result = Class.forName(className, false, inMemoryLoader());
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
        if (result != null) classCache.put(internalName, result);
        else if (!inMemoryClasses.containsKey(internalName)) missCache.add(internalName);
        return result;
    }

    /**
     * Returns a lazily-created classloader that defines classes from the
     * in-memory bytes registered via {@link #registerInMemoryClass}. Used by
     * lambda codegen and other reflection-driven paths that require a real
     * {@link Class} object for a type the compiler has only produced
     * skeleton bytes for so far.
     */
    private @NotNull InMemoryClassLoader inMemoryLoader() {
        if (inMemoryLoaderInstance == null) {
            ClassLoader parent = userClassLoader();
            if (parent == null) parent = Thread.currentThread().getContextClassLoader();
            if (parent == null) parent = ClassLoader.getSystemClassLoader();
            inMemoryLoaderInstance = new InMemoryClassLoader(parent);
        }
        return inMemoryLoaderInstance;
    }

    /**
     * Returns ASM-parsed class info for {@code internalName} by reading the {@code .class}
     * bytes directly from classpath entries. This bypasses {@link Class#forName}, so it
     * succeeds even when the class would otherwise fail to link due to missing transitive
     * dependencies. Returns {@code null} if no classpath entry contains the class.
     */
    public @Nullable AsmClassInfo asmClassInfo(@NotNull String internalName) {
        AsmClassInfo cached = asmInfoCache.get(internalName);
        if (cached != null) return cached;
        if (asmInfoMiss.contains(internalName)) return null;
        byte[] bytes = readClassBytes(internalName);
        if (bytes == null) {
            asmInfoMiss.add(internalName);
            return null;
        }
        AsmClassInfo info = AsmClassInfo.parse(bytes);
        if (info == null) {
            asmInfoMiss.add(internalName);
            return null;
        }
        asmInfoCache.put(internalName, info);
        return info;
    }

    /**
     * Atomically claims the skeleton-registration slot for this classpath manager.
     * Returns {@code true} the first time it is called (so the caller should do
     * the skeleton emit + register) and {@code false} on every subsequent call,
     * letting the warm benchmark path skip the several-millisecond skeleton
     * pre-pass on iterations 2..N.
     */
    public boolean markSkeletonsRegistered() {
        return skeletonsRegistered.compareAndSet(false, true);
    }

    /**
     * Returns a cached file-level worker pool sized to {@code workers}. Kept
     * separate from {@link #sharedMethodPool(int)} so the two pools never
     * interfere when hybrid parallelism is active.
     */
    public @NotNull ExecutorService sharedFilePool(int workers) {
        ExecutorService existing = sharedFilePool;
        if (existing != null && sharedFilePoolSize == workers) return existing;
        synchronized (this) {
            ExecutorService current = sharedFilePool;
            if (current != null && sharedFilePoolSize == workers) return current;
            if (current != null) current.shutdown();
            ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
                Thread t = new Thread(r, "Vanta-File-Worker");
                t.setDaemon(true);
                return t;
            });
            sharedFilePool = pool;
            sharedFilePoolSize = workers;
            return pool;
        }
    }

    /**
     * Returns a cached method-level worker pool sized to {@code workers}. Kept
     * separate from {@link #sharedFilePool(int)} so the two pools never
     * interfere when hybrid parallelism is active.
     */
    public @NotNull ExecutorService sharedMethodPool(int workers) {
        ExecutorService existing = sharedMethodPool;
        if (existing != null && sharedMethodPoolSize == workers) return existing;
        synchronized (this) {
            ExecutorService current = sharedMethodPool;
            if (current != null && sharedMethodPoolSize == workers) return current;
            if (current != null) current.shutdown();
            ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
                Thread t = new Thread(r, "Vanta-Method-Worker");
                t.setDaemon(true);
                return t;
            });
            sharedMethodPool = pool;
            sharedMethodPoolSize = workers;
            return pool;
        }
    }

    /**
     * Registers raw class bytes under an internal name so subsequent lookups via
     * {@link #asmClassInfo(String)} succeed without touching disk. Used by the compiler
     * to expose just-generated classes to peers in the same compilation batch.
     */
    public void registerInMemoryClass(@NotNull String internalName, byte @NotNull [] bytes) {
        boolean alreadyHad = inMemoryClasses.put(internalName, bytes) != null;
        asmInfoCache.remove(internalName);
        asmInfoMiss.remove(internalName);
        if (!alreadyHad) missCache.remove(internalName);
        // Keep any previously-defined Class<?> intact so in-flight parallel workers holding a
        // reference to the in-memory loader continue to see the same Class and do not trigger
        // LinkageError from redefinition attempts. The ASM-level caches above still update so
        // subsequent inference uses the real bytes.
    }

    private byte @Nullable [] readClassBytes(@NotNull String internalName) {
        String resourcePath = internalName + ".class";
        for (Path entry : classpathEntries) {
            File f = entry.toFile();
            if (!f.exists()) continue;
            if (f.isDirectory()) {
                Path candidate = entry.resolve(resourcePath);
                if (Files.isRegularFile(candidate)) {
                    try {
                        return Files.readAllBytes(candidate);
                    } catch (IOException ignored) {
                    }
                }
            } else if (f.getName().endsWith(".jar") || f.getName().endsWith(".zip")) {
                try (ZipFile zip = new ZipFile(f)) {
                    ZipEntry e = zip.getEntry(resourcePath);
                    if (e != null) {
                        try (var in = zip.getInputStream(e)) {
                            return in.readAllBytes();
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return inMemoryClasses.get(internalName);
    }

    /**
     * Gets or creates the user class loader from classpath entries.
     *
     * @return the user class loader, or null if no entries
     */
    private @Nullable URLClassLoader userClassLoader() {
        if (userClassLoader != null) return userClassLoader;
        if (classpathEntries.isEmpty()) return null;
        try {
            URL[] urls = new URL[classpathEntries.size()];
            for (int i = 0; i < classpathEntries.size(); i++) {
                urls[i] = classpathEntries.get(i).toUri().toURL();
            }
            userClassLoader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
            return userClassLoader;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Classloader that defines classes on demand from the
     * {@link #inMemoryClasses} map. Delegates anything it has not been
     * registered for to its parent so already-loaded JDK and user classpath
     * entries keep their normal resolution.
     */
    private final class InMemoryClassLoader extends ClassLoader {
        InMemoryClassLoader(@NotNull ClassLoader parent) {
            super(parent);
        }

        @Override
        protected @NotNull Class<?> findClass(@NotNull String name) throws ClassNotFoundException {
            String internal = name.replace('.', '/');
            byte[] bytes = inMemoryClasses.get(internal);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
