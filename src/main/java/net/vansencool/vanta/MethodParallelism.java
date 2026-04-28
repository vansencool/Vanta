package net.vansencool.vanta;

/**
 * Ambient signal read by the code generator to decide whether to fan out
 * method emission within a class across multiple threads. Each file-worker
 * thread sets its own value before calling {@code compile}; the value is
 * purely thread-local so concurrent file workers with different method-worker
 * counts never race.
 */
public final class MethodParallelism {

    private static final ThreadLocal<Integer> WORKERS = ThreadLocal.withInitial(() -> 1);

    private MethodParallelism() {
    }

    public static void current(int workers) {
        WORKERS.set(Math.max(1, workers));
    }

    public static void clear() {
        WORKERS.remove();
    }

    public static int workers() {
        return WORKERS.get();
    }
}
