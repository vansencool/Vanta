package net.vansencool.vanta;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.SkeletonGenerator;
import net.vansencool.vanta.codegen.exception.CodeGenException;
import net.vansencool.vanta.exception.CompilationException;
import net.vansencool.vanta.lexer.Lexer;
import net.vansencool.vanta.lexer.exception.LexerException;
import net.vansencool.vanta.lexer.token.Token;
import net.vansencool.vanta.parser.Parser;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.CompilationUnit;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.parser.exception.ParserException;
import net.vansencool.vanta.resolver.TypeResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URLClassLoader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main compiler entry point. Compiles Java source code into bytecode.
 */
public record VantaCompiler(@NotNull ClasspathManager classpathManager) {

    /**
     * Creates a compiler with no additional classpath entries.
     */
    public VantaCompiler() {
        this(new ClasspathManager());
    }

    /**
     * Creates a compiler that discovers its classpath from the given ClassLoader
     * hierarchy. Any {@link URLClassLoader} ancestors contribute their URLs.
     *
     * @param classLoader the class loader to harvest classpath URLs from
     * @return a configured compiler
     */
    public static @NotNull VantaCompiler withClassLoader(@NotNull ClassLoader classLoader) {
        ClasspathManager cp = new ClasspathManager();
        cp.addClassLoader(classLoader);
        return new VantaCompiler(cp);
    }

    /**
     * Creates a compiler seeded with a classpath string (platform-separated entries).
     *
     * @param classpath the classpath string
     * @return a configured compiler
     */
    public static @NotNull VantaCompiler withClasspath(@NotNull String classpath) {
        ClasspathManager cp = new ClasspathManager();
        cp.addClasspath(classpath);
        return new VantaCompiler(cp);
    }

    private static void registerInnerClasses(@NotNull TypeResolver typeResolver, @NotNull ClassDeclaration classDecl, @NotNull String outerInternalName) {
        for (AstNode member : classDecl.members()) {
            if (member instanceof ClassDeclaration inner) {
                typeResolver.registerInnerClass(inner.name(), outerInternalName);
            }
        }
        registerInheritedInnerClasses(typeResolver, classDecl);
    }

    /**
     * Walks the declared superclass and interface chain of {@code classDecl} and
     * registers their declared nested type names so unqualified references inside
     * the body (e.g. {@code Entry} from {@code Map.Entry} inherited via
     * {@code AbstractMap}) resolve to the inherited type rather than some other
     * nested class that happens to share the simple name.
     */
    private static void registerInheritedInnerClasses(@NotNull TypeResolver typeResolver, @NotNull ClassDeclaration classDecl) {
        List<TypeNode> supers = new ArrayList<>();
        if (classDecl.superClass() != null) supers.add(classDecl.superClass());
        supers.addAll(classDecl.interfaces());
        for (TypeNode sup : supers) {
            String supInternal = typeResolver.resolveInternalName(sup);
            collectInheritedInnerNames(typeResolver, supInternal);
        }
    }

