package tqk114514.chunkstorageoptimizer.format;

import java.io.Closeable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import tqk114514.chunkstorageoptimizer.metrics.CsoStats;

import static tqk114514.chunkstorageoptimizer.format.CsoFormat.BUCKET_ENTRY_SIZE;
import static tqk114514.chunkstorageoptimizer.format.CsoFormat.CHUNK_ENTRY_SIZE;
import static tqk114514.chunkstorageoptimizer.format.CsoFormat.HEADER_SIZE;

/**
 * A single {@code r.X.Z.cso} region file.
 *
 * <p>Pure Java, no Minecraft dependencies — this is what makes the format unit-testable
 * outside a running game.
 *
 * <p>Thread safety: all public methods are synchronized. The vanilla {@code IOWorker} serializes
 * access per storage anyway, but {@code scanChunk} can arrive from a different pool.
 */
public final class CsoRegionFile implements Closeable {

    private static final System.Logger LOGGER = System.getLogger(CsoRegionFile.class.getName());
    private static final byte[] WAL_MAGIC = {'C', 'S', 'O', 'W', 'A', 'L', 0};
    /** Stored in the header when the filename carries no region coordinates. */
    private static final int UNKNOWN_COORD = Integer.MIN_VALUE;

    private final Path path;
    /**
     * Write-ahead log for the batch currently in flight. Without it a crash during a batch loses
     * that batch entirely; with it, the next open replays the changes.
     */
    private final Path walPath;
    private FileChannel channel;
    private final int grid;
    private final int chunksPerBucket;
    private final int bucketCount;
    private final int dataStart;
    private final Compressor compressor;
    private final boolean verifyCrc;
    private final long compactionMinWasted;
    private final double compactionWastedRatio;

    private final BucketEntry[] entries;
    /** Which table copy currently holds the newest entry per bucket. Writes use the other copy. */
    private final int[] lastTable;
    /** Monotonic counter persisted in every table entry; recovery keeps the highest valid one. */
    private int sequenceCounter;
    private final Map<Integer, byte[]> bucketCache;
    private final int maxCachedBuckets;

    private long fileEnd;
    private boolean compacting;

