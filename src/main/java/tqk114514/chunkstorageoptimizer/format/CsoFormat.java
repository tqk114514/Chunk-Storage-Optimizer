package tqk114514.chunkstorageoptimizer.format;

import java.nio.charset.StandardCharsets;

/**
 * CSO region file format constants and index math.
 *
 * <p>Layout: {@code [FileHeader 128B][BucketTable N*24B][data blocks...]}.
 * All multi-byte integers are little-endian.
 */
public final class CsoFormat {
    /** 8 bytes, ASCII. Also encodes the major format generation. */
    public static final byte[] MAGIC = "CSOREG01".getBytes(StandardCharsets.US_ASCII);
    public static final int MAGIC_LENGTH = 8;
    public static final int FORMAT_VERSION = 1;

    public static final int HEADER_SIZE = 128;
    /**
     * 32 bytes, not 24: a power of two that divides the 512-byte disk sector, so a single table
     * entry can never straddle a sector boundary and tear. The extra bytes buy a sequence number
     * and a per-entry CRC, which is what makes crash recovery possible.
     */
    public static final int BUCKET_ENTRY_SIZE = 32;
    /** Two copies of the bucket table, written alternately. See {@link #tableOffset}. */
    public static final int TABLE_COUNT = 2;
    public static final int CHUNK_ENTRY_SIZE = 12;
    public static final int REGION_CHUNKS = 32;

    // Bucket table entry field offsets (32 bytes total).
    public static final int ENTRY_CRC = 0;       // u32, CRC32 of bytes 4..31 (self-check)
    public static final int ENTRY_OFFSET = 4;    // u64
    public static final int ENTRY_COMP_LEN = 12; // u32
    public static final int ENTRY_RAW_LEN = 16;  // u32
    public static final int ENTRY_CRC32 = 20;    // u32, CRC32 of the bucket payload
    public static final int ENTRY_CHUNKS = 24;   // u32
    public static final int ENTRY_SEQUENCE = 28; // u32, monotonic per write

    public static final int COMPRESSION_NONE = 0;
    public static final int COMPRESSION_ZSTD = 1;

    public static final int MIN_GRID = 1;
    public static final int MAX_GRID = 32;

    private CsoFormat() {
    }

    public static void validateGrid(int grid) {
        if (grid < MIN_GRID || grid > MAX_GRID || Integer.bitCount(grid) != 1) {
            throw new IllegalArgumentException("grid must be a power of two within [1, 32], got " + grid);
        }
    }

    /** Chunks along one edge of a bucket. */
    public static int span(int grid) {
        return REGION_CHUNKS / grid;
    }

    public static int chunksPerBucket(int grid) {
        int s = span(grid);
        return s * s;
    }

    public static int bucketCount(int grid) {
        return grid * grid;
    }

    /** Bucket ordinal inside a region file for the given region-local chunk coordinates. */
    public static int bucketIndex(int localX, int localZ, int grid) {
        int s = span(grid);
        return (localZ / s) * grid + (localX / s);
    }

    /** Ordinal of a chunk inside its bucket payload. */
    public static int chunkIndexInBucket(int localX, int localZ, int grid) {
        int s = span(grid);
        return (localZ % s) * s + (localX % s);
    }

    /** Offset of one bucket entry inside one of the two table copies. */
    public static int tableOffset(int table, int bucket, int bucketCount) {
        return HEADER_SIZE + (table * bucketCount + bucket) * BUCKET_ENTRY_SIZE;
    }

    public static int dataStart(int bucketCount) {
        return HEADER_SIZE + bucketCount * BUCKET_ENTRY_SIZE * TABLE_COUNT;
    }

    // --- little-endian helpers on byte[] ---

    public static int readShort(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    public static void writeShort(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    public static int readInt(byte[] b, int off) {
        return (b[off] & 0xFF)
            | ((b[off + 1] & 0xFF) << 8)
            | ((b[off + 2] & 0xFF) << 16)
            | ((b[off + 3] & 0xFF) << 24);
    }

    public static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    public static void writeLong(byte[] b, int off, long v) {
        writeInt(b, off, (int) v);
        writeInt(b, off + 4, (int) (v >>> 32));
    }
}
