package tqk114514.chunkstorageoptimizer.storage;

/**
 * Runtime settings for the CSO storage layer, supplied by the NeoForge config.
 *
 * <p>Kept as a plain record so the storage layer stays testable without a config subsystem.
 *
 * @param grid               bucket grid edge length (1..32, power of two). Only applies to newly
 *                           created region files; existing files keep the grid they were born with.
 * @param compressionId      see {@link tqk114514.chunkstorageoptimizer.format.CsoFormat}
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
    public static CsoSettings defaults() {
        return new CsoSettings(
            16,
            tqk114514.chunkstorageoptimizer.format.CsoFormat.COMPRESSION_ZSTD,
            3,
            4,
            true,
            true,
            4L * 1024 * 1024,
            0.25,
            16,
            5000L
        );
    }
}
