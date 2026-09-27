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

        Converter.Corpus corpus = Converter.corpus(dir, "auto", Converter.ALL_FILES);

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
        assertEquals("mca", Converter.corpus(dir, "auto", Converter.ALL_FILES).kind());
        assertEquals("cso", Converter.corpus(dir, "cso", Converter.ALL_FILES).kind());
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
        Converter.Corpus corpus = Converter.corpus(dir, "auto", Converter.ALL_FILES);

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

        List<AnvilRegionFile.Chunk> read = Converter.corpus(dir, "cso", Converter.ALL_FILES).byFile().get(dir.resolve("r.0.0.cso"));

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

    /**
     * A chunk at a chosen slot, tagged so a merge can be traced back to the file it came from:
     * byte 0 is the slot, byte 1 the tag the caller passed.
     */
    private static AnvilRegionFile.Chunk chunkAt(int index, char tag) {
        byte[] data = new byte[400];
        new Random(index).nextBytes(data);
        for (int p = 0; p + 8 < data.length; p += 8) {
            System.arraycopy("minecraft".getBytes(), 0, data, p, 8);
        }
        data[0] = (byte) index;
        data[1] = (byte) tag;
        return new AnvilRegionFile.Chunk(index, data);
    }

    private static List<AnvilRegionFile.Chunk> at(int[] indices, char tag) {
        List<AnvilRegionFile.Chunk> out = new ArrayList<>();
        for (int index : indices) {
            out.add(chunkAt(index, tag));
        }
        return out;
    }

    private static AnvilRegionFile.Chunk slot(List<AnvilRegionFile.Chunk> chunks, int index) {
        return chunks.stream()
            .filter(c -> c.index() == index)
            .findFirst()
            .orElseThrow(() -> new AssertionError("slot " + index + " missing from " + chunks.size() + " chunks"));
    }

    @Test
    void writingCsoOverAnExistingFileReplacesItWholesale(@TempDir Path dir) throws IOException {
        // CsoRegionFile deliberately never truncates, because a live world's file must survive being
        // reopened. A converter that writes fewer chunks has to replace the file anyway, or the tail
        // of the longer payload stays readable as extra chunks.
        writeCso(dir, "r.0.0.cso", at(new int[] {0, 1, 2, 3, 4}, 'c'));
        writeCso(dir, "r.0.0.cso", at(new int[] {0, 1, 2}, 'd'));

        List<AnvilRegionFile.Chunk> read = Converter.readCso(dir.resolve("r.0.0.cso"));
        assertEquals(3, read.size(), "the two dropped chunks must not linger");
        assertEquals((byte) 'd', slot(read, 0).nbt()[1], "what is left is the new file, not the old one");
    }

    @Test
    void preferKeepsEverySlotAndLetsThePrimaryWin() {
        List<AnvilRegionFile.Chunk> merged = Converter.prefer(
            at(new int[] {1, 2}, 'c'), at(new int[] {0, 1}, 'm'));

        assertEquals(3, merged.size());
        assertEquals((byte) 'm', slot(merged, 0).nbt()[1]);
        assertEquals((byte) 'c', slot(merged, 1).nbt()[1], "both files hold slot 1; the primary is the copy to keep");
        assertEquals((byte) 'c', slot(merged, 2).nbt()[1]);
    }

    @Test
    void aHalfMigratedRegionKeepsEveryChunkWhenConverted(@TempDir Path dir) throws IOException {
        // The shape a world ends up in when it spent time on each format: the .mca has slots 0 and 1,
        // the .cso has 1, 2 and 3, and slot 1 disagrees between them.
        writeAnvil(dir, "r.0.0.mca", at(new int[] {0, 1}, 'm'));
        Path target = dir.resolve("r.0.0.cso");
        writeCso(dir, "r.0.0.cso", at(new int[] {1, 2, 3}, 'c'));

        List<AnvilRegionFile.Chunk> merged = Converter.prefer(
            Converter.readCso(target), AnvilRegionFile.read(dir.resolve("r.0.0.mca")));
        Converter.writeCso(target, merged, 4, 3);

        List<AnvilRegionFile.Chunk> read = Converter.readCso(target);
        assertEquals(4, read.size(), "the union, not whichever file was written second");
        assertEquals((byte) 'm', slot(read, 0).nbt()[1], "a chunk only the .mca had is carried over");
        assertEquals((byte) 'c', slot(read, 1).nbt()[1], "the .cso copy wins, as it does for the live reader");
        assertEquals((byte) 'c', slot(read, 3).nbt()[1]);
    }
}
