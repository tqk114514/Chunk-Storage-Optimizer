package tqk114514.chunkstorageoptimizer.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adversarial tests written during a bug-hunting review. Each test asserts the
 * behaviour the format documents; a failure here is a real defect, not a bad
 * test.
 */
class CsoRegionFileAdversarialTest {

    private static final int GRID = 8;
    private static final int LEVEL = 3;

    private static CsoRegionFile open(Path dir, int compressionId) throws IOException {
        return CsoRegionFile.open(
            dir.resolve("r.0.0.cso"), GRID, compressionId, LEVEL,
            4, true, Long.MAX_VALUE, 10.0
        );
    }

    /**
     * Whether this platform can produce the failure the two compaction tests need: a swap that fails
     * while the file itself stays usable.
     *
     * <p>On POSIX a read-only parent directory is enough — the file's own permissions are untouched,
     * so reopening it still works, which is exactly what those tests go on to exercise. On Windows
     * the read-only attribute belongs to the file, so blocking the replacement blocks the reopen
     * with it and the object is left holding a closed channel. There is no third option to reach
     * for: Java's file handles always share delete, so holding the file open cannot fail the move
     * either. Skipped rather than asserted where it cannot be done, so a Windows run stays green
     * while CI, on Linux, still covers the behaviour.
     */
    private static boolean canFailASwapAndKeepTheFileUsable() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static byte[] chunkData(int seed, int size) {
        byte[] out = new byte[size];
        Random random = new Random(seed);
        int p = 0;
        while (p < size) {
            byte[] token = ("minecraft:" + random.nextInt(64) + "_block").getBytes();
            int n = Math.min(token.length, size - p);
            System.arraycopy(token, 0, out, p, n);
            p += n;
        }
        return out;
    }

