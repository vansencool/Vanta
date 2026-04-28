package net.vansencool.vanta.benchmark;

import org.jetbrains.annotations.NotNull;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;

/**
 * Hot (steady-state) benchmarks for the full gson source corpus.
 */
public class GsonHotBenchmark extends HotBenchmarkBase {

    @Override
    protected @NotNull String sourceRoot() {
        return "test-libraries/gson";
    }

    @Benchmark
    public void serial(@NotNull Blackhole bh) throws IOException {
        benchSerial(bh);
    }

    @Benchmark
    public void parallel(@NotNull Blackhole bh) throws IOException {
        benchParallel(bh);
    }

    @Benchmark
    public void hybrid(@NotNull Blackhole bh) throws IOException {
        benchHybrid(bh);
    }

    @Benchmark
    public void smart(@NotNull Blackhole bh) throws IOException {
        benchSmart(bh);
    }

    @Benchmark
    public void javac(@NotNull Blackhole bh) throws IOException {
        benchJavac(bh);
    }
}
