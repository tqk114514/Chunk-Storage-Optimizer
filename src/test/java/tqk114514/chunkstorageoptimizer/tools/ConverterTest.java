package tqk114514.chunkstorageoptimizer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tqk114514.chunkstorageoptimizer.format.AnvilRegionFile;

/**
 * Covers which format a benchmark directory is measured from — the property that used to be
 * hardcoded to {@code .mca}, which made an already-converted save impossible to benchmark.
 */
class ConverterTest {

    private static List<AnvilRegionFile.Chunk> chunks(int count, int size) {
        List<AnvilRegionFile.Chunk> out = new ArrayList<>();
        Random random = new Random(count);
        for (int i = 0; i < count; i++) {
            byte[] data = new byte[size];
            random.nextBytes(data);
            for (int p = 0; p + 8 < size; p += 8) {
                System.arraycopy("minecraft".getBytes(), 0, data, p, 8); // so it compresses at all
            }
            out.add(new AnvilRegionFile.Chunk(i, data));
        }
        return out;
    }

    private static void writeAnvil(Path dir, String name, List<AnvilRegionFile.Chunk> chunks) throws IOException {
        AnvilRegionFile.write(dir.resolve(name), chunks);
    }

    private static void writeCso(Path dir, String name, List<AnvilRegionFile.Chunk> chunks) throws IOException {
        Converter.writeCso(dir.resolve(name), chunks, 4, 3);
    }

    @Test
    void csoOnlyDirectoryIsStillMeasurable(@TempDir Path dir) throws IOException {
        writeCso(dir, "r.0.0.cso", chunks(20, 400));
        writeCso(dir, "r.0.1.cso", chunks(5, 400));

        Converter.Corpus corpus = Converter.corpus(dir, "auto", 0);

        assertEquals("cso", corpus.kind());
        assertEquals(2, corpus.byFile().size());
        assertEquals(25, corpus.byFile().values().stream().mapToInt(List::size).sum());
        assertTrue(corpus.onDiskBytes() > 0, "the bytes on disk are the baseline for a re-compare");
    }

    @Test
    void anvilWinsWhenBothFormatsArePresent(@TempDir Path dir) throws IOException {
        List<AnvilRegionFile.Chunk> chunks = chunks(12, 400);
        writeAnvil(dir, "r.0.0.mca", chunks);
        writeCso(dir, "r.0.0.cso", chunks);

        // Mid-migration is the normal state after enabling the mod. The .mca is what vanilla wrote,
        // so it is the number the comparisons are expressed against.
        assertEquals("mca", Converter.corpus(dir, "auto", 0).kind());
        assertEquals("cso", Converter.corpus(dir, "cso", 0).kind());
    }

    @Test
    void maxFilesCapsTheCorpusButReportsTheFullCount(@TempDir Path dir) throws IOException {
        for (int i = 0; i < 3; i++) {
            writeAnvil(dir, "r.0." + i + ".mca", chunks(6, 400));
        }

        Converter.Corpus corpus = Converter.corpus(dir, "auto", 2);

        assertEquals(2, corpus.byFile().size());
        assertEquals(3, corpus.totalFiles());
    }

    @Test
    void emptyDirectoryYieldsAnEmptyCorpus(@TempDir Path dir) throws IOException {
        Converter.Corpus corpus = Converter.corpus(dir, "auto", 0);

        assertTrue(corpus.byFile().isEmpty());
        assertEquals(0, corpus.onDiskBytes());
    }

    @Test
    void estimateWeighsEveryGridOnAnvilInput(@TempDir Path dir) throws IOException {
        writeAnvil(dir, "r.0.0.mca", chunks(300, 500));
        writeAnvil(dir, "r.0.1.mca", chunks(120, 500));

        Converter.Estimate estimate = Converter.estimate(dir, "auto", 8, new int[] {1, 8, 16, 32});

        assertEquals("mca", estimate.kind());
        assertEquals(2, estimate.filesSampled());
        assertEquals(2, estimate.totalFiles());
        assertEquals(420, estimate.chunks());
        assertTrue(estimate.currentBytes() > 0);
        assertEquals(Set.of(1, 8, 16, 32), estimate.bytesByGrid().keySet());
        // The whole point of the grid knob: a bigger bucket shares more compression context.
        assertTrue(estimate.bytesByGrid().get(1) < estimate.bytesByGrid().get(32),
            "expected grid 1 to beat grid 32, got " + estimate.bytesByGrid());
    }

    @Test
    void estimateRunsOnAConvertedDirectory(@TempDir Path dir) throws IOException {
        writeCso(dir, "r.0.0.cso", chunks(200, 500));

        Converter.Estimate estimate = Converter.estimate(dir, "auto", 4, new int[] {16});

        assertEquals("cso", estimate.kind());
        assertEquals(200, estimate.chunks());
        assertEquals(1, estimate.bytesByGrid().size());
    }

    @Test
    void estimateOnAnEmptyDirectoryReportsNothing(@TempDir Path dir) throws IOException {
        assertEquals(0, Converter.estimate(dir, "auto", 4, new int[] {16}).filesSampled());
    }

    @Test
    void chunksSurviveTheCsoRoundTrip(@TempDir Path dir) throws IOException {
        List<AnvilRegionFile.Chunk> chunks = chunks(30, 900);
        writeCso(dir, "r.0.0.cso", chunks);

        List<AnvilRegionFile.Chunk> read = Converter.corpus(dir, "cso", 0).byFile().get(dir.resolve("r.0.0.cso"));

        assertEquals(chunks.size(), read.size());
        for (AnvilRegionFile.Chunk original : chunks) {
            byte[] back = read.stream()
                .filter(c -> c.index() == original.index())
                .findFirst()
                .orElseThrow(() -> new AssertionError("slot " + original.index() + " lost"))
                .nbt();
            assertTrue(java.util.Arrays.equals(original.nbt(), back), "slot " + original.index() + " changed");
        }
    }
}