    private static byte[] readFully(Path path, long position, int length) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                channel.read(buffer, position + buffer.position());
            }
            return buffer.array();
        }
    }

    private static boolean entryIsBlank(Path path, int table, int bucket, int bucketCount)
        throws IOException {
        byte[] entry = readFully(
            path, CsoFormat.tableOffset(table, bucket, bucketCount), CsoFormat.BUCKET_ENTRY_SIZE);
        for (byte b : entry) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    // ----------------------------------------------------------------------
    // Bug 1: the file header records the compression codec (h[12]), but open()
    // builds the decompressor from the CONFIG, never from the file. grid adapts
    // to the file; compression does not. Changing the config key makes every
    // existing .cso unreadable — chunk reads throw CsoCorruptedException, which
    // the game treats as "missing chunk" and regenerates terrain.
    // ----------------------------------------------------------------------

    @Test
    void fileWrittenWithZstdMustReadWithCompressionNoneConfigured(@TempDir Path dir)
        throws IOException {
        byte[] data = chunkData(1, 1500);
        try (CsoRegionFile file = open(dir, CsoFormat.COMPRESSION_ZSTD)) {
            file.writeChunk(0, 0, data);
        }
        // Same file, config now says compression=none — the file should still
        // decode, because the codec is recorded in its header.
        try (CsoRegionFile file = open(dir, CsoFormat.COMPRESSION_NONE)) {
            assertArrayEquals(data, file.readChunk(0, 0));
        }
    }

    @Test
    void fileWrittenWithNoneMustReadWithZstdConfigured(@TempDir Path dir) throws IOException {
        byte[] data = chunkData(2, 1500);
        try (CsoRegionFile file = open(dir, CsoFormat.COMPRESSION_NONE)) {
            file.writeChunk(0, 0, data);
        }
        try (CsoRegionFile file = open(dir, CsoFormat.COMPRESSION_ZSTD)) {
            assertArrayEquals(data, file.readChunk(0, 0));
        }
    }

    // ----------------------------------------------------------------------
    // Bug 2: compact() sets lastTable[b]=0 while still writing the .tmp file —
    // BEFORE the Files.move that commits the new layout. When the move fails
    // but the object keeps serving (reopen succeeded: e.g. Windows file locking
    // or a read-only-directory swap attempt), the next write targets the table
    // copy that is still the ONLY valid one on disk, leaving zero intact copies
    // during the write window. One torn write then kills the whole file.
    //
    // Reproduced deterministically on Linux: the .tmp is pre-created so compact
    // can open it, then the directory is made read-only so the rename fails
    // while both files stay perfectly readable/writable.
    // ----------------------------------------------------------------------

    @Test
    void failedCompactionMustNotMoveWritesOntoTheNewestTableCopy(@TempDir Path dir)
        throws IOException {
        Assumptions.assumeTrue(canFailASwapAndKeepTheFileUsable(), "no way to fail the swap here");
        Path path = dir.resolve("r.0.0.cso");
        int bucketCount = CsoFormat.bucketCount(GRID);
        byte[] first = chunkData(11, 800);
        byte[] second = chunkData(12, 900);

        CsoRegionFile file = open(dir, CsoFormat.COMPRESSION_ZSTD);
        try {
            file.writeChunk(0, 0, first); // first write lands on table 1

            // Sanity: disk newest copy is table 1, table 0 blank.
            assertTrue(entryIsBlank(path, 0, 0, bucketCount));
            assertFalse(entryIsBlank(path, 1, 0, bucketCount));

            // Let compact() open its .tmp but never rename it.
            Files.write(dir.resolve("r.0.0.cso.tmp"), new byte[0]);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                assertThrows(IOException.class, file::compact, "the swap must fail");

                // The object survived (reopen succeeded). The next write must go
                // to the STALE copy (table 0). Under the bug it goes to table 1 —
                // overwriting the only intact copy.
                file.writeChunk(0, 0, second);
                assertFalse(
                    entryIsBlank(path, 0, 0, bucketCount),
                    "write after a failed compaction overwrote the newest copy — "
                        + "the file again had only ONE valid table entry during the write");
            } finally {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        } finally {
            file.close();
        }
    }

    /**
     * The consequence end of bug 2: after the failed swap + a write (which the
     * bug aimed at the live copy), a single torn entry destroys the file. Had
     * the write gone to the stale copy, the tear would have fallen back.
     */
    @Test
    void failedCompactionLeavesZeroRedundancySoOneTornEntryIsFatal(@TempDir Path dir)
        throws IOException {
        Assumptions.assumeTrue(canFailASwapAndKeepTheFileUsable(), "no way to fail the swap here");
        Path path = dir.resolve("r.0.0.cso");
        int bucketCount = CsoFormat.bucketCount(GRID);

        CsoRegionFile file = open(dir, CsoFormat.COMPRESSION_ZSTD);
        try {
            file.writeChunk(0, 0, chunkData(21, 800));
            Files.write(dir.resolve("r.0.0.cso.tmp"), new byte[0]);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                assertThrows(IOException.class, file::compact);
                file.writeChunk(0, 0, chunkData(22, 900));
            } finally {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        } finally {
            file.close();
        }

        // Simulate the crash the write window invited: tear the live entry.
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.write(
                ByteBuffer.wrap(new byte[] {0, 0, 0, 0}),
                CsoFormat.tableOffset(1, 0, bucketCount) + CsoFormat.ENTRY_CRC);
        }

        // Table 0 is still blank (the write never went there), so this is the
        // "both copies unreadable" case — one 4-byte tear kills the file. With
        // the write correctly aimed at the stale copy, this opens and serves
        // the last committed chunk.
        try (CsoRegionFile reopened = open(dir, CsoFormat.COMPRESSION_ZSTD)) {
            assertArrayEquals(
                chunkData(22, 900), reopened.readChunk(0, 0),
                "the surviving table copy must serve the last committed write");
        }
    }

    // ----------------------------------------------------------------------
    // Bug 3: a failed open() leaks the FileChannel. open() creates the channel
    // before validating anything; if parseGrid / readMetadata / replayWal
    // throws, the channel is never closed — one fd (and on Windows a file
    // lock) per failed attempt.
    // ----------------------------------------------------------------------

    private static int openFileDescriptors() {
        return new File("/proc/self/fd").list().length;
    }

    @Test
    void failedOpenMustNotLeakTheFileChannel(@TempDir Path dir) throws IOException {
        // Counting descriptors has no portable equivalent: /proc/self/fd is Linux's. Skipped where it
        // does not exist rather than asserted, so a Windows run stays green — CI, on Linux, still
        // covers the leak itself.
        Assumptions.assumeTrue(
            new File("/proc/self/fd").isDirectory(), "no /proc/self/fd to count open descriptors with");

        Path path = dir.resolve("r.0.0.cso");
        try (CsoRegionFile file = open(dir, CsoFormat.COMPRESSION_ZSTD)) {
            file.writeChunk(1, 1, chunkData(31, 300));
        }
        // Corrupt the magic so open() throws after the channel is created.
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(new byte[] {'X', 'Y'}), 0L);
        }

        int before = openFileDescriptors();
        for (int i = 0; i < 5; i++) {
            assertThrows(CsoCorruptedException.class, () -> open(dir, CsoFormat.COMPRESSION_ZSTD));
        }
        assertEquals(
            before, openFileDescriptors(),
            "each rejected open leaked a FileChannel (" + before + " -> " + openFileDescriptors() + ")");
    }

    // ----------------------------------------------------------------------
    // Bug 4: the write path trusts chunk-entry offsets from a decompressed
    // payload without bounds checks; the read path checks. A payload whose
    // index names an out-of-range extent makes writeChunks throw an unchecked
    // IndexOutOfBoundsException instead of CsoCorruptedException.
    // ----------------------------------------------------------------------

    /** Hand-builds a .cso holding one bucket whose slot-0 index entry lies. */
    private static Path fileWithLyingIndex(Path dir, int grid) throws IOException {
        int bucketCount = CsoFormat.bucketCount(grid);
        int chunksPerBucket = CsoFormat.chunksPerBucket(grid);
        int dataStart = CsoFormat.dataStart(bucketCount);
        Path path = dir.resolve("r.0.0.cso");

        // Payload: index of chunksPerBucket*12 bytes, then chunk bytes.
        byte[] payload = new byte[chunksPerBucket * CsoFormat.CHUNK_ENTRY_SIZE + 64];
        int dataOffset = chunksPerBucket * CsoFormat.CHUNK_ENTRY_SIZE;
        // slot 0: offset points BEYOND the payload end — readChunk bounds-checks
        // and throws CsoCorruptedException; rebuildPayload does not and crashes.
        CsoFormat.writeInt(payload, 0, payload.length + 64);
        CsoFormat.writeInt(payload, 4, 40);
        CsoFormat.writeInt(payload, 8, 1);
        // slot 1: a sane entry so the payload is not all-corrupt.
        CsoFormat.writeInt(payload, CsoFormat.CHUNK_ENTRY_SIZE, dataOffset);
        CsoFormat.writeInt(payload, CsoFormat.CHUNK_ENTRY_SIZE + 4, 64);
        CsoFormat.writeInt(payload, CsoFormat.CHUNK_ENTRY_SIZE + 8, 1);
        for (int i = 0; i < 64; i++) {
            payload[dataOffset + i] = (byte) i;
        }

        CRC32 crc = new CRC32();
        crc.update(payload);
        int payloadCrc = (int) crc.getValue();
        byte[] compressed = Compressor.create(CsoFormat.COMPRESSION_ZSTD, LEVEL).compress(payload);

        // Header.
        byte[] file = new byte[dataStart + compressed.length];
        System.arraycopy(CsoFormat.MAGIC, 0, file, 0, CsoFormat.MAGIC_LENGTH);
        CsoFormat.writeShort(file, 8, CsoFormat.FORMAT_VERSION);
        CsoFormat.writeShort(file, 10, grid);
        file[12] = CsoFormat.COMPRESSION_ZSTD;
        file[13] = LEVEL;
        CsoFormat.writeShort(file, 14, 0);
        CsoFormat.writeInt(file, 16, bucketCount);
        CRC32 headerCrc = new CRC32();
        headerCrc.update(file, 0, 20);
        CsoFormat.writeInt(file, 20, (int) headerCrc.getValue());
        CsoFormat.writeInt(file, 24, 0); // region X
        CsoFormat.writeInt(file, 28, 0); // region Z
        CsoFormat.writeInt(file, 32, chunksPerBucket);

        // Table 1 entry for bucket 0 (first-write convention).
        byte[] entry = new byte[CsoFormat.BUCKET_ENTRY_SIZE];
        CsoFormat.writeLong(entry, CsoFormat.ENTRY_OFFSET, dataStart);
        CsoFormat.writeInt(entry, CsoFormat.ENTRY_COMP_LEN, compressed.length);
        CsoFormat.writeInt(entry, CsoFormat.ENTRY_RAW_LEN, payload.length);
        CsoFormat.writeInt(entry, CsoFormat.ENTRY_CRC32, payloadCrc);
        CsoFormat.writeInt(entry, CsoFormat.ENTRY_CHUNKS, 2);
        CsoFormat.writeInt(entry, CsoFormat.ENTRY_SEQUENCE, 1);
        CRC32 entryCrc = new CRC32();
        entryCrc.update(entry, 4, CsoFormat.BUCKET_ENTRY_SIZE - 4);
        CsoFormat.writeInt(entry, CsoFormat.ENTRY_CRC, (int) entryCrc.getValue());
        System.arraycopy(
            entry, 0, file, CsoFormat.tableOffset(1, 0, bucketCount), CsoFormat.BUCKET_ENTRY_SIZE);

        // Data block.
        System.arraycopy(compressed, 0, file, dataStart, compressed.length);
        Files.write(path, file);
        return path;
    }

    @Test
    void corruptChunkIndexMustFailWithCsoCorruptedExceptionNotUnchecked(@TempDir Path dir)
        throws IOException {
        fileWithLyingIndex(dir, GRID);
        try (CsoRegionFile file = open(dir, CsoFormat.COMPRESSION_ZSTD)) {
            // The read path has the bounds check.
            assertThrows(CsoCorruptedException.class, () -> file.readChunk(0, 0));
            // The write path does not: rebuilding the payload arraycopies from
            // the out-of-range offset and dies with an unchecked exception.
            assertThrows(
                CsoCorruptedException.class,
                () -> file.writeChunks(0, Map.of(1, chunkData(41, 100))),
                "a corrupt index must produce CsoCorruptedException, not an unchecked crash");
        }
    }

    // ----------------------------------------------------------------------
    // Bug 5: replayWal() applies whatever bucket ordinal the log names. A WAL
    // whose CRC validates (written whole, then grid semantics no longer match,
    // or hand-corrupted past the checksum) with a bucket >= bucketCount escapes
    // open() as an unchecked ArrayIndexOutOfBoundsException — the file cannot
    // even report itself as corrupt.
    // ----------------------------------------------------------------------

    @Test
    void outOfRangeWalBucketMustFailWithCsoCorruptedException(@TempDir Path dir) throws IOException {
        try (CsoRegionFile file = open(dir, CsoFormat.COMPRESSION_ZSTD)) {
            file.writeChunk(0, 0, chunkData(51, 800));
        }

        // A whole, checksummed WAL that names a bucket the file does not have.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[] {'C', 'S', 'O', 'W', 'A', 'L', 0});
        out.writeShort(CsoFormat.FORMAT_VERSION);
        out.writeInt(1);               // bucket count
        out.writeInt(999);             // bucket ordinal — out of range for grid 8 (64 buckets)
        out.writeInt(1);               // slot count
        out.writeInt(0);               // slot
        out.writeInt(4);               // data length
        out.write(new byte[] {1, 2, 3, 4});
        out.flush();
        byte[] payload = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);
        byte[] wal = new byte[payload.length + 4];
        System.arraycopy(payload, 0, wal, 0, payload.length);
        CsoFormat.writeInt(wal, payload.length, (int) crc.getValue());
        Files.write(dir.resolve("r.0.0.cso.wal"), wal);

        assertThrows(
            CsoCorruptedException.class,
            () -> open(dir, CsoFormat.COMPRESSION_ZSTD).close(),
            "a well-formed WAL naming a nonexistent bucket must fail as corruption");
    }
}
