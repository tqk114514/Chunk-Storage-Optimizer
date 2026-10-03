package tqk114514.chunkstorageoptimizer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tqk114514.chunkstorageoptimizer.format.AnvilRegionFile;

/**
 * Reproduces the silent data loss in the in-game
 * {@code /cso convert mca} merge.
 *
 * <p>{@code CsoCommands.convertWorld} used to merge an existing {@code .mca} with
 * {@code Converter.prefer(in, AnvilRegionFile.read(out))} — and {@code read()}
 * silently drops every slot it cannot decode (external {@code .mcc} stubs,
 * unknown compression ids). Those chunks vanished from the merged output while
 * the {@code .mcc} bytes remained on disk, orphaned. The command now reads with
 * {@code readReporting} and skips the file, matching the offline
 * {@code Converter.convert}, which hard-stops on the same case.
 */
class ConvertMergeAdversarialTest {

    private static byte[] chunkData(int seed) {
        byte[] out = new byte[800 + (seed % 5) * 200];
        Random random = new Random(seed);
        int p = 0;
        while (p < out.length) {
            byte[] token = ("minecraft:" + random.nextInt(32) + "_block").getBytes();
            int n = Math.min(token.length, out.length - p);
            System.arraycopy(token, 0, out, p, n);
            p += n;
        }
        return out;
    }

    @Test
    void mergeMustNotDropUndecodableLegacySlots(@TempDir Path dir) throws IOException {
        // A legacy .mca holding a normal chunk (slot 1) and an external .mcc
        // stub (slot 0): the shape vanilla leaves for an oversized chunk.
        Path mca = dir.resolve("r.0.0.mca");
        writeRawMca(mca, new int[] {0, 1}, new int[] {130, 2});

        // The .cso holds two chunks — one it shares with the .mca (newer copy)
        // and one the .mca never had.
        Path cso = dir.resolve("r.0.0.cso");
        Converter.writeCso(
            cso,
            List.of(
                new AnvilRegionFile.Chunk(1, chunkData(2)),
                new AnvilRegionFile.Chunk(2, chunkData(3))),
            8, 3);

        // What CsoCommands.convertWorld does since the fix: read the existing
        // .mca with the reporting reader, and skip the file entirely when a
        // slot cannot decode — merging without it would orphan the .mcc bytes.
        AnvilRegionFile.ReadResult existing = AnvilRegionFile.readReporting(mca);
        assertEquals(
            1, existing.unreadable(),
            "the external .mcc slot must count as unreadable, so the command "
                + "skips this file instead of merging without that chunk");
        assertEquals(1, existing.chunks().size());

        // And the union it is allowed to do — had every slot decoded — really
        // does keep all three chunks.
        Path cleanMca = dir.resolve("r.0.1.mca");
        writeRawMca(cleanMca, new int[] {0, 1}, new int[] {2, 2});
        List<AnvilRegionFile.Chunk> merged = Converter.prefer(
            Converter.readCso(cso), AnvilRegionFile.readReporting(cleanMca).chunks());
        assertEquals(3, merged.size());
    }

    /**
     * The offline converter does the right thing: it reads the existing .mca
     * with readReporting and refuses to merge. This test documents that the
     * same data the command silently loses is detected as unreadable.
     */
    @Test
    void reportingReaderSeesTheSlotThePlainReaderDrops(@TempDir Path dir) throws IOException {
        Path mca = dir.resolve("r.0.0.mca");
        writeRawMca(mca, new int[] {0, 1}, new int[] {130, 2});

        AnvilRegionFile.ReadResult result = AnvilRegionFile.readReporting(mca);
        assertEquals(1, result.chunks().size());
        assertEquals(1, result.unreadable(), "the external stub is a named chunk that decodes to nothing");
        assertTrue(AnvilRegionFile.read(mca).size() == 1,
            "plain read() hides the loss this test is about");
    }

    /** Same hand-built region writer the format tests use. */
    private static void writeRawMca(Path path, int[] slots, int[] compressionIds) throws IOException {
        byte[][] prefixes = new byte[slots.length][];
        for (int i = 0; i < slots.length; i++) {
            int id = compressionIds[i];
            if ((id & 128) != 0) {
                // RegionFile.createExternalStub(): length 1, flag set, no payload.
                prefixes[i] = new byte[] {0, 0, 0, 1, (byte) id};
            } else {
                byte[] payload = id == 2 ? deflateBytes(chunkData(slots[i])) : new byte[] {9};
                prefixes[i] = new byte[5 + payload.length];
                ByteBuffer.wrap(prefixes[i])
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(payload.length + 1)
                    .put((byte) id)
                    .put(payload);
            }
        }
        writeMcaWithPrefixes(path, slots, prefixes);
    }

    private static void writeMcaWithPrefixes(Path path, int[] slots, byte[][] prefixes)
        throws IOException {
        int sectorBytes = 4096;
        int headerBytes = 8192;
        try (FileChannel channel = FileChannel.open(
            path, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ByteBuffer header = ByteBuffer.allocate(headerBytes).order(ByteOrder.BIG_ENDIAN);
            int sector = 2;
            for (int i = 0; i < slots.length; i++) {
                header.putInt(slots[i] * 4, (sector << 8) | 1);
                ByteBuffer block = ByteBuffer.allocate(sectorBytes).order(ByteOrder.BIG_ENDIAN);
                block.put(prefixes[i]);
                block.flip();
                channel.write(block, (long) sector * sectorBytes);
                sector += 1;
            }
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
