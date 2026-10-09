package tqk114514.chunkstorageoptimizer.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <p>External chunks ({@code c.x.z.mcc}, flagged by compression id | 128) cannot be decoded here;
 * they are counted as unreadable rather than skipped, because the header slot still names a real
 * chunk and a caller must not delete the file that is the only pointer to it.
 */
public final class AnvilRegionFile {

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = 8192;
    private static final int CHUNKS = 1024;
    /** Set on the compression id of a chunk whose bytes live in a separate {@code c.x.z.mcc}. */
    private static final int EXTERNAL_STREAM_FLAG = 128;

    private AnvilRegionFile() {
    }

    /** @param index slot in the region, {@code x = index % 32}, {@code z = index / 32} */
    public record Chunk(int index, byte[] nbt) {
    }

    /**
     * What a read found, including what it could not read.
     *
     * <p>{@code unreadable} counts header slots that name a chunk whose bytes this parser cannot
     * turn back into NBT: an external {@code .mcc}, an unknown compression id, a codec that is not
     * on the classpath, or a stream that fails to inflate. Those slots are real chunks, so a caller
     * that deletes the source file must treat a non-zero count as a reason to stop. Reporting only
     * the readable chunks is what let {@code convert --prune} delete a file while silently leaving
     * some of its chunks behind.
     *
     * @param chunks     the slots that decoded, in slot order
     * @param unreadable slots that hold a chunk this parser could not decode
     * @param occupied   slots the header names as holding a chunk — the denominator the other two
     *                   have to add up to
     */
    public record ReadResult(List<Chunk> chunks, int unreadable, int occupied) {
        /** Whether nothing was lost: every chunk the header named was read back. */
        public boolean isComplete() {
            return this.unreadable == 0;
        }
    }

    public static List<Chunk> read(Path path) throws IOException {
        return readReporting(path).chunks();
    }

