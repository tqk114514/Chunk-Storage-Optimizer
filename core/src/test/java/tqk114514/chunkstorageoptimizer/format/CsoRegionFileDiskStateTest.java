package tqk114514.chunkstorageoptimizer.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static tqk114514.chunkstorageoptimizer.format.CsoFormat.BUCKET_ENTRY_SIZE;
import static tqk114514.chunkstorageoptimizer.format.CsoFormat.HEADER_SIZE;

/**
 * The disk, told independently what the engine says. This is the guard for work on the table
 * commit and the space allocator: after randomized churn, the two are cross-checked against
 * the raw bytes rather than against each other, so neither can drift and still pass.
 *
 * <ul>
 * <li>the bucket table on disk — both copies, decoded by hand — names exactly the extents
 * the engine serves, and a live entry's sequence is never behind what either copy holds;
 * <li>live extents never overlap and stay inside the data area: two entries sharing bytes is
 * the shape the failed-write path had (1.0.10), where a retry overwrote the only intact copy;
 * <li>{@link CsoRegionFile#wastedBytes()} equals the interior gaps recomputed here from the
 * disk table — the allocator's bookkeeping checked against the file it claims to describe;
 * <li>every chunk read back byte-identical, including across reopen and compaction.
 * </ul>
 *
 * The churn is seeded, and the seed lands in every failure message.
 */
class CsoRegionFileDiskStateTest {

    private static final int GRID = 8;
    private static final long SEED = 20261009L;

    @Test
    void diskStateMatchesEngineAccountingThroughRandomChurn(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.cso");
        Random random = new Random(SEED);
        Map<Integer, byte[]> expected = new HashMap<>();

        for (int round = 0; round < 30; round++) {
            try (CsoRegionFile region = open(file)) {
                int writes = 4 + random.nextInt(8);
                for (int i = 0; i < writes; i++) {
                    int slot = random.nextInt(GRID * GRID);
                    if (random.nextInt(6) == 0) {
                        region.deleteChunk(slot / GRID, slot % GRID);
                        expected.remove(slot);
                    } else {
                        byte[] data = chunkData(random.nextInt(), 100 + random.nextInt(2000));
                        region.writeChunk(slot / GRID, slot % GRID, data);
                        expected.put(slot, data);
                    }
                }
                region.flush();
                if (random.nextInt(4) == 0) {
                    region.compactIfWasted();
                }
            }
            checkDiskState(file, expected, "round " + round + " (seed " + SEED + ")");
        }

        // After the last close every chunk reads back byte-identical from a fresh open.
        try (CsoRegionFile region = open(file)) {
            for (Map.Entry<Integer, byte[]> chunk : expected.entrySet()) {
                byte[] read = region.readChunk(chunk.getKey() / GRID, chunk.getKey() % GRID);
                assertTrue(Arrays.equals(chunk.getValue(), read),
                    "chunk " + chunk.getKey() + " changed across the churn (seed " + SEED + ")");
            }
        }
    }

    /** The independent accounting: both table copies decoded by hand, cross-checked. */
    private void checkDiskState(Path file, Map<Integer, byte[]> expected, String when) throws IOException {
        byte[] whole = Files.readAllBytes(file);
        // The file is created by this test, so its grid is the one this test opens with.
        int bucketCount = CsoFormat.bucketCount(GRID);
        int dataStart = (int) CsoFormat.dataStart(bucketCount);
        int tableStride = bucketCount * BUCKET_ENTRY_SIZE;
        assertTrue(whole.length >= dataStart, "file shorter than its structural prefix: " + when);

        // The winning entry per bucket: highest valid sequence across both copies — the same
        // rule readMetadata recovers by. Blank entries are "never written", not damage.
        TableEntry[] fromDisk = new TableEntry[bucketCount];
        for (int b = 0; b < bucketCount; b++) {
            for (int table = 0; table < CsoFormat.TABLE_COUNT; table++) {
                int base = HEADER_SIZE + table * tableStride + b * BUCKET_ENTRY_SIZE;
                if (TableEntry.isBlank(whole, base)) {
                    continue;
                }
                TableEntry candidate = TableEntry.decode(whole, base);
                if (candidate == null) {
                    continue; // torn copy; the other one speaks, as it is built to
                }
                if (fromDisk[b] == null || candidate.sequence > fromDisk[b].sequence) {
                    fromDisk[b] = candidate;
                }
            }
        }

        List<long[]> live = new ArrayList<>();
        for (int b = 0; b < bucketCount; b++) {
            TableEntry e = fromDisk[b];
            if (e == null) {
                continue;
            }
            assertTrue(e.offset >= dataStart, "bucket " + b + " block starts before the data area: " + when);
            assertTrue(e.offset + e.compressedLength <= whole.length,
                "bucket " + b + " block runs past the file end: " + when);
            live.add(new long[] {e.offset, e.offset + e.compressedLength});
        }
        live.sort(Comparator.comparingLong(a -> a[0]));
        for (int i = 1; i < live.size(); i++) {
            assertTrue(live.get(i - 1)[1] <= live.get(i)[0],
                "live blocks overlap: " + Arrays.deepToString(live.toArray()) + " — " + when);
        }

        // Wasted bytes, recomputed here: interior gaps between the data start and the last
        // live block. The trailing free span is reclaimable without compaction, so it does
        // not count — the same line the engine's own accounting draws.
        long cursor = dataStart;
        long wasted = 0;
        for (long[] range : live) {
            if (range[0] > cursor) {
                wasted += range[0] - cursor;
            }
            if (range[1] > cursor) {
                cursor = range[1];
            }
        }

        try (CsoRegionFile region = open(file)) {
            assertEquals(wasted, region.wastedBytes(),
                "engine's waste accounting disagrees with the disk table: " + when);
            for (int b = 0; b < bucketCount; b++) {
                TableEntry disk = fromDisk[b];
                if (disk != null) {
                    assertTrue(region.hasBucketIndex(b),
                        "disk names bucket " + b + " but the engine does not serve it: " + when);
                }
            }
        }
    }

    private static CsoRegionFile open(Path file) throws IOException {
        return CsoRegionFile.open(
            file, GRID, CsoFormat.COMPRESSION_ZSTD, 3, 4, true, 1 << 12, 0.25);
    }

    private static byte[] chunkData(int seed, int size) {
        byte[] out = new byte[size];
        Random random = new Random(seed);
        int p = 0;
        while (p < size) {
            int run = Math.min(size - p, 8 + random.nextInt(24));
            byte value = (byte) random.nextInt();
            for (int i = 0; i < run; i++) {
                out[p++] = value;
            }
        }
        return out;
    }
}
