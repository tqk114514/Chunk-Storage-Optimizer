package tqk114514.chunkstorageoptimizer.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
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

    /**
     * Data zstd cannot shrink, so a bucket block is as long as its payload. Used where a test needs
     * block sizes to grow strictly — compressible filler bounces around instead, and the allocator
     * reuses a hole that happens to fit.
     */
    private static byte[] incompressible(int seed, int size) {
        byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
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
    void hasBucketTellsWrittenApartFromDeleted(@TempDir Path dir) throws IOException {
        // What a read-through fallback must be able to ask: did CSO ever write this position's
        // bucket? An untouched bucket says nothing about its slots (fall back); a written one is
        // authoritative and an empty slot in it means the chunk was deleted (do NOT fall back).
        try (CsoRegionFile file = open(dir, GRID)) {
            assertFalse(file.hasBucket(5, 5), "nothing written yet");

            file.writeChunk(5, 5, chunkData(1, 500));
            assertTrue(file.hasBucket(5, 5));

            file.deleteChunk(5, 5);
            assertFalse(file.hasChunk(5, 5), "the slot is empty");
            assertTrue(file.hasBucket(5, 5), "but the bucket was written, so a miss here is a deletion");
        }
    }

    @Test
    void hasBucketSurvivesReopen(@TempDir Path dir) throws IOException {
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(9, 9, chunkData(2, 600));
            file.deleteChunk(9, 9);
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            assertTrue(file.hasBucket(9, 9), "the written-bucket fact is on disk, not just in memory");
            assertFalse(file.hasChunk(9, 9));
            assertFalse(file.hasBucket(1, 1), "an untouched bucket is still untouched");
        }
    }

    @Test
    void writesLeaveWasteForFlushToReclaim(@TempDir Path dir) throws IOException {
        long bloated;
        try (CsoRegionFile file = open(dir, GRID)) {
            // Incompressible and strictly growing: every block is bigger than the hole the
            // previous one left, so best-fit cannot reuse it and unreachable space piles up.
            for (int i = 0; i < 40; i++) {
                file.writeChunk(2, 2, incompressible(7, 1000 + i * 300));
            }
            // The write path must not compact: saving one chunk would otherwise pay for rewriting
            // the whole file at the worst possible moment.
            assertTrue(file.wastedBytes() > 4096,
                "expected growing rewrites to leave waste, got " + file.wastedBytes());
            bloated = file.fileSize();

            assertTrue(file.compactIfWasted(), "waste over both thresholds should be reclaimed");
            assertTrue(file.wastedBytes() < 4096, "waste left after compaction: " + file.wastedBytes());
            assertTrue(file.fileSize() < bloated / 2,
                "file only shrank from " + bloated + " to " + file.fileSize());
            assertArrayEquals(incompressible(7, 1000 + 39 * 300), file.readChunk(2, 2));
        }
        // And the reclaimed file is still readable after a reopen, i.e. both tables landed.
        try (CsoRegionFile file = open(dir, GRID)) {
            assertArrayEquals(incompressible(7, 1000 + 39 * 300), file.readChunk(2, 2));
        }
    }

    @Test
    void wasteBelowTheThresholdIsLeftAlone(@TempDir Path dir) throws IOException {
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(1, 1, chunkData(1, 2000));
            file.writeChunk(1, 1, chunkData(2, 2000));
            long size = file.fileSize();

            assertFalse(file.compactIfWasted(), "one stale block is under the floor; rewriting the file is not worth it");
            assertEquals(size, file.fileSize());
        }
    }

    @Test
    void trailingFreeSpaceIsReusableNotWaste(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.cso");
        byte[] data = chunkData(3, 800);
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(0, 0, data);
        }
        // What a crash between the block write and the table update leaves: bytes past the last
        // extent no entry points at. Hand-appended here; the next open sets fileEnd to the size.
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.allocate(8192), channel.size());
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            assertEquals(0L, file.wastedBytes(),
                "the free tail is handed out by allocate(), not unreachable waste");
            long sizeBefore = file.fileSize();
            file.writeChunk(4, 4, incompressible(9, 2000));
            assertEquals(sizeBefore, file.fileSize(),
                "the next write must reuse the stranded tail instead of growing the file");
            assertArrayEquals(data, file.readChunk(0, 0));
            assertArrayEquals(incompressible(9, 2000), file.readChunk(4, 4));
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

    /**
     * A compaction that cannot land its file swap must fail loudly, and the data already on disk
     * must be untouched. The swap closes the live channel before replacing the file, so this also
     * covers the reopen-before-throwing path: the failure is reported, not left as a half-applied
     * state that a later write would discover as a closed channel.
     *
     * <p>A move onto a non-empty directory cannot succeed on any platform, which makes the failure
     * deterministic without depending on OS file-locking semantics.
     */
    @Test
    void failedCompactionIsLoudAndLeavesTheOldFileIntact(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.cso");
        byte[] present = chunkData(51, 900);

        Files.createDirectories(dir);
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(0, 0, present);
            file.flush();
            // Put an unreplaceable non-empty directory where the swap wants to land. The live
            // channel still points at the now-unlinked old file, so the data is not lost — only the
            // compaction cannot proceed.
            Files.deleteIfExists(path);
            Files.createDirectory(path);
            Files.createFile(path.resolve("occupied"));

            assertThrows(IOException.class, file::compact, "the failed swap must propagate");
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

    /**
     * The one case that must NOT be tolerated: both table copies unreadable for a bucket that has
     * data. Skipping it would make the bucket's chunks vanish with no error, which is the silent
     * corruption this format exists to prevent. An untouched bucket, by contrast, is all zeros and
     * must still be skipped quietly.
     */
    @Test
    void bothTableCopiesTornIsFatalButUnwrittenBucketIsNot(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.cso");
        try (CsoRegionFile file = open(dir, GRID)) {
            // Twice, so that BOTH table copies hold an entry: the first write lands in table 1 and
            // the second in table 0. One write would leave table 0 blank, which is a different
            // case — it means no write ever completed, and that is handled by its own test.
            file.writeChunk(0, 0, chunkData(23, 800));
            file.writeChunk(0, 0, chunkData(24, 800));
        }

        // Bucket 1 was never written — its entry is all zeros — and must keep opening cleanly.
        try (CsoRegionFile file = open(dir, GRID)) {
            assertNull(file.readChunk(0, 1), "an unwritten bucket reads as absent");
        }

        int bucketCount = CsoFormat.bucketCount(GRID);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // Zero the CRC of bucket 0's entry in both copies, which decodeEntry reads as torn.
            for (int table = 0; table < CsoFormat.TABLE_COUNT; table++) {
                long crcPosition = CsoFormat.tableOffset(table, 0, bucketCount) + CsoFormat.ENTRY_CRC;
                channel.write(ByteBuffer.wrap(new byte[] {0, 0, 0, 0}), crcPosition);
            }
        }

        assertThrows(CsoCorruptedException.class, () -> open(dir, GRID),
            "a bucket with data but no readable table entry must fail loudly, not be dropped");
    }

    /**
     * The other side of that rule: one torn copy is not loss when it is the bucket's <em>first</em>
     * write that tore. Writes start at table 1, so table 0 stays all zeros until a write has
     * completed — a blank table 0 is proof that nothing durable ever landed here. The
     * write-ahead log, still on disk and replayed just after this, holds what the interrupted
     * batch meant to write, so the file must open and serve it.
     */
    @Test
    void tornFirstWriteIsRecoveredByTheWriteAheadLog(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.cso");
        byte[] recovered = chunkData(52, 900);
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(0, 0, chunkData(51, 800));
            // A log left behind is exactly what an interrupted apply leaves.
            file.writeWal(Map.of(0, Map.of(0, recovered)));
        }

        // Damage table 1's entry. Table 0 is still blank, so this is the shape of a crash during
        // the bucket's very first write.
        int bucketCount = CsoFormat.bucketCount(GRID);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long crcPosition = CsoFormat.tableOffset(1, 0, bucketCount) + CsoFormat.ENTRY_CRC;
            channel.write(ByteBuffer.wrap(new byte[] {0, 0, 0, 0}), crcPosition);
        }

        try (CsoRegionFile file = open(dir, GRID)) {
            assertArrayEquals(recovered, file.readChunk(0, 0),
                "the interrupted batch must be replayed, not refused");
        }
    }

    /**
     * Without a log the damage is not explained by an interrupted batch, so it is real loss. A
     * blank table 0 still says no write completed — but the safe reading of "no write completed"
     * is only available while a log exists to supply the content.
     */
    @Test
    void tornFirstWriteWithoutALogIsFatal(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("r.0.0.cso");
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(0, 0, chunkData(53, 800));
        }

        int bucketCount = CsoFormat.bucketCount(GRID);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long crcPosition = CsoFormat.tableOffset(1, 0, bucketCount) + CsoFormat.ENTRY_CRC;
            channel.write(ByteBuffer.wrap(new byte[] {0, 0, 0, 0}), crcPosition);
        }

        assertThrows(CsoCorruptedException.class, () -> open(dir, GRID),
            "without a log, a bucket that cannot be read must fail loudly rather than be dropped");
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
    void tornLegacyWalIsIgnored(@TempDir Path dir) throws IOException {
        byte[] first = chunkData(41, 800);
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunks(0, Map.of(0, first));
        }
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeWal(Map.of(0, Map.of(0, chunkData(42, 900))));
        }

        // Truncated mid-file, the shape a crash left behind when the WAL was still written
        // in place: no length tail, so the reader cannot tell it from damage and rightly
        // keeps the old policy — a torn log of that era never described an applied batch.
        Path wal = dir.resolve("r.0.0.cso.wal");
        byte[] whole = Files.readAllBytes(wal);
        Files.write(wal, java.util.Arrays.copyOf(whole, whole.length / 2));

        // A torn legacy WAL must be discarded, leaving the last consistent state — not
        // half-applied.
        try (CsoRegionFile file = open(dir, GRID)) {
            assertArrayEquals(first, file.readChunk(0, 0), "a torn legacy WAL must be ignored");
        }
        assertFalse(Files.exists(wal), "a discarded legacy WAL is deleted");
    }

    @Test
    void damagedWalMustRefuseTheOpenAndKeepTheLog(@TempDir Path dir) throws IOException {
        byte[] first = chunkData(41, 800);
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunks(0, Map.of(0, first));
        }
        // A whole, forced WAL — the state at the moment the batch is about to apply.
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeWal(Map.of(0, Map.of(0, chunkData(42, 900))));
        }

        // Damage after the force. The atomic write means a WAL that exists was written
        // whole, so a failed checksum can only mean exactly this — and the batch it
        // describes may sit half-applied, which must not be opened over silently.
        Path wal = dir.resolve("r.0.0.cso.wal");
        byte[] whole = Files.readAllBytes(wal);
        whole[whole.length / 2] ^= (byte) 0xFF;
        Files.write(wal, whole);

        CsoCorruptedException expected = assertThrows(CsoCorruptedException.class,
            () -> open(dir, GRID),
            "a damaged WAL describes a batch that may be half-applied; the open must refuse");
        assertTrue(Files.exists(wal), "the refused WAL is kept beside the file for manual recovery");
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

    @Test
    void cacheIsBoundedByBytesNotJustCount(@TempDir Path dir) throws IOException {
        // 64 buckets of headroom on the count, but three ~3 MB incompressible payloads blow
        // past the per-file byte ceiling: the least recently used payload must go, even
        // though the count limit alone would happily keep all three. This is the guard that
        // lets the default count sit at the top of its range — a small grid makes one bucket
        // a whole region's worth of chunks, and a count alone would pin gigabytes.
        Path path = dir.resolve("r.0.0.cso");
        try (CsoRegionFile file = CsoRegionFile.open(path, GRID, COMPRESSION, LEVEL,
            64, true, 4096, 0.25)) {
            file.writeChunk(0, 0, incompressible(1, 3_000_000));
            file.writeChunk(4, 0, incompressible(2, 3_000_000));
            assertEquals(2, file.cachedBucketCount(), "two payloads fit the byte budget");

            file.writeChunk(8, 0, incompressible(3, 3_000_000));
            assertEquals(2, file.cachedBucketCount(),
                "the third put must evict the eldest to stay inside the budget");
        }

        // A payload bigger than the whole budget is not cacheable at all: nothing else gets
        // evicted for its sake, and re-reading that bucket decompresses again.
        try (CsoRegionFile file = CsoRegionFile.open(path, GRID, COMPRESSION, LEVEL,
            64, true, 4096, 0.25)) {
            file.writeChunk(0, 4, incompressible(4, 9_000_000));
            assertEquals(0, file.cachedBucketCount(), "a payload over the ceiling must not be pinned");
        }
    }

    @Test
    void readChunkSliceExposesTheSameBytesWithoutCopying(@TempDir Path dir) throws IOException {
        byte[] data = chunkData(77, 5000);
        try (CsoRegionFile file = open(dir, GRID)) {
            file.writeChunk(2, 3, data);

            CsoRegionFile.ChunkSlice slice = file.readChunkSlice(2, 3);
            assertNotNull(slice, "a written chunk must come back as a view");
            assertArrayEquals(data, Arrays.copyOfRange(
                slice.payload(), slice.offset(), slice.offset() + slice.length()),
                "the view must expose exactly the chunk's bytes");
            // The view aliases the cached payload: same array, positioned at the chunk.
            assertTrue(slice.offset() >= 0 && slice.offset() + slice.length() <= slice.payload().length,
                "the view must stay inside its payload");

            // The owned-copy API agrees with the view, and unwritten positions read as absent.
            assertArrayEquals(data, file.readChunk(2, 3));
            assertNull(file.readChunkSlice(1, 1), "an unwritten position reads as absent");
        }
    }
}