    private CsoRegionFile(
        Path path,
        FileChannel channel,
        int grid,
        Compressor compressor,
        boolean verifyCrc,
        int maxCachedBuckets,
        long compactionMinWasted,
        double compactionWastedRatio
    ) {
        this.path = path;
        this.walPath = path.resolveSibling(path.getFileName().toString() + ".wal");
        this.channel = channel;
        this.grid = grid;
        this.chunksPerBucket = CsoFormat.chunksPerBucket(grid);
        this.bucketCount = CsoFormat.bucketCount(grid);
        this.dataStart = CsoFormat.dataStart(this.bucketCount);
        this.compressor = compressor;
        this.verifyCrc = verifyCrc;
        this.compactionMinWasted = compactionMinWasted;
        this.compactionWastedRatio = compactionWastedRatio;
        this.entries = new BucketEntry[this.bucketCount];
        this.lastTable = new int[this.bucketCount];
        for (int i = 0; i < this.bucketCount; i++) {
            this.entries[i] = new BucketEntry();
        }
        this.maxCachedBuckets = maxCachedBuckets;
        this.bucketCache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
                return this.size() > CsoRegionFile.this.maxCachedBuckets;
            }
        };
    }

    /**
     * Opens (or creates) a region file. The grid recorded in an existing file always wins over
     * {@code preferredGrid} — changing the config must never reinterpret existing data.
     */
    public static CsoRegionFile open(
        Path path,
        int preferredGrid,
        int compressionId,
        int level,
        int maxCachedBuckets,
        boolean verifyCrc,
        long compactionMinWasted,
        double compactionWastedRatio
    ) throws IOException {
        CsoFormat.validateGrid(preferredGrid);
        Compressor compressor = Compressor.create(compressionId, level);
        boolean exists = Files.isRegularFile(path) && Files.size(path) > 0;
        // An existing file's grid always wins. Reinterpreting live data with a different bucket
        // layout would read the wrong bytes, so the config only applies to newly created files.
        int grid = exists ? peekGrid(path) : preferredGrid;
        FileChannel channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        );
        CsoRegionFile file = new CsoRegionFile(
            path, channel, grid, compressor, verifyCrc,
            maxCachedBuckets, compactionMinWasted, compactionWastedRatio
        );
        if (exists) {
            file.readMetadata();
        } else {
            file.writeNewFile(grid);
        }
        // A crash during a previous batch leaves its WAL behind — replay it before serving reads.
        file.replayWal();
        return file;
    }

    /** Reads just enough of an existing file to learn its bucket grid. */
    private static int peekGrid(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            byte[] header = new byte[HEADER_SIZE];
            readFullyStatic(ch, ByteBuffer.wrap(header), 0L);
            for (int i = 0; i < CsoFormat.MAGIC_LENGTH; i++) {
                if (header[i] != CsoFormat.MAGIC[i]) {
                    throw new CsoCorruptedException("Bad magic in " + path + " (not a CSO region file)");
                }
            }
            int version = CsoFormat.readShort(header, 8);
            if (version != CsoFormat.FORMAT_VERSION) {
                throw new CsoCorruptedException("Unsupported CSO format version " + version + " in " + path);
            }
            CRC32 crc = new CRC32();
            crc.update(header, 0, 20);
            if ((int) crc.getValue() != CsoFormat.readInt(header, 20)) {
                throw new CsoCorruptedException("Header CRC mismatch in " + path + " — file is damaged");
            }
            int grid = CsoFormat.readShort(header, 10);
            CsoFormat.validateGrid(grid);
            return grid;
        }
    }

    private static void readFullyStatic(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) {
                throw new CsoCorruptedException("Unexpected end of file in " + position);
            }
            pos += n;
        }
    }

    // ------------------------------------------------------------------ metadata

    private void writeNewFile(int grid) throws IOException {
        byte[] header = buildHeader(grid, 0);
        writeFully(ByteBuffer.wrap(header), 0L);
        byte[] table = new byte[this.bucketCount * BUCKET_ENTRY_SIZE * CsoFormat.TABLE_COUNT];
        writeFully(ByteBuffer.wrap(table), (long) HEADER_SIZE);
        this.fileEnd = this.dataStart;
        this.channel.truncate(this.fileEnd);
    }

    private void readMetadata() throws IOException {
        long size = this.channel.size();
        if (size < HEADER_SIZE) {
            throw new CsoCorruptedException("File too small to be a CSO region: " + size + " bytes at " + this.path);
        }
        byte[] header = new byte[HEADER_SIZE];
        readFully(ByteBuffer.wrap(header), 0L);

        for (int i = 0; i < CsoFormat.MAGIC_LENGTH; i++) {
            if (header[i] != CsoFormat.MAGIC[i]) {
                throw new CsoCorruptedException("Bad magic in " + this.path + " (not a CSO region file)");
            }
        }
        int version = CsoFormat.readShort(header, 8);
        if (version != CsoFormat.FORMAT_VERSION) {
            throw new CsoCorruptedException("Unsupported CSO format version " + version + " in " + this.path);
        }
        int expectedCrc = CsoFormat.readInt(header, 20);
        CRC32 crc = new CRC32();
        crc.update(header, 0, 20);
        if ((int) crc.getValue() != expectedCrc) {
            throw new CsoCorruptedException("Header CRC mismatch in " + this.path + " — file is damaged");
        }

        int fileGrid = CsoFormat.readShort(header, 10);
        CsoFormat.validateGrid(fileGrid);
        if (fileGrid != this.grid) {
            // Unreachable: open() reads the grid before constructing this object. Hard invariant.
            throw new CsoCorruptedException(
                "Grid mismatch in " + this.path + ": file=" + fileGrid + " expected=" + this.grid
            );
        }
        verifyRegionCoords(header);

        byte[] tables = new byte[this.bucketCount * BUCKET_ENTRY_SIZE * CsoFormat.TABLE_COUNT];
        readFully(ByteBuffer.wrap(tables), (long) HEADER_SIZE);
        long maxEnd = this.dataStart;

        for (int i = 0; i < this.bucketCount; i++) {
            // Recovery is per bucket: take the newest copy that still validates. A crash can only
            // damage the copy being written; the other one still points at intact data.
            BucketEntry newest = null;
            int newestTable = 0;
            for (int table = 0; table < CsoFormat.TABLE_COUNT; table++) {
                BucketEntry candidate = decodeEntry(tables, table * this.bucketCount * BUCKET_ENTRY_SIZE
                    + i * BUCKET_ENTRY_SIZE);
                if (candidate != null && (newest == null || candidate.sequence > newest.sequence)) {
                    newest = candidate;
                    newestTable = table;
                }
            }
            if (newest == null) {
                continue;
            }
            BucketEntry e = this.entries[i];
            e.offset = newest.offset;
            e.compressedLength = newest.compressedLength;
            e.rawLength = newest.rawLength;
            e.crc32 = newest.crc32;
            e.chunkCount = newest.chunkCount;
            e.sequence = newest.sequence;
            this.lastTable[i] = newestTable;
            this.sequenceCounter = Math.max(this.sequenceCounter, newest.sequence);
            if (e.offset != 0) {
                if (e.offset < this.dataStart || e.offset + e.compressedLength > size) {
                    throw new CsoCorruptedException(
                        "Bucket " + i + " in " + this.path + " points outside the file (offset=" + e.offset
                            + ", len=" + e.compressedLength + ", fileSize=" + size + ")"
                    );
                }
                maxEnd = Math.max(maxEnd, e.offset + e.compressedLength);
            }
        }
        this.fileEnd = size;
    }

    private byte[] buildHeader(int grid, int flags) {
        byte[] h = new byte[HEADER_SIZE];
        System.arraycopy(CsoFormat.MAGIC, 0, h, 0, CsoFormat.MAGIC_LENGTH);
        CsoFormat.writeShort(h, 8, CsoFormat.FORMAT_VERSION);
        CsoFormat.writeShort(h, 10, grid);
        h[12] = (byte) this.compressor.id();
        h[13] = (byte) (this.compressor instanceof ZstdCompressor z ? z.level() : 0);
        CsoFormat.writeShort(h, 14, flags);
        CsoFormat.writeInt(h, 16, this.bucketCount);
        CRC32 crc = new CRC32();
        crc.update(h, 0, 20);
        CsoFormat.writeInt(h, 20, (int) crc.getValue());
        CsoFormat.writeInt(h, 24, regionXFromPath());
        CsoFormat.writeInt(h, 28, regionZFromPath());
        CsoFormat.writeInt(h, 32, this.chunksPerBucket);
        return h;
    }

    private int regionXFromPath() {
        return parseCoord(0);
    }

    private int regionZFromPath() {
        return parseCoord(1);
    }

    private int parseCoord(int index) {
        String name = this.path.getFileName().toString();
        // r.<x>.<z>.cso
        String[] parts = name.split("\\.");
        if (parts.length < 4) {
            return UNKNOWN_COORD;
        }
        try {
            return Integer.parseInt(parts[1 + index]);
        } catch (NumberFormatException e) {
            return UNKNOWN_COORD;
        }
    }

    /**
     * Rejects a file whose header names a different region than the filename does: a renamed or
     * misplaced region file would otherwise answer for another region's chunks, and the game cannot
     * tell that apart from a damaged save.
     *
     * <p>Skipped when the filename carries no coordinates — the tooling opens scratch files like
     * {@code plain.cso}, whose header stores the same marker.
     */
    private void verifyRegionCoords(byte[] header) throws CsoCorruptedException {
        int expectedX = regionXFromPath();
        int expectedZ = regionZFromPath();
        if (expectedX == UNKNOWN_COORD || expectedZ == UNKNOWN_COORD) {
            return;
        }
        int recordedX = CsoFormat.readInt(header, 24);
        int recordedZ = CsoFormat.readInt(header, 28);
        if (recordedX != expectedX || recordedZ != expectedZ) {
            throw new CsoCorruptedException(
                "Coordinate mismatch in " + this.path + ": header holds r." + recordedX + "." + recordedZ
                    + " but the file is named " + this.path.getFileName()
            );
        }
    }

    /**
     * Writes to the table copy that is NOT currently newest for this bucket, then flips
     * {@link #lastTable}. The previous copy stays valid until this write lands, so a crash
     * mid-write always leaves one intact copy behind.
     */
    private void writeBucketEntry(int bucket) throws IOException {
        int table = 1 - this.lastTable[bucket];
        byte[] entry = encodeEntry(this.entries[bucket]);
        writeFully(ByteBuffer.wrap(entry), (long) CsoFormat.tableOffset(table, bucket, this.bucketCount));
        this.lastTable[bucket] = table;
    }

    private static byte[] encodeEntry(BucketEntry e) {
        byte[] out = new byte[BUCKET_ENTRY_SIZE];
        writeLong(out, CsoFormat.ENTRY_OFFSET, e.offset);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_COMP_LEN, e.compressedLength);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_RAW_LEN, e.rawLength);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_CRC32, e.crc32);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_CHUNKS, e.chunkCount);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_SEQUENCE, e.sequence);
        CRC32 crc = new CRC32();
        crc.update(out, 4, BUCKET_ENTRY_SIZE - 4);
        CsoFormat.writeInt(out, CsoFormat.ENTRY_CRC, (int) crc.getValue());
        return out;
    }

    /** @return the entry, or null when its own CRC fails — torn write, or never written. */
    private static BucketEntry decodeEntry(byte[] buffer, int base) {
        int expected = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_CRC);
        CRC32 crc = new CRC32();
        crc.update(buffer, base + 4, BUCKET_ENTRY_SIZE - 4);
        if ((int) crc.getValue() != expected) {
            return null;
        }
        BucketEntry e = new BucketEntry();
        e.offset = readLong(buffer, base + CsoFormat.ENTRY_OFFSET);
        e.compressedLength = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_COMP_LEN);
        e.rawLength = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_RAW_LEN);
        e.crc32 = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_CRC32);
        e.chunkCount = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_CHUNKS);
        e.sequence = CsoFormat.readInt(buffer, base + CsoFormat.ENTRY_SEQUENCE);
        return e;
    }

    // ------------------------------------------------------------------ payload

    private byte[] getPayload(int bucket) throws IOException {
        byte[] cached = this.bucketCache.get(bucket);
        if (cached != null) {
            CsoStats.cacheHit();
            return cached;
        }
        BucketEntry e = this.entries[bucket];
        if (e.offset == 0 || e.compressedLength == 0 || e.chunkCount == 0) {
            return null;
        }
        byte[] compressed = new byte[e.compressedLength];
        readFully(ByteBuffer.wrap(compressed), e.offset);
        long startedAt = System.nanoTime();
        byte[] raw = this.compressor.decompress(compressed, e.rawLength);
        long elapsedNanos = System.nanoTime() - startedAt;
        if (this.verifyCrc && (int) crc32(raw) != e.crc32) {
            throw new CsoCorruptedException(
                "CRC mismatch for bucket " + bucket + " in " + this.path + " — data is damaged"
            );
        }
        CsoStats.bucketDecompressed(elapsedNanos, e.compressedLength, raw.length);
        this.bucketCache.put(bucket, raw);
        return raw;
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /** Returns the raw NBT bytes for a chunk, or {@code null} when absent. */
    public synchronized byte[] readChunk(int localX, int localZ) throws IOException {
        CsoStats.chunkRead();
        int bucket = CsoFormat.bucketIndex(localX, localZ, this.grid);
        int idx = CsoFormat.chunkIndexInBucket(localX, localZ, this.grid);
        byte[] payload = getPayload(bucket);
        if (payload == null) {
            return null;
        }
        int base = idx * CHUNK_ENTRY_SIZE;
        int length = CsoFormat.readInt(payload, base + 4);
        if (length <= 0) {
            return null;
        }
        int offset = CsoFormat.readInt(payload, base);
        int indexBytes = this.chunksPerBucket * CHUNK_ENTRY_SIZE;
        if (offset < indexBytes || offset + length > payload.length) {
            throw new CsoCorruptedException(
                "Chunk entry out of bounds in " + this.path + ": offset=" + offset + " length=" + length
                    + " payload=" + payload.length
            );
        }
        return Arrays.copyOfRange(payload, offset, offset + length);
    }

    public synchronized boolean hasChunk(int localX, int localZ) throws IOException {
        int bucket = CsoFormat.bucketIndex(localX, localZ, this.grid);
        int idx = CsoFormat.chunkIndexInBucket(localX, localZ, this.grid);
        byte[] payload = getPayload(bucket);
        if (payload == null) {
            return false;
        }
        return CsoFormat.readInt(payload, idx * CHUNK_ENTRY_SIZE + 4) > 0;
    }

    public synchronized void writeChunk(int localX, int localZ, byte[] data) throws IOException {
        modifyChunk(localX, localZ, data);
    }

    public synchronized void deleteChunk(int localX, int localZ) throws IOException {
        modifyChunk(localX, localZ, null);
    }

    private void modifyChunk(int localX, int localZ, byte[] data) throws IOException {
        int bucket = CsoFormat.bucketIndex(localX, localZ, this.grid);
        int idx = CsoFormat.chunkIndexInBucket(localX, localZ, this.grid);
        writeChunks(bucket, Collections.singletonMap(idx, data));
    }

    /**
     * Applies several chunk changes to one bucket in a single decompress/recompress cycle.
     *
     * <p>This is the whole point of bucketing: N chunks landing in the same bucket cost one
     * compression and one write, not N. Slot values are the new chunk bytes; {@code null} deletes.
     */
    public synchronized void writeChunks(int bucket, Map<Integer, byte[]> changes) throws IOException {
        if (changes.isEmpty()) {
            return;
        }
        for (byte[] data : changes.values()) {
            if (data == null) {
                CsoStats.chunkDeleted();
            } else {
                CsoStats.chunkWritten();
            }
        }
        storeBucket(bucket, rebuildPayload(getPayload(bucket), changes));
    }

    /**
     * The bucket grid this file actually uses. Callers must group writes using this value, not
     * the configured one — an existing file keeps its original grid.
     */
    public int grid() {
        return this.grid;
    }

    // ------------------------------------------------------------------ write-ahead log

    /**
     * Records a whole batch of bucket changes before any of them is applied, and forces it to disk.
     *
     * <p>The granularity is the batch, not the single bucket, on purpose: one forced write per
     * batch keeps the write path fast. A bucket-at-a-time WAL would need a forced write per chunk
     * save, which would throw away most of the speed this format exists to provide.
     */
    public synchronized void writeWal(Map<Integer, Map<Integer, byte[]>> changes) throws IOException {
        if (changes.isEmpty()) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(WAL_MAGIC);
        out.writeShort(CsoFormat.FORMAT_VERSION);
        out.writeInt(changes.size());
        for (Map.Entry<Integer, Map<Integer, byte[]>> bucketEntry : changes.entrySet()) {
            out.writeInt(bucketEntry.getKey());
            out.writeInt(bucketEntry.getValue().size());
            for (Map.Entry<Integer, byte[]> slotEntry : bucketEntry.getValue().entrySet()) {
                out.writeInt(slotEntry.getKey());
                byte[] data = slotEntry.getValue();
                out.writeInt(data == null ? -1 : data.length);
                if (data != null) {
                    out.write(data);
                }
            }
        }
        out.flush();

        byte[] payload = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);
        try (FileChannel wal = FileChannel.open(
            this.walPath,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ByteBuffer buffer = ByteBuffer.allocate(payload.length + 4);
            buffer.put(payload);
            // Written with CsoFormat.writeInt (little-endian) rather than ByteBuffer.putInt
            // (big-endian) — the reader uses readInt, and a mismatch here silently fails the
            // checksum so the log is discarded and the recovery never happens.
            byte[] checksum = new byte[4];
            CsoFormat.writeInt(checksum, 0, (int) crc.getValue());
            buffer.put(checksum);
            buffer.flip();
            while (buffer.hasRemaining()) {
                wal.write(buffer);
            }
            wal.force(true);
        }
    }

    /** Drops the WAL. Only safe once the batch it describes is already durable. */
    public synchronized void clearWal() throws IOException {
        Files.deleteIfExists(this.walPath);
    }

    /**
     * Replays a WAL left behind by a crash. Idempotent — applying the same changes twice produces
     * the same bytes — so dying during replay is harmless; it just runs again next time.
     */
    private synchronized void replayWal() throws IOException {
        if (!Files.isRegularFile(this.walPath)) {
            return;
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(this.walPath);
        } catch (IOException e) {
            deleteWalQuietly();
            return;
        }
        if (raw.length < WAL_MAGIC.length + 4) {
            deleteWalQuietly();
            return;
        }
        CRC32 crc = new CRC32();
        crc.update(raw, 0, raw.length - 4);
        if ((int) crc.getValue() != CsoFormat.readInt(raw, raw.length - 4)) {
            // Torn WAL: never fully written, so there is nothing to replay. The bucket tables
            // still describe the last consistent state, so falling back is safe.
            deleteWalQuietly();
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte[] magic = new byte[WAL_MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, WAL_MAGIC)) {
                deleteWalQuietly();
                return;
            }
            in.readShort(); // format version
            int buckets = in.readInt();
            for (int i = 0; i < buckets; i++) {
                int bucket = in.readInt();
                int entries = in.readInt();
                Map<Integer, byte[]> changes = new HashMap<>();
                for (int j = 0; j < entries; j++) {
                    int slot = in.readInt();
                    int length = in.readInt();
                    byte[] data = length < 0 ? null : new byte[length];
                    if (data != null) {
                        in.readFully(data);
                    }
                    changes.put(slot, data);
                }
                writeChunks(bucket, changes);
            }
        } catch (IOException e) {
            throw new CsoCorruptedException("Failed to replay WAL for " + this.path, e);
        }
        flush();
        deleteWalQuietly();
    }

    private void deleteWalQuietly() {
        try {
            Files.deleteIfExists(this.walPath);
        } catch (IOException ignored) {
            // A leftover WAL is harmless: it gets replayed or ignored on the next open.
        }
    }

    /**
     * Rebuilds a bucket payload applying every entry in {@code changes} at once.
     * Key = slot index, value = new chunk bytes ({@code null} removes it).
     *
     * <p>Output is always compact — bucket payloads never accumulate internal holes, because every
     * modification rewrites the whole bucket.
     */
    private byte[] rebuildPayload(byte[] old, Map<Integer, byte[]> changes) {
        int k = this.chunksPerBucket;
        int[] offsets = new int[k];
        int[] lengths = new int[k];
        int[] stamps = new int[k];
        int total = 0;

        if (old != null) {
            for (int i = 0; i < k; i++) {
                int base = i * CHUNK_ENTRY_SIZE;
                offsets[i] = CsoFormat.readInt(old, base);
                lengths[i] = CsoFormat.readInt(old, base + 4);
                stamps[i] = CsoFormat.readInt(old, base + 8);
                if (!changes.containsKey(i) && lengths[i] > 0) {
                    total += lengths[i];
                }
            }
        }
        for (byte[] data : changes.values()) {
            if (data != null) {
                total += data.length;
            }
        }

        byte[] out = new byte[k * CHUNK_ENTRY_SIZE + total];
        int p = k * CHUNK_ENTRY_SIZE;
        int now = (int) (System.currentTimeMillis() / 1000L);

        for (int i = 0; i < k; i++) {
            int base = i * CHUNK_ENTRY_SIZE;
            if (changes.containsKey(i)) {
                byte[] data = changes.get(i);
                if (data != null) {
                    CsoFormat.writeInt(out, base, p);
                    CsoFormat.writeInt(out, base + 4, data.length);
                    CsoFormat.writeInt(out, base + 8, now);
                    System.arraycopy(data, 0, out, p, data.length);
                    p += data.length;
                }
            } else if (lengths[i] > 0) {
                CsoFormat.writeInt(out, base, p);
                CsoFormat.writeInt(out, base + 4, lengths[i]);
                CsoFormat.writeInt(out, base + 8, stamps[i]);
                System.arraycopy(old, offsets[i], out, p, lengths[i]);
                p += lengths[i];
            }
        }

        return out;
    }

    private void storeBucket(int bucket, byte[] payload) throws IOException {
        long startedAt = System.nanoTime();
        byte[] compressed = this.compressor.compress(payload);
        long elapsedNanos = System.nanoTime() - startedAt;
        long offset = allocate(compressed.length);
        writeFully(ByteBuffer.wrap(compressed), offset);
        CsoStats.bucketCompressed(elapsedNanos, payload.length, compressed.length);
        CsoStats.ioWrite(compressed.length);

        BucketEntry e = this.entries[bucket];
        e.sequence = ++this.sequenceCounter;
        e.offset = offset;
        e.compressedLength = compressed.length;
        e.rawLength = payload.length;
        e.crc32 = (int) crc32(payload);
        e.chunkCount = countChunks(payload);
        writeBucketEntry(bucket);
        this.fileEnd = Math.max(this.fileEnd, offset + compressed.length);
        this.bucketCache.put(bucket, payload);

        maybeCompact();
    }

    private int countChunks(byte[] payload) {
        int n = 0;
        for (int i = 0; i < this.chunksPerBucket; i++) {
            if (CsoFormat.readInt(payload, i * CHUNK_ENTRY_SIZE + 4) > 0) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ space

    /**
     * Best-fit allocation over the free space implied by the bucket table.
     *
     * <p>The bucket being rewritten keeps its old extent marked as IN USE. Reusing that space would
     * be faster to reclaim, but it destroys the only intact copy of the bucket if the process dies
     * between writing the new block and updating the table. Reclaiming happens in {@link #compact()}
     * instead, which is the whole point of having a compaction pass.
     */
    private long allocate(int size) {
        List<long[]> used = new ArrayList<>(this.bucketCount + 1);
        used.add(new long[] {0L, (long) this.dataStart});
        for (BucketEntry e : this.entries) {
            if (e.offset != 0 && e.compressedLength > 0) {
                used.add(new long[] {e.offset, e.offset + e.compressedLength});
            }
        }
        used.sort(Comparator.comparingLong(a -> a[0]));

        long cursor = 0L;
        long best = -1L;
        long bestSize = Long.MAX_VALUE;

        for (long[] range : used) {
            if (range[0] > cursor) {
                long free = range[0] - cursor;
                if (free >= size && free < bestSize) {
                    best = cursor;
                    bestSize = free;
                }
            }
            if (range[1] > cursor) {
                cursor = range[1];
            }
        }
        if (this.fileEnd > cursor) {
            long free = this.fileEnd - cursor;
            if (free >= size && free < bestSize) {
                best = cursor;
            }
        }
        return best >= 0 ? best : this.fileEnd;
    }

    /** Bytes occupied by buckets that are no longer reachable. */
    public synchronized long wastedBytes() {
        long used = this.dataStart;
        for (BucketEntry e : this.entries) {
            if (e.offset != 0 && e.compressedLength > 0) {
                used += e.compressedLength;
            }
        }
        return Math.max(0L, this.fileEnd - used);
    }

    public synchronized long fileSize() {
        return this.fileEnd;
    }

    private void maybeCompact() throws IOException {
        long wasted = wastedBytes();
        long used = this.fileEnd - wasted;
        if (wasted >= this.compactionMinWasted && wasted >= used * this.compactionWastedRatio) {
            compact();
        }
    }

    /**
     * Rewrites the file with reachable buckets packed from the front. Blocks are copied as
     * compressed bytes — no recompression, so compaction stays cheap in CPU.
     */
    public synchronized void compact() throws IOException {
        if (this.compacting) {
            return;
        }
        this.compacting = true;
        long startedAt = System.nanoTime();
        try {
            long[] newOffsets = new long[this.bucketCount];
            Path tmp = this.path.resolveSibling(this.path.getFileName() + ".tmp");
            long newEnd = this.dataStart;

            try (FileChannel out = FileChannel.open(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )) {
                writeChannel(out, ByteBuffer.wrap(buildHeader(this.grid, 0)), 0L);
                writeChannel(
                    out,
                    ByteBuffer.allocate(this.bucketCount * BUCKET_ENTRY_SIZE * CsoFormat.TABLE_COUNT),
                    (long) HEADER_SIZE
                );

                for (int b = 0; b < this.bucketCount; b++) {
                    BucketEntry e = this.entries[b];
                    if (e.offset == 0 || e.compressedLength == 0) {
                        continue;
                    }
                    byte[] block = new byte[e.compressedLength];
                    readFully(ByteBuffer.wrap(block), e.offset);
                    writeChannel(out, ByteBuffer.wrap(block), newEnd);
                    newOffsets[b] = newEnd;
                    newEnd += e.compressedLength;
                }

                // After compaction there is no older copy left to fall back to, so both table
                // copies are written with identical, newest content.
                for (int b = 0; b < this.bucketCount; b++) {
                    if (newOffsets[b] == 0) {
                        continue;
                    }
                    BucketEntry e = this.entries[b];
                    long previousOffset = e.offset;
                    e.offset = newOffsets[b];
                    byte[] entry = encodeEntry(e);
                    e.offset = previousOffset; // applied only once compaction succeeds
                    for (int table = 0; table < CsoFormat.TABLE_COUNT; table++) {
                        writeChannel(
                            out, ByteBuffer.wrap(entry),
                            (long) CsoFormat.tableOffset(table, b, this.bucketCount)
                        );
                    }
                    this.lastTable[b] = 0;
                }
                out.force(true);
            }

            this.channel.close();
            Files.move(tmp, this.path, StandardCopyOption.REPLACE_EXISTING);
            this.channel = FileChannel.open(
                this.path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE
            );
            for (int b = 0; b < this.bucketCount; b++) {
                if (newOffsets[b] != 0) {
                    this.entries[b].offset = newOffsets[b];
                }
            }
            this.fileEnd = newEnd;
            this.channel.truncate(newEnd);
            CsoStats.compaction(System.nanoTime() - startedAt);
            LOGGER.log(
                System.Logger.Level.DEBUG,
                "Compacted " + this.path.getFileName() + ": " + newEnd + " bytes"
            );
        } finally {
            this.compacting = false;
        }
    }

    // ------------------------------------------------------------------ io helpers

    private void readFully(ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            int n = this.channel.read(buf, pos);
            if (n < 0) {
                throw new CsoCorruptedException("Unexpected end of file in " + this.path);
            }
            pos += n;
        }
    }

    private void writeFully(ByteBuffer buf, long position) throws IOException {
        writeChannel(this.channel, buf, position);
    }

    private static void writeChannel(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void flush() throws IOException {
        this.channel.force(true);
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            flush();
        } finally {
            this.channel.close();
        }
    }

    private static long readLong(byte[] b, int off) {
        return (CsoFormat.readInt(b, off) & 0xFFFFFFFFL) | ((long) CsoFormat.readInt(b, off + 4) << 32);
    }

    private static void writeLong(byte[] b, int off, long v) {
        CsoFormat.writeInt(b, off, (int) v);
        CsoFormat.writeInt(b, off + 4, (int) (v >>> 32));
    }

    static final class BucketEntry {
        long offset;
        int compressedLength;
        int rawLength;
        int crc32;
        int chunkCount;
        int sequence;
    }
}
