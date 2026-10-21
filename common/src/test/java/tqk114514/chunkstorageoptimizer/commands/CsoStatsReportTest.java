package tqk114514.chunkstorageoptimizer.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tqk114514.chunkstorageoptimizer.metrics.CsoStats;

/**
 * The stats report's treatment of a metric with nothing behind it.
 *
 * <p>Every derived figure in {@code /cso stats} answers "over what denominator", and a zero
 * denominator used to print as {@code 0.0} — so a brand-new world reported the cache as failing
 * ({@code hit=0.0%}) at the exact moment it had not needed a single decompression. Measured on a
 * live 1.21.11 server: {@code read=856, hit=100.0%, decompress=0} printed
 * {@code (0.00 chunks per decompression)}, and the same line with no lookups at all printed
 * {@code hit=0.0%}. Both are the best possible outcome wearing the costume of the worst.
 */
class CsoStatsReportTest {

    private static CsoStats.Snapshot snapshot(
        long reads, long decompressions, long hits, long misses, long rawIn, long stored
    ) {
        return new CsoStats.Snapshot(
            reads, 0, 0, decompressions, 0, hits, misses,
            0, 0, 0, 0, rawIn, 0, stored, 0, 0, 0
        );
    }

    @Test
    void aMetricWithNoSampleSaysSoInsteadOfReadingLikeAFailure() {
        // Nothing measured at all: no lookups, no decompressions, nothing stored.
        String report = CsoCommands.format(snapshot(0, 0, 0, 0, 0, 0), "active");

        assertTrue(report.lines().anyMatch(l -> l.contains("cache") && l.contains("hit=n/a")),
            "a cache that was never consulted has no hit rate to report:\n" + report);
        assertTrue(report.contains("(n/a chunks per decompression)"),
            "zero decompressions is not a ratio of zero");
        assertTrue(report.contains("ratio=n/a"),
            "with nothing stored there is no compression ratio either");
    }

    @Test
    void aPerfectCacheReportsItsHitsAndNoDecompressions() {
        // The case measured live on a fresh world: 856 chunks read, every one from the cache, so
        // not a single decompression happened. The hit rate is real here and must still print —
        // what used to be wrong is the second number, which came out as 0.00 and read like the
        // worst cache result available.
        String report = CsoCommands.format(snapshot(856, 0, 856, 0, 12_600_000, 1_500_000), "active");

        assertTrue(report.lines().anyMatch(l -> l.contains("cache") && l.contains("hit=100.0%")),
            "856 hits out of 856 lookups is a real measurement and belongs in the report:\n" + report);
        assertTrue(report.contains("(n/a chunks per decompression)"),
            "while the decompressions denominator is still empty");
        assertTrue(report.contains("ratio=8.40x"), "and a stored byte count does support a ratio");
    }

    @Test
    void aSampledMetricStillPrintsItsNumber() {
        String report = CsoCommands.format(snapshot(1000, 250, 750, 250, 1_000_000, 100_000), "active");

        assertTrue(report.contains("hit=75.0%"), "three quarters of lookups hit: " + report);
        assertTrue(report.contains("(4.00 chunks per decompression)"),
            "four chunks served per decompression is the point of the format");
        assertTrue(report.contains("ratio=10.00x"), "one megabyte in, a hundredth stored");
    }
}
