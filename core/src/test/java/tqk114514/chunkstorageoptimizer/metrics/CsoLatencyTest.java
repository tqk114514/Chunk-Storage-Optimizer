package tqk114514.chunkstorageoptimizer.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CsoLatencyTest {

    private static final long MICROS = 1_000L;
    private static final long MILLIS = 1_000_000L;

    @Test
    void emptyHistogramReportsNothing() {
        CsoLatency latency = new CsoLatency();

        assertEquals(0L, latency.count());
        assertEquals(0.0, latency.percentileNanos(50.0));
        assertEquals(0L, latency.maxNanos());
    }

    @Test
    void percentileFallsInTheBucketThatHoldsTheRank() {
        CsoLatency latency = new CsoLatency();
        for (int i = 0; i < 100; i++) {
            latency.record(MILLIS);
        }
        latency.record(1_000_000_000L);

        assertEquals(101L, latency.count());
        assertEquals(1_000_000_000L, latency.maxNanos());
        // 1 ms sits in the bucket [524,288 .. 1,048,575) ns; a binned histogram cannot do better
        // than naming that bucket, so that is what the assertion checks.
        assertInBucket(latency.percentileNanos(50.0), 524_288L, 1_048_575L);
        assertInBucket(latency.percentileNanos(99.0), 524_288L, 1_048_575L);
        assertInBucket(latency.percentileNanos(100.0), 536_870_912L, 1_073_741_824L);
    }

    @Test
    void percentilesAreMonotonic() {
        CsoLatency latency = new CsoLatency();
        for (int i = 1; i <= 1000; i++) {
            latency.record(i * MILLIS);
        }

        double p50 = latency.percentileNanos(50.0);
        double p95 = latency.percentileNanos(95.0);
        double p99 = latency.percentileNanos(99.0);

        assertTrue(p50 <= p95 && p95 <= p99 && p99 <= latency.maxNanos(),
            "expected p50 <= p95 <= p99 <= max, got " + p50 + "/" + p95 + "/" + p99 + "/" + latency.maxNanos());
        // Half of the samples are at or below 500 ms; the bucket bound is allowed to overshoot but
        // not by more than a factor of two.
        assertTrue(p50 > 250 * MILLIS / 2 && p50 < 1000 * MILLIS, "p50 was " + p50);
    }

    @Test
    void negativeDurationsAreDropped() {
        CsoLatency latency = new CsoLatency();
        latency.record(-MICROS);

        assertEquals(0L, latency.count(), "a negative duration means the caller measured nonsense");
    }

    @Test
    void resetClearsCountAndMax() {
        CsoLatency latency = new CsoLatency();
        latency.record(10 * MILLIS);
        latency.record(MICROS);
        latency.reset();

        assertEquals(0L, latency.count());
        assertEquals(0L, latency.maxNanos());
        latency.record(MICROS);
        assertEquals(1L, latency.count(), "recording after reset must work");
        assertEquals(MICROS, latency.maxNanos());
    }

    private static void assertInBucket(double value, long lowInclusive, long highExclusive) {
        assertTrue(value >= lowInclusive && value < highExclusive,
            "expected " + lowInclusive + "<=" + value + "<" + highExclusive);
    }
}
