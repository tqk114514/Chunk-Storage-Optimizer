package tqk114514.chunkstorageoptimizer.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tqk114514.chunkstorageoptimizer.format.CsoFormat;

/**
 * These bounds are the contract every loader's config file has to satisfy, so they are checked
 * without a game or a config subsystem on the classpath.
 */
class CsoSettingsTest {

    private static CsoSettings withGrid(int grid) {
        return CsoSettings.normalized(grid, CsoFormat.COMPRESSION_ZSTD, 3, 4, true, true, 1L << 20, 0.25, 16, 5000L);
    }

    @Test
    void gridSnapsDownToAPowerOfTwo() {
        assertEquals(16, withGrid(16).grid());
        assertEquals(2, withGrid(3).grid(), "3 is not a legal edge length; 2 is the next one down");
        assertEquals(16, withGrid(17).grid());
        assertEquals(1, withGrid(0).grid());
        assertEquals(1, withGrid(-8).grid());
        assertEquals(32, withGrid(1000).grid(), "the grid is capped at the region edge, not left huge");
    }

    @Test
    void everyFieldStaysInsideItsRange() {
        CsoSettings wild = CsoSettings.normalized(32, CsoFormat.COMPRESSION_ZSTD, 0, -5, true, true,
            1L, 0.0, 0, 0L);
        assertEquals(CsoSettings.MIN_LEVEL, wild.level(), "level 0 would not compress at all");
        assertEquals(CsoSettings.MIN_CACHED_BUCKETS, wild.cachedBuckets());
        assertEquals(CsoSettings.MIN_COMPACTION_BYTES, wild.compactionMinBytes());
        assertEquals(CsoSettings.MIN_COMPACTION_RATIO, wild.compactionRatio(), 0.0);
        assertEquals(CsoSettings.MIN_BATCH_CHUNKS, wild.batchMaxChunks());
        assertEquals(CsoSettings.MIN_BATCH_DELAY_MS, wild.batchMaxDelayMs());

        CsoSettings bigger = CsoSettings.normalized(32, CsoFormat.COMPRESSION_ZSTD, 999, 1000, true, true,
            Long.MAX_VALUE, 1e9, 100_000, Long.MAX_VALUE);
        assertEquals(CsoSettings.MAX_LEVEL, bigger.level());
        assertEquals(CsoSettings.MAX_CACHED_BUCKETS, bigger.cachedBuckets());
        assertEquals(CsoSettings.MAX_COMPACTION_RATIO, bigger.compactionRatio(), 0.0);
        assertEquals(CsoSettings.MAX_BATCH_CHUNKS, bigger.batchMaxChunks());
        assertEquals(CsoSettings.MAX_BATCH_DELAY_MS, bigger.batchMaxDelayMs());
    }

    @Test
    void unknownCompressionFallsBackToZstd() {
        assertEquals(CsoFormat.COMPRESSION_NONE, CsoSettings.compressionId("none"));
        assertEquals(CsoFormat.COMPRESSION_NONE, CsoSettings.compressionId("NONE"));
        assertEquals(CsoFormat.COMPRESSION_ZSTD, CsoSettings.compressionId("lzma"));
        assertEquals(CsoFormat.COMPRESSION_ZSTD, CsoSettings.compressionId(""));
        assertEquals("zstd", CsoSettings.compressionName(CsoFormat.COMPRESSION_ZSTD));
        assertEquals("none", CsoSettings.compressionName(CsoFormat.COMPRESSION_NONE));
    }

    @Test
    void compressionFieldCannotSmuggleInAnotherCodec() {
        CsoSettings none = CsoSettings.normalized(16, CsoFormat.COMPRESSION_NONE, 3, 4, true, true,
            1L << 20, 0.25, 16, 5000L);
        assertEquals(CsoFormat.COMPRESSION_NONE, none.compressionId());
        // Anything that is not "none" ends up as zstd, never a third value the format cannot read.
        assertNotEquals(CsoFormat.COMPRESSION_NONE, withGrid(16).compressionId());
    }

    @Test
    void defaultsAreInsideEveryRangeAndUsable() {
        CsoSettings d = CsoSettings.defaults();
        assertEquals(16, d.grid());
        assertEquals(CsoFormat.COMPRESSION_ZSTD, d.compressionId());
        assertTrue(d.fallbackToMca(), "shipping with the fallback off can regenerate terrain");
        assertTrue(d.verifyCrc(), "shipping with CRC checks off hides corruption instead of reporting it");
        assertEquals(d, CsoSettings.normalized(d.grid(), d.compressionId(), d.level(), d.cachedBuckets(),
            d.verifyCrc(), d.fallbackToMca(), d.compactionMinBytes(), d.compactionRatio(),
            d.batchMaxChunks(), d.batchMaxDelayMs()), "defaults must survive their own normalisation");
    }
}
