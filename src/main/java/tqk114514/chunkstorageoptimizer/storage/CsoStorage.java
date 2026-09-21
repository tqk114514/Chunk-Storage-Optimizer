package tqk114514.chunkstorageoptimizer.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.metrics.CsoStats;
import tqk114514.chunkstorageoptimizer.format.CsoRegionFile;

/**
 * Drop-in replacement for the region-file half of {@code RegionFileStorage}.
 *
 * <p>One instance per storage type (chunk / entities / poi). Owns an LRU of open region files,
 * plus a small LRU of legacy {@code .mca} files used for read-through during migration.
 *
 * <h3>Write batching</h3>
 * Writing one chunk means recompressing its whole bucket, so writes are staged in memory and
 * flushed per bucket. Ten chunks in the same bucket cost one compression instead of ten.
 * Staged data is visible to reads, and is forced to disk by {@link #flush()} (which the game
 * calls on autosave) and by {@link #close()}.
 */
public final class CsoStorage implements AutoCloseable {

    private static final int MAX_OPEN_REGIONS = 256;
    private static final int MAX_OPEN_LEGACY = 8;

    private final RegionStorageInfo info;
    private final Path folder;
    private final boolean sync;
    private final CsoSettings settings;

    private final Map<Long, CsoRegionFile> regions = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Long, RegionFile> legacyRegions = new LinkedHashMap<>(8, 0.75f, true);

    /** regionKey -> chunkKey -> staged bytes; a null value means "deleted". */
    private final Map<Long, Map<Long, byte[]>> pending = new HashMap<>();
    /** One representative ChunkPos per pending region, enough to derive its file path. */
    private final Map<Long, ChunkPos> pendingRegionSample = new HashMap<>();
    private int pendingCount;
    private long lastFlushMillis = System.currentTimeMillis();

    public CsoStorage(RegionStorageInfo info, Path folder, boolean sync, CsoSettings settings) {
        this.info = info;
        this.folder = folder;
        this.sync = sync;
        this.settings = settings;
        CsoRegistry.add(this);
    }

    // ------------------------------------------------------------------ api

    public CompoundTag read(ChunkPos pos) throws IOException {
        byte[] staged = staged(pos);
        if (staged != null) {
            return deserialize(staged);
        }
        if (isStagedDeleted(pos)) {
            return null;
        }
        byte[] data = region(pos).readChunk(pos.getRegionLocalX(), pos.getRegionLocalZ());
        if (data != null) {
            return deserialize(data);
        }
        if (!this.settings.fallbackToMca()) {
            return null;
        }
        RegionFile legacy = legacy(pos);
        if (legacy == null) {
            return null;
        }
        try (DataInputStream in = legacy.getChunkDataInputStream(pos)) {
            return in == null ? null : NbtIo.read(in);
        }
    }

    public void write(ChunkPos pos, CompoundTag value) throws IOException {
        stage(pos, value == null ? null : serialize(value));
        if (this.pendingCount >= this.settings.batchMaxChunks()
            || System.currentTimeMillis() - this.lastFlushMillis >= this.settings.batchMaxDelayMs()) {
            flushPending();
        }
    }

    public void scanChunk(ChunkPos pos, StreamTagVisitor visitor) throws IOException {
        byte[] staged = staged(pos);
        if (staged != null) {
            NbtIo.parse(new DataInputStream(new ByteArrayInputStream(staged)), visitor, NbtAccounter.unlimitedHeap());
            return;
        }
        if (isStagedDeleted(pos)) {
            return;
        }
        byte[] data = region(pos).readChunk(pos.getRegionLocalX(), pos.getRegionLocalZ());
        if (data != null) {
            NbtIo.parse(new DataInputStream(new ByteArrayInputStream(data)), visitor, NbtAccounter.unlimitedHeap());
            return;
        }
        if (!this.settings.fallbackToMca()) {
            return;
        }
        RegionFile legacy = legacy(pos);
        if (legacy == null) {
            return;
        }
        try (DataInputStream in = legacy.getChunkDataInputStream(pos)) {
            if (in != null) {
                NbtIo.parse(in, visitor, NbtAccounter.unlimitedHeap());
            }
        }
    }

