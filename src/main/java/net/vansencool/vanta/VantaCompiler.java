package net.vansencool.vanta;

import net.vansencool.vanta.classpath.ClasspathManager;
import net.vansencool.vanta.codegen.ClassGenerator;
import net.vansencool.vanta.codegen.diagnostic.imp.ImportValidator;
import net.vansencool.vanta.diagnostic.DiagnosticReport;
import net.vansencool.vanta.exception.CompilationException;
import net.vansencool.vanta.exception.MultiCompilationException;
import net.vansencool.vanta.lexer.Lexer;
import net.vansencool.vanta.lexer.token.Token;
import net.vansencool.vanta.parser.Parser;
import net.vansencool.vanta.parser.ast.AstNode;
import net.vansencool.vanta.parser.ast.declaration.ClassDeclaration;
import net.vansencool.vanta.parser.ast.declaration.CompilationUnit;
import net.vansencool.vanta.parser.ast.type.TypeNode;
import net.vansencool.vanta.resolver.TypeResolver;
import net.vansencool.vanta.symbol.registry.TypeRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URLClassLoader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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
     * Lexes and parses a single source file into a {@link CompilationUnit}.
     */
    public @NotNull CompilationUnit parse(@NotNull String source, @Nullable String sourceFile) {
        Lexer lexer = new Lexer(source, sourceFile);
        List<Token> tokens = lexer.tokenize();
        return new Parser(tokens, source, sourceFile, lexer.comments()).parse();
    }

    /**
     * Lexes and parses a batch of source files. The returned map preserves
     * the input iteration order so downstream phases see files in the same
     * order they were submitted.
     */
    public @NotNull Map<String, CompilationUnit> parseAll(@NotNull Map<String, String> sources) {
        Map<String, CompilationUnit> parsed = new LinkedHashMap<>(sources.size());
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            parsed.put(entry.getKey(), parse(entry.getValue(), entry.getKey()));
        }
        return parsed;
    }

    /**
     * Parallel variant of {@link #parseAll}. Each file is lexed and parsed on
     * {@code pool}; the returned map preserves input iteration order.
     */
    public @NotNull Map<String, CompilationUnit> parseAllParallel(@NotNull Map<String, String> sources, @NotNull ExecutorService pool) {
        int n = sources.size();
        if (n <= 16) return parseAll(sources);
        List<Map.Entry<String, String>> entries = new ArrayList<>(sources.entrySet());
        CompilationUnit[] results = new CompilationUnit[n];
        int workers = 8;
        int batchSize = Math.max(8, (int) Math.ceil((double) n / workers));
        List<Future<?>> futures = new ArrayList<>(workers);
        AtomicReference<RuntimeException> firstFailure = new AtomicReference<>();
        for (int start = 0; start < n; start += batchSize) {
            int from = start;
            int to = Math.min(start + batchSize, n);
            futures.add(pool.submit(() -> {
                if (firstFailure.get() != null) return;
                try {
                    for (int i = from; i < to; i++) {
                        Map.Entry<String, String> entry = entries.get(i);
                        results[i] = parse(entry.getValue(), entry.getKey());
                    }
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
        Map<String, CompilationUnit> parsed = new LinkedHashMap<>(n);
        for (int i = 0; i < n; i++) parsed.put(entries.get(i).getKey(), results[i]);
        return parsed;
    }

    /**
     * Compiles a single source file. A single file may produce multiple classes
     * when it declares inner or anonymous classes; each lands in the result map
     * keyed by its internal name.
     */
    public @NotNull Map<String, byte[]> compile(@NotNull String source, @Nullable String sourceFile) {
        return compile(parse(source, sourceFile), source, sourceFile, null);
    }

    /**
     * Compiles multiple Java source files. Each file is compiled independently
     * with its own imports and package context.
     *
     * @param sources map from source file path to source code
     * @return map from class internal name to bytecode bytes
     */
    public @NotNull Map<String, byte[]> compileAll(@NotNull Map<String, String> sources) {
        Map<String, CompilationUnit> parsed = parseAll(sources);
        registerSources(parsed);
        DiagnosticReport report = new DiagnosticReport();
        Map<String, byte[]> result = new HashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String path = entry.getKey();
            String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            try {
                result.putAll(compile(parsed.get(path), entry.getValue(), fileName, report));
            } catch (CompilationException e) {
                report.report(fileName, null, e.diagnostic());
            }
        }
        if (report.hasErrors()) throw new MultiCompilationException(report);
        return result;
    }

    /**
     * Compiles a single parsed unit. When {@code report} is non null,
     * recoverable errors inside individual methods are appended to the report
     * and emission continues with stub bodies; otherwise the first failure
     * throws.
     */
    public @NotNull Map<String, byte[]> compile(@NotNull CompilationUnit cu, @NotNull String source, @Nullable String sourceFile, @Nullable DiagnosticReport report) {
        try {
            ImportValidator.validate(classpathManager, source, sourceFile, cu);
            TypeResolver typeResolver = new TypeResolver(classpathManager, cu.imports(), cu.packageName());
            ClassGenerator classGenerator = new ClassGenerator(classpathManager, typeResolver, sourceFile, source, cu.spanTable());
            classGenerator.report(report);
            Map<String, byte[]> result = new HashMap<>();
            for (AstNode typeDecl : cu.typeDeclarations()) {
                if (typeDecl instanceof ClassDeclaration classDecl) {
                    String outerInternalName = toInternalName(classDecl.name(), cu.packageName());
                    registerInnerClasses(typeResolver, classDecl, outerInternalName);
                    Map<String, byte[]> innerBytecodes = classGenerator.generateInnerClasses(classDecl, cu.packageName());
                    byte[] bytecode = classGenerator.generate(classDecl, cu.packageName());
                    result.put(outerInternalName, bytecode);
                    result.putAll(innerBytecodes);
                    result.putAll(classGenerator.getAndClearAnonClassBytecodes());
                }
            }
            return result;
        } catch (CompilationException e) {
            throw e;
        } catch (RuntimeException e) {
            System.err.println("[Vanta] Unexpected failure compiling " + sourceFile + ": " + e);
            e.printStackTrace(System.err);
            throw e;
        }
    }

    /**
     * Registers each parsed compilation unit on the shared {@link TypeRegistry}
     * so cross file types resolve to AST backed symbols. Builds a
     * {@link TypeResolver} per source file from its imports and package so
     * unqualified references inside one file resolve against that file's
     * scope.
     */
    public void registerSources(@NotNull Map<String, CompilationUnit> parsed) {
        TypeRegistry registry = classpathManager.typeRegistry();
        Map<String, TypeResolver> resolvers = new HashMap<>();
        for (Map.Entry<String, CompilationUnit> e : parsed.entrySet()) {
            CompilationUnit cu = e.getValue();
            TypeResolver tr = new TypeResolver(classpathManager, cu.imports(), cu.packageName());
            resolvers.put(e.getKey(), tr);
            registry.register(e.getKey(), cu, tr);
        }
        for (Map.Entry<String, CompilationUnit> e : parsed.entrySet()) {
            CompilationUnit cu = e.getValue();
            TypeResolver tr = resolvers.get(e.getKey());
            for (AstNode typeDecl : cu.typeDeclarations()) {
                if (typeDecl instanceof ClassDeclaration cd) {
                    String outerInternalName = toInternalName(cd.name(), cu.packageName());
                    registerInnerClasses(tr, cd, outerInternalName);
                }
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
        int fileWorkers = Math.min(mode.fileWorkers(), sources.size());
        if (sources.size() <= 1 || fileWorkers <= 1) {
            MethodParallelism.current(mode.methodWorkers());
            try {
                return compileAll(sources);
            } finally {
                MethodParallelism.clear();
            }
        }
        ExecutorService pool = classpathManager.sharedFilePool(fileWorkers);
        Map<String, CompilationUnit> parsed = parseAllParallel(sources, pool);
        registerSources(parsed);
        MethodParallelism.current(mode.methodWorkers());
        try {
            Map<String, byte[]> result = new ConcurrentHashMap<>();
            AtomicReference<RuntimeException> firstFailure = new AtomicReference<>();
            List<Map.Entry<String, String>> ordered = new ArrayList<>(sources.entrySet());
            ordered.sort(new LongestFirst());
            List<Future<?>> futures = new ArrayList<>(sources.size());
            for (Map.Entry<String, String> entry : ordered) {
                futures.add(pool.submit(() -> {
                    if (firstFailure.get() != null) return;
                    String path = entry.getKey();
                    String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                    try {
                        result.putAll(compile(parsed.get(path), entry.getValue(), fileName, null));
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
