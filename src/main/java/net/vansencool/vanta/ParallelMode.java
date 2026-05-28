package net.vansencool.vanta;

import org.jetbrains.annotations.NotNull;

/**
 * Parallelism strategy for batch compilation. Selects between file-level
 * parallelism (one worker per source file), method-level parallelism (workers
 * share a class and emit its methods concurrently), or a hybrid split.
 */
@SuppressWarnings("unused")
public final class ParallelMode {

    private final int fileWorkers;
    private final int methodWorkers;

    private ParallelMode(int fileWorkers, int methodWorkers) {
        this.fileWorkers = fileWorkers;
        this.methodWorkers = methodWorkers;
    }

    /**
     * File-level parallelism. {@code workers} source files compile at once; each
     * file is generated on a single thread.
     */
    public static @NotNull ParallelMode files(int workers) {
        if (workers < 1) throw new IllegalArgumentException("workers must be >= 1");
        return new ParallelMode(workers, 1);
    }

    /**
     * Method-level parallelism. One file at a time, but its methods emit across
     * {@code workers} threads. Useful for source files dominated by a single
     * large class.
     */
    public static @NotNull ParallelMode methods(int workers) {
        if (workers < 1) throw new IllegalArgumentException("workers must be >= 1");
        return new ParallelMode(1, workers);
    }

    /**
     * Hybrid split: {@code fileWorkers} files compile in parallel and each file
     * further fans out method emission across {@code methodWorkers} threads.
     * Total concurrency peaks at {@code fileWorkers * methodWorkers}.
     */
    public static @NotNull ParallelMode hybrid(int fileWorkers, int methodWorkers) {
        if (fileWorkers < 1 || methodWorkers < 1)
            throw new IllegalArgumentException("worker counts must be >= 1");
        return new ParallelMode(fileWorkers, methodWorkers);
    }

    public int fileWorkers() {
        return fileWorkers;
    }

    public int methodWorkers() {
        return methodWorkers;
    }
}
