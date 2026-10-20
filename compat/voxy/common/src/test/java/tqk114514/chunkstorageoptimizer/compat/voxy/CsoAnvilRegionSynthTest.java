package tqk114514.chunkstorageoptimizer.compat.voxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tqk114514.chunkstorageoptimizer.format.AnvilRegionFile;
import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.format.CsoRegionFile;

/**
 * The synthesizer produces a byte layout two other implementations parse: Voxy's importer (the
 * point of the module) and core's own Anvil reader (this test). Round-tripping through core's
 * reader — written for vanilla files, with its own adversarial tests — checks the header, the
 * slot order, the length prefix and the raw stream id against an implementation that has no
 * interest in this module being right.
 */
class CsoAnvilRegionSynthTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsThroughCoresAnvilReader() throws IOException {
        Map<Integer, byte[]> written = new HashMap<>();
        writeSampleRegion(dir.resolve("r.0.0.cso"), written, 0);

        try (CsoRegionFile region = open(dir.resolve("r.0.0.cso"))) {
            CsoAnvilRegionSynth.Synthesis synthesis = CsoAnvilRegionSynth.toAnvilRegion(region);
            assertEquals(written.size(), synthesis.chunks());
            assertEquals(0, synthesis.skipped());
            assertNotNull(synthesis.anvil());

            Path anvil = dir.resolve("r.0.0.mca");
            Files.write(anvil, synthesis.anvil());
            AnvilRegionFile.ReadResult read = AnvilRegionFile.readReporting(anvil);
            assertTrue(read.isComplete());
            assertEquals(written.size(), read.occupied());
            for (AnvilRegionFile.Chunk chunk : read.chunks()) {
                assertArrayEquals(written.get(chunk.index()), chunk.nbt());
            }
            assertEquals(written.size(), read.chunks().size());
        }
    }

    @Test
    void skipsChunksTooLargeForAnvil() throws IOException {
        Path path = dir.resolve("r.0.0.cso");
        byte[] huge = new byte[MAX_SECTORS * 4096 + 16]; // 256 sectors: one over the limit
        new Random(1).nextBytes(huge);
        byte[] normal = new byte[] {1, 2, 3};
        try (CsoRegionFile region = open(path)) {
            region.writeChunk(0, 0, normal);
            region.writeChunk(1, 0, huge);
            CsoAnvilRegionSynth.Synthesis synthesis = CsoAnvilRegionSynth.toAnvilRegion(region);
            assertEquals(1, synthesis.chunks());
            assertEquals(1, synthesis.skipped());
            assertNotNull(synthesis.anvil());

            Path anvil = dir.resolve("r.0.0.mca");
            Files.write(anvil, synthesis.anvil());
            AnvilRegionFile.ReadResult read = AnvilRegionFile.readReporting(anvil);
            assertTrue(read.isComplete());
            assertEquals(1, read.chunks().size());
            assertArrayEquals(normal, read.chunks().get(0).nbt());
        }
    }

    @Test
    void emptyRegionSynthesizesToNothing() throws IOException {
        try (CsoRegionFile region = open(dir.resolve("r.0.0.cso"))) {
            CsoAnvilRegionSynth.Synthesis synthesis = CsoAnvilRegionSynth.toAnvilRegion(region);
            assertNull(synthesis.anvil());
            assertEquals(0, synthesis.chunks());
        }
    }

    /**
     * A deleted chunk must not leave a trace in the synthesised Anvil: a header slot that parses
     * as an empty chunk would import as terrain that no longer exists. The positions span three
     * buckets and one deletion empties its bucket completely — the state where the bucket table
     * entry survives while the payload has no chunks left.
     */
    @Test
    void deletedChunksLeaveNoTombstoneInTheSynthesisedAnvil() throws IOException {
        Path path = dir.resolve("r.0.0.cso");
        int[][] positions = {{0, 0}, {1, 1}, {14, 14}, {15, 15}, {16, 16}, {17, 17}};
        int[][] removed = {{0, 0}, {1, 1}, {14, 14}};
        Map<Integer, byte[]> live = new HashMap<>();
        Map<Integer, byte[]> deleted = new HashMap<>();

        try (CsoRegionFile region = open(path)) {
            for (int i = 0; i < positions.length; i++) {
                byte[] nbt = new byte[200 + i * 50];
                new Random(7 + i).nextBytes(nbt);
                region.writeChunk(positions[i][0], positions[i][1], nbt);
                live.put(positions[i][1] * 32 + positions[i][0], nbt);
            }
            for (int[] position : removed) {
                region.deleteChunk(position[0], position[1]);
                int index = position[1] * 32 + position[0];
                deleted.put(index, live.remove(index));
            }
        }

        try (CsoRegionFile region = open(path)) {
            CsoAnvilRegionSynth.Synthesis synthesis = CsoAnvilRegionSynth.toAnvilRegion(region);
            assertEquals(live.size(), synthesis.chunks());
            assertEquals(0, synthesis.skipped());
            assertNotNull(synthesis.anvil());

            Path anvil = dir.resolve("r.0.0.mca");
            Files.write(anvil, synthesis.anvil());
            AnvilRegionFile.ReadResult read = AnvilRegionFile.readReporting(anvil);
            assertTrue(read.isComplete());
            assertEquals(live.size(), read.occupied());
            assertEquals(live.size(), read.chunks().size());
            for (AnvilRegionFile.Chunk chunk : read.chunks()) {
                assertArrayEquals(live.get(chunk.index()), chunk.nbt(),
                    "chunk " + chunk.index() + " changed on the way through Anvil");
            }
            for (int index : deleted.keySet()) {
                assertTrue(read.chunks().stream().noneMatch(chunk -> chunk.index() == index),
                    "deleted chunk " + index + " came back");
                // The reader treats a zero header entry as absent; checking the header directly
                // is what rules out a tombstone that merely parses as an empty chunk.
                assertEquals(0, ByteBuffer.wrap(synthesis.anvil()).getInt(index * 4),
                    "deleted chunk " + index + " left a header entry");
            }
        }
    }

    /** Every chunk in the region was deleted, so there is no Anvil region worth handing to Voxy. */
    @Test
    void aRegionEmptiedByDeletionSynthesisesToNothing() throws IOException {
        Path path = dir.resolve("r.0.0.cso");
        try (CsoRegionFile region = open(path)) {
            region.writeChunk(9, 4, new byte[] {1, 2, 3, 4});
            region.deleteChunk(9, 4);
        }

        try (CsoRegionFile region = open(path)) {
            CsoAnvilRegionSynth.Synthesis synthesis = CsoAnvilRegionSynth.toAnvilRegion(region);
            assertNull(synthesis.anvil());
            assertEquals(0, synthesis.chunks());
            assertEquals(0, synthesis.skipped());
        }
    }

    private static final int MAX_SECTORS = 255;

    /** One chunk at every size boundary that matters, spread over the slot space. */
    private static void writeSampleRegion(Path path, Map<Integer, byte[]> written, int unused)
        throws IOException {
        int[][] positions = {
            {0, 0}, {1, 0}, {31, 0}, {0, 31}, {31, 31}, {5, 7}, {16, 16}, {17, 16}, {12, 3},
        };
        int[] sizes = {
            100, 4091, 4092, 4096, 60000, 100_000, 3, 1, 8192,
        };
        try (CsoRegionFile region = open(path)) {
            for (int i = 0; i < positions.length; i++) {
                byte[] nbt = new byte[sizes[i]];
                new Random(42 + i).nextBytes(nbt);
                region.writeChunk(positions[i][0], positions[i][1], nbt);
                written.put(positions[i][1] * 32 + positions[i][0], nbt);
            }
        }
    }

    private static CsoRegionFile open(Path path) throws IOException {
        return CsoRegionFile.open(
            path, 16, CsoFormat.COMPRESSION_ZSTD, 3, 64, true, 4194304, 0.25);
    }
}