    /**
     * Walks the super chain for {@code ownerInternal} via reflection and
     * registers every nested type it declares into the type resolver. Stops
     * cycles via a visited set. Falls back silently when a class cannot be
     * loaded.
     */
    private static void collectInheritedInnerNames(@NotNull TypeResolver typeResolver, @NotNull String ownerInternal) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.push(ownerInternal);
        while (!queue.isEmpty()) {
            String cur = queue.pop();
            if (!visited.add(cur)) continue;
            Class<?> clazz = typeResolver.classpathManager().loadClass(cur);
            if (clazz == null) continue;
            try {
                for (Class<?> nested : clazz.getDeclaredClasses()) {
                    String simple = nested.getSimpleName();
                    String internal = nested.getName().replace('.', '/');
                    typeResolver.registerInheritedInnerClass(simple, internal);
                }
                Class<?> sc = clazz.getSuperclass();
                if (sc != null) queue.push(sc.getName().replace('.', '/'));
                for (Class<?> i : clazz.getInterfaces()) queue.push(i.getName().replace('.', '/'));
            } catch (LinkageError ignored) {
            }
        }
    }

    /**
     * Compiles Java source code into bytecode.
     * Returns a map of class internal names to their compiled bytecode.
     * A single source file may produce multiple classes (e.g., inner classes, anonymous classes).
     *
     * @param source     the Java source code
     * @param sourceFile the source file name for debug info, or null
     * @return map from class internal name to bytecode bytes
     */
    public @NotNull Map<String, byte[]> compile(@NotNull String source, @Nullable String sourceFile) {
        try {
            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.tokenize();
            Parser parser = new Parser(tokens);
            CompilationUnit cu = parser.parse();

            TypeResolver typeResolver = new TypeResolver(classpathManager, cu.imports(), cu.packageName());
            ClassGenerator classGenerator = new ClassGenerator(classpathManager, typeResolver, sourceFile);

            Map<String, byte[]> result = new HashMap<>();

            for (AstNode typeDecl : cu.typeDeclarations()) {
                if (typeDecl instanceof ClassDeclaration classDecl) {
                    String outerInternalName = toInternalName(classDecl.name(), cu.packageName());
                    registerInnerClasses(typeResolver, classDecl, outerInternalName);
                    Map<String, byte[]> innerBytecodes = classGenerator.generateInnerClasses(classDecl, cu.packageName());
                    for (var e : innerBytecodes.entrySet())
                        classpathManager.registerInMemoryClass(e.getKey(), e.getValue());
                    byte[] bytecode = classGenerator.generate(classDecl, cu.packageName());
                    classpathManager.registerInMemoryClass(outerInternalName, bytecode);
                    result.put(outerInternalName, bytecode);
                    result.putAll(innerBytecodes);
                    Map<String, byte[]> anon = classGenerator.getAndClearAnonClassBytecodes();
                    for (var e : anon.entrySet()) classpathManager.registerInMemoryClass(e.getKey(), e.getValue());
                    result.putAll(anon);
                }
            }

            return result;
        } catch (LexerException e) {
            throw new CompilationException(CompilationException.formatError(source, sourceFile, e.getMessage(), e.line(), e.column(), "Lexer"), e.line(), e.column(), e);
        } catch (ParserException e) {
            throw new CompilationException(CompilationException.formatError(source, sourceFile, e.rawMessage(), e.line(), e.column(), "Parser"), e.line(), e.column(), e);
        } catch (CodeGenException e) {
            throw new CompilationException(CompilationException.formatError(source, sourceFile, e.rawMessage(), e.line(), 1, "CodeGen"), e.line(), 1, e);
        } catch (RuntimeException e) {
            System.err.println("[Vanta] Unexpected failure compiling " + sourceFile + ": " + e);
            e.printStackTrace(System.err);
            throw e;
        }
    }

    /**
     * Compiles multiple Java source files. Each file is compiled independently
     * with its own imports and package context.
     *
     * @param sources map from source file path to source code
     * @return map from class internal name to bytecode bytes
     */
    public @NotNull Map<String, byte[]> compileAll(@NotNull Map<String, String> sources) {
        if (classpathManager.markSkeletonsRegistered()) {
            registerSignatureSkeletons(sources);
        }
        Map<String, byte[]> result = new HashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String path = entry.getKey();
            String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            result.putAll(compile(entry.getValue(), fileName));
        }
        return result;
    }

    /**
     * Parses {@code sources} and registers a signature-only skeleton class
     * for every declared type so subsequent {@link #compile} calls resolve
     * cross-file references against real classpath entries instead of
     * silently falling back to {@code Object}. Safe to call once before a
     * batch of per-file {@link #compile} invocations when a driver cannot
     * use {@link #compileAll} directly.
     */
    public void registerSignatureSkeletons(@NotNull Map<String, String> sources) {
        SkeletonGenerator skeletonGen = new SkeletonGenerator();
        List<CompilationUnit> parsed = new ArrayList<>(sources.size());
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            try {
                List<Token> tokens = new Lexer(entry.getValue()).tokenize();
                CompilationUnit cu = new Parser(tokens).parse();
                skeletonGen.registerBatchTypes(cu);
                parsed.add(cu);
            } catch (LexerException | ParserException ignored) {
                // Let the full compile pass surface the error with a proper message.
            }
        }
        for (CompilationUnit cu : parsed) {
            Map<String, byte[]> skeletons = skeletonGen.emit(cu);
            for (Map.Entry<String, byte[]> sk : skeletons.entrySet()) {
                classpathManager.registerInMemoryClass(sk.getKey(), sk.getValue());
            }
        }
    }

    /**
     * Parallel variant of {@link #registerSignatureSkeletons}. Parsing + skeleton
     * emission are independent per file, so the whole pre-pass scales with the
     * worker count instead of serialising on a single thread. The batch-type
     * registration inside the shared {@link SkeletonGenerator}
     * has to see every unit before any emission runs, so that step stays
     * sequential on the submitting thread.
     */
    public void registerSignatureSkeletonsParallel(@NotNull Map<String, String> sources, int workers) {
        if (sources.size() <= 1 || workers <= 1) {
            registerSignatureSkeletons(sources);
            return;
        }
        SkeletonGenerator skeletonGen = new SkeletonGenerator();
        List<Map.Entry<String, String>> entries = new ArrayList<>(sources.entrySet());
        CompilationUnit[] parsedArr = new CompilationUnit[entries.size()];
        ExecutorService parsePool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "Vanta-Skel-Parse");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> parseFutures = new ArrayList<>(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                final int idx = i;
                parseFutures.add(parsePool.submit(() -> {
                    try {
                        List<Token> tokens = new Lexer(entries.get(idx).getValue()).tokenize();
                        parsedArr[idx] = new Parser(tokens).parse();
                    } catch (LexerException | ParserException ignored) {
                    }
                }));
            }
            for (Future<?> f : parseFutures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
        } finally {
            parsePool.shutdown();
        }
        for (CompilationUnit cu : parsedArr) if (cu != null) skeletonGen.registerBatchTypes(cu);
        byte[][][] emitted = new byte[parsedArr.length][][];
        String[][] emittedNames = new String[parsedArr.length][];
        ExecutorService emitPool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "Vanta-Skel-Emit");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> emitFutures = new ArrayList<>(parsedArr.length);
            for (int i = 0; i < parsedArr.length; i++) {
                if (parsedArr[i] == null) continue;
                final int idx = i;
                emitFutures.add(emitPool.submit(() -> {
                    Map<String, byte[]> skeletons = skeletonGen.emit(parsedArr[idx]);
                    String[] names = new String[skeletons.size()];
                    byte[][] bytes = new byte[skeletons.size()][];
                    int k = 0;
                    for (Map.Entry<String, byte[]> sk : skeletons.entrySet()) {
                        names[k] = sk.getKey();
                        bytes[k] = sk.getValue();
                        k++;
                    }
                    emittedNames[idx] = names;
                    emitted[idx] = bytes;
                }));
            }
            for (Future<?> f : emitFutures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
        } finally {
            emitPool.shutdown();
        }
        for (int i = 0; i < emitted.length; i++) {
            if (emitted[i] == null) continue;
            for (int j = 0; j < emitted[i].length; j++) {
                classpathManager.registerInMemoryClass(emittedNames[i][j], emitted[i][j]);
            }
        }
    }

    /**
     * Compiles {@code sources} according to the given {@link ParallelMode}.
     * File workers and method workers use separate thread pools so hybrid
     * modes never cause one pool to shut down while the other is still active.
     * Smart mode auto-tunes: heavy files (above 2x median source length) receive
     * extra method workers; light files get file-level parallelism only.
     *
     * @param sources map from source file path to source code
     * @param mode    parallelism strategy
     * @return map from class internal name to bytecode bytes
     */
    public @NotNull Map<String, byte[]> compileAllParallel(@NotNull Map<String, String> sources, @NotNull ParallelMode mode) {
        if (mode.isSmart()) return compileAllSmart(sources, mode.maxThreads(), mode.heavyPercentile());
        int fileWorkers = Math.min(mode.fileWorkers(), sources.size());
        if (sources.size() <= 1 || fileWorkers <= 1) {
            MethodParallelism.current(mode.methodWorkers());
            try {
                return compileAll(sources);
            } finally {
                MethodParallelism.clear();
            }
        }
        if (classpathManager.markSkeletonsRegistered()) {
            registerSignatureSkeletonsParallel(sources, fileWorkers);
        }
        MethodParallelism.current(mode.methodWorkers());
        try {
            Map<String, byte[]> result = new ConcurrentHashMap<>();
            AtomicReference<RuntimeException> firstFailure = new AtomicReference<>();
            ExecutorService pool = classpathManager.sharedFilePool(fileWorkers);
            List<Map.Entry<String, String>> ordered = new ArrayList<>(sources.entrySet());
            ordered.sort(new LongestFirst());
            List<Future<?>> futures = new ArrayList<>(sources.size());
            for (Map.Entry<String, String> entry : ordered) {
                futures.add(pool.submit(() -> {
                    if (firstFailure.get() != null) return;
                    String path = entry.getKey();
                    String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                    try {
                        result.putAll(compile(entry.getValue(), fileName));
                    } catch (RuntimeException e) {
                        firstFailure.compareAndSet(null, e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
            if (firstFailure.get() != null) throw firstFailure.get();
            return result;
        } finally {
            MethodParallelism.clear();
        }
    }

    /**
     * Smart-mode compilation. Uses work-stealing via one-task-per-file dispatch.
     * Files whose source length is dramatically larger than the median (outliers)
     * receive dedicated method workers; everything else runs pure file-parallel.
     * File worker count is clamped to min(maxThreads, 8) because past 8 workers
     * gains are noise and queue contention rises.
     */
    private @NotNull Map<String, byte[]> compileAllSmart(@NotNull Map<String, String> sources, int maxThreads, int heavyPercentile) {
        if (sources.size() <= 1) return compileAll(sources);
        int fileWorkers = Math.min(Math.min(maxThreads, 8), sources.size());
        if (fileWorkers <= 1) return compileAll(sources);
        if (classpathManager.markSkeletonsRegistered()) {
            registerSignatureSkeletonsParallel(sources, fileWorkers);
        }
        int[] lengths = sources.values().stream().mapToInt(String::length).sorted().toArray();
        int median = lengths[lengths.length / 2];
        int p95 = lengths[Math.min(lengths.length - 1, (int) (lengths.length * 0.95))];
        boolean worthHeavy = p95 >= median * 4;
        int threshold = worthHeavy ? lengths[(int) (lengths.length * heavyPercentile / 100.0)] : Integer.MAX_VALUE;
        Set<String> heavyKeys;
        int methodWorkersForHeavy;
        if (worthHeavy) {
            heavyKeys = new HashSet<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                if (e.getValue().length() >= threshold) heavyKeys.add(e.getKey());
            }
            int spare = Math.max(0, maxThreads - fileWorkers);
            methodWorkersForHeavy = heavyKeys.isEmpty() ? 1 : Math.max(2, 1 + spare / heavyKeys.size());
        } else {
            heavyKeys = Collections.emptySet();
            methodWorkersForHeavy = 1;
        }
        Map<String, byte[]> result = new ConcurrentHashMap<>();
        AtomicReference<RuntimeException> firstFailure = new AtomicReference<>();
        ExecutorService pool = classpathManager.sharedFilePool(fileWorkers);
        List<Map.Entry<String, String>> ordered = new ArrayList<>(sources.entrySet());
        ordered.sort(new LongestFirst());
        List<Future<?>> futures = new ArrayList<>(sources.size());
        for (Map.Entry<String, String> entry : ordered) {
            int mw = heavyKeys.contains(entry.getKey()) ? methodWorkersForHeavy : 1;
            futures.add(pool.submit(() -> {
                if (firstFailure.get() != null) return;
                String path = entry.getKey();
                String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                if (mw > 1) MethodParallelism.current(mw);
                try {
                    result.putAll(compile(entry.getValue(), fileName));
                } catch (RuntimeException e) {
                    firstFailure.compareAndSet(null, e);
                } finally {
                    if (mw > 1) MethodParallelism.clear();
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        if (firstFailure.get() != null) throw firstFailure.get();
        return result;
    }

    /**
     * Compiles Java source code and returns the bytecode for a single class.
     * Convenience method for source files that contain exactly one type declaration.
     *
     * @param source     the Java source code
     * @param sourceFile the source file name for debug info, or null
     * @return the bytecode bytes
     */
    public byte @NotNull [] compileSingle(@NotNull String source, @Nullable String sourceFile) {
        Map<String, byte[]> result = compile(source, sourceFile);
        if (result.isEmpty()) throw new IllegalArgumentException("No type declarations found in source");
        if (sourceFile != null) {
            String className = sourceFile;
            if (className.endsWith(".java")) className = className.substring(0, className.length() - 5);
            className = className.replace('.', '/');
            if (result.containsKey(className)) return result.get(className);
        }
        return result.values().iterator().next();
    }

    /**
     * Builds the internal name for a class.
     *
     * @param className   the simple class name
     * @param packageName the package name, or null
     * @return the internal name
     */
    private @NotNull String toInternalName(@NotNull String className, @Nullable String packageName) {
        if (packageName == null || packageName.isEmpty()) return className;
        return packageName.replace('.', '/') + "/" + className;
    }

    /**
     * Orders map entries by source length, descending, so the parallel
     * compile pool hands out the biggest files first. Named class instead of
     * lambda so Vanta can still self-compile {@link VantaCompiler} on a
     * classpath that lacks a full generic-aware JDK.
     */
    private static final class LongestFirst implements Comparator<Map.Entry<String, String>> {
        @Override
        public int compare(@NotNull Map.Entry<String, String> a, @NotNull Map.Entry<String, String> b) {
            int la = a.getValue().length();
            int lb = b.getValue().length();
            if (la == lb) return 0;
            return lb - la;
        }
    }
}
