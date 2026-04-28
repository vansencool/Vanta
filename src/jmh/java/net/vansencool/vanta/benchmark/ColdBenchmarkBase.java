package net.vansencool.vanta.benchmark;

import net.vansencool.vanta.ParallelMode;
import net.vansencool.vanta.VantaCompiler;
import net.vansencool.vanta.classpath.ClasspathManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 10, warmups = 0)
@Warmup(iterations = 0)
@Measurement(iterations = 1, batchSize = 1)
@BenchmarkMode(Mode.SingleShotTime)
public abstract class ColdBenchmarkBase {

    protected Map<String, String> sources;
    protected List<Path> sourceFiles;

    protected abstract @NotNull String sourceRoot();

    @Setup(Level.Trial)
    public void loadSources() throws IOException {
        sources = new LinkedHashMap<>();
        sourceFiles = new ArrayList<>();
        Path root = Paths.get(sourceRoot());
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
        if (sources.isEmpty()) throw new IOException(sourceRoot() + " empty or not found - run from project root");
    }

    private @NotNull VantaCompiler freshCompiler() throws IOException {
        ClasspathManager cp = new ClasspathManager();
        cp.addClassLoader(Thread.currentThread().getContextClassLoader());
        return new VantaCompiler(cp);
    }

    protected void benchSerial(@NotNull Blackhole bh) throws IOException {
        bh.consume(freshCompiler().compileAll(sources));
    }

    protected void benchParallel(@NotNull Blackhole bh) throws IOException {
        bh.consume(freshCompiler().compileAllParallel(sources, ParallelMode.files(8)));
    }

    protected void benchHybrid(@NotNull Blackhole bh) throws IOException {
        bh.consume(freshCompiler().compileAllParallel(sources, ParallelMode.hybrid(8, 4)));
    }

    protected void benchSmart(@NotNull Blackhole bh) throws IOException {
        bh.consume(freshCompiler().compileAllParallel(sources, ParallelMode.smart()));
    }

    protected void benchJavac(@NotNull Blackhole bh) throws IOException {
        Path out = Files.createTempDirectory("bench-jcold-");
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
        if (rc != 0) throw new IOException("javac failed on " + sourceRoot());
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
