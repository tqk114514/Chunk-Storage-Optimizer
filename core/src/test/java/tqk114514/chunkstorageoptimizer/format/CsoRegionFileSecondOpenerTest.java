package tqk114514.chunkstorageoptimizer.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a second opener of one {@code .cso} does to a storage layer that is using it.
 *
 * <p>The format layer used to have one open path, and it opened for write and recovered the file as
 * its own first act. That is right for {@code CsoStorage}, which owns the file, and wrong for
 * anything that merely wants to look — Voxy's importer ({@code WorldImporterMixin}) and every read
 * in the offline tooling ({@code Converter.readCso}) went through it, against folders a running game
 * is writing.
 *
 * <p>The first two tests characterise the shared-writer behaviour, and are expected to keep
 * describing it: {@code open()} is the writer's door, and a second writer through it is still a
 * second writer. The last two are the contract the read-only path owes those callers instead.
 */
class CsoRegionFileSecondOpenerTest {

    private static final int GRID = 8;
    private static final int COMPRESSION = CsoFormat.COMPRESSION_ZSTD;
    private static final int LEVEL = 3;

    private static CsoRegionFile openWrite(Path path) throws IOException {
        return CsoRegionFile.open(path, GRID, COMPRESSION, LEVEL, 4, true, 4096, 0.25);
    }

