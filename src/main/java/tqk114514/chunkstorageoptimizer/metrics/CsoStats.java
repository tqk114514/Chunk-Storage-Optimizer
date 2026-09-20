package tqk114514.chunkstorageoptimizer.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide counters for the CSO storage layer.
 *
 * <p>Needed because "is it faster?" cannot be answered by reading code — it has to be measured,
 * and the vanilla JFR hooks ({@code JvmProfiler.onRegionFileRead/Write}) are bypassed entirely once
 * we take over {@code RegionFileStorage}.
 *
 * <p>All counters are {@link LongAdder}: the IO worker is single-threaded per storage, but
 * {@code scanChunk} can arrive from a different pool.
 */
public final class CsoStats {

    private static final LongAdder CHUNKS_READ = new LongAdder();
    private static final LongAdder CHUNKS_WRITTEN = new LongAdder();
    private static final LongAdder CHUNKS_DELETED = new LongAdder();
    private static final LongAdder BUCKET_DECOMPRESSIONS = new LongAdder();
    private static final LongAdder BUCKET_COMPRESSIONS = new LongAdder();
    private static final LongAdder CACHE_HITS = new LongAdder();
    private static final LongAdder CACHE_MISSES = new LongAdder();
    private static final LongAdder DECOMPRESS_NANOS = new LongAdder();
    private static final LongAdder COMPRESS_NANOS = new LongAdder();
    private static final LongAdder COMPACTION_NANOS = new LongAdder();
    private static final LongAdder COMPACTIONS = new LongAdder();
    private static final LongAdder RAW_BYTES_IN = new LongAdder();
    private static final LongAdder RAW_BYTES_OUT = new LongAdder();
    private static final LongAdder STORED_BYTES = new LongAdder();
    private static final LongAdder IO_READ_BYTES = new LongAdder();
    private static final LongAdder IO_WRITE_BYTES = new LongAdder();
    private static final LongAdder BATCH_FLUSHES = new LongAdder();

    private CsoStats() {
    }

    // --- recording ---

    public static void chunkRead() {
        CHUNKS_READ.increment();
    }

    public static void chunkWritten() {
        CHUNKS_WRITTEN.increment();
    }

    public static void chunkDeleted() {
        CHUNKS_DELETED.increment();
    }

    public static void cacheHit() {
        CACHE_HITS.increment();
    }

    public static void bucketDecompressed(long nanos, int compressedBytes, int rawBytes) {
        BUCKET_DECOMPRESSIONS.increment();
        CACHE_MISSES.increment();
        DECOMPRESS_NANOS.add(nanos);
        IO_READ_BYTES.add(compressedBytes);
        RAW_BYTES_OUT.add(rawBytes);
    }

    public static void bucketCompressed(long nanos, int rawBytes, int compressedBytes) {
        BUCKET_COMPRESSIONS.increment();
        COMPRESS_NANOS.add(nanos);
        RAW_BYTES_IN.add(rawBytes);
        STORED_BYTES.add(compressedBytes);
    }

    public static void compaction(long nanos) {
        COMPACTIONS.increment();
        COMPACTION_NANOS.add(nanos);
    }

    public static void ioWrite(int bytes) {
        IO_WRITE_BYTES.add(bytes);
    }

    public static void batchFlushed() {
        BATCH_FLUSHES.increment();
    }

    // --- reporting ---

    public record Snapshot(
        long chunksRead,
        long chunksWritten,
        long chunksDeleted,
        long bucketDecompressions,
        long bucketCompressions,
        long cacheHits,
        long cacheMisses,
        long compressNanos,
        long decompressNanos,
        long compactionNanos,
        long compactions,
        long rawBytesIn,
        long rawBytesOut,
        long storedBytes,
        long ioReadBytes,
        long ioWriteBytes,
        long batchFlushes
    ) {
        public double cacheHitRatio() {
            long total = this.cacheHits + this.cacheMisses;
            return total == 0 ? 0.0 : (double) this.cacheHits / total;
        }

        /** Compression achieved on the write path: raw bytes in vs bytes actually stored. */
        public double writeRatio() {
            return this.storedBytes == 0 ? 0.0 : (double) this.rawBytesIn / this.storedBytes;
        }

        /** Chunks served per bucket decompression — the payoff of batching and caching. */
        public double chunksPerDecompression() {
            return this.bucketDecompressions == 0 ? 0.0 : (double) this.chunksRead / this.bucketDecompressions;
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            CHUNKS_READ.sum(),
            CHUNKS_WRITTEN.sum(),
            CHUNKS_DELETED.sum(),
            BUCKET_DECOMPRESSIONS.sum(),
            BUCKET_COMPRESSIONS.sum(),
            CACHE_HITS.sum(),
            CACHE_MISSES.sum(),
            COMPRESS_NANOS.sum(),
            DECOMPRESS_NANOS.sum(),
            COMPACTION_NANOS.sum(),
            COMPACTIONS.sum(),
            RAW_BYTES_IN.sum(),
            RAW_BYTES_OUT.sum(),
            STORED_BYTES.sum(),
            IO_READ_BYTES.sum(),
            IO_WRITE_BYTES.sum(),
            BATCH_FLUSHES.sum()
        );
    }

    public static void reset() {
        for (LongAdder adder : new LongAdder[] {
            CHUNKS_READ, CHUNKS_WRITTEN, CHUNKS_DELETED,
            BUCKET_DECOMPRESSIONS, BUCKET_COMPRESSIONS,
            CACHE_HITS, CACHE_MISSES,
            COMPRESS_NANOS, DECOMPRESS_NANOS, COMPACTION_NANOS, COMPACTIONS,
            RAW_BYTES_IN, RAW_BYTES_OUT, STORED_BYTES,
            IO_READ_BYTES, IO_WRITE_BYTES, BATCH_FLUSHES
        }) {
            adder.reset();
        }
    }
}
