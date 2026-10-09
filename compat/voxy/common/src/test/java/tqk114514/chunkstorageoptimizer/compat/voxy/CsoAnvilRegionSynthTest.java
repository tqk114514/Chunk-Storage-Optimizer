package tqk114514.chunkstorageoptimizer.compat.voxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
