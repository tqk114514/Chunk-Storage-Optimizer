package tqk114514.chunkstorageoptimizer.storage;

import tqk114514.chunkstorageoptimizer.format.CsoFormat;

/**
 * Runtime settings for the CSO storage layer.
 *
 * <p>Kept as a plain record so the storage layer stays testable without a config subsystem or a
 * game on the classpath.
 *
 * <p>Every loader reads its own file format and hands the raw numbers to {@link #normalized}, so
 * the legal ranges, the grid snapping and the compression names exist once rather than once per
 * loader. Nothing downstream has to trust a hand-edited config.
 *
 * @param grid               bucket grid edge length, snapped to a power of two within 1..32. Only
 *                           applies to newly created region files; existing files keep the grid
 *                           they were born with.
 * @param compressionId      see {@link CsoFormat#COMPRESSION_NONE} and {@link CsoFormat#COMPRESSION_ZSTD}
 * @param level              zstd level for the hot write path
 * @param cachedBuckets      decompressed buckets kept in memory per region file
 * @param verifyCrc          validate bucket CRC32 on read
 * @param fallbackToMca      read through to the original {@code .mca} when a chunk is absent
 * @param compactionMinBytes minimum wasted bytes before compaction
 * @param compactionRatio    wasted/live ratio before compaction
 */
public record CsoSettings(
    int grid,
    int compressionId,
    int level,
    int cachedBuckets,
    boolean verifyCrc,
    boolean fallbackToMca,
    long compactionMinBytes,
    double compactionRatio,
    int batchMaxChunks,
    long batchMaxDelayMs
) {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 22;
    public static final int MIN_CACHED_BUCKETS = 0;
    public static final int MAX_CACHED_BUCKETS = 64;
    public static final long MIN_COMPACTION_BYTES = 4096L;
    public static final double MIN_COMPACTION_RATIO = 0.01;
    public static final double MAX_COMPACTION_RATIO = 10.0;
    public static final int MIN_BATCH_CHUNKS = 1;
    public static final int MAX_BATCH_CHUNKS = 1024;
    public static final long MIN_BATCH_DELAY_MS = 100L;
    public static final long MAX_BATCH_DELAY_MS = 600_000L;

    public static CsoSettings defaults() {
        // cachedBuckets defaults to the top of its range: the working set around one player
        // is dozens of buckets at the default grid (render distance 12 covers ~150 of them),
        // and the cost of a miss is a full bucket decompress per chunk read. The per-file
        // byte ceiling in CsoRegionFile is what keeps small grids safe where one payload is
        // huge; at grid=16 the count knob stays the effective limit.
        return normalized(16, CsoFormat.COMPRESSION_ZSTD, 3, 64, true, true, 4L * 1024 * 1024, 0.25, 16, 5000L);
    }

    /**
     * Clamps every field into its legal range and snaps the grid, so a typo in a config file
     * degrades to a nearby working value instead of either refusing to load or corrupting the
     * bucket layout.
     */
    public static CsoSettings normalized(
        int grid, int compressionId, int level, int cachedBuckets, boolean verifyCrc, boolean fallbackToMca,
        long compactionMinBytes, double compactionRatio, int batchMaxChunks, long batchMaxDelayMs
    ) {
        return new CsoSettings(
            snapGrid(grid),
            compressionId == CsoFormat.COMPRESSION_NONE ? CsoFormat.COMPRESSION_NONE : CsoFormat.COMPRESSION_ZSTD,
            (int) Math.clamp(level, MIN_LEVEL, MAX_LEVEL),
            (int) Math.clamp(cachedBuckets, MIN_CACHED_BUCKETS, MAX_CACHED_BUCKETS),
            verifyCrc,
            fallbackToMca,
            Math.max(compactionMinBytes, MIN_COMPACTION_BYTES),
            Math.clamp(compactionRatio, MIN_COMPACTION_RATIO, MAX_COMPACTION_RATIO),
            (int) Math.clamp(batchMaxChunks, MIN_BATCH_CHUNKS, MAX_BATCH_CHUNKS),
            Math.clamp(batchMaxDelayMs, MIN_BATCH_DELAY_MS, MAX_BATCH_DELAY_MS)
        );
    }

    /** The name a config file uses for a compression setting. */
    public static String compressionName(int id) {
        return id == CsoFormat.COMPRESSION_NONE ? "none" : "zstd";
    }

    /** Maps a config word to a compression id; anything unrecognised stays on the default codec. */
    public static int compressionId(String name) {
        return "none".equalsIgnoreCase(name) ? CsoFormat.COMPRESSION_NONE : CsoFormat.COMPRESSION_ZSTD;
    }

    /**
     * Rounds down to a legal bucket grid: a power of two within {@code CsoFormat}'s bounds. Rounding
     * down rather than rejecting keeps a bad config from stopping the world.
     */
    public static int snapGrid(int value) {
        int clamped = Math.clamp(value, CsoFormat.MIN_GRID, CsoFormat.MAX_GRID);
        int snapped = Integer.highestOneBit(clamped);
        return Math.max(snapped, CsoFormat.MIN_GRID);
    }
}