    private static byte[] data(int seed, int size) {
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = (byte) (seed * 31 + i * 7);
        }
        return out;
    }

    /** One bucket's single slot, addressed the way the storage layer addresses a batch. */
    private static Map<Integer, Map<Integer, byte[]>> batch(int localX, int localZ, byte[] bytes) {
        int bucket = CsoFormat.bucketIndex(localX, localZ, GRID);
        int slot = CsoFormat.chunkIndexInBucket(localX, localZ, GRID);
        Map<Integer, byte[]> slots = new HashMap<>();
        slots.put(slot, bytes);
        Map<Integer, Map<Integer, byte[]>> byBucket = new HashMap<>();
        byBucket.put(bucket, slots);
        return byBucket;
    }

    private static Path walOf(Path region) {
        return region.resolveSibling(region.getFileName() + ".wal");
    }

    // ---------------------------------------------------------------- characterize the hazard

    /**
     * A writer-open replays the log and then drops it, so the durability of a batch the storage
     * layer has staged but not applied ends up in the hands of a caller whose failures are logged
     * and skipped — Voxy warns "Skipping" and moves on to the next region.
     */
    @Test
    void writeOpenByASecondOpenerConsumesThePendingBatchLog(@TempDir Path dir) throws IOException {
        Path region = dir.resolve("r.0.0.cso");
        try (CsoRegionFile storage = openWrite(region)) {
            storage.writeChunk(0, 0, data(1, 2000));
            storage.flush();

            storage.writeWal(batch(4, 0, data(2, 2000)));
            assertTrue(Files.isRegularFile(walOf(region)),
                "a staged batch is logged before it is applied");

            try (CsoRegionFile reader = openWrite(region)) {
                reader.readChunk(0, 0);
            }

            assertFalse(Files.isRegularFile(walOf(region)),
                "characterisation: the reader replayed the log and cleared it while the holder of"
                    + " the batch had not applied it — recovery must belong to the writer alone");
        }
    }

    /**
     * Two writers each allocate from the file end they saw at open, so a reader's write lands on top
     * of bytes the storage layer had already committed, and that bucket's table entry ends up
     * naming someone else's payload.
     */
    @Test
    void writeOpenByASecondOpenerOverwritesACommittedBlock(@TempDir Path dir) throws IOException {
        Path region = dir.resolve("r.0.0.cso");
        byte[] committed = data(3, 40_000);
        byte[] later = data(4, 60_000);
        try (CsoRegionFile storage = openWrite(region); CsoRegionFile reader = openWrite(region)) {
            storage.writeChunk(0, 0, committed);
            storage.flush();
            // The reader took its view of the layout at open, before that block existed.
            reader.writeChunk(4, 0, later);
            reader.flush();
            storage.flush();
        }

        try (CsoRegionFile reopened = openWrite(region)) {
            assertArrayEquals(later, reopened.readChunk(4, 0),
                "the later writer's block is the one that survived at the shared offset");
            boolean damaged;
            try {
                damaged = !Arrays.equals(committed, reopened.readChunk(0, 0));
            } catch (IOException expected) {
                damaged = true;
            }
            assertTrue(damaged,
                "characterisation: the committed chunk now reads as the other writer's bytes or as"
                    + " CRC damage — a reader that writes allocates space the storage layer used");
        }
    }

    // ---------------------------------------------------------------- the read-only contract

    /**
     * A batch in flight is the writer's business. A reader refuses the file rather than showing the
     * state before it, and above all does not consume the log: the conversion callers of this path
     * write what they read back over the same file, so a stale view there is the batch — gone.
     */
    @Test
    void readOnlyOpenRefusesAFileWhoseBatchIsStillInFlight(@TempDir Path dir) throws IOException {
        Path region = dir.resolve("r.0.0.cso");
        byte[] staged = data(2, 2000);
        try (CsoRegionFile storage = openWrite(region)) {
            storage.writeChunk(0, 0, data(1, 2000));
            storage.flush();
            storage.writeWal(batch(4, 0, staged));

            CsoPendingBatchException refused = assertThrows(CsoPendingBatchException.class,
                () -> CsoRegionFile.openReadOnly(region, LEVEL, 4, true));
            assertTrue(refused.getMessage().contains("write-ahead log"),
                "the refusal has to say what it found; the callers print this text verbatim");
            assertTrue(Files.isRegularFile(walOf(region)),
                "a reader must leave the recovery of someone else's batch to its writer");

            // The writer still owns it, so applying the batch here lands it as it would anyway.
            storage.writeChunks(CsoFormat.bucketIndex(4, 0, GRID),
                Map.of(CsoFormat.chunkIndexInBucket(4, 0, GRID), staged));
            storage.flush();
            storage.clearWal();
        }

        try (CsoRegionFile reopened = openWrite(region)) {
            assertArrayEquals(staged, reopened.readChunk(4, 0),
                "the batch the reader refused to look at is still recoverable by its own writer");
        }
    }

    /**
     * Reading a live file must cost that file nothing: no allocation, no table entry, no recovery,
     * and no new file where there was none.
     */
    @Test
    void readOnlyOpenCannotChangeTheFileItReads(@TempDir Path dir) throws IOException {
        Path region = dir.resolve("r.0.0.cso");
        byte[] committed = data(3, 40_000);
        try (CsoRegionFile storage = openWrite(region)) {
            storage.writeChunk(0, 0, committed);
            storage.flush();

            try (CsoRegionFile reader = CsoRegionFile.openReadOnly(region, LEVEL, 4, true)) {
                // The layout comes off the disk, never from what the reader would have preferred.
                assertEquals(GRID, reader.grid(), "a reader reads the file's own layout");
                assertArrayEquals(committed, reader.readChunk(0, 0));
                assertTrue(reader.hasBucket(0, 0),
                    "the bucket holding the committed chunk is authoritative to a reader too");
                assertThrows(IllegalStateException.class,
                    () -> reader.writeChunk(4, 0, data(4, 60_000)),
                    "a read handle must refuse a write rather than allocate over the writer's data");
                assertThrows(IllegalStateException.class,
                    () -> reader.writeWal(batch(4, 0, data(4, 60_000))),
                    "and must not be able to log a batch it does not own");
                assertThrows(IllegalStateException.class, reader::compact,
                    "compaction rewrites the whole file, so it belongs to the writer");
            }

            // Nothing landed, so the committed block is still the block its entry names.
            try (CsoRegionFile reopened = openWrite(region)) {
                assertArrayEquals(committed, reopened.readChunk(0, 0),
                    "reading a live region must leave it exactly as it was");
                assertFalse(reopened.hasBucket(4, 0),
                    "the refused write must not have reached the file at all");
            }
        }

        // A file that does not exist stays a missing file; a reader must not create a region.
        Path absent = dir.resolve("r.9.9.cso");
        assertThrows(IOException.class, () -> CsoRegionFile.openReadOnly(absent, LEVEL, 4, true));
        assertFalse(Files.exists(absent), "reading cannot bring a region into being");
    }
}
