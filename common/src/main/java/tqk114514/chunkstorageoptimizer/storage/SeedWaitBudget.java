package tqk114514.chunkstorageoptimizer.storage;

import java.util.function.LongSupplier;

/**
 * How long one batch may spend waiting for legacy chunks it cannot read.
 *
 * <p>The per-chunk retries in {@code CsoStorage.legacyChunkBytes} exist because a transient fault
 * and a damaged chunk cannot be told apart by exception type, and giving up on a chunk that was
 * only momentarily unreadable costs that chunk forever — its bucket becomes authoritative and the
 * {@code .mca} copy is never consulted again. The waiting is right; unbounded repetition of it is
 * not, because it happens under the storage lock, and one flush timer serves every storage in the
 * process. This is the bound: the first chunks in a batch get their patience, and once a second of
 * waiting has gone the rest are given up on at once, in the same batch, under the same reasoning.
 *
 * <p>The clock is a supplier rather than {@code System::nanoTime} directly for one reason: a
 * bound measured against wall time is a test that fails on a loaded CI runner for reasons that have
 * nothing to do with the code (measured: the same batch took 1.0 s locally and 1.85 s on a shared
 * runner, because the reading itself, not the waiting, grew). With the clock in hand the arithmetic
 * is testable exactly.
 */
final class SeedWaitBudget {

    private final long limitNanos;
    private final LongSupplier clock;
    private final long startNanos;

    SeedWaitBudget(long limitMillis) {
        this(limitMillis, System::nanoTime);
    }

    SeedWaitBudget(long limitMillis, LongSupplier clock) {
        this.limitNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(limitMillis);
        this.clock = clock;
        this.startNanos = clock.getAsLong();
    }

    /**
     * Reserves up to {@code waitNanos} of waiting. Returns the granted nanos, or 0 when the batch
     * has spent its budget — which is the caller's signal to stop retrying and report the chunk.
     */
    long reserve(long waitNanos) {
        long remaining = limitNanos - (clock.getAsLong() - startNanos);
        if (remaining <= 0) {
            return 0;
        }
        return Math.min(waitNanos, remaining);
    }
}