    public void flush() throws IOException {
        flushPending();
        IOException failure = null;
        for (CsoRegionFile file : this.regions.values()) {
            try {
                file.flush();
            } catch (IOException e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
        // Housekeeping only after the batch is durable: a failed compaction must not be able to
        // skip the forced write above.
        compactOneFile();
    }

    /**
     * Reclaims space in at most one file per flush, coldest file first — the region map is LRU, so
     * iterating it starts at the files nobody is touching. A save that fragmented while the server
     * was down therefore heals across several autosaves instead of stalling one of them.
     */
    private void compactOneFile() throws IOException {
        for (CsoRegionFile file : this.regions.values()) {
            if (file.compactIfWasted()) {
                return;
            }
        }
    }

    /** Compacts every open region file in this storage. Returns how many were processed. */
    public int compactAll() throws IOException {
        flushPending();
        int count = 0;
        IOException failure = null;
        for (CsoRegionFile file : this.regions.values()) {
            try {
                file.compact();
                count++;
            } catch (IOException e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
        return count;
    }

    /**
     * Flushes everything staged and closes every open handle, then reports the folder it manages.
     *
     * <p>This exists for in-game conversion: files must not be rewritten while we still hold them
     * open. Handles are reopened lazily on next access, so this is safe to call at any time.
     */
    public Path pauseForConversion() throws IOException {
        flushPending();
        IOException failure = null;
        for (CsoRegionFile file : this.regions.values()) {
            try {
                file.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        this.regions.clear();
        for (RegionFile file : this.legacyRegions.values()) {
            try {
                file.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        this.legacyRegions.clear();
        if (failure != null) {
            throw failure;
        }
        return this.folder;
    }

    @Override
    public void close() throws IOException {
        CsoRegistry.remove(this);
        IOException failure = null;
        try {
            flushPending();
        } catch (IOException e) {
            failure = e;
        }
        for (CsoRegionFile file : this.regions.values()) {
            try {
                file.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        this.regions.clear();
        for (RegionFile file : this.legacyRegions.values()) {
            try {
                file.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        this.legacyRegions.clear();
        if (failure != null) {
            throw failure;
        }
    }

    // ------------------------------------------------------------------ batching

    private void stage(ChunkPos pos, byte[] data) {
        long regionKey = ChunkPos.pack(pos.getRegionX(), pos.getRegionZ());
        long chunkKey = ChunkPos.pack(pos.getRegionLocalX(), pos.getRegionLocalZ());
        Map<Long, byte[]> forRegion = this.pending.computeIfAbsent(regionKey, k -> new HashMap<>());
        if (!forRegion.containsKey(chunkKey)) {
            this.pendingCount++;
        }
        forRegion.put(chunkKey, data);
        this.pendingRegionSample.putIfAbsent(regionKey, pos);
    }

    private byte[] staged(ChunkPos pos) {
        Map<Long, byte[]> forRegion = this.pending.get(ChunkPos.pack(pos.getRegionX(), pos.getRegionZ()));
        return forRegion == null ? null : forRegion.get(ChunkPos.pack(pos.getRegionLocalX(), pos.getRegionLocalZ()));
    }

    private boolean isStagedDeleted(ChunkPos pos) {
        Map<Long, byte[]> forRegion = this.pending.get(ChunkPos.pack(pos.getRegionX(), pos.getRegionZ()));
        if (forRegion == null) {
            return false;
        }
        long chunkKey = ChunkPos.pack(pos.getRegionLocalX(), pos.getRegionLocalZ());
        return forRegion.containsKey(chunkKey) && forRegion.get(chunkKey) == null;
    }

    /** Writes every staged chunk, grouping by bucket so each bucket is recompressed once. */
    private void flushPending() throws IOException {
        if (this.pending.isEmpty()) {
            return;
        }
        // Stage 1 — group by bucket and force a write-ahead log. If we die during stage 2, the
        // next open replays exactly these changes. One forced write per batch, not per bucket:
        // a finer-grained WAL would cost a forced write per chunk save and wipe out the speed
        // this format exists to provide.
        Map<CsoRegionFile, Map<Integer, Map<Integer, byte[]>>> staged = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<Long, byte[]>> regionEntry : this.pending.entrySet()) {
            ChunkPos sample = this.pendingRegionSample.get(regionEntry.getKey());
            if (sample == null) {
                continue;
            }
            CsoRegionFile file = region(sample);
            int grid = file.grid();
            Map<Integer, Map<Integer, byte[]>> byBucket = new HashMap<>();
            for (Map.Entry<Long, byte[]> chunkEntry : regionEntry.getValue().entrySet()) {
                ChunkPos local = ChunkPos.unpack(chunkEntry.getKey());
                int bucket = CsoFormat.bucketIndex(local.x(), local.z(), grid);
                int slot = CsoFormat.chunkIndexInBucket(local.x(), local.z(), grid);
                byBucket.computeIfAbsent(bucket, k -> new HashMap<>()).put(slot, chunkEntry.getValue());
            }
            file.writeWal(byBucket);
            staged.put(file, byBucket);
        }

        // Stage 2 — apply.
        for (Map.Entry<CsoRegionFile, Map<Integer, Map<Integer, byte[]>>> entry : staged.entrySet()) {
            for (Map.Entry<Integer, Map<Integer, byte[]>> bucketEntry : entry.getValue().entrySet()) {
                entry.getKey().writeChunks(bucketEntry.getKey(), bucketEntry.getValue());
            }
        }

        // Stage 3 — force, and only then drop the log. Skipping the force would defeat the purpose:
        // the log would be gone while the data might still be sitting in a page cache.
        for (CsoRegionFile file : staged.keySet()) {
            file.flush();
            file.clearWal();
        }

        this.pending.clear();
        this.pendingRegionSample.clear();
        this.pendingCount = 0;
        this.lastFlushMillis = System.currentTimeMillis();
        CsoStats.batchFlushed();
    }

    /** Bytes currently staged but not yet written to disk. Diagnostic use. */
    public int pendingCount() {
        return this.pendingCount;
    }

    // ------------------------------------------------------------------ internals

    private static byte[] serialize(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8096);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        }
        return bytes.toByteArray();
    }

    private static CompoundTag deserialize(byte[] data) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(data)));
    }

    private CsoRegionFile region(ChunkPos pos) throws IOException {
        long key = ChunkPos.pack(pos.getRegionX(), pos.getRegionZ());
        CsoRegionFile file = this.regions.get(key);
        if (file != null) {
            return file;
        }
        Files.createDirectories(this.folder);
        file = CsoRegionFile.open(
            regionPath(pos, ".cso"),
            this.settings.grid(),
            this.settings.compressionId(),
            this.settings.level(),
            this.settings.cachedBuckets(),
            this.settings.verifyCrc(),
            this.settings.compactionMinBytes(),
            this.settings.compactionRatio()
        );
        evictIfNeeded(this.regions, MAX_OPEN_REGIONS);
        this.regions.put(key, file);
        return file;
    }

    /** Legacy handle for read-through. Null when there is no {@code .mca} to fall back to. */
    private RegionFile legacy(ChunkPos pos) {
        Path path = regionPath(pos, ".mca");
        if (!Files.isRegularFile(path)) {
            return null;
        }
        long key = ChunkPos.pack(pos.getRegionX(), pos.getRegionZ());
        RegionFile file = this.legacyRegions.get(key);
        if (file != null) {
            return file;
        }
        try {
            file = new RegionFile(this.info, path, this.folder, this.sync);
        } catch (IOException e) {
            // A broken legacy file must not break the new format: skip the fallback.
            return null;
        }
        evictIfNeeded(this.legacyRegions, MAX_OPEN_LEGACY);
        this.legacyRegions.put(key, file);
        return file;
    }

    private Path regionPath(ChunkPos pos, String extension) {
        return this.folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + extension);
    }

    private static <T extends AutoCloseable> void evictIfNeeded(Map<Long, T> map, int limit) {
        if (map.size() < limit) {
            return;
        }
        var it = map.entrySet().iterator();
        if (it.hasNext()) {
            try {
                it.next().getValue().close();
            } catch (Exception ignored) {
                // Closing during eviction is best effort; the data was already written.
            }
            it.remove();
        }
    }
}
