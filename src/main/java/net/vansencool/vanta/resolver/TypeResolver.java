package net.vansencool.vanta.resolver;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.parser.ast.declaration.ImportDeclaration;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.parser.ast.type.TypeParameter;
import net.vansencool.vanta.resolver.type.ResolvedType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves AST type nodes into JVM descriptors and internal names.
 * Handles imports, primitives, arrays, and classpath lookups.
 */
public final class TypeResolver {

    private final @NotNull ClasspathManager classpathManager;
    private final @NotNull Map<String, String> importMap;
    private final @NotNull List<String> wildcardImports;
    private final @NotNull Map<String, String> staticImports;
    private final @NotNull List<String> staticWildcards;
    private final @Nullable String packageName;
    private final @NotNull Map<String, String> innerClassMap;
    private final @NotNull Map<String, ResolvedType> typeParameterErasures;
    private final @NotNull Map<String, List<ResolvedType>> typeParameterBounds;
    private final @NotNull Map<String, ResolvedType> baseNameCache = new ConcurrentHashMap<>();
    private final @NotNull ThreadLocal<Map<String, ResolvedType>> tlErasures = new ThreadLocal<>();
    private final @NotNull ThreadLocal<Map<String, List<ResolvedType>>> tlBounds = new ThreadLocal<>();

    /**
     * Creates a type resolver.
     *
     * @param classpathManager the classpath manager
     * @param imports          the import declarations
     * @param packageName      the current package name, or null
     */
    public TypeResolver(@NotNull ClasspathManager classpathManager, @NotNull List<ImportDeclaration> imports, @Nullable String packageName) {
        this.classpathManager = classpathManager;
        this.importMap = new HashMap<>();
        this.wildcardImports = new ArrayList<>();
        this.staticImports = new HashMap<>();
        this.staticWildcards = new ArrayList<>();
        this.packageName = packageName;
        this.innerClassMap = new ConcurrentHashMap<>();
        this.typeParameterErasures = new ConcurrentHashMap<>();
        this.typeParameterBounds = new ConcurrentHashMap<>();

        wildcardImports.add("java.lang");

        for (ImportDeclaration imp : imports) {
            if (imp.isStatic()) {
                if (imp.isWildcard()) {
                    String owner = imp.name().replace(".*", "").replace('.', '/');
                    staticWildcards.add(owner);
                } else {
                    int dot = imp.name().lastIndexOf('.');
                    String owner = imp.name().substring(0, dot).replace('.', '/');
                    String member = imp.name().substring(dot + 1);
                    staticImports.put(member, owner);
                }
            } else if (imp.isWildcard()) {
                String pkg = imp.name().replace(".*", "");
                wildcardImports.add(pkg);
            } else {
                String simpleName = imp.name().substring(imp.name().lastIndexOf('.') + 1);
                importMap.put(simpleName, imp.name());
            }
        }
    }

