package tqk114514.chunkstorageoptimizer.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * A lock-free latency histogram with power-of-two buckets, so a percentile can be reported without
 * keeping every sample.
 *
 * <p>Bucket {@code k} covers {@code [2^(k-1), 2^k)} nanoseconds; bucket 0 holds exact zeros. Sixty-five
 * buckets therefore cover the whole long range with no bounds checks, at the cost of resolution: a
 * bucket is at most twice as wide as its lower edge, so the reported percentiles are approximate and
 * are interpolated inside the bucket that holds the rank.
 */
public final class CsoLatency {

    private static final int BUCKETS = 65;

    private final LongAdder[] counts = new LongAdder[BUCKETS];
    private final LongAdder total = new LongAdder();
    private final AtomicLong maxNanos = new AtomicLong();

    public CsoLatency() {
        for (int i = 0; i < BUCKETS; i++) {
            this.counts[i] = new LongAdder();
        }
    }

    public void record(long nanos) {
        if (nanos < 0) {
            // A negative duration means the caller measured something it should not have; counting
            // it as zero would hide that, so it is dropped and left visible via count().
            return;
        }
        this.counts[bucketOf(nanos)].increment();
        this.total.increment();
        this.maxNanos.accumulateAndGet(nanos, Math::max);
    }

    public long count() {
        return this.total.sum();
    }

    public long maxNanos() {
        return this.maxNanos.get();
    }

    /**
     * The approximate {@code p}-th percentile in nanoseconds, for {@code p} in {@code (0, 100]}.
     *
     * <p>Returns 0 when nothing was recorded, which callers must not render as "0 ms" without a
     * count alongside it.
     */
    public double percentileNanos(double p) {
        long samples = this.total.sum();
        if (samples == 0) {
            return 0.0;
        }
        long rank = Math.max(1L, (long) Math.ceil(samples * p / 100.0));
        long seen = 0;
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            long inBucket = this.counts[bucket].sum();
            if (inBucket == 0) {
                continue;
            }
            if (seen + inBucket >= rank) {
                // Clamped: the bucket's upper edge can overshoot the largest sample actually seen,
                // and no percentile may come out above the maximum.
                return Math.min(interpolate(bucket, rank - seen, inBucket), (double) this.maxNanos.get());
            }
            seen += inBucket;
        }
        return (double) this.maxNanos.get();
    }

    public void reset() {
        for (LongAdder bucket : this.counts) {
            bucket.reset();
        }
        this.total.reset();
        this.maxNanos.set(0L);
    }

    private static int bucketOf(long nanos) {
        // floor(log2(n)) + 1: bucket k then covers [2^(k-1), 2^k - 1], and the largest positive
        // long lands in bucket 63.
        return nanos == 0 ? 0 : 64 - Long.numberOfLeadingZeros(nanos);
    }

    private static long lowerEdge(int bucket) {
        return bucket <= 1 ? 0L : 1L << (bucket - 1);
    }

    private static long upperEdge(int bucket) {
        return bucket >= 63 ? Long.MAX_VALUE : (1L << bucket) - 1;
    }

    /** Linear guess inside the bucket, which is the best any binned histogram can do. */
    private static double interpolate(int bucket, long rankInBucket, long inBucket) {
        long low = lowerEdge(bucket);
        long high = upperEdge(bucket);
        double fraction = (rankInBucket - 0.5) / inBucket;
        return low + (high - low) * Math.min(1.0, Math.max(0.0, fraction));
    }
}
