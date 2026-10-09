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
    /** Stored in the header when the filename carries no region coordinates. */
    private static final int UNKNOWN_COORD = Integer.MIN_VALUE;

    private final Path path;
    /**
     * Write-ahead log for the batch currently in flight. Without it a crash during a batch loses
     * that batch entirely; with it, the next open replays the changes.
     */
    private final CsoWal wal;
    private FileChannel channel;
    private final int grid;
    private final int chunksPerBucket;
    private final int bucketCount;
    private final int dataStart;
    private final Compressor compressor;
    private final boolean verifyCrc;
    private final long compactionMinWasted;
    private final double compactionWastedRatio;

    private final TableEntry[] entries;
    /** Which table copy currently holds the newest entry per bucket. Writes use the other copy. */
    private final int[] lastTable;
    /** Monotonic counter persisted in every table entry; recovery keeps the highest valid one. */
    private int sequenceCounter;
    /** Decompressed-bucket payloads, LRU under a count and byte budget; see {@link BucketCache}. */
    private final BucketCache bucketCache;

    private long fileEnd;
    private boolean compacting;
    /** Set by anything that puts bytes in this file; cleared by {@link #flush()}. */
    private boolean dirty;
    /** Reused compression destination — see {@link Compressor#compress(byte[], byte[])}. */
    private byte[] compressScratch = new byte[0];
    /** Best-fit allocation and waste accounting over the live table; see {@link BlockAllocator}. */
    private final BlockAllocator allocator;

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
        this.channel = channel;
        this.grid = grid;
        this.chunksPerBucket = CsoFormat.chunksPerBucket(grid);
        this.bucketCount = CsoFormat.bucketCount(grid);
        this.wal = new CsoWal(path, this.bucketCount, this.chunksPerBucket);
        this.dataStart = CsoFormat.dataStart(this.bucketCount);
        this.compressor = compressor;
        this.verifyCrc = verifyCrc;
        this.compactionMinWasted = compactionMinWasted;
        this.compactionWastedRatio = compactionWastedRatio;
        this.entries = new TableEntry[this.bucketCount];
        this.lastTable = new int[this.bucketCount];
        for (int i = 0; i < this.bucketCount; i++) {
            this.entries[i] = new TableEntry();
        }
        this.allocator = new BlockAllocator(this.dataStart, this.bucketCount);
        this.bucketCache = new BucketCache(maxCachedBuckets);
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
        boolean exists = Files.isRegularFile(path) && Files.size(path) > 0;
        FileChannel channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        );
        try {
            byte[] header = null;
            // An existing file's grid always wins. Reinterpreting live data with a different bucket
            // layout would read the wrong bytes, so the config only applies to newly created files.
            int grid = preferredGrid;
            if (exists) {
                // One open and one header read: the grid must come off disk before this object can
                // be built, and those same 128 bytes also carry everything readMetadata validates.
                header = new byte[HEADER_SIZE];
                readFullyStatic(channel, ByteBuffer.wrap(header), 0L);
                grid = parseGrid(path, header);
            }
            // The codec wins for the same reason as the grid: everything the file holds was
            // written with the codec its header records. The configured codec picks what new
            // files are created with; it cannot reinterpret data already on disk — a zstd bucket
            // read as raw bytes, or the reverse, just surfaces as corruption, and the game would
            // quietly regenerate the chunk. The configured level still applies to new writes into
            // an existing file, since a zstd stream decodes the same at every level.
            Compressor compressor = exists
                ? compressorForFile(path, header, level)
                : Compressor.create(compressionId, level);
            CsoRegionFile file = new CsoRegionFile(
                path, channel, grid, compressor, verifyCrc,
                maxCachedBuckets, compactionMinWasted, compactionWastedRatio
            );
            if (exists) {
                file.readMetadata(header);
            } else {
                file.writeNewFile(grid);
            }
            // A crash during a previous batch leaves its WAL behind — replay it before serving reads.
            file.replayWal();
            return file;
        } catch (IOException | RuntimeException | Error failure) {
            // Everything above happens after the channel exists, so a rejected open must close it
            // here — otherwise every corrupt file costs one descriptor (and a Windows file lock).
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * The codec an existing file declares in its header. An id this build does not know means the
     * file came from a newer build — failing as corruption beats decoding the buckets as garbage.
     */
    private static Compressor compressorForFile(Path path, byte[] header, int level)
        throws CsoCorruptedException {
        int id = header[12] & 0xFF;
        if (id != CsoFormat.COMPRESSION_NONE && id != CsoFormat.COMPRESSION_ZSTD) {
            throw new CsoCorruptedException(
                "Unsupported compression id " + id + " in " + path + " — written by a newer CSO build?");
        }
        return Compressor.create(id, level);
    }

    /** Validates the fixed part of a header and returns the bucket grid it declares. */
    private static int parseGrid(Path path, byte[] header) throws CsoCorruptedException {
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

    private static void readFullyStatic(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) {
                throw new CsoCorruptedException("Unexpected end of file at " + pos);
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
        this.dirty = true;
    }

    /** @param header the 128 header bytes {@code open()} already read and validated */
    private void readMetadata(byte[] header) throws IOException {
        long size = this.channel.size();
        if (size < HEADER_SIZE) {
            throw new CsoCorruptedException("File too small to be a CSO region: " + size + " bytes at " + this.path);
        }
        // Magic, format version, header CRC and the grid's legality were all checked by open()
        // before this object existed; re-checking them here meant a second read of the same bytes.
        int fileGrid = CsoFormat.readShort(header, 10);
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
            TableEntry newest = null;
            int newestTable = 0;
            // Writes alternate between the two copies and always start at table 1, so a blank
            // table 0 says no write to this bucket ever finished — the two copies are NOT only ever
            // blank together, which an earlier version of this assumed.
            int tableStride = this.bucketCount * BUCKET_ENTRY_SIZE;
            boolean blank0 = TableEntry.isBlank(tables, i * BUCKET_ENTRY_SIZE);
            boolean blank1 = TableEntry.isBlank(tables, tableStride + i * BUCKET_ENTRY_SIZE);
            for (int table = 0; table < CsoFormat.TABLE_COUNT; table++) {
                int base = table * tableStride + i * BUCKET_ENTRY_SIZE;
                TableEntry candidate = TableEntry.decode(tables, base);
                if (candidate != null && (newest == null || candidate.sequence > newest.sequence)) {
                    newest = candidate;
                    newestTable = table;
                }
            }
            if (newest == null) {
                // No copy validated, which has two quite different reasons.
                if (blank0 && blank1) {
                    // Nothing was ever written here, so there is nothing to lose.
                    continue;
                }
                // Otherwise a copy holds bytes that do not validate. A blank table 0 narrows that
                // to the bucket's first write having been interrupted — and a write-ahead log left
                // on disk confirms it and holds what that batch meant to write, so the entry gets
                // rebuilt from the log right after this. With no log the damage is not explained by
                // an interrupted batch, so it is real loss: reading it as "never written" would
                // drop the bucket's chunks without a word, the one failure mode this format must
                // never have.
                if (blank0 && this.wal.exists()) {
                    continue;
                }
                throw new CsoCorruptedException(
                    "Bucket " + i + " in " + this.path + " has no readable table entry in either copy"
                );
            }
            TableEntry e = this.entries[i];
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
     *
     * <p>Takes the entry to record as a parameter, so a writer may durable-commit a state it
     * has not applied in memory yet — see {@link #storeBucket} for why that ordering matters.
     */
    private void writeTableEntry(int bucket, TableEntry entry) throws IOException {
        int table = 1 - this.lastTable[bucket];
        byte[] bytes = TableEntry.encode(entry);
        writeFully(ByteBuffer.wrap(bytes), (long) CsoFormat.tableOffset(table, bucket, this.bucketCount));
        this.lastTable[bucket] = table;
    }

    // ------------------------------------------------------------------ payload

    private byte[] getPayload(int bucket) throws IOException {
        byte[] cached = this.bucketCache.get(bucket);
        if (cached != null) {
            CsoStats.cacheHit();
            return cached;
        }
        TableEntry e = this.entries[bucket];
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

    /**
     * How many decompressed payloads this file currently holds. Diagnostic, in the spirit of
     * {@link #wastedBytes()}: tests observe cache eviction through it.
     */
    public synchronized int cachedBucketCount() {
        return this.bucketCache.size();
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * The chunk's bytes as an immutable view into the cached bucket payload — no copy.
     *
     * @param payload the bucket's decompressed payload, shared with the cache
     * @param offset  where the chunk starts inside {@code payload}
     * @param length  the chunk's byte count
     */
    public record ChunkSlice(byte[] payload, int offset, int length) {
    }

    /**
     * The chunk's bytes as a zero-copy view, or {@code null} when absent.
     *
     * <p>The array is the bucket's decompressed payload, shared with the cache; it is never
     * mutated in place (a bucket rewrite installs a new array), so the view stays valid for
     * however long the caller holds it — it simply shows the version read. That is what makes
     * it safe to parse straight out of: the parse reads the bytes where they live, and the
     * copy the hot read path used to make per chunk goes away. Callers that need an owned
     * copy, like the offline converter, keep {@link #readChunk}.
     */
    public synchronized ChunkSlice readChunkSlice(int localX, int localZ) throws IOException {
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
        // The long cast matters: offset + length can wrap an int and slip past this check,
        // turning a corrupt index into an unchecked exception at the parse site instead of
        // the typed failure FORMAT.md promises. rebuildPayload casts for the same reason on
        // the write path.
        if (offset < indexBytes || (long) offset + length > payload.length) {
            throw new CsoCorruptedException(
                "Chunk entry out of bounds in " + this.path + ": offset=" + offset + " length=" + length
                    + " payload=" + payload.length
            );
        }
        return new ChunkSlice(payload, offset, length);
    }

    /** Returns the raw NBT bytes for a chunk, or {@code null} when absent. */
    public synchronized byte[] readChunk(int localX, int localZ) throws IOException {
        ChunkSlice slice = readChunkSlice(localX, localZ);
        return slice == null
            ? null
            : Arrays.copyOfRange(slice.payload(), slice.offset(), slice.offset() + slice.length());
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

    /**
     * Whether this file has ever been written at the given position's bucket.
     *
     * <p>A bucket with no entry has never been touched, so nothing is known about its slots. A
     * bucket that does have an entry is authoritative for every slot in it: a slot whose length is
     * zero was explicitly deleted, not merely absent. Callers that fall back to another format on a
     * miss need this to tell "never migrated" apart from "migrated and then deleted" — reading the
     * former from the fallback is the point, reading the latter resurrects deleted chunks.
     *
     * <p>Deliberately checks the entry, not {@link #getPayload}: a bucket whose every chunk was
     * deleted still has an entry, but its payload is not worth holding in memory, so getPayload
     * reports it as absent. That is exactly the case this method exists to detect.
     */
    public synchronized boolean hasBucket(int localX, int localZ) {
        return hasBucketIndex(CsoFormat.bucketIndex(localX, localZ, this.grid));
    }

    /**
     * The same question asked by bucket ordinal rather than by position.
     *
     * <p>The storage layer groups a batch by ordinal, so this is the form it needs; asking that way
     * also keeps the caller from having to invent a coordinate to stand for the whole bucket.
     */
    public synchronized boolean hasBucketIndex(int bucket) {
        TableEntry entry = this.entries[bucket];
        return entry.offset != 0 && entry.compressedLength > 0;
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
    // All of the write-ahead log lives in CsoWal now; these are the thin pass-throughs the
    // storage layer and the tests call.

    public synchronized void writeWal(Map<Integer, Map<Integer, byte[]>> changes) throws IOException {
        this.wal.write(changes);
    }

    /** Drops the WAL. Only safe once the batch it describes is already durable. */
    public synchronized void clearWal() throws IOException {
        this.wal.clear();
    }

    /**
     * Replays a WAL left behind by a crash. Idempotent — applying the same changes twice produces
     * the same bytes — so dying during replay is harmless; it just runs again next time. The
     * flush before the clear is load-bearing: the log may only disappear once the batch it
     * carried is durable, the same ordering the write path guarantees.
     */
    private synchronized void replayWal() throws IOException {
        this.wal.replayBucketChanges(this::writeChunks);
        flush();
        this.wal.clear();
    }

    /**
     * Rebuilds a bucket payload applying every entry in {@code changes} at once.
     * Key = slot index, value = new chunk bytes ({@code null} removes it).
     *
     * <p>Output is always compact — bucket payloads never accumulate internal holes, because every
     * modification rewrites the whole bucket.
     */
    private byte[] rebuildPayload(byte[] old, Map<Integer, byte[]> changes) throws CsoCorruptedException {
        int k = this.chunksPerBucket;
        int indexBytes = k * CHUNK_ENTRY_SIZE;
        int[] offsets = new int[k];
        int[] lengths = new int[k];
        int[] stamps = new int[k];
        int total = 0;

        if (old != null) {
            if (old.length < indexBytes) {
                throw new CsoCorruptedException(
                    "Bucket payload in " + this.path + " is shorter than its index: " + old.length
                        + " bytes for a " + indexBytes + "-byte index");
            }
            for (int i = 0; i < k; i++) {
                int base = i * CHUNK_ENTRY_SIZE;
                offsets[i] = CsoFormat.readInt(old, base);
                lengths[i] = CsoFormat.readInt(old, base + 4);
                stamps[i] = CsoFormat.readInt(old, base + 8);
                if (!changes.containsKey(i) && lengths[i] > 0) {
                    // The read path bounds-checks every index entry it serves; the write path must
                    // check the ones it carries over, or a corrupt index turns the next save into
                    // an unchecked arraycopy crash.
                    if (offsets[i] < indexBytes || (long) offsets[i] + lengths[i] > old.length) {
                        throw new CsoCorruptedException(
                            "Chunk entry out of bounds in " + this.path + ": offset=" + offsets[i]
                                + " length=" + lengths[i] + " payload=" + old.length);
                    }
                    total += lengths[i];
                }
            }
        }
        for (byte[] data : changes.values()) {
            if (data != null) {
                total += data.length;
            }
        }

        byte[] out = new byte[indexBytes + total];
        int p = indexBytes;
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
        int bound = this.compressor.compressBound(payload.length);
        if (this.compressScratch.length < bound) {
            this.compressScratch = new byte[bound];
        }
        int compressedLength = this.compressor.compress(payload, this.compressScratch);
        long elapsedNanos = System.nanoTime() - startedAt;
        long offset = this.allocator.allocate(this.entries, compressedLength, this.fileEnd);
        writeFully(ByteBuffer.wrap(this.compressScratch, 0, compressedLength), offset);
        CsoStats.bucketCompressed(elapsedNanos, payload.length, compressedLength);
        CsoStats.ioWrite(compressedLength);
        // The block is on disk but no table copy names it yet, so nothing can reach it. The
        // extent bookkeeping covers it from here on: if the table write below fails, this is
        // dead space — a gap or tail the next allocate() may freely reuse, because no entry
        // anywhere points at it.
        this.fileEnd = Math.max(this.fileEnd, offset + compressedLength);

        // The next state is built WITHOUT touching entries[bucket]: that entry is what keeps
        // the old block marked in use for allocate(), and it is the copy a recovery falls back
        // to. Committing it in memory before the durable table write meant that a failure
        // between the two freed the old block, and the retry — CsoStorage re-runs a failed
        // batch on its 500 ms timer — overwrote the last intact copy a valid on-disk table
        // entry still pointed at. compact() already follows this deferred-commit rule; this is
        // the write path's copy of it.
        TableEntry next = new TableEntry();
        next.sequence = this.sequenceCounter + 1;
        next.offset = offset;
        next.compressedLength = compressedLength;
        next.rawLength = payload.length;
        next.crc32 = (int) crc32(payload);
        next.chunkCount = countChunks(payload);
        writeTableEntry(bucket, next);

        // The durable commit landed; only now does the in-memory state follow the disk.
        this.sequenceCounter++;
        TableEntry e = this.entries[bucket];
        e.sequence = next.sequence;
        e.offset = next.offset;
        e.compressedLength = next.compressedLength;
        e.rawLength = next.rawLength;
        e.crc32 = next.crc32;
        e.chunkCount = next.chunkCount;
        this.bucketCache.put(bucket, payload);
        this.dirty = true;
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

    /** Bytes occupied by blocks no table entry still points at — interior gaps only. */
    public synchronized long wastedBytes() {
        return this.allocator.wastedBytes(this.entries);
    }

    public synchronized long fileSize() {
        return this.fileEnd;
    }

    /**
     * Compacts this file if its waste has crossed both thresholds; reports whether it did.
     *
     * <p>Deliberately not called from the write path. Compaction rewrites the whole file, so
     * triggering it after a bucket lands turns one chunk save into a multi-megabyte copy at the
     * exact moment the server is busy saving chunks. Callers run it at flush time instead, where a
     * stall costs nothing a player can feel.
     */
    public synchronized boolean compactIfWasted() throws IOException {
        long wasted = wastedBytes();
        long used = this.fileEnd - wasted;
        if (wasted < this.compactionMinWasted || wasted < used * this.compactionWastedRatio) {
            return false;
        }
        compact();
        return true;
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
                    TableEntry e = this.entries[b];
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
                    TableEntry e = this.entries[b];
                    long previousOffset = e.offset;
                    e.offset = newOffsets[b];
                    byte[] entry = TableEntry.encode(e);
                    e.offset = previousOffset; // applied only once compaction succeeds
                    for (int table = 0; table < CsoFormat.TABLE_COUNT; table++) {
                        writeChannel(
                            out, ByteBuffer.wrap(entry),
                            (long) CsoFormat.tableOffset(table, b, this.bucketCount)
                        );
                    }
                }
                out.force(true);
            }

            // From here the live channel is gone and this.path is being replaced, so any failure in
            // between would leave the object holding a closed channel: every later write and every
            // flush would then fail until the game restarted. The channel is therefore reopened on
            // the way out no matter how the swap goes, and a failure to do so is reported rather
            // than swallowed.
            this.channel.close();
            try {
                Files.move(tmp, this.path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                // The swap failed, so the file this object serves is still the pre-compaction one.
                // Reopening it keeps the object usable; the recovered copy is discarded. A failure
                // to reopen has to be attached rather than replace the move failure, or the reason
                // the compaction broke would be lost.
                try {
                    this.channel = FileChannel.open(
                        this.path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE
                    );
                } catch (IOException reopenFailure) {
                    moveFailure.addSuppressed(reopenFailure);
                    throw moveFailure;
                }
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // A leftover .tmp is inert; the next compaction truncates it.
                }
                throw moveFailure;
            }
            this.channel = FileChannel.open(
                this.path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE
            );
            for (int b = 0; b < this.bucketCount; b++) {
                if (newOffsets[b] != 0) {
                    this.entries[b].offset = newOffsets[b];
                    // Point the next write at the table copy the compaction just made stale — but
                    // only now that the swap has committed. Marking it while the .tmp was still
                    // being written aimed a post-failure write at the only copy still valid on
                    // disk, leaving zero redundancy during that write window.
                    this.lastTable[b] = 0;
                }
            }
            this.fileEnd = newEnd;
            this.channel.truncate(newEnd);
            // The replacement itself was forced before the move; this only records that the new
            // handle has a pending length change worth forcing with the next batch.
            this.dirty = true;
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
        if (!this.dirty) {
            return;
        }
        this.channel.force(true);
        this.dirty = false;
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            flush();
        } finally {
            this.channel.close();
        }
    }
}