    /** Reads a region file, reporting both what decoded and how much did not. */
    public static ReadResult readReporting(Path path) throws IOException {
        List<Chunk> out = new ArrayList<>();
        int unreadable = 0;
        int occupied = 0;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (channel.size() < HEADER_BYTES) {
                return new ReadResult(out, 0, 0);
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, header, 0L);
            header.flip();

            for (int i = 0; i < CHUNKS; i++) {
                int packed = header.getInt(i * 4);
                // A zero slot is an unwritten slot, not a chunk we failed on.
                if (packed == 0) {
                    continue;
                }
                // Past here the header names a chunk, so anything that stops us from decoding it
                // means one chunk would be lost rather than absent.
                occupied++;
                int sector = (packed >> 8) & 0xFFFFFF;
                int sectorCount = packed & 0xFF;
                if (sector < 2 || sectorCount == 0) {
                    unreadable++;
                    continue;
                }
                long position = (long) sector * SECTOR_BYTES;
                if (position + 5 > channel.size()) {
                    unreadable++;
                    continue;
                }
                ByteBuffer prefix = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
                readFully(channel, prefix, position);
                prefix.flip();
                int length = prefix.getInt();
                int compressionId = prefix.get() & 0xFF;
                // The external flag has to be read before the length is judged. Vanilla writes an
                // external chunk's stub as length 1 with no payload (RegionFile.createExternalStub),
                // which is the same length an empty slot has — so a reader that looks at the length
                // first calls a real chunk "nothing here", and then neither reports it as a loss nor
                // stops --prune from deleting the .mca that is the only pointer to it. Vanilla's own
                // reader checks this flag first for the same reason (RegionFile:135).
                if ((compressionId & EXTERNAL_STREAM_FLAG) != 0) {
                    unreadable++;
                    continue;
                }
                if (length <= 1) {
                    // An allocated slot with no payload. Vanilla's writer never emits this: even an
                    // empty compound serializes to its TAG_End byte, so a zero-length stream is
                    // damage rather than an absence. Counting it as a loss is the safe reading —
                    // a false positive only skips the file, a false negative deletes a chunk.
                    unreadable++;
                    continue;
                }
                int streamLength = length - 1;
                if (position + 5 + streamLength > channel.size()) {
                    unreadable++;
                    continue;
                }
                byte[] raw = new byte[streamLength];
                readFully(channel, ByteBuffer.wrap(raw), position + 5);
                byte[] nbt = decompress(compressionId, raw);
                if (nbt != null) {
                    out.add(new Chunk(i, nbt));
                } else {
                    // External .mcc, unknown id, missing lz4, or a failed inflate.
                    unreadable++;
                }
            }
        }
        // Every slot the header named either decoded or was counted, so these three numbers have
        // to agree. They cannot disagree today — every path past the `packed == 0` test either adds
        // a chunk or bumps `unreadable` — and the check is here anyway, because the failure it
        // guards is exactly the one that started this: a slot skipped without being counted, which
        // reads as a smaller world rather than as an error. Counting `occupied` in this same pass
        // is also what retired the second file open the old accessor needed.
        if (occupied != out.size() + unreadable) {
            throw new IllegalStateException(
                "Read " + out.size() + " chunks and " + unreadable + " unreadable from " + path
                    + ", but the header names " + occupied + " slots"
            );
        }
        return new ReadResult(out, unreadable, occupied);
    }


    /** Writes chunks with zlib compression, matching what vanilla writes by default. */
    public static void write(Path path, List<Chunk> chunks) throws IOException {
        write(path, chunks, Deflater.DEFAULT_COMPRESSION);
    }

    public static void write(Path path, List<Chunk> chunks, int deflateLevel) throws IOException {
        // Deflate and validate everything BEFORE touching the target. A chunk whose stream
        // needs more than 255 sectors cannot be represented: the header packs the sector
        // count into eight bits, and a count of 256 wraps into the sector field — the slot
        // then names the wrong offset and the chunk is lost in place. Vanilla never writes
        // such a chunk inline (it externalizes to a .mcc), and this writer has no external
        // path, so it refuses instead of producing a file even its own reader cannot read
        // back. Validating up front also means a refusal never leaves a partial file.
        byte[][] streams = new byte[chunks.size()][];
        int[] sectorCounts = new int[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            byte[] compressed = deflate(chunk.nbt(), deflateLevel);
            int sectorCount = (compressed.length + 5 + SECTOR_BYTES - 1) / SECTOR_BYTES;
            if (sectorCount > 255) {
                throw new IOException("chunk at slot " + chunk.index() + " is " + compressed.length
                    + " bytes compressed and needs " + sectorCount + " sectors, but the Anvil"
                    + " header stores only 255. Vanilla would have written it to an external"
                    + " .mcc; refusing rather than writing a header entry the game could not"
                    + " read back");
            }
            streams[i] = compressed;
            sectorCounts[i] = sectorCount;
        }

        int[] offsets = new int[CHUNKS];
        int sector = 2;
        int now = (int) (System.currentTimeMillis() / 1000L);

        // Through a sibling temp and an atomic move, the same shape Converter.writeCso uses:
        // a genuine I/O error mid-write must leave whatever .mca was there before untouched,
        // not a half-written region in its place.
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
            )) {
                writeFully(channel, ByteBuffer.allocate(HEADER_BYTES), 0L);

                for (int i = 0; i < chunks.size(); i++) {
                    Chunk chunk = chunks.get(i);
                    ByteBuffer block = ByteBuffer.allocate(sectorCounts[i] * SECTOR_BYTES).order(ByteOrder.BIG_ENDIAN);
                    block.putInt(streams[i].length + 1);
                    block.put((byte) 2); // zlib
                    block.put(streams[i]);
                    block.flip();
                    writeFully(channel, block, (long) sector * SECTOR_BYTES);
                    offsets[chunk.index()] = (sector << 8) | sectorCounts[i];
                    sector += sectorCounts[i];
                }

                ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
                for (int offset : offsets) {
                    header.putInt(offset);
                }
                for (int i = 0; i < CHUNKS; i++) {
                    header.putInt(offsets[i] == 0 ? 0 : now);
                }
                header.flip();
                writeFully(channel, header, 0L);
            }
            FileMoves.settle(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
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

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            // FileChannel.write is allowed to short-write; a partial block would silently corrupt
            // the file, so keep writing until the buffer drains.
            pos += channel.write(buffer, pos);
        }
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
