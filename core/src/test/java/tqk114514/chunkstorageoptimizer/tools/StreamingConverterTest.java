package tqk114514.chunkstorageoptimizer.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tqk114514.chunkstorageoptimizer.format.AnvilRegionFile;
import tqk114514.chunkstorageoptimizer.format.CsoFormat;

/**
 * The streaming conversion pipeline against the bulk path it replaces.
 *
 * <p>The bulk path's semantics are the tested, in-game-verified contract: same chunks out,
 * same union precedence, same refusals, same "nothing empty is written". The streaming path
 * exists to hold one bucket of chunks instead of one region — if it also changed any of those
 * behaviors, the speedup would be buying someone's world.
 */
class StreamingConverterTest {

    private static final int GRID = 16;

    /** Distinct, compressible stand-in for a chunk's NBT. */
    private static byte[] nbt(int seed) {
        byte[] out = new byte[900 + (seed % 5) * 250];
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

    private static List<AnvilRegionFile.Chunk> chunks(int... seeds) {
        List<AnvilRegionFile.Chunk> out = new ArrayList<>();
        for (int seed : seeds) {
            out.add(new AnvilRegionFile.Chunk(seed, nbt(seed)));
        }
        return out;
    }

    /** A .cso read back as slot -> bytes, the shape every comparison here works in. */
    private static Map<Integer, byte[]> readCsoSlots(Path file) throws IOException {
        return Converter.readCso(file).stream()
            .collect(Collectors.toMap(AnvilRegionFile.Chunk::index, AnvilRegionFile.Chunk::nbt,
                (a, b) -> b, TreeMap::new));
    }

    private static Map<Integer, byte[]> readMcaSlots(Path file) throws IOException {
        return AnvilRegionFile.readReporting(file).chunks().stream()
            .collect(Collectors.toMap(AnvilRegionFile.Chunk::index, AnvilRegionFile.Chunk::nbt,
                (a, b) -> b, TreeMap::new));
    }

    /** byte[] is compared by identity under Map.equals, so the slots are walked by hand. */
    private static void assertSameSlots(Map<Integer, byte[]> expected, Map<Integer, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((slot, bytes) -> assertArrayEquals(bytes, actual.get(slot), "slot " + slot));
    }

    @Test
    void mcaToCsoMatchesTheBulkPathChunkForChunk(@TempDir Path dir) throws IOException {
        Path mca = dir.resolve("r.0.0.mca");
        AnvilRegionFile.write(mca, chunks(0, 1, 33, 700, 1023));

        Path bulk = dir.resolve("bulk.cso");
        Converter.writeCso(bulk, AnvilRegionFile.read(mca), GRID, 3);
        Path streamed = dir.resolve("streamed.cso");
        StreamingConverter.Outcome outcome = StreamingConverter.mcaToCso(mca, streamed, GRID, 3);

        assertEquals(5, outcome.written());
        assertEquals(5, outcome.sourceChunks());
        assertEquals(0, outcome.unreadable());
        assertSameSlots(readCsoSlots(bulk), readCsoSlots(streamed));
    }

    @Test
    void csoToMcaMatchesTheBulkPathChunkForChunk(@TempDir Path dir) throws IOException {
        Path cso = dir.resolve("r.0.0.cso");
        Converter.writeCso(cso, chunks(0, 7, 1023), GRID, 3);

        Path bulk = dir.resolve("bulk.mca");
        AnvilRegionFile.write(bulk, Converter.readCso(cso));
        Path streamed = dir.resolve("streamed.mca");
        StreamingConverter.Outcome outcome = StreamingConverter.csoToMca(cso, streamed);

        assertEquals(3, outcome.written());
        assertEquals(3, outcome.sourceChunks());
        assertEquals(0, outcome.unreadable());
        assertSameSlots(readMcaSlots(bulk), readMcaSlots(streamed));
    }

    @Test
    void mcaToCsoUnionsWithTheExistingCsoAndTheCsoWinsPerSlot(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("r.0.0.cso");
        Converter.writeCso(out, chunks(1, 5), GRID, 3); // slot 1 on the .cso side already
        Path mca = dir.resolve("r.0.0.mca");
        AnvilRegionFile.write(mca, List.of(
            new AnvilRegionFile.Chunk(1, nbt(101)), // shadowed: the .cso copy is the served one
            new AnvilRegionFile.Chunk(9, nbt(9))    // a chunk the .cso never saw
        ));

        StreamingConverter.Outcome outcome = StreamingConverter.mcaToCso(mca, out, GRID, 3);

        assertEquals(3, outcome.written());
        assertEquals(2, outcome.sourceChunks());
        Map<Integer, byte[]> back = readCsoSlots(out);
        assertArrayEquals(nbt(1), back.get(1), "the .cso copy wins the contested slot");
        assertArrayEquals(nbt(5), back.get(5), "the .cso-only chunk survives the rewrite");
        assertArrayEquals(nbt(9), back.get(9), "the .mca-only chunk is carried over");
    }

    @Test
    void csoToMcaUnionsWithTheExistingMcaAndFillsOnlyItsGaps(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("r.0.0.mca");
        AnvilRegionFile.write(out, List.of(
            new AnvilRegionFile.Chunk(1, nbt(201)), // contested: the .cso wins
            new AnvilRegionFile.Chunk(7, nbt(7))    // the .mca's own, the .cso never saw it
        ));
        Path cso = dir.resolve("r.0.0.cso");
        Converter.writeCso(cso, List.of(
            new AnvilRegionFile.Chunk(1, nbt(1)),
            new AnvilRegionFile.Chunk(2, nbt(2))
        ), GRID, 3);

        StreamingConverter.Outcome outcome = StreamingConverter.csoToMca(cso, out);

        assertEquals(3, outcome.written());
        assertEquals(2, outcome.sourceChunks());
        Map<Integer, byte[]> back = readMcaSlots(out);
        assertArrayEquals(nbt(1), back.get(1), "the input .cso wins the contested slot");
        assertArrayEquals(nbt(2), back.get(2));
        assertArrayEquals(nbt(7), back.get(7), "the .mca-only chunk survives the rewrite");
    }

    @Test
    void anUndecodableSourceSlotRefusesAndLeavesBothSidesUntouched(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("r.0.0.cso");
        Converter.writeCso(out, chunks(5), GRID, 3);
        byte[] before = Files.readAllBytes(out);
        Path mca = dir.resolve("r.0.0.mca");
        writeRawMcaWithUnreadable(mca, 2, 8);

        StreamingConverter.Outcome outcome = StreamingConverter.mcaToCso(mca, out, GRID, 3);

        assertEquals(2, outcome.unreadable(), "the refused slots are counted, so the report says how much");
        assertEquals(0, outcome.written());
        assertArrayEquals(before, Files.readAllBytes(out), "a refused run must not touch the target");
        assertFalse(Files.exists(out.resolveSibling(out.getFileName() + ".tmp")),
            "and must not leave its temp behind");
    }

    @Test
    void anUndecodableExistingMcaSlotRefusesTheCsoToMca(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("r.0.0.mca");
        writeRawMcaWithUnreadable(out, 2, 8);
        byte[] before = Files.readAllBytes(out);
        Path cso = dir.resolve("r.0.0.cso");
        Converter.writeCso(cso, chunks(1), GRID, 3);

        StreamingConverter.Outcome outcome = StreamingConverter.csoToMca(cso, out);

        assertEquals(2, outcome.unreadable());
        assertEquals(0, outcome.written());
        assertArrayEquals(before, Files.readAllBytes(out), "the union must not drop slots it cannot read");
        assertFalse(Files.exists(out.resolveSibling(out.getFileName() + ".tmp")));
    }

    @Test
    void emptySourcesWriteNothingAndTouchNothing(@TempDir Path dir) throws IOException {
        // An empty .mca: the header names no slot at all.
        Path mca = dir.resolve("r.0.0.mca");
        Files.write(mca, new byte[8192]);
        Path out = dir.resolve("r.0.0.cso");
        assertEquals(new StreamingConverter.Outcome(0, 0, 0),
            StreamingConverter.mcaToCso(mca, out, GRID, 3));
        assertFalse(Files.exists(out), "an empty source must not produce a shell file");

        // An empty .cso: a real file whose buckets are all absent.
        Path cso = dir.resolve("r.1.1.cso");
        Converter.writeCso(cso, List.of(), GRID, 3);
        Path mcaOut = dir.resolve("r.1.1.mca");
        assertEquals(new StreamingConverter.Outcome(0, 0, 0), StreamingConverter.csoToMca(cso, mcaOut));
        assertFalse(Files.exists(mcaOut));
    }

    /**
     * The one fixture the bulk helpers cannot build: an .mca holding readable chunks beside
     * slots that name a chunk this parser refuses — an unknown compression id and a
     * zero-length stream.
     */
    private static void writeRawMcaWithUnreadable(Path path, int... readableSlots) throws IOException {
        try (FileChannel channel = FileChannel.open(
            path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ByteBuffer header = ByteBuffer.allocate(8192).order(ByteOrder.BIG_ENDIAN);
            ByteBuffer body = ByteBuffer.allocate(4096 * (readableSlots.length + 2));
            int sector = 2;
            for (int slot : readableSlots) {
                java.io.ByteArrayOutputStream deflated = new java.io.ByteArrayOutputStream();
                try (java.util.zip.DeflaterOutputStream stream = new java.util.zip.DeflaterOutputStream(deflated)) {
                    stream.write(nbt(slot));
                }
                byte[] compressed = deflated.toByteArray();
                body.putInt(compressed.length + 1).put((byte) 2).put(compressed);
                body.put(new byte[4096 - 5 - compressed.length]); // one sector per chunk
                header.putInt(slot * 4, (sector << 8) | 1);
                sector++;
            }
            header.putInt(6 * 4, (sector << 8) | 1); // slot 6: unknown compression id
            body.putInt(4).put((byte) 77).put(new byte[4096 - 5]);
            sector++;
            header.putInt(9 * 4, (sector << 8) | 1); // slot 9: zero length — damage, not absence
            body.putInt(0).put((byte) 2).put(new byte[4091]);

            // limit(), not flip(): the absolute putInt calls above never moved the header's
            // position, so flip() would hand over a zero-length buffer and the body would
            // become the header instead.
            header.limit(8192);
            header.position(0);
            body.flip();
            ByteBuffer file = ByteBuffer.allocate(header.remaining() + body.remaining());
            file.put(header).put(body);
            file.flip();
            while (file.hasRemaining()) {
                channel.write(file);
            }
        }
    }
}
