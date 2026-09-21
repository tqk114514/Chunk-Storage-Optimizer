package tqk114514.chunkstorageoptimizer.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsoRegionFileTest {

    private static final int GRID = 8;
    private static final int COMPRESSION = CsoFormat.COMPRESSION_ZSTD;
    private static final int LEVEL = 3;

    private static CsoRegionFile open(Path dir, int grid) throws IOException {
        return openAt(dir, "r.0.0.cso", grid);
    }

    private static CsoRegionFile openAt(Path dir, String name, int grid) throws IOException {
        return CsoRegionFile.open(
            dir.resolve(name), grid, COMPRESSION, LEVEL,
            4,          // cached buckets
            true,       // verify CRC
            4096,       // compaction min wasted (small on purpose, to exercise it)
            0.25
        );
    }

    /** Semi-compressible blob that stands in for serialized chunk NBT. */
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

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, pos);
            if (n < 0) {
                throw new IOException("Unexpected EOF at " + pos);
            }
            pos += n;
        }
    }

    @Test
    void writeThenReadAllChunks(@TempDir Path dir) throws IOException {
        Map<Integer, byte[]> expected = new HashMap<>();
        try (CsoRegionFile file = open(dir, GRID)) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    byte[] data = chunkData(x * 32 + z, 400 + (x * z) % 900);
                    file.writeChunk(x, z, data);
                    expected.put(x * 32 + z, data);
                }
            }
        }

        try (CsoRegionFile file = open(dir, GRID)) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    assertArrayEquals(
                        expected.get(x * 32 + z),
                        file.readChunk(x, z),
                        "chunk (" + x + "," + z + ") mismatch"
                    );
                }
            }
        }
    }

    @Test
    void missingChunkIsNull(@TempDir Path dir) throws IOException {
        try (CsoRegionFile file = open(dir, GRID)) {
            assertNull(file.readChunk(3, 7));
            assertFalse(file.hasChunk(3, 7));
        }
    }

    @Test
    void deleteRemovesChunk(@TempDir Path dir) throws IOException {
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(5, 5, chunkData(1, 500));
            assertTrue(file.hasChunk(5, 5));
            file.deleteChunk(5, 5);
            assertFalse(file.hasChunk(5, 5));
            assertNull(file.readChunk(5, 5));
        }
    }

    @Test
    void overwriteDoesNotGrowWithoutBound(@TempDir Path dir) throws IOException {
        long before;
        try (CsoRegionFile file = open(dir, GRID)) {
            for (int i = 0; i < 400; i++) {
                file.writeChunk(2, 2, chunkData(i, 2000));
            }
            before = Files.size(dir.resolve("r.0.0.cso"));
        }
        // Rewriting one chunk 400 times must not leave 400 stale blocks behind.
        // With compaction enabled the file stays proportional to live data.
        assertTrue(before < 400L * 2000, "file grew to " + before + " bytes; compaction is not reclaiming space");

        try (CsoRegionFile file = open(dir, GRID)) {
            assertArrayEquals(chunkData(399, 2000), file.readChunk(2, 2));
        }
    }

    @Test
    void compactPreservesData(@TempDir Path dir) throws IOException {
        Map<Integer, byte[]> expected = new HashMap<>();
        try (CsoRegionFile file = open(dir, GRID)) {
            for (int i = 0; i < 64; i++) {
                byte[] data = chunkData(i, 1500);
                file.writeChunk(i % 32, i / 32, data);
                expected.put(i, data);
            }
            long before = Files.size(dir.resolve("r.0.0.cso"));
            file.compact();
            long after = Files.size(dir.resolve("r.0.0.cso"));
            assertTrue(after <= before, "compaction grew the file: " + before + " -> " + after);
        }

        try (CsoRegionFile file = open(dir, GRID)) {
            for (int i = 0; i < 64; i++) {
                assertArrayEquals(expected.get(i), file.readChunk(i % 32, i / 32));
            }
        }
    }

    @Test
    void gridIsReadFromExistingFile(@TempDir Path dir) throws IOException {
        try (CsoRegionFile file = open(dir, 4)) {
            file.writeChunk(10, 10, chunkData(7, 800));
        }
        // Reopening with a *different* configured grid must not reinterpret the data.
        try (CsoRegionFile file = open(dir, 8)) {
            assertArrayEquals(chunkData(7, 800), file.readChunk(10, 10));
        }
    }

    @Test
    void badMagicIsRejected(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.cso");
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(1, 1, chunkData(1, 300));
        }
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(new byte[] {'X', 'Y'}), 0L);
        }
        assertThrows(CsoCorruptedException.class, () -> open(dir, GRID));
    }

    @Test
    void misnamedRegionFileIsRejected(@TempDir Path dir) throws IOException {
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(1, 1, chunkData(1, 300));
        }
        // The header records which region it belongs to, so a file that has been renamed or copied
        // into another slot cannot quietly serve one region's chunks as another's.
        Files.move(dir.resolve("r.0.0.cso"), dir.resolve("r.0.1.cso"));
        assertThrows(CsoCorruptedException.class, () -> openAt(dir, "r.0.1.cso", GRID));
    }

    @Test
    void corruptedPayloadFailsCrc(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.cso");
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(1, 1, chunkData(1, 3000));
        }
        long size = Files.size(path);
        // Flip a byte in the middle of the data area.
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(new byte[] {(byte) 0xFF}), size - 8);
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            assertThrows(CsoCorruptedException.class, () -> file.readChunk(1, 1));
        }
    }

    @Test
    void zstdActuallyCompresses(@TempDir Path dir) throws IOException {
        try (CsoRegionFile file = open(dir, GRID)) {
            // Highly repetitive data: zstd must shrink it well below raw size.
            byte[] data = new byte[40000];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % 7);
            }
            file.writeChunk(0, 0, data);
            file.flush();
        }
        long size = Files.size(dir.resolve("r.0.0.cso"));
        assertTrue(size < 40000, "expected compression, file is " + size + " bytes for 40 KB of input");
    }

    @Test
    void batchWriteAppliesEverySlotInOnePass(@TempDir Path dir) throws IOException {
        // grid=8 -> span=4, so bucket 0 covers local (0..3, 0..3) = 16 slots.
        Map<Integer, byte[]> changes = new HashMap<>();
        for (int i = 0; i < 16; i++) {
            changes.put(i, chunkData(100 + i, 600));
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunks(0, changes);
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            for (int i = 0; i < 16; i++) {
                assertArrayEquals(chunkData(100 + i, 600), file.readChunk(i % 4, i / 4));
            }
            assertNull(file.readChunk(8, 8), "a slot outside the touched bucket must stay empty");
        }
    }

    @Test
    void batchWriteCanMixUpdateAndDelete(@TempDir Path dir) throws IOException {
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(0, 0, chunkData(1, 500));
            file.writeChunk(1, 0, chunkData(2, 500));
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            Map<Integer, byte[]> changes = new HashMap<>();
            changes.put(0, null);               // delete slot 0
            changes.put(1, chunkData(3, 700));  // replace slot 1
            file.writeChunks(0, changes);
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            assertNull(file.readChunk(0, 0));
            assertArrayEquals(chunkData(3, 700), file.readChunk(1, 0));
        }
    }

    @Test
    void rewriteAcrossReopenKeepsNewestCopy(@TempDir Path dir) throws IOException {
        byte[] first = chunkData(11, 800);
        byte[] second = chunkData(12, 900);
        byte[] third = chunkData(13, 1000);
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(0, 0, first);
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(0, 0, second);
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(0, 0, third);
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            assertArrayEquals(third, file.readChunk(0, 0));
        }
    }

    /**
     * Simulates the crash we actually care about: the process dies after writing a new data block
     * but while writing its table entry, leaving that entry torn. The previous table copy must
     * still be valid, so the file opens and serves the previous version instead of throwing.
     */
    @Test
    void tornTableEntryFallsBackToPreviousCopy(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.cso");
        byte[] first = chunkData(21, 800);
        byte[] second = chunkData(22, 900);

        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(0, 0, first);
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(0, 0, second);
        }

        // Corrupt whichever table copy currently holds the newest entry for bucket 0.
        int bucketCount = CsoFormat.bucketCount(GRID);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            byte[] tables = new byte[CsoFormat.BUCKET_ENTRY_SIZE * bucketCount * CsoFormat.TABLE_COUNT];
            readFully(channel, ByteBuffer.wrap(tables), (long) CsoFormat.HEADER_SIZE);
            int sequence0 = CsoFormat.readInt(tables, 0 * bucketCount * CsoFormat.BUCKET_ENTRY_SIZE
                + CsoFormat.ENTRY_SEQUENCE);
            int sequence1 = CsoFormat.readInt(tables, 1 * bucketCount * CsoFormat.BUCKET_ENTRY_SIZE
                + CsoFormat.ENTRY_SEQUENCE);
            int newest = sequence1 > sequence0 ? 1 : 0;
            long crcPosition = CsoFormat.tableOffset(newest, 0, bucketCount) + CsoFormat.ENTRY_CRC;
            channel.write(ByteBuffer.wrap(new byte[] {0, 0, 0, 0}), crcPosition);
        }

        // Must still open, and must serve the last intact version — not throw, not return garbage.
        try (CsoRegionFile file = open(dir, GRID)) {
            assertArrayEquals(first, file.readChunk(0, 0), "should fall back to the previous intact copy");
        }
    }

    @Test
    void walReplaysChangesThatWereNeverApplied(@TempDir Path dir) throws IOException {
        byte[] first = chunkData(31, 800);
        byte[] second = chunkData(32, 900);

        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunks(0, Map.of(0, first));
        }
        // Simulate the crash we care about: the batch reached the WAL and was forced to disk,
        // but the process died before applying it.
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeWal(Map.of(0, Map.of(0, second)));
        }

        // Reopening must replay the log, so the write is recovered rather than lost.
        try (CsoRegionFile file = open(dir, GRID)) {
            assertArrayEquals(second, file.readChunk(0, 0));
        }
    }

    @Test
    void tornWalIsIgnored(@TempDir Path dir) throws IOException {
        byte[] first = chunkData(41, 800);
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunks(0, Map.of(0, first));
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeWal(Map.of(0, Map.of(0, chunkData(42, 900))));
        }

        // Damage the WAL so it can never have been fully written.
        Path wal = dir.resolve("r.0.0.cso.wal");
        try (FileChannel channel = FileChannel.open(wal, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {0x7F}), 0);
        }

        // A torn WAL must be discarded, leaving the last consistent state — not half-applied.
        try (CsoRegionFile file = open(dir, GRID)) {
            assertArrayEquals(first, file.readChunk(0, 0), "a torn WAL must be ignored");
        }
    }

    @Test
    void indexMathIsConsistent() {
        for (int grid : new int[] {1, 2, 4, 8, 16, 32}) {
            int span = CsoFormat.span(grid);
            int per = CsoFormat.chunksPerBucket(grid);
            assertEquals(grid * grid, CsoFormat.bucketCount(grid), "bucket count for grid " + grid);
            assertEquals(span * span, per, "chunks per bucket for grid " + grid);
            // Slots are only unique *within* a bucket, so uniqueness is asserted on the
            // (bucket, slot) pair. Together they must cover all 1024 chunks exactly once.
            boolean[] seen = new boolean[per * CsoFormat.bucketCount(grid)];
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int bucket = CsoFormat.bucketIndex(x, z, grid);
                    int slot = CsoFormat.chunkIndexInBucket(x, z, grid);
                    assertTrue(bucket >= 0 && bucket < CsoFormat.bucketCount(grid), "bucket out of range");
                    assertTrue(slot >= 0 && slot < per, "slot out of range");
                    int key = bucket * per + slot;
                    assertFalse(seen[key], "collision at (" + x + "," + z + ") grid " + grid);
                    seen[key] = true;
                }
            }
            for (boolean b : seen) {
                assertTrue(b, "grid " + grid + " does not cover every chunk");
            }
        }
    }
}
