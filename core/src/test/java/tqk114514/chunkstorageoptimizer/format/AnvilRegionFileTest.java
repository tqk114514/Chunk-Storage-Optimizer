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

    @Test
    void unwrittenSlotsAreNotCountedAsLosses(@TempDir Path dir) throws IOException {
        Path mca = dir.resolve("r.0.0.mca");
        AnvilRegionFile.write(mca, List.of(new AnvilRegionFile.Chunk(5, chunkData(1))));

        AnvilRegionFile.ReadResult result = AnvilRegionFile.readReporting(mca);

        assertEquals(1, result.chunks().size());
        assertEquals(0, result.unreadable(), "a zero header slot is unwritten, not a chunk that failed");
        assertTrue(result.isComplete());
        assertEquals(1, AnvilRegionFile.occupiedSlots(mca));
    }

    @Test
    void externalAndUnknownCompressionSlotsAreReportedAsUnreadable(@TempDir Path dir) throws IOException {
        // The exact shape of the silent-loss bug: the header names five chunks, but three of them
        // are of a kind this parser cannot turn back into NBT. Counting only the readable ones made
        // convert --prune delete the .mca while leaving those three chunks behind.
        Path mca = dir.resolve("r.0.0.mca");
        int[] slots = {0, 1, 2, 3, 4};
        int[] compressionIds = {2, 2, 2, 130, 7}; // zlib, zlib, zlib, external .mcc, unknown id
        writeRawMca(mca, slots, compressionIds);

        AnvilRegionFile.ReadResult result = AnvilRegionFile.readReporting(mca);

        assertEquals(3, result.chunks().size(), "only the zlib chunks decode");
        assertEquals(2, result.unreadable(), "the external and unknown slots are losses, not absences");
        assertTrue(!result.isComplete());
        assertEquals(5, AnvilRegionFile.occupiedSlots(mca), "the header names all five");
        // The old read() cannot tell the difference — this is what callers must stop relying on.
        assertEquals(3, AnvilRegionFile.read(mca).size());
    }

    @Test
    void allExternalRegionIsReportedAsFullyUnreadable(@TempDir Path dir) throws IOException {
        Path mca = dir.resolve("r.0.0.mca");
        writeRawMca(mca, new int[] {0, 1}, new int[] {130, 130});

        AnvilRegionFile.ReadResult result = AnvilRegionFile.readReporting(mca);

        assertTrue(result.chunks().isEmpty());
        assertEquals(2, result.unreadable());
        // A caller that only checks isEmpty() would treat this as "nothing here" and delete it.
        assertTrue(AnvilRegionFile.read(mca).isEmpty());
    }

    @Test
    void realExternalStubIsReportedAsUnreadable(@TempDir Path dir) throws IOException {
        // The shape vanilla actually leaves behind for an oversized chunk: a five-byte stub with a
        // length of 1 and no payload, next to a sibling c.x.z.mcc holding the bytes
        // (RegionFile.createExternalStub). That length is the same one an empty slot has, so a
        // reader that judges the length before the flag calls a real chunk "nothing here" — and
        // --prune then deletes the .mca that is the only pointer to it.
        Path mca = dir.resolve("r.0.0.mca");
        writeRawMca(mca, new int[] {0, 1, 2}, new int[] {130, 130, 130});

        AnvilRegionFile.ReadResult result = AnvilRegionFile.readReporting(mca);

        assertTrue(result.chunks().isEmpty(), "a stub carries no decodable bytes");
        assertEquals(3, result.unreadable(), "every stub still names a chunk that lives in a .mcc");
        assertTrue(!result.isComplete());
        assertEquals(3, AnvilRegionFile.occupiedSlots(mca));
    }

    @Test
    void allocatedSlotWithNoPayloadIsDamageNotAnAbsence(@TempDir Path dir) throws IOException {
        // Vanilla never writes this: deleting a chunk clears the header slot instead, and even an
        // empty compound serializes to its TAG_End byte. A slot that names a chunk but carries no
        // stream is therefore damage, and reading it as "nothing here" would let --prune drop it.
        Path mca = dir.resolve("r.0.0.mca");
        writeMcaWithPrefixes(mca, new int[] {0}, new byte[][] {{0, 0, 0, 1, 2}});

        AnvilRegionFile.ReadResult result = AnvilRegionFile.readReporting(mca);

        assertTrue(result.chunks().isEmpty());
        assertEquals(1, result.unreadable(), "an allocated slot with no stream is a loss, not an absence");
    }

    /**
     * Hand-builds a region file so slots can carry shapes the writer never emits — the external
     * {@code .mcc} stub and a compression id this parser does not know.
     *
     * <p>The external stub is written exactly as vanilla writes it: a length of 1 with no payload.
     * Building it any other way is what let a length-first reader mistake it for an empty slot.
     *
     * <p>Zlib slots get a genuine deflate stream so they really decode; the other kinds only need
     * the header to name them, since the reader rejects them before looking at their bytes.
     */
    private static void writeRawMca(Path path, int[] slots, int[] compressionIds) throws IOException {
        byte[][] prefixes = new byte[slots.length][];
        for (int i = 0; i < slots.length; i++) {
            int id = compressionIds[i];
            if ((id & 128) != 0) {
                // RegionFile.createExternalStub(): length 1, flag set, nothing else.
                prefixes[i] = new byte[] {0, 0, 0, 1, (byte) id};
            } else {
                byte[] payload = id == 2 ? deflateBytes(chunkData(slots[i])) : new byte[] {9};
                prefixes[i] = new byte[5 + payload.length];
                java.nio.ByteBuffer.wrap(prefixes[i])
                    .order(java.nio.ByteOrder.BIG_ENDIAN)
                    .putInt(payload.length + 1)
                    .put((byte) id)
                    .put(payload);
            }
        }
        writeMcaWithPrefixes(path, slots, prefixes);
    }

    /** Writes one sector per slot, each starting with the given prefix (length + compression id). */
    private static void writeMcaWithPrefixes(Path path, int[] slots, byte[][] prefixes) throws IOException {
        int sectorBytes = 4096;
        int headerBytes = 8192;
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
            path, java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
        )) {
            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(headerBytes)
                .order(java.nio.ByteOrder.BIG_ENDIAN);
            int sector = 2;
            for (int i = 0; i < slots.length; i++) {
                // Absolute put: the offsets do not need to be written in slot order, and the
                // timestamp half of the header is left zeroed because the reader ignores it.
                header.putInt(slots[i] * 4, (sector << 8) | 1);
                java.nio.ByteBuffer block = java.nio.ByteBuffer.allocate(sectorBytes)
                    .order(java.nio.ByteOrder.BIG_ENDIAN);
                block.put(prefixes[i]);
                block.flip();
                channel.write(block, (long) sector * sectorBytes);
                sector += 1;
            }
            // limit(headerBytes), not flip(): the absolute putInt calls never moved the position, so
            // flip() would leave a zero-length buffer and write an all-zero header.
            header.limit(headerBytes);
            header.position(0);
            channel.write(header, 0L);
        }
    }

    private static byte[] deflateBytes(byte[] data) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream stream = new java.util.zip.DeflaterOutputStream(out)) {
            stream.write(data);
        }
        return out.toByteArray();
    }
}
