package tqk114514.chunkstorageoptimizer.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tqk114514.chunkstorageoptimizer.tools.Converter;

/**
 * Guards the converter. A silent bug here does not fail loudly at runtime — it quietly writes a
 * world that no longer matches the original — so both directions are checked byte for byte.
 */
class AnvilRegionFileTest {

    /** Stands in for serialized chunk NBT: structured enough to compress, distinct per seed. */
    private static byte[] chunkData(int seed) {
        byte[] out = new byte[1200 + (seed % 7) * 300];
        Random random = new Random(seed);
        int p = 0;
        while (p < out.length) {
            byte[] token = ("minecraft:" + random.nextInt(48) + "_block").getBytes();
            int n = Math.min(token.length, out.length - p);
            System.arraycopy(token, 0, out, p, n);
            p += n;
        }
        return out;
    }

    @Test
    void anvilWriteThenReadIsLossless(@TempDir Path dir) throws IOException {
        Map<Integer, byte[]> expected = new LinkedHashMap<>();
        List<AnvilRegionFile.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            int index = (i * 7 + 3) % 1024; // 7 is coprime with 1024, so no repeats under 1024
            byte[] data = chunkData(i);
            chunks.add(new AnvilRegionFile.Chunk(index, data));
            expected.put(index, data);
        }

        Path mca = dir.resolve("r.0.0.mca");
        AnvilRegionFile.write(mca, chunks);

        List<AnvilRegionFile.Chunk> read = AnvilRegionFile.read(mca);
        assertEquals(expected.size(), read.size(), "chunk count changed");
        for (AnvilRegionFile.Chunk chunk : read) {
            assertArrayEquals(expected.get(chunk.index()), chunk.nbt(), "chunk " + chunk.index() + " differs");
        }
    }

    @Test
    void mcaToCsoBackToMcaPreservesChunks(@TempDir Path dir) throws IOException {
        Map<Integer, byte[]> expected = new LinkedHashMap<>();
        List<AnvilRegionFile.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            int index = (i * 7 + 3) % 1024;
            byte[] data = chunkData(i);
            chunks.add(new AnvilRegionFile.Chunk(index, data));
            expected.put(index, data);
        }

        Path mca = dir.resolve("r.0.0.mca");
        AnvilRegionFile.write(mca, chunks);

        Path cso = dir.resolve("r.0.0.cso");
        Converter.writeCso(cso, AnvilRegionFile.read(mca), 8, 3);

        List<AnvilRegionFile.Chunk> back = Converter.readCso(cso);
        assertEquals(expected.size(), back.size(), "chunk count changed across conversion");
        for (AnvilRegionFile.Chunk chunk : back) {
            byte[] want = expected.get(chunk.index());
            assertNotNull(want, "unexpected chunk index " + chunk.index());
            assertArrayEquals(want, chunk.nbt(), "chunk " + chunk.index() + " differs after round trip");
        }
    }

    @Test
    void conversionActuallyShrinksRepetitiveData(@TempDir Path dir) throws IOException {
        List<AnvilRegionFile.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            // Highly repetitive: the kind of data where sharing one compression context pays off.
            byte[] data = new byte[3000];
            for (int j = 0; j < data.length; j++) {
                data[j] = (byte) ((i + j) % 5);
            }
            chunks.add(new AnvilRegionFile.Chunk(i, data));
        }
        Path mca = dir.resolve("r.0.0.mca");
        AnvilRegionFile.write(mca, chunks);
        Path cso = dir.resolve("r.0.0.cso");
        Converter.writeCso(cso, AnvilRegionFile.read(mca), 8, 3);

        long anvil = Files.size(mca);
        long converted = Files.size(cso);
        assertTrue(converted < anvil, "expected .cso (" + converted + ") to be smaller than .mca (" + anvil + ")");
    }

    @Test
    void emptyOrTinyFilesAreHandled(@TempDir Path dir) throws IOException {
        Path mca = dir.resolve("r.0.0.mca");
        AnvilRegionFile.write(mca, List.of());
        assertTrue(AnvilRegionFile.read(mca).isEmpty(), "empty region should read back empty");
    }
}
