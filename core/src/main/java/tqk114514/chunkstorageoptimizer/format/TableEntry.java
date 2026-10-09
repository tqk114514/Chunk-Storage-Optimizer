package tqk114514.chunkstorageoptimizer.format;

import static tqk114514.chunkstorageoptimizer.format.CsoFormat.BUCKET_ENTRY_SIZE;

import java.util.zip.CRC32;

/**
 * One bucket's table entry, and the codec for the bytes each of the two table copies holds:
 * offset, both lengths, the payload's CRC, the chunk count, a monotonic sequence, and the
 * entry's own CRC — the field that tells a torn write from a bucket nobody ever wrote.
 *
 * <p>Package-visible fields rather than accessors: this is the format's own private
 * vocabulary, and every reader of it lives beside it.
 */
final class TableEntry {

    long offset;
    int compressedLength;
    int rawLength;
    int crc32;
    int chunkCount;
    int sequence;

    static byte[] encode(TableEntry e) {
        byte[] out = new byte[BUCKET_ENTRY_SIZE];
        writeLong(out, CsoFormat.ENTRY_OFFSET, e.offset);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_COMP_LEN, e.compressedLength);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_RAW_LEN, e.rawLength);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_CRC32, e.crc32);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_CHUNKS, e.chunkCount);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_SEQUENCE, e.sequence);
        CRC32 crc = new CRC32();
        crc.update(out, 4, BUCKET_ENTRY_SIZE - 4);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_CRC, (int) crc.getValue());
        return out;
    }

    /** @return the entry, or null when its own CRC fails — torn write, or never written. */
    static TableEntry decode(byte[] buffer, int base) {
        int expected = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_CRC);
        CRC32 crc = new CRC32();
        crc.update(buffer, base + 4, BUCKET_ENTRY_SIZE - 4);
        if ((int) crc.getValue() != expected) {
            return null;
        }
        TableEntry e = new TableEntry();
        e.offset = readLong(buffer, base + CsoFormat.ENTRY_OFFSET);
        e.compressedLength = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_COMP_LEN);
        e.rawLength = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_RAW_LEN);
        e.crc32 = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_CRC32);
        e.chunkCount = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_CHUNKS);
        e.sequence = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_SEQUENCE);
        return e;
    }

    /**
     * Whether a table entry is all zeros, which is what a bucket nobody ever wrote looks like.
     *
     * <p>This is the only shape that may be skipped without complaint. Its own CRC field is
     * zero too, but the CRC of the remaining 28 zero bytes is not, so {@link #decode} rejects
     * it the same way it rejects a torn entry — the two are told apart here instead.
     */
    static boolean isBlank(byte[] buffer, int base) {
        for (int i = 0; i < BUCKET_ENTRY_SIZE; i++) {
            if (buffer[base + i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static long readLong(byte[] b, int off) {
        return (CsoFormat.readInt(b, off) & 0xFFFFFFFFL) | ((long) CsoFormat.readInt(b, off + 4) << 32);
    }

    private static void writeLong(byte[] b, int off, long v) {
        CsoFormat.writeInt(b, off, (int) v);
        CsoFormat.writeInt(b, off + 4, (int) (v >>> 32));
    }
}
