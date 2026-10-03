package tqk114514.chunkstorageoptimizer.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tqk114514.chunkstorageoptimizer.format.AnvilRegionFile;
import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.format.CsoRegionFile;

/**
 * Guards the rule that keeps progressive migration from destroying a world.
 *
 * <p>The read path treats a written bucket as authoritative and will not fall back to the
 * {@code .mca} for it — that is what stops a deleted chunk from coming back. A save only writes the
 * chunks it was asked to write, so without seeding the first write to a bucket would strand every
 * neighbour still living in the {@code .mca}: they would read back as absent, which the game
 * answers with fresh terrain.
 */
class BucketSeederTest {

    /** span 2, so a bucket covers four chunks and the coordinate mapping is easy to state. */
    private static final int GRID = 16;

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static CsoRegionFile open(Path path) throws IOException {
        return CsoRegionFile.open(path, GRID, CsoFormat.COMPRESSION_ZSTD, 3, 4, true, Long.MAX_VALUE, 10.0);
    }

    /**
     * The legacy source must not be able to fail, and this is asserted rather than only written down
     * because re-adding {@code throws IOException} compiles perfectly well everywhere else.
     *
     * <p>A source that threw would abort the whole batch, and a batch that failed stays staged and is
     * retried on a timer — so one unreadable chunk in the legacy {@code .mca} would stop the world
     * from saving anything at all. Vanilla's reader only returns null for damage visible in the
     * header, because decompression is lazy, so a corrupt stream surfaces exactly here.
     */
    @Test
    void theLegacySourceCannotFail() throws NoSuchMethodException {
        Method read = BucketSeeder.LegacySource.class.getMethod("read", int.class, int.class);
        for (Class<?> thrown : read.getExceptionTypes()) {
            assertTrue(
                RuntimeException.class.isAssignableFrom(thrown) || Error.class.isAssignableFrom(thrown),
                "LegacySource.read declares the checked exception " + thrown.getName()
                    + " — a failure there would take the whole batch, and the batch is retried forever"
            );
        }
    }

    @Test
    void seedingFillsEverySlotTheSaveDidNotTouch() throws IOException {
        Map<Integer, byte[]> changes = new HashMap<>();
        changes.put(0, bytes("saved"));

        Map<Integer, byte[]> seeded = BucketSeeder.seed(
            changes, 0, GRID, (x, z) -> bytes("legacy-" + x + "," + z));

        assertEquals(4, seeded.size(), "all four slots of a grid-16 bucket must be decided");
        assertArrayEquals(bytes("saved"), seeded.get(0), "the live change wins");
        assertArrayEquals(bytes("legacy-1,0"), seeded.get(1));
        assertArrayEquals(bytes("legacy-0,1"), seeded.get(2));
        assertArrayEquals(bytes("legacy-1,1"), seeded.get(3));
    }

    @Test
    void seedingNeverUndoesADeletion() throws IOException {
        Map<Integer, byte[]> changes = new HashMap<>();
        changes.put(0, null); // the game deleted this chunk

        Map<Integer, byte[]> seeded = BucketSeeder.seed(changes, 0, GRID, (x, z) -> bytes("legacy"));

        assertTrue(seeded.containsKey(0), "a deletion is a decision, not an absence");
        assertNull(seeded.get(0), "a deleted chunk must not come back from the .mca");
        assertArrayEquals(bytes("legacy"), seeded.get(1), "the neighbours are still seeded");
    }

    @Test
    void seedingLeavesChunksTheLegacyFileNeverHadAbsent() throws IOException {
        Map<Integer, byte[]> changes = new HashMap<>();
        changes.put(0, bytes("saved"));

        Map<Integer, byte[]> seeded = BucketSeeder.seed(changes, 0, GRID, (x, z) -> null);

        assertEquals(1, seeded.size(), "nothing to seed means the slot stays empty");
    }

    @Test
    void seedingOnlyAsksForTheSlotsItNeeds() throws IOException {
        Set<String> asked = new HashSet<>();
        Map<Integer, byte[]> changes = new HashMap<>();
        changes.put(0, bytes("saved"));

        BucketSeeder.seed(changes, 0, GRID, (x, z) -> {
            asked.add(x + "," + z);
            return null;
        });

        assertEquals(Set.of("1,0", "0,1", "1,1"), asked,
            "the changed slot is not read back, and nothing outside the bucket is touched");
    }

    @Test
    void seedingMapsABucketBackToTheRightCoordinates() throws IOException {
        // The ordinal is (localZ / span) * grid + (localX / span), so bucket 1 at grid 16 is the
        // second bucket along X. Seeding the wrong coordinates would pull in a neighbour's chunks
        // and then overwrite them with a copy of themselves — or worse, with the wrong ones.
        Set<String> asked = new HashSet<>();
        BucketSeeder.seed(new HashMap<>(), 1, GRID, (x, z) -> {
            asked.add(x + "," + z);
            return null;
        });

        assertEquals(Set.of("2,0", "3,0", "2,1", "3,1"), asked);
    }