    public @Nullable String resolveStaticFieldOwner(@NotNull String name) {
        String direct = staticImports.get(name);
        if (direct != null) return direct;
        for (String owner : staticWildcards) {
            Class<?> c = classpathManager.loadClass(owner);
            if (c == null) continue;
            try {
                Field f = c.getField(name);
                if (Modifier.isStatic(f.getModifiers())) {
                    return f.getDeclaringClass().getName().replace('.', '/');
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /**
     * Resolves the owner class for a static-imported method name. Checks single-
     * member static imports first, then wildcard static imports. Returns null
     * when no matching static method is visible.
     */
    public @Nullable String resolveStaticMethodOwner(@NotNull String methodName) {
        String direct = staticImports.get(methodName);
        if (direct != null) return direct;
        for (String owner : staticWildcards) {
            Class<?> c = classpathManager.loadClass(owner);
            if (c == null) continue;
            for (Method m : c.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getName().equals(methodName)) {
                    return owner;
                }
            }
        }
        return null;
    }

    public void registerInnerClass(@NotNull String simpleName, @NotNull String outerInternalName) {
        innerClassMap.put(simpleName, outerInternalName + "$" + simpleName);
        baseNameCache.remove(simpleName);
    }

    /**
     * Registers a nested type inherited from a supertype (e.g. {@code Map.Entry}
     * visible via {@code extends AbstractMap}). Own-nested registrations
     * (via {@link #registerInnerClass}) take precedence, since they shadow
     * inherited members per JLS.
     */
    public void registerInheritedInnerClass(@NotNull String simpleName, @NotNull String internalName) {
        innerClassMap.putIfAbsent(simpleName, internalName);
    }

    public @NotNull ClasspathManager classpathManager() {
        return classpathManager;
    }

    /**
     * Converts a dotted fully qualified name (e.g.
     * {@code com.foo.Outer.Inner.Deep}) to a JVM internal name
     * (e.g. {@code com/foo/Outer$Inner$Deep}). Tries progressively longer package
     * prefixes against the classpath to distinguish package boundaries from
     * inner-class boundaries.
     */
    private @NotNull String dottedFqnToInternal(@NotNull String dotted) {
        String[] parts = dotted.split("\\.");
        for (int pkgEnd = parts.length - 1; pkgEnd >= 1; pkgEnd--) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pkgEnd; i++) {
                if (i > 0) sb.append('/');
                sb.append(parts[i]);
            }
            sb.append('/').append(parts[pkgEnd]);
            for (int i = pkgEnd + 1; i < parts.length; i++) {
                sb.append('$').append(parts[i]);
            }
            String candidate = sb.toString();
            if (classpathManager.exists(candidate)) return candidate;
        }
        return dotted.replace('.', '/');
    }

    /**
     * Registers generic type parameters for erasure. For each type parameter the erased
     * type is the first bound, or {@code java/lang/Object} if unbounded.
     *
     * @param typeParameters the type parameters to register
     */
    public void registerTypeParameters(@NotNull List<TypeParameter> typeParameters) {
        Map<String, ResolvedType> erasures = writableErasures();
        Map<String, List<ResolvedType>> bounds = writableBounds();
        for (TypeParameter tp : typeParameters) {
            if (tp.bounds() != null && !tp.bounds().isEmpty()) {
                erasures.put(tp.name(), resolveBaseName(tp.bounds().get(0).name()));
                List<ResolvedType> boundsResolved = new ArrayList<>();
                for (TypeNode b : tp.bounds()) boundsResolved.add(resolveBaseName(b.name()));
                bounds.put(tp.name(), boundsResolved);
            } else {
                erasures.put(tp.name(), ResolvedType.ofObject("java/lang/Object"));
            }
            baseNameCache.remove(tp.name());
        }
    }

    /**
     * Captures the current thread's generic-parameter scope so a worker thread
     * can start from the same baseline. Callers hand the returned snapshot to
     * {@link #adoptScope} on the worker before it runs.
     */
    public @NotNull Object captureScope() {
        return new Object[]{
                new HashMap<>(erasureView()),
                new HashMap<>(boundsView())
        };
    }

    /**
     * Installs a captured scope as the current thread's generic-parameter
     * overlay. Worker threads call this before emitting a method so the
     * class-level type parameters registered by the orchestrator thread are
     * visible locally.
     */
    @SuppressWarnings("unchecked")
    public void adoptScope(@NotNull Object snapshot) {
        Object[] parts = (Object[]) snapshot;
        tlErasures.set(new HashMap<>((Map<String, ResolvedType>) parts[0]));
        tlBounds.set(new HashMap<>((Map<String, List<ResolvedType>>) parts[1]));
    }

    public void clearScope() {
        tlErasures.remove();
        tlBounds.remove();
    }

    private @NotNull Map<String, ResolvedType> erasureView() {
        Map<String, ResolvedType> tl = tlErasures.get();
        return tl != null ? tl : typeParameterErasures;
    }

    private @NotNull Map<String, List<ResolvedType>> boundsView() {
        Map<String, List<ResolvedType>> tl = tlBounds.get();
        return tl != null ? tl : typeParameterBounds;
    }

    private @NotNull Map<String, ResolvedType> writableErasures() {
        Map<String, ResolvedType> tl = tlErasures.get();
        return tl != null ? tl : typeParameterErasures;
    }

    private @NotNull Map<String, List<ResolvedType>> writableBounds() {
        Map<String, List<ResolvedType>> tl = tlBounds.get();
        return tl != null ? tl : typeParameterBounds;
    }

    /**
     * Returns the list of declared bounds for a type parameter, or null when
     * the name is not a registered type parameter. Callers use this to walk
     * intersection-type bounds during member resolution, so
     * {@code <M extends AccessibleObject & Member>} can resolve
     * {@code Member.getModifiers} even though the first bound is
     * {@code AccessibleObject}.
     */
    public @Nullable List<ResolvedType> typeParameterBounds(@NotNull String name) {
        return boundsView().get(name);
    }

    /**
     * Given an erased internal name, returns the additional (secondary)
     * intersection bounds for any registered type parameter whose primary
     * bound erases to that name. Returns an empty list when there are no
     * matches or the type parameter has only a single bound.
     */
    public @NotNull List<ResolvedType> additionalBoundsForErasure(@NotNull String erasedInternal) {
        List<ResolvedType> out = new ArrayList<>();
        for (Map.Entry<String, List<ResolvedType>> e : boundsView().entrySet()) {
            List<ResolvedType> bounds = e.getValue();
            if (bounds == null || bounds.size() < 2) continue;
            ResolvedType primary = bounds.get(0);
            if (primary.internalName() == null || !primary.internalName().equals(erasedInternal)) continue;
            for (int i = 1; i < bounds.size(); i++) out.add(bounds.get(i));
        }
        return out;
    }

    /**
     * Resolves a TypeNode to a ResolvedType.
     *
     * @param typeNode the AST type node
     * @return the resolved type
     */
    public @NotNull ResolvedType resolve(@NotNull TypeNode typeNode) {
        String name = typeNode.name();
        ResolvedType base = resolveBaseName(name);
        if (typeNode.typeArguments() != null && !typeNode.typeArguments().isEmpty()) {
            List<ResolvedType> args = new ArrayList<>();
            for (TypeNode a : typeNode.typeArguments()) args.add(resolve(a));
            base = base.withTypeArguments(args);
        }
        if (typeNode.arrayDimensions() > 0) {
            return base.asArray(typeNode.arrayDimensions());
        }
        return base;
    }

    /**
     * Resolves a type descriptor from a TypeNode.
     *
     * @param typeNode the type node
     * @return the JVM descriptor string
     */
    public @NotNull String resolveDescriptor(@NotNull TypeNode typeNode) {
        return resolve(typeNode).descriptor();
    }

    /**
     * Resolves the internal name for a TypeNode.
     *
     * @param typeNode the type node
     * @return the internal name (e.g., "java/lang/String")
     */
    public @NotNull String resolveInternalName(@NotNull TypeNode typeNode) {
        ResolvedType resolved = resolve(typeNode);
        if (resolved.internalName() != null) return resolved.internalName();
        return resolved.descriptor();
    }

    /**
     * Resolves a base type name (without array dimensions).
     *
     * @param name the type name
     * @return the resolved type
     */
    private @NotNull ResolvedType resolveBaseName(@NotNull String name) {
        ResolvedType erased = erasureView().get(name);
        if (erased != null) return erased;
        ResolvedType cached = baseNameCache.get(name);
        if (cached != null) return cached;
        ResolvedType result = resolveBaseNameUncached(name);
        baseNameCache.put(name, result);
        return result;
    }

    /**
     * Resolves a dotted name like {@code Inner} or
     * {@code Entry} by walking from the shortest possible
     * package prefix until one resolves on the classpath, then treating any
     * trailing segments as nested classes joined by {@code $}. Returns the
     * resulting internal name, or null when nothing on the classpath
     * matches any prefix.
     */
    public @Nullable String resolveDottedFqn(@NotNull String dotted) {
        String[] parts = dotted.split("\\.");
        for (int split = parts.length - 1; split >= 1; split--) {
            StringBuilder pkgAndClass = new StringBuilder(parts[0]);
            for (int i = 1; i < split; i++) pkgAndClass.append('/').append(parts[i]);
            pkgAndClass.append('/').append(parts[split]);
            StringBuilder internal = new StringBuilder(pkgAndClass);
            for (int i = split + 1; i < parts.length; i++) internal.append('$').append(parts[i]);
            String candidate = internal.toString();
            if (classpathManager.exists(candidate)) return candidate;
        }
        return null;
    }

    private @NotNull ResolvedType resolveBaseNameUncached(@NotNull String name) {
        ResolvedType primitive = ResolvedType.fromPrimitiveName(name);
        if (primitive != null) return primitive;

        if ("void".equals(name)) return ResolvedType.VOID;

        ResolvedType erased = erasureView().get(name);
        if (erased != null) return erased;

        if (name.contains("/")) {
            return ResolvedType.ofObject(name);
        }

        if (name.contains(".")) {
            int dot = name.indexOf('.');
            String head = name.substring(0, dot);
            String tail = name.substring(dot + 1);
            if (!head.contains("/")) {
                ResolvedType headResolved = resolveBaseName(head);
                if (headResolved.internalName() != null) {
                    String candidate = headResolved.internalName() + "$" + tail.replace('.', '$');
                    if (classpathManager.exists(candidate)) {
                        return ResolvedType.ofObject(candidate);
                    }
                }
            }
            String resolved = resolveDottedFqn(name);
            if (resolved != null) return ResolvedType.ofObject(resolved);
            return ResolvedType.ofObject(name.replace('.', '/'));
        }

        String mapped = importMap.get(name);
        if (mapped != null) {
            return ResolvedType.ofObject(dottedFqnToInternal(mapped));
        }

        String innerMapping = innerClassMap.get(name);
        if (innerMapping != null) {
            return ResolvedType.ofObject(innerMapping);
        }

        for (String importedFqn : importMap.values()) {
            String nested = importedFqn.replace('.', '/') + "$" + name;
            if (classpathManager.exists(nested)) {
                return ResolvedType.ofObject(nested);
            }
        }

        if (packageName != null) {
            String fqn = packageName + "." + name;
            String internal = fqn.replace('.', '/');
            if (classpathManager.exists(internal)) {
                return ResolvedType.ofObject(internal);
            }
        }

        for (String wildcardImport : wildcardImports) {
            String fqn = wildcardImport + "." + name;
            String internal = fqn.replace('.', '/');
            if (classpathManager.exists(internal)) {
                return ResolvedType.ofObject(internal);
            }
        }

        String internal = name.replace('.', '/');
        return ResolvedType.ofObject(internal);
    }

    /**
     * Builds a method descriptor from parameter types and return type.
     *
     * @param paramTypes the parameter types
     * @param returnType the return type
     * @return the method descriptor string
     */
    public @NotNull String methodDescriptor(@NotNull List<TypeNode> paramTypes, @NotNull TypeNode returnType) {
        StringBuilder sb = new StringBuilder("(");
        for (TypeNode param : paramTypes) {
            sb.append(resolveDescriptor(param));
        }
        sb.append(')');
        sb.append(resolveDescriptor(returnType));
        return sb.toString();
    }
}
