package tqk114514.chunkstorageoptimizer.compat.voxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import tqk114514.chunkstorageoptimizer.format.CsoRegionFile;

/**
 * Materialises a {@code .cso} region as an in-memory Anvil ({@code .mca}) region, so Voxy's
 * importer can parse it with its own vanilla-Anvil path completely untouched: the bytes this
 * produces are what a {@code .mca} file holding the same chunks would contain.
 *
 * <p>Chunk payloads are written with the raw stream id (3): every Anvil reader accepts it
 * (vanilla's {@code RegionFileVersion.NONE}, core's own reader, Voxy's importer), and it keeps
 * the synthesis a pure copy — decompression already happened when the bucket was read. Chunks
 * too large for Anvil's one-byte sector count (over 255 sectors, the same limit that makes
 * vanilla spill to {@code .mcc}) are skipped and reported, matching what Voxy could ever import
 * from a vanilla world.
 *
 * <p>Depends on Voxy and Minecraft not at all: only on core's public reading API and the JDK,
 * so this class is unit-testable against core's own Anvil reader.
 */
public final class CsoAnvilRegionSynth {

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = 8192;
    /** Anvil stores the sector count in one byte; more than this cannot be represented. */
    private static final int MAX_SECTORS_PER_CHUNK = 255;
    /** Vanilla {@code RegionFileVersion.NONE}: the payload is the chunk's raw NBT bytes. */
    private static final int RAW_STREAM_ID = 3;

    /** A region's worth of Anvil bytes plus what the synthesis had to leave out. */
    public record Synthesis(byte[] anvil, int chunks, int skipped) {
    }

    private record Slot(int index, int sectors, byte[] nbt) {
    }

    private CsoAnvilRegionSynth() {
    }

    public static Synthesis toAnvilRegion(CsoRegionFile region) throws IOException {
        List<Slot> slots = new ArrayList<>();
        int skipped = 0;
        long totalSectors = 2; // the 8 KiB header occupies the first two sectors

        for (int localZ = 0; localZ < 32; localZ++) {
            for (int localX = 0; localX < 32; localX++) {
                byte[] nbt = region.readChunk(localX, localZ);
                if (nbt == null) {
                    continue;
                }
                int sectors = (5 + nbt.length + SECTOR_BYTES - 1) / SECTOR_BYTES;
                if (sectors > MAX_SECTORS_PER_CHUNK) {
                    skipped++;
                    continue;
                }
                // Anvil's slot order: x = index % 32, z = index / 32.
                slots.add(new Slot(localZ * 32 + localX, sectors, nbt));
                totalSectors += sectors;
            }
        }

        if (slots.isEmpty()) {
            return new Synthesis(null, 0, skipped);
        }

        byte[] out = new byte[(int) (totalSectors * SECTOR_BYTES)];
        ByteBuffer header = ByteBuffer.wrap(out, 0, HEADER_BYTES);
        int sector = 2;
        for (Slot slot : slots) {
            header.putInt(slot.index() * 4, (sector << 8) | slot.sectors());
            // [length = nbt + 1][stream id][nbt...]: the length counts the stream-id byte, the
            // way every Anvil writer does. Padding bytes stay zero; no reader looks at them.
            ByteBuffer chunk = ByteBuffer.wrap(out, sector * SECTOR_BYTES, slot.sectors() * SECTOR_BYTES);
            chunk.putInt(slot.nbt().length + 1);
            chunk.put((byte) RAW_STREAM_ID);
            chunk.put(slot.nbt());
            sector += slot.sectors();
        }
        // The second 4 KiB half of the header holds timestamps. They stay zero: Voxy's importer
        // never reads them, and vanilla itself tolerates absent timestamps.
        return new Synthesis(out, slots.size(), skipped);
    }
}
