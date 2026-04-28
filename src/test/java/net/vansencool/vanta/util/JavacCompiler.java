package net.vansencool.vanta.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility that compiles Java source code using the standard javac compiler.
 * Useful for comparing Vanta compiler output against reference bytecode.
 */
public final class JavacCompiler {

    private JavacCompiler() {
    }

    /**
     * Compiles a single Java source string and returns the bytecode.
     *
     * @param className the fully qualified class name
     * @param source    the Java source code
     * @return the compiled bytecode, or null if compilation failed
     */
    public static byte @Nullable [] compileSingle(@NotNull String className, @NotNull String source) {
        Map<String, byte[]> result = compile(className, source);
        if (result.isEmpty()) return null;
        String internalName = className.replace('.', '/');
        if (result.containsKey(internalName)) return result.get(internalName);
        if (result.containsKey(className)) return result.get(className);
        return result.values().iterator().next();
    }

    /**
     * Compiles a Java source string using javac and returns all generated class bytecodes.
     *
     * @param className the fully qualified class name
     * @param source    the Java source code
     * @return map from class name to bytecode, empty if compilation failed
     */
    public static @NotNull Map<String, byte[]> compile(@NotNull String className, @NotNull String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("No system Java compiler available");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> outputMap = new HashMap<>();

        StandardJavaFileManager stdFileManager = compiler.getStandardFileManager(diagnostics, null, null);

        InMemoryFileManager fileManager = new InMemoryFileManager(stdFileManager, outputMap);
        JavaFileObject sourceFile = new InMemorySource(className, source);

        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, List.of("-parameters", "-classpath", System.getProperty("java.class.path")), null, Collections.singletonList(sourceFile));

        boolean success = task.call();
        if (!success) {
            diagnostics.getDiagnostics().forEach(d -> System.err.println("JAVAC: " + d.getKind() + " " + d.getMessage(null)));
            return Map.of();
        }

        Map<String, byte[]> result = new HashMap<>();
        for (Map.Entry<String, ByteArrayOutputStream> entry : outputMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toByteArray());
        }
        return result;
    }

    /**
     * Compiles multiple Java source files at once using javac.
     * All sources are compiled together so they can reference each other.
     *
     * @param sources map from fully qualified class name to source code
     * @return map from fully qualified class name to bytecode, empty if compilation failed
     */
    public static @NotNull Map<String, byte[]> compileAll(@NotNull Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("No system Java compiler available");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> outputMap = new HashMap<>();

        StandardJavaFileManager stdFileManager = compiler.getStandardFileManager(diagnostics, null, null);
        InMemoryFileManager fileManager = new InMemoryFileManager(stdFileManager, outputMap);

        List<JavaFileObject> sourceFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            sourceFiles.add(new InMemorySource(entry.getKey(), entry.getValue()));
        }

        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, List.of("-parameters", "-classpath", System.getProperty("java.class.path")), null, sourceFiles);

        boolean success = task.call();
        if (!success) {
            diagnostics.getDiagnostics().forEach(d -> System.err.println("JAVAC: " + d.getKind() + " " + d.getMessage(null)));
            return Map.of();
        }

        Map<String, byte[]> result = new HashMap<>();
        for (Map.Entry<String, ByteArrayOutputStream> entry : outputMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toByteArray());
        }
        return result;
    }

    private static final class InMemorySource extends SimpleJavaFileObject {

        private final @NotNull String source;

        private InMemorySource(@NotNull String className, @NotNull String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public @NotNull CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        private final @NotNull Map<String, ByteArrayOutputStream> outputMap;

        private InMemoryFileManager(@NotNull StandardJavaFileManager delegate, @NotNull Map<String, ByteArrayOutputStream> outputMap) {
            super(delegate);
            this.outputMap = outputMap;
        }

        @Override
        public @NotNull JavaFileObject getJavaFileForOutput(Location location, @NotNull String className, JavaFileObject.Kind kind, FileObject sibling) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            outputMap.put(className, baos);
            return new SimpleJavaFileObject(URI.create("mem:///" + className + kind.extension), kind) {
                @Override
                public @NotNull OutputStream openOutputStream() {
                    return baos;
                }
            };
        }
    }
}
