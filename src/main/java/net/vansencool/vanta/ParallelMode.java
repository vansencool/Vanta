package net.vansencool.vanta;

import org.jetbrains.annotations.NotNull;

/**
 * Parallelism strategy for batch compilation. Selects between file-level
 * parallelism (one worker per source file), method-level parallelism (workers
 * share a class and emit its methods concurrently), a hybrid split, or a
 * self-tuning smart mode.
 */
@SuppressWarnings("unused")
public final class ParallelMode {

    private final int fileWorkers;
    private final int methodWorkers;
    private final boolean smart;
    private final int maxThreads;
    /**
     * Percentile cutoff (0–100) above which a file is considered heavy and
     * receives method-level workers. 75 means the top 25% by source length.
     */
    private final int heavyPercentile;

    private ParallelMode(int fileWorkers, int methodWorkers, boolean smart, int maxThreads, int heavyPercentile) {
        this.fileWorkers = fileWorkers;
        this.methodWorkers = methodWorkers;
        this.smart = smart;
        this.maxThreads = maxThreads;
        this.heavyPercentile = heavyPercentile;
    }

    /**
     * File-level parallelism. {@code workers} source files compile at once; each
     * file is generated on a single thread.
     */
    public static @NotNull ParallelMode files(int workers) {
        if (workers < 1) throw new IllegalArgumentException("workers must be >= 1");
        return new ParallelMode(workers, 1, false, 0, 75);
    }

    /**
     * Method-level parallelism. One file at a time, but its methods emit across
     * {@code workers} threads. Useful for source files dominated by a single
     * large class.
     */
    public static @NotNull ParallelMode methods(int workers) {
        if (workers < 1) throw new IllegalArgumentException("workers must be >= 1");
        return new ParallelMode(1, workers, false, 0, 75);
    }

    /**
     * Hybrid split: {@code fileWorkers} files compile in parallel and each file
     * further fans out method emission across {@code methodWorkers} threads.
     * Total concurrency peaks at {@code fileWorkers * methodWorkers}.
     */
    public static @NotNull ParallelMode hybrid(int fileWorkers, int methodWorkers) {
        if (fileWorkers < 1 || methodWorkers < 1)
            throw new IllegalArgumentException("worker counts must be >= 1");
        return new ParallelMode(fileWorkers, methodWorkers, false, 0, 75);
    }

    /**
     * Self-tuning mode. Uses all available processors. Files above the 75th
     * percentile by source length receive extra method-level workers; lighter
     * files get file-level parallelism only.
     */
    public static @NotNull ParallelMode smart() {
        return new ParallelMode(0, 0, true, Runtime.getRuntime().availableProcessors(), 75);
    }

    /**
     * Self-tuning mode capped at {@code maxThreads} total threads, using the
     * default 75th-percentile heavy threshold.
     */
    public static @NotNull ParallelMode smart(int maxThreads) {
        if (maxThreads < 1) throw new IllegalArgumentException("maxThreads must be >= 1");
        return new ParallelMode(0, 0, true, maxThreads, 75);
    }

    /**
     * Self-tuning mode with explicit control over the thread cap and the
     * percentile above which a file is considered heavy. {@code heavyPercentile}
     * of 90 means only the top 10% of files by source length get method workers.
     */
    public static @NotNull ParallelMode smart(int maxThreads, int heavyPercentile) {
        if (maxThreads < 1) throw new IllegalArgumentException("maxThreads must be >= 1");
        if (heavyPercentile < 0 || heavyPercentile > 99)
            throw new IllegalArgumentException("heavyPercentile must be 0-99");
        return new ParallelMode(0, 0, true, maxThreads, heavyPercentile);
    }

    public int fileWorkers() {
        return fileWorkers;
    }

    public int methodWorkers() {
        return methodWorkers;
    }

    public boolean isSmart() {
        return smart;
    }

    public int maxThreads() {
        return maxThreads;
    }

    public int heavyPercentile() {
        return heavyPercentile;
    }
}
