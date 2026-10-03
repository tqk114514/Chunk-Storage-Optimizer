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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.metrics.CsoLatency;
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
 *
 * <h3>Threading</h3>
 * Vanilla reaches this object from the storage's own {@code IOWorker} thread, but commands reach it
 * from the server thread: {@code /cso compact} and {@code /cso convert} iterate the open region
 * files while the game may be writing to them. Every field below is therefore guarded by
 * {@link #lock}, and so are the command entry points. The guard is a {@link ReentrantLock} rather
 * than {@code synchronized} so the region-file calls it wraps — themselves synchronized — are
 * taken in one consistent order, which is what keeps the stampede off the file objects.
 */
public final class CsoStorage implements AutoCloseable {

    private static final int MAX_OPEN_REGIONS = 256;
    private static final int MAX_OPEN_LEGACY = 8;

    /**
     * One daemon thread for every storage in the process, waking often enough that no store's
     * staged data can sit longer than its configured delay by much.
     *
     * <p>The timeout check in {@link #write} only runs when another write arrives, so a server that
     * goes quiet — which is exactly what autosave looks like, since vanilla does not flush the
     * region storage on a regular autosave — would leave that last batch in memory until shutdown.
     * A timer is what makes the configured delay a real bound rather than a best-effort idea.
     */
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
        runnable -> {
            Thread thread = new Thread(runnable, "cso-batch-flush");
            thread.setDaemon(true);
            return thread;
        }
    );
    private static final long TIMER_PERIOD_MS = 500L;

    private static final System.Logger LOGGER = System.getLogger(CsoStorage.class.getName());

    private final RegionStorageInfo info;
    private final Path folder;
    private final boolean sync;
    private final CsoSettings settings;

    /**
     * Guards every mutable field below. Reentrant so a guarded method may call another one.
     *
     * <p>Held across IO on purpose: two threads rewriting the same bucket at once would corrupt the
     * file, and the file's own lock is taken inside this one, so the ordering is always
     * storage-then-file. {@link #flush()} may block the IOWorker for the length of one forced write,
     * which is the same wait vanilla's own flush imposes.
     */
    private final ReentrantLock lock = new ReentrantLock();

    private final Map<Long, CsoRegionFile> regions = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Long, RegionFile> legacyRegions = new LinkedHashMap<>(8, 0.75f, true);

    /** regionKey -> chunkKey -> staged bytes; a null value means "deleted". */
    private final Map<Long, Map<Long, byte[]>> pending = new HashMap<>();
    /** One representative ChunkPos per pending region, enough to derive its file path. */
    private final Map<Long, ChunkPos> pendingRegionSample = new HashMap<>();
    private int pendingCount;
    private long lastFlushMillis = System.currentTimeMillis();
    /** Set once by {@link #release()}; the mixin then stops routing this folder to us. */
    private volatile boolean released;
    /** Cancels the periodic timeout flush once this storage is closed. */
    private ScheduledFuture<?> timerTask;

    /** "overworld/region" style label, so /cso stats can attribute numbers to one store. */
    private final String label;
    private final LongAdder chunksRead = new LongAdder();
    private final LongAdder chunksWritten = new LongAdder();
    private final CsoLatency flushLatency = new CsoLatency();
    /** Reused NBT serialization buffer; see {@link #serialize}. */
    private final ByteArrayOutputStream serializeSink = new ByteArrayOutputStream(8192);

    public CsoStorage(RegionStorageInfo info, Path folder, boolean sync, CsoSettings settings) {
        this.info = info;
        this.folder = folder;
        this.sync = sync;
        this.settings = settings;
        this.label = labelOf(folder);
        CsoRegistry.add(this);
        this.timerTask = TIMER.scheduleWithFixedDelay(
            this::flushIfOverdue, TIMER_PERIOD_MS, TIMER_PERIOD_MS, TimeUnit.MILLISECONDS
        );
    }

    /**
     * The timer's job: write out anything that has been staged longer than the configured delay.
     *
     * <p>A failure here is logged, not thrown at the timer thread — the data is still in memory and
     * the next write or flush tries again, whereas an escaping exception would silently kill the
     * periodic task and reintroduce the unbounded staging this exists to prevent.
     */
    private void flushIfOverdue() {
        if (this.released) {
            return;
        }
        this.lock.lock();
        try {
            if (this.pending.isEmpty()
                || System.currentTimeMillis() - this.lastFlushMillis < this.settings.batchMaxDelayMs()) {
                return;
            }
            flushPending();
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                "Timed flush failed for " + this.folder + "; the batch stays staged", e);
        } finally {
            this.lock.unlock();
        }
    }

    private static String labelOf(Path folder) {
        Path parent = folder.getParent();
        String store = folder.getFileName().toString();
        // Save layouts nest deeper than dimensions/.../<dim>/<store>, but the two trailing names
        // are always the interesting ones.
        return parent == null ? store : parent.getFileName() + "/" + store;
    }

    public String label() {
        return this.label;
    }

    /** Which world's directory this storage writes, so callers can scope an action to one save. */
    public Path folder() {
        return this.folder;
    }

    public boolean isReleased() {
        return this.released;
    }

    /**
     * Detaches this storage for the rest of the session: staged data is written, handles closed,
     * and from here on the game's own Anvil files serve the folder again.
     *
     * <p>This is what a world's opt-out needs besides the marker file. The marker only reaches
     * storages created from now on; without releasing, an already-attached storage would go on
     * writing {@code .cso} straight into a directory that was just converted to {@code .mca}.
     */
    public void release() throws IOException {
        // A released storage serves nothing any more, so its timer has nothing left to flush.
        cancelTimer();
        this.lock.lock();
        try {
            this.released = true;
            CsoRegistry.remove(this);
            pauseForConversion();
        } finally {
            this.lock.unlock();
        }
    }

    public long chunksReadCount() {
        return this.chunksRead.sum();
    }

    public long chunksWrittenCount() {
        return this.chunksWritten.sum();
    }

    public CsoLatency flushLatency() {
        return this.flushLatency;
    }

    /** Clears this storage's own counters; the process-wide ones live in CsoStats. */
    public void resetStats() {
        this.chunksRead.reset();
        this.chunksWritten.reset();
        this.flushLatency.reset();
    }

    // ------------------------------------------------------------------ api

    public CompoundTag read(ChunkPos pos) throws IOException {
        this.lock.lock();
        try {
            this.chunksRead.increment();
            byte[] staged = staged(pos);
            if (staged != null) {
                return deserialize(staged);
            }
            if (isStagedDeleted(pos)) {
                return null;
            }
            CsoRegionFile file = region(pos);
            byte[] data = file.readChunk(pos.getRegionLocalX(), pos.getRegionLocalZ());
            if (data != null) {
                return deserialize(data);
            }
            if (!this.settings.fallbackToMca()) {
                return null;
            }
            // A bucket that CSO has written is authoritative for every slot in it: a zero-length slot
            // there was deleted on purpose. Falling back for one would resurrect the chunk vanilla
            // cleared (emptied entity chunks are written as null), so the fallback is only correct
            // while the bucket has never been written at all.
            if (file.hasBucket(pos.getRegionLocalX(), pos.getRegionLocalZ())) {
                return null;
            }
            RegionFile legacy = legacy(pos);
            if (legacy == null) {
                return null;
            }
            try (DataInputStream in = legacy.getChunkDataInputStream(pos)) {
                return in == null ? null : NbtIo.read(in);
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void write(ChunkPos pos, CompoundTag value) throws IOException {
        this.lock.lock();
        try {
            this.chunksWritten.increment();
            stage(pos, value == null ? null : serialize(value));
            if (this.pendingCount >= this.settings.batchMaxChunks()
                || System.currentTimeMillis() - this.lastFlushMillis >= this.settings.batchMaxDelayMs()) {
                flushPending();
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void scanChunk(ChunkPos pos, StreamTagVisitor visitor) throws IOException {
        this.lock.lock();
        try {
            byte[] staged = staged(pos);
            if (staged != null) {
                NbtIo.parse(new DataInputStream(new ByteArrayInputStream(staged)), visitor, NbtAccounter.unlimitedHeap());
                return;
            }
            if (isStagedDeleted(pos)) {
                return;
            }
            CsoRegionFile file = region(pos);
            byte[] data = file.readChunk(pos.getRegionLocalX(), pos.getRegionLocalZ());
            if (data != null) {
                NbtIo.parse(new DataInputStream(new ByteArrayInputStream(data)), visitor, NbtAccounter.unlimitedHeap());
                return;
            }
            if (!this.settings.fallbackToMca()) {
                return;
            }
            // Same reasoning as read(): a written bucket is authoritative for its empty slots.
            if (file.hasBucket(pos.getRegionLocalX(), pos.getRegionLocalZ())) {
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
        } finally {
            this.lock.unlock();
        }
    }

    public void flush() throws IOException {
        this.lock.lock();
        try {
            long startedAt = System.nanoTime();
            try {
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
            } finally {
                this.flushLatency.record(System.nanoTime() - startedAt);
            }
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Stops the periodic timeout flush. Idempotent, and safe to call while the task is running:
     * {@code cancel(false)} only prevents future runs, and the task itself takes the same lock the
     * caller is about to hold, so the two cannot interleave destructively.
     */
    private void cancelTimer() {
        ScheduledFuture<?> task = this.timerTask;
        if (task != null) {
            task.cancel(false);
            this.timerTask = null;
        }
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
        this.lock.lock();
        try {
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
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Flushes everything staged and closes every open handle, then reports the folder it manages.
     *
     * <p>This exists for in-game conversion: files must not be rewritten while we still hold them
     * open. Handles are reopened lazily on next access, so this is safe to call at any time.
     */
    public Path pauseForConversion() throws IOException {
        this.lock.lock();
        try {
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
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        // Cancel first, outside the lock: a periodic task that is already running must not be
        // allowed to re-enter flushPending after the handles below are closed.
        cancelTimer();
        this.lock.lock();
        try {
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
        } finally {
            this.lock.unlock();
        }
    }

    // ------------------------------------------------------------------ batching

    private void stage(ChunkPos pos, byte[] data) {
        long regionKey = CsoFormat.coordKey(pos.getRegionX(), pos.getRegionZ());
        long chunkKey = CsoFormat.coordKey(pos.getRegionLocalX(), pos.getRegionLocalZ());
        Map<Long, byte[]> forRegion = this.pending.computeIfAbsent(regionKey, k -> new HashMap<>());
        if (!forRegion.containsKey(chunkKey)) {
            this.pendingCount++;
        }
        forRegion.put(chunkKey, data);
        this.pendingRegionSample.putIfAbsent(regionKey, pos);
    }

    private byte[] staged(ChunkPos pos) {
        Map<Long, byte[]> forRegion = this.pending.get(CsoFormat.coordKey(pos.getRegionX(), pos.getRegionZ()));
        return forRegion == null ? null
                : forRegion.get(CsoFormat.coordKey(pos.getRegionLocalX(), pos.getRegionLocalZ()));
    }

    private boolean isStagedDeleted(ChunkPos pos) {
        Map<Long, byte[]> forRegion = this.pending.get(CsoFormat.coordKey(pos.getRegionX(), pos.getRegionZ()));
        if (forRegion == null) {
            return false;
        }
        long chunkKey = CsoFormat.coordKey(pos.getRegionLocalX(), pos.getRegionLocalZ());
        return forRegion.containsKey(chunkKey) && forRegion.get(chunkKey) == null;
    }

    /** Writes every staged chunk, grouping by bucket so each bucket is recompressed once. */
    private void flushPending() throws IOException {
        if (this.pending.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
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
                long packed = chunkEntry.getKey();
                int localX = CsoFormat.keyX(packed);
                int localZ = CsoFormat.keyZ(packed);
                int bucket = CsoFormat.bucketIndex(localX, localZ, grid);
                int slot = CsoFormat.chunkIndexInBucket(localX, localZ, grid);
                byBucket.computeIfAbsent(bucket, k -> new HashMap<>()).put(slot, chunkEntry.getValue());
            }
            // Before the log, not after: the seed is part of what this batch will write, so a crash
            // between the log and the apply has to replay it too.
            seedNewBuckets(file, sample, grid, byBucket);
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
        CsoStats.batchFlushed(System.nanoTime() - startedAt);
    }

    /** Bytes currently staged but not yet written to disk. Diagnostic use. */
    public int pendingCount() {
        return this.pendingCount;
    }

    /**
     * Decides every slot of each bucket that has never been written, taking the chunks it is not
     * changing from the legacy {@code .mca}.
     *
     * <p>Without this a bucket's first write would record only the chunks of one save, and the
     * neighbours still living in the {@code .mca} would read back as absent — because the read path
     * skips the fallback for any written bucket, which is exactly what keeps a deleted chunk
     * deleted. The game reads "absent" as "never generated" and regenerates terrain over it, so
     * this is the difference between migrating a world and destroying it.
     *
     * <p>The cost is bounded and paid once per bucket: at most {@code chunksPerBucket - 1} extra
     * reads the first time a bucket is touched, which at grid 16 is three.
     *
     * <p>Declares no failure of its own, and that is deliberate: a batch that throws stays staged and
     * is retried on a timer, so anything escaping here would stop the world from saving. An
     * unreadable legacy chunk is reported and left out instead — see
     * {@link #legacyChunkBytes(RegionFile, ChunkPos, int, int)}.
     */
    private void seedNewBuckets(
        CsoRegionFile file, ChunkPos sample, int grid, Map<Integer, Map<Integer, byte[]>> byBucket
    ) {
        RegionFile legacy = null;
        for (Map.Entry<Integer, Map<Integer, byte[]>> bucketEntry : byBucket.entrySet()) {
            if (file.hasBucketIndex(bucketEntry.getKey())) {
                continue; // already written, so it is already authoritative for every slot
            }
            if (legacy == null) {
                legacy = legacy(sample);
                if (legacy == null) {
                    // No .mca for this region: nothing has ever been migrated here, so every empty
                    // slot really is empty and there is nothing to seed from.
                    return;
                }
            }
            RegionFile source = legacy;
            bucketEntry.setValue(BucketSeeder.seed(
                bucketEntry.getValue(), bucketEntry.getKey(), grid,
                (localX, localZ) -> legacyChunkBytes(source, sample, localX, localZ)
            ));
        }
    }

    /**
     * Raw NBT bytes of one chunk in the legacy {@code .mca}, or null when it cannot be read.
     *
     * <p>Read as bytes rather than parsed and re-serialized: the {@code .cso} payload stores exactly
     * what {@code NbtIo.write} produced and the legacy stream holds those same bytes, so a round
     * trip through NBT would only cost time.
     *
     * <p>Nothing escapes from here, and that is the point. Vanilla's reader returns null for the
     * damage it can see in the header — a truncated sector, an unknown compression id, a missing
     * {@code .mcc} — but decompression is lazy, so damage <em>inside</em> the stream only surfaces on
     * the read below, as a {@code ZipException} or an {@code EOFException}. Letting that out would
     * take the whole batch with it: the exception would leave {@code flushPending} with the batch
     * still staged, and the periodic flush would retry the same unreadable chunk every half second,
     * so the world would stop saving altogether — far worse than the one chunk. A chunk that cannot
     * be decompressed is one vanilla could not have served either, so leaving it out of the seed
     * costs nothing that was not already lost. It is logged rather than swallowed in silence,
     * because a chunk disappearing without a word is the very thing this mod exists to prevent.
     */
    private static byte[] legacyChunkBytes(RegionFile legacy, ChunkPos sample, int localX, int localZ) {
        ChunkPos pos = new ChunkPos(
            sample.getRegionX() * CsoFormat.REGION_CHUNKS + localX,
            sample.getRegionZ() * CsoFormat.REGION_CHUNKS + localZ
        );
        try (DataInputStream in = legacy.getChunkDataInputStream(pos)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            LOGGER.log(
                System.Logger.Level.WARNING,
                "Unreadable chunk " + pos + " in the legacy .mca — left out of the seed so the batch"
                    + " can still be written; this chunk is one vanilla could not have read either",
                e
            );
            return null;
        }
    }

    // ------------------------------------------------------------------ internals

    private byte[] serialize(CompoundTag tag) throws IOException {
        // Reusing the sink matters: a fresh ByteArrayOutputStream per chunk allocates its buffer and
        // then grows it, so every chunk write paid for two copies plus a fresh array. Only the IO
        // worker writes here, one chunk at a time.
        this.serializeSink.reset();
        try (DataOutputStream out = new DataOutputStream(this.serializeSink)) {
            NbtIo.write(tag, out);
        }
        return this.serializeSink.toByteArray();
    }

    private static CompoundTag deserialize(byte[] data) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(data)));
    }

    private CsoRegionFile region(ChunkPos pos) throws IOException {
        long key = CsoFormat.coordKey(pos.getRegionX(), pos.getRegionZ());
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
        long key = CsoFormat.coordKey(pos.getRegionX(), pos.getRegionZ());
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
