package net.vansencool.vanta.benchmark;

import net.vansencool.vanta.ParallelMode;
import net.vansencool.vanta.VantaCompiler;
import net.vansencool.vanta.classpath.ClasspathManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks full-gson compilation with both Vanta and javac, at cold and
 * hot JIT states. Cold results measure first-call latency on a completely
 * fresh compiler and classpath built inside the measurement itself. Hot
 * results measure steady-state throughput with a lazily-created compiler
 * that is reused across iterations.
 *
 * <p>Each measurement compiles the complete gson corpus (89 source files)
 * so the numbers scale with real-world compilation cost, not micro-tricks.</p>
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class GsonCompileBenchmark {

    private Map<String, String> sources;
    private List<Path> sourceFiles;

    @Nullable
    private static volatile VantaCompiler hotCompiler;
    @Nullable
    private static volatile VantaCompiler hotCompilerParallel;
    @Nullable
    private static volatile VantaCompiler hotBoth84;

    @Setup(Level.Trial)
    public void loadSources() throws IOException {
        sources = new LinkedHashMap<>();
        sourceFiles = new ArrayList<>();
        Path root = Paths.get("gson");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path p, @NotNull BasicFileAttributes a) throws IOException {
                if (p.toString().endsWith(".java")) {
                    sources.put(p.getFileName().toString(), Files.readString(p));
                    sourceFiles.add(p);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (sources.isEmpty()) throw new IOException("gson/ directory empty - run from project root");
    }

    /**
     * Single cold compile per fork on a completely fresh Vanta compiler and
     * classpath built right here. No shared state with other forks or iterations.
     */
    @Benchmark
    @Fork(value = 10, warmups = 0)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1, batchSize = 1)
    @BenchmarkMode(Mode.SingleShotTime)
    public void compileGsonColdVanta(@NotNull Blackhole bh) throws IOException {
        Path stubDir = Files.createTempDirectory("gson-bench-vcold-stubs-");
        ClasspathManager cp = new ClasspathManager();
        cp.addClassLoader(Thread.currentThread().getContextClassLoader());
        cp.addEntry(stubDir);
        VantaCompiler compiler = new VantaCompiler(cp);
        try {
            bh.consume(compiler.compileAll(sources));
        } finally {
            deleteTree(stubDir);
        }
    }

    @Benchmark
    @Fork(value = 10, warmups = 0)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1, batchSize = 1)
    @BenchmarkMode(Mode.SingleShotTime)
    public void compileGsonColdVantaParallel(@NotNull Blackhole bh) throws IOException {
        Path stubDir = Files.createTempDirectory("gson-bench-vcoldp-stubs-");
        ClasspathManager cp = new ClasspathManager();
        cp.addClassLoader(Thread.currentThread().getContextClassLoader());
        cp.addEntry(stubDir);
        VantaCompiler compiler = new VantaCompiler(cp);
        try {
            bh.consume(compiler.compileAllParallel(sources, ParallelMode.files(8)));
        } finally {
            deleteTree(stubDir);
        }
    }

    @Benchmark
    @Fork(value = 10, warmups = 0)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1, batchSize = 1)
    @BenchmarkMode(Mode.SingleShotTime)
    public void compileGsonColdVantaParallelBoth(@NotNull Blackhole bh) throws IOException {
        Path stubDir = Files.createTempDirectory("gson-bench-vcoldb-stubs-");
        ClasspathManager cp = new ClasspathManager();
        cp.addClassLoader(Thread.currentThread().getContextClassLoader());
        cp.addEntry(stubDir);
        VantaCompiler compiler = new VantaCompiler(cp);
        try {
            bh.consume(compiler.compileAllParallel(sources, ParallelMode.hybrid(8, 4)));
        } finally {
            deleteTree(stubDir);
        }
    }

    @Benchmark
    @Fork(value = 10, warmups = 0)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1, batchSize = 1)
    @BenchmarkMode(Mode.SingleShotTime)
    public void compileGsonColdVantaSmart(@NotNull Blackhole bh) throws IOException {
        Path stubDir = Files.createTempDirectory("gson-bench-vcolds-stubs-");
        ClasspathManager cp = new ClasspathManager();
        cp.addClassLoader(Thread.currentThread().getContextClassLoader());
        cp.addEntry(stubDir);
        VantaCompiler compiler = new VantaCompiler(cp);
        try {
            bh.consume(compiler.compileAllParallel(sources, ParallelMode.smart()));
        } finally {
            deleteTree(stubDir);
        }
    }

    /**
     * Vanta steady-state: JIT warm, single compiler instance reused across
     * invocations. Compiler is created lazily on first call, not in setup.
     */
    @Benchmark
    @Fork(value = 2)
    @Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 7, time = 2, timeUnit = TimeUnit.SECONDS)
    @BenchmarkMode(Mode.AverageTime)
    public void compileGsonHotVanta(@NotNull Blackhole bh) throws IOException {
        if (hotCompiler == null) {
            synchronized (GsonCompileBenchmark.class) {
                if (hotCompiler == null) {
                    Path stubDir = Files.createTempDirectory("gson-bench-vhot-stubs-");
                    ClasspathManager cp = new ClasspathManager();
                    cp.addClassLoader(Thread.currentThread().getContextClassLoader());
                    cp.addEntry(stubDir);
                    hotCompiler = new VantaCompiler(cp);
                }
            }
        }
        bh.consume(Objects.requireNonNull(hotCompiler).compileAll(sources));
    }

    /**
     * Vanta steady-state using {@link VantaCompiler#compileAllParallel} with 8
     * worker threads. Measures the throughput win available to callers that
     * batch-compile a whole source tree instead of calling {@code compile} per file.
     */
    @Benchmark
    @Fork(value = 2)
    @Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 7, time = 2, timeUnit = TimeUnit.SECONDS)
    @BenchmarkMode(Mode.AverageTime)
    public void compileGsonHotVantaParallel(@NotNull Blackhole bh) throws IOException {
        if (hotCompilerParallel == null) {
            synchronized (GsonCompileBenchmark.class) {
                if (hotCompilerParallel == null) {
                    Path stubDir = Files.createTempDirectory("gson-bench-vhotp-stubs-");
                    ClasspathManager cp = new ClasspathManager();
                    cp.addClassLoader(Thread.currentThread().getContextClassLoader());
                    cp.addEntry(stubDir);
                    hotCompilerParallel = new VantaCompiler(cp);
                }
            }
        }
        bh.consume(Objects.requireNonNull(hotCompilerParallel).compileAllParallel(sources, ParallelMode.files(8)));
    }

    /**
     * Hybrid parallelism: 8 file workers, each fanning out method emission
     * across 4 threads. Peak concurrency 32.
     */
    @Benchmark
    @Fork(value = 2)
    @Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 7, time = 2, timeUnit = TimeUnit.SECONDS)
    @BenchmarkMode(Mode.AverageTime)
    public void compileGsonParallelBoth(@NotNull Blackhole bh) throws IOException {
        if (hotBoth84 == null) {
            synchronized (GsonCompileBenchmark.class) {
                if (hotBoth84 == null) {
                    Path stubDir = Files.createTempDirectory("gson-bench-both84-stubs-");
                    ClasspathManager cp = new ClasspathManager();
                    cp.addClassLoader(Thread.currentThread().getContextClassLoader());
                    cp.addEntry(stubDir);
                    hotBoth84 = new VantaCompiler(cp);
                }
            }
        }
        bh.consume(Objects.requireNonNull(hotBoth84).compileAllParallel(sources, ParallelMode.hybrid(8, 4)));
    }

    /**
     * Cold reference: javac's first-call latency on the same source set,
     * matched to {@link #compileGsonColdVanta} settings so the two numbers
     * are directly comparable.
     */
    @Benchmark
    @Fork(value = 10, warmups = 0)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1, batchSize = 1)
    @BenchmarkMode(Mode.SingleShotTime)
    public void compileGsonColdJavac(@NotNull Blackhole bh) throws IOException {
        Path out = Files.createTempDirectory("gson-bench-jcold-");
        try {
            compileWithJavac(out);
            bh.consume(out);
        } finally {
            deleteTree(out);
        }
    }

    /**
     * Hot reference: javac steady-state throughput, same shape as
     * {@link #compileGsonHotVanta} so the comparison is apples-to-apples.
     */
    @Benchmark
    @Fork(value = 2)
    @Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
    @Measurement(iterations = 7, time = 2, timeUnit = TimeUnit.SECONDS)
    @BenchmarkMode(Mode.AverageTime)
    public void compileGsonHotJavac(@NotNull Blackhole bh) throws IOException {
        Path out = Files.createTempDirectory("gson-bench-jhot-");
        try {
            compileWithJavac(out);
            bh.consume(out);
        } finally {
            deleteTree(out);
        }
    }

    private void compileWithJavac(@NotNull Path out) throws IOException {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) throw new IllegalStateException("no system javac available");
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(out.toString());
        args.add("-source");
        args.add("17");
        args.add("-target");
        args.add("17");
        args.add("-proc:none");
        args.add("-g:none");
        for (Path p : sourceFiles) args.add(p.toString());
        int rc = jc.run(null, null, new ByteArrayOutputStream(), args.toArray(new String[0]));
        if (rc != 0) throw new IOException("javac compile of gson failed");
    }

    private void deleteTree(@NotNull Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path p, @NotNull BasicFileAttributes a) throws IOException {
                Files.deleteIfExists(p);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, @Nullable IOException ex) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
