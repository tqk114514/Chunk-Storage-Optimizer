package tqk114514.chunkstorageoptimizer.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Minimal Anvil ({@code .mca}) reader/writer for conversion and benchmarking.
 *
 * <p>Deliberately works on raw NBT bytes and never parses them, so the converter needs no
 * Minecraft runtime. Layout matches {@code net.minecraft.world.level.chunk.storage.RegionFile}:
 * two 4096-byte header sectors (1024 offsets, 1024 timestamps), 4096-byte sectors, each chunk
 * prefixed with a 4-byte length and a 1-byte compression id.
 *
 * <p>External chunks ({@code c.x.z.mcc}, flagged by compression id | 128) are skipped on read;
 * they are rare and only exist for chunks over ~1 MiB.
 */
public final class AnvilRegionFile {

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = 8192;
    private static final int CHUNKS = 1024;

    private AnvilRegionFile() {
    }

    /** @param index slot in the region, {@code x = index % 32}, {@code z = index / 32} */
    public record Chunk(int index, byte[] nbt) {
    }

    public static List<Chunk> read(Path path) throws IOException {
        List<Chunk> out = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (channel.size() < HEADER_BYTES) {
                return out;
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, header, 0L);
            header.flip();

            for (int i = 0; i < CHUNKS; i++) {
                int packed = header.getInt(i * 4);
                if (packed == 0) {
                    continue;
                }
                int sector = (packed >> 8) & 0xFFFFFF;
                int sectorCount = packed & 0xFF;
                if (sector < 2 || sectorCount == 0) {
                    continue;
                }
                long position = (long) sector * SECTOR_BYTES;
                if (position + 5 > channel.size()) {
                    continue;
                }
                ByteBuffer prefix = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
                readFully(channel, prefix, position);
                prefix.flip();
                int length = prefix.getInt();
                int compressionId = prefix.get() & 0xFF;
                if (length <= 1 || (compressionId & 128) != 0) {
                    continue; // empty, or stored in an external .mcc
                }
                int streamLength = length - 1;
                if (position + 5 + streamLength > channel.size()) {
                    continue;
                }
                byte[] raw = new byte[streamLength];
                readFully(channel, ByteBuffer.wrap(raw), position + 5);
                byte[] nbt = decompress(compressionId, raw);
                if (nbt != null) {
                    out.add(new Chunk(i, nbt));
                }
            }
        }
        return out;
    }

    /** Writes chunks with zlib compression, matching what vanilla writes by default. */
    public static void write(Path path, List<Chunk> chunks) throws IOException {
        write(path, chunks, Deflater.DEFAULT_COMPRESSION);
    }

    public static void write(Path path, List<Chunk> chunks, int deflateLevel) throws IOException {
        int[] offsets = new int[CHUNKS];
        int sector = 2;
        int now = (int) (System.currentTimeMillis() / 1000L);

        try (FileChannel channel = FileChannel.open(
            path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
        )) {
            channel.write(ByteBuffer.allocate(HEADER_BYTES), 0L);

            for (Chunk chunk : chunks) {
                byte[] compressed = deflate(chunk.nbt(), deflateLevel);
                int total = compressed.length + 5;
                int sectorCount = (total + SECTOR_BYTES - 1) / SECTOR_BYTES;
                ByteBuffer block = ByteBuffer.allocate(sectorCount * SECTOR_BYTES).order(ByteOrder.BIG_ENDIAN);
                block.putInt(compressed.length + 1);
                block.put((byte) 2); // zlib
                block.put(compressed);
                block.flip();
                channel.write(block, (long) sector * SECTOR_BYTES);
                offsets[chunk.index()] = (sector << 8) | sectorCount;
                sector += sectorCount;
            }

            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
            for (int offset : offsets) {
                header.putInt(offset);
            }
            for (int i = 0; i < CHUNKS; i++) {
                header.putInt(offsets[i] == 0 ? 0 : now);
            }
            header.flip();
            channel.write(header, 0L);
        }
    }

    private static byte[] decompress(int compressionId, byte[] raw) {
        try {
            InputStream in = switch (compressionId) {
                case 1 -> new GZIPInputStream(new ByteArrayInputStream(raw));
                case 2 -> new InflaterInputStream(new ByteArrayInputStream(raw));
                case 3 -> new ByteArrayInputStream(raw);
                case 4 -> lz4Stream(raw);
                default -> null;
            };
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * lz4 is optional. Vanilla only emits it when {@code region-file-compression=lz4} is set, and
     * this converter may run without Minecraft's bundled lz4 on the classpath (tests, plain CLI).
     * Reflected so a missing class degrades to "cannot read this chunk" instead of failing the
     * whole file.
     */
    private static InputStream lz4Stream(byte[] raw) {
        try {
            Class<?> type = Class.forName("net.jpountz.lz4.LZ4BlockInputStream");
            return (InputStream) type.getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(raw));
        } catch (ReflectiveOperationException | RuntimeException | Error e) {
            return null;
        }
    }

    private static byte[] deflate(byte[] data, int level) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 64);
        Deflater deflater = new Deflater(level);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater)) {
            stream.write(data);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, pos);
            if (n < 0) {
                throw new IOException("Unexpected end of file at " + pos);
            }
            pos += n;
        }
    }
}