    /**
     * The migration scenario end to end: an {@code .mca} holds all four chunks of one bucket, one
     * of them is changed and saved. The other three must still read back, because the bucket now
     * counts as written and the read path will not fall back for them any more.
     */
    @Test
    void aSeededBucketStillServesItsNeighboursAfterTheSave(@TempDir Path dir) throws IOException {
        Map<Integer, byte[]> legacy = new HashMap<>();
        for (int slot = 0; slot < 4; slot++) {
            legacy.put(slot, bytes("legacy-" + slot));
        }
        Map<Integer, byte[]> changes = new HashMap<>();
        changes.put(0, bytes("saved"));

        Map<Integer, byte[]> seeded = BucketSeeder.seed(changes, 0, GRID,
            (x, z) -> legacy.get(CsoFormat.chunkIndexInBucket(x, z, GRID)));

        try (CsoRegionFile file = open(dir.resolve("r.0.0.cso"))) {
            file.writeChunks(0, seeded);

            assertTrue(file.hasBucketIndex(0), "the bucket is written, so it is authoritative");
            assertArrayEquals(bytes("saved"), file.readChunk(0, 0));
            assertArrayEquals(bytes("legacy-1"), file.readChunk(1, 0), "neighbour must survive");
            assertArrayEquals(bytes("legacy-2"), file.readChunk(0, 1));
            assertArrayEquals(bytes("legacy-3"), file.readChunk(1, 1));
        }
    }

    /**
     * The shape of the bug, shown without the seeding: a bucket written from one save holds only
     * that save's chunk, and a neighbour reads back as absent even though the {@code .mca} still
     * has it. "Absent" is what the game answers with regenerated terrain, which is why the storage
     * layer seeds before a bucket's first write.
     */
    @Test
    void withoutSeedingTheNeighboursWouldReadAsAbsent(@TempDir Path dir) throws IOException {
        try (CsoRegionFile file = open(dir.resolve("r.0.0.cso"))) {
            file.writeChunk(0, 0, bytes("saved"));

            assertTrue(file.hasBucketIndex(0), "one save is enough to make the bucket authoritative");
            assertNull(file.readChunk(1, 0), "and the neighbour it never wrote reads as absent");
        }
    }

    /**
     * The whole path a real migration takes, with only core classes: a genuine {@code .mca} on
     * disk, one of its four bucket-mates changed, and the bucket written the way the storage layer
     * writes it. All four chunks must survive — three of them were never touched by the save.
     */
    @Test
    void aWholeRegionMigratesWithoutLosingItsNeighbours(@TempDir Path dir) throws IOException {
        // Slots 0, 1, 32, 33 are the four chunks of bucket 0 at grid 16: (0,0) (1,0) (0,1) (1,1).
        int[] slots = {0, 1, 32, 33};
        List<AnvilRegionFile.Chunk> chunks = new ArrayList<>();
        for (int slot : slots) {
            chunks.add(new AnvilRegionFile.Chunk(slot, bytes("chunk-" + slot)));
        }
        AnvilRegionFile.write(dir.resolve("r.0.0.mca"), chunks);

        // Read back through the real .mca reader, the same way the storage layer's seed source does.
        List<AnvilRegionFile.Chunk> legacy = AnvilRegionFile.read(dir.resolve("r.0.0.mca"));
        BucketSeeder.LegacySource source = (localX, localZ) -> {
            int index = localZ * CsoFormat.REGION_CHUNKS + localX;
            for (AnvilRegionFile.Chunk chunk : legacy) {
                if (chunk.index() == index) {
                    return chunk.nbt();
                }
            }
            return null;
        };

        Map<Integer, byte[]> changes = new HashMap<>();
        changes.put(0, bytes("changed"));
        Map<Integer, byte[]> seeded = BucketSeeder.seed(changes, 0, GRID, source);

        try (CsoRegionFile file = open(dir.resolve("r.0.0.cso"))) {
            file.writeChunks(0, seeded);

            assertArrayEquals(bytes("changed"), file.readChunk(0, 0), "the save wins");
            assertArrayEquals(bytes("chunk-1"), file.readChunk(1, 0), "bucket-mate must survive");
            assertArrayEquals(bytes("chunk-32"), file.readChunk(0, 1), "bucket-mate must survive");
            assertArrayEquals(bytes("chunk-33"), file.readChunk(1, 1), "bucket-mate must survive");
        }
    }
}
