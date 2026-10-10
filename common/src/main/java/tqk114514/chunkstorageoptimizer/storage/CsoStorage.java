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

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

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
     * How hard to try before giving up on a legacy chunk, and how long to wait between attempts.
     *
     * <p>Giving up is not harmless: a chunk left out of the seed leaves its slot empty in a bucket
     * that is about to become authoritative, and the read path never looks at the {@code .mca} for a
     * written bucket again — so the game reads the chunk as absent and regenerates it. That is the
     * right answer when the bytes really are damaged, and the wrong one when the cause was the disk:
     * a transient fault would turn into a silently regenerated chunk. The two cannot be told apart
     * by exception type (lz4-java reports corruption as a plain {@code IOException} as well), so the
     * only lever available is to try again.
     */
    private static final int LEGACY_READ_ATTEMPTS = 3;
    private static final long LEGACY_READ_RETRY_DELAY_MS = 100L;

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

    /**
     * slf4j, like the rest of common — deliberately not {@link System.Logger}: without a
     * JUL-to-log4j bridge on the classpath (Fabric ships none), System.Logger output never
     * reaches the game log, and the warnings this class emits are exactly the ones someone
     * has to see when a save misbehaves.
     */
    private static final Logger LOGGER = LogUtils.getLogger();

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
    /**
     * The vanilla side of the same folder, as the mixin that owns it. Held so a conversion can
     * latch BOTH sides: ours is only half of the picture, and a background reader (the world
     * map building tiles) reaches vanilla's handles directly whenever this storage is released.
     */
    private final VanillaHandles vanillaHandles;
    /**
     * Latched while a conversion rewrites this folder. A latched read answers empty instead of
     * reopening what is being replaced — the storage's lazy reopen is exactly what kept
     * re-holding files a conversion had just paused closed (measured in-game: a map tile
     * thread asking for chunks mid-conversion reopened the handles, and the run skipped those
     * regions). Staged writes are kept for after the resume; the game itself cannot read
     * mid-conversion, because the command holds the server thread.
     */
    private boolean conversionPaused;

    /** The vanilla half of this folder's storage, reachable for commands. */
    public interface VanillaHandles {

        /**
         * Latches vanilla reads closed and closes every open vanilla handle: a conversion is
         * about to rewrite this folder, and an open handle is a file it cannot replace. Reads
         * from here on answer empty until {@link #resumeAfterConversion()}.
         */
        void pauseForConversion() throws IOException;

        /** Releases the latch; vanilla handles reopen lazily on next use. */
        void resumeAfterConversion();
    }

    public CsoStorage(RegionStorageInfo info, Path folder, boolean sync, CsoSettings settings,
        VanillaHandles vanillaHandles) {
        this.info = info;
        this.folder = folder;
        this.sync = sync;
        this.settings = settings;
        this.vanillaHandles = vanillaHandles;
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
            LOGGER.warn("Timed flush failed for {}; the batch stays staged", this.folder, e);
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
            // Deliberately still registered: the conversion that released this storage needs to
            // reach its vanilla side — both to latch it (a background reader walks straight
            // into vanilla once we stop serving) and to unlatch it afterwards. The registry's
            // actions all no-op on a released storage's empty own state, and the world unload
            // removes it for good.
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
        this.chunksRead.increment();
        Located located = locate(pos);
        return located == null ? null : deserialize(located.data(), located.offset(), located.length());
    }

    /**
     * Everything a read touches that is shared, under the one lock — with the NBT parse
     * deliberately outside it.
     *
     * <p>The parse costs an order of magnitude more than the whole locked part combined
     * (measured against real vanilla chunk NBT: ~29 us to parse a 10 KB chunk, ~1 us for the
     * staged/bucket lookups), so holding the lock across it made every contending thread —
     * another reader, the scan pool, the batch timer — queue behind a duration that protects
     * nothing: the bytes it reads are immutable once published (staged arrays are never
     * mutated after staging, cached payloads are never mutated in place, a bucket rewrite
     * installs a new array), so the parse can safely run while another thread flushes, saves
     * or scans.
     *
     * <p>Returns the chunk's bytes to parse, or null when the chunk is absent by any of the
     * paths: not staged and not deleted, absent from the {@code .cso}, a written bucket's
     * authoritative empty slot, or no fallback copy.
     */
    private Located locate(ChunkPos pos) throws IOException {
        this.lock.lock();
        try {
            // The conversion pause answers empty: the file behind this region is being
            // rewritten, and the only callers that can arrive here mid-pause are background
            // ones — the game itself is the thread running the command. A null read is what
            // "chunk absent" already means to every caller, and the tile builders behind those
            // background reads treat it as "nothing to draw", not as damage.
            if (this.conversionPaused) {
                return null;
            }
            byte[] staged = staged(pos);
            if (staged != null) {
                return new Located(staged, 0, staged.length);
            }
            if (isStagedDeleted(pos)) {
                return null;
            }
            CsoRegionFile file = regionIfExists(pos);
            CsoRegionFile.ChunkSlice slice = file == null
                ? null
                : file.readChunkSlice(pos.getRegionLocalX(), pos.getRegionLocalZ());
            if (slice != null) {
                // Zero-copy: the parse reads straight out of the cached bucket payload, which
                // is never mutated in place — one allocation and one memcpy less per chunk load.
                return new Located(slice.payload(), slice.offset(), slice.length());
            }
            if (!this.settings.fallbackToMca()) {
                return null;
            }
            // A bucket that CSO has written is authoritative for every slot in it: a zero-length slot
            // there was deleted on purpose. Falling back for one would resurrect the chunk vanilla
            // cleared (emptied entity chunks are written as null), so the fallback is only correct
            // while the bucket has never been written at all.
            if (file != null && file.hasBucket(pos.getRegionLocalX(), pos.getRegionLocalZ())) {
                return null;
            }
            RegionFile legacy = legacy(pos);
            if (legacy == null) {
                return null;
            }
            // Read to bytes rather than streaming into the parser: the parse happens outside
            // the lock, and one extra copy on this cold path is nothing next to the disk read
            // it already pays.
            try (DataInputStream in = legacy.getChunkDataInputStream(pos)) {
                if (in == null) {
                    return null;
                }
                byte[] bytes = in.readAllBytes();
                return new Located(bytes, 0, bytes.length);
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** Bytes for the parser to consume outside the storage lock; owned or immutable-shared. */
    private record Located(byte[] data, int offset, int length) {
    }

    public void write(ChunkPos pos, CompoundTag value) throws IOException {
        // Serialized before the lock: nothing shared is touched, and the tag may be any size —
        // the lock only needs the resulting bytes. Still one caller at a time (vanilla's
        // IO worker is the only writer), which is what the reused sink below relies on.
        byte[] bytes = value == null ? null : serialize(value);
        this.lock.lock();
        try {
            this.chunksWritten.increment();
            stage(pos, bytes);
            if (this.pendingCount >= this.settings.batchMaxChunks()
                || System.currentTimeMillis() - this.lastFlushMillis >= this.settings.batchMaxDelayMs()) {
                flushPending();
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void scanChunk(ChunkPos pos, StreamTagVisitor visitor) throws IOException {
        // Same shape as read(): everything shared under the lock, the parse outside it. The
        // visitor belongs to the caller, which never held our lock to begin with.
        Located located = locate(pos);
        if (located != null) {
            NbtIo.parse(
                new DataInputStream(new ByteArrayInputStream(located.data(), located.offset(), located.length())),
                visitor, NbtAccounter.unlimitedHeap());
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
            // One real flush FIRST, while the gate is still open — this is the last chance to
            // write what was staged before the conversion — then the latch, then the closes.
            // A read that slips in after the latch answers empty; a read that finished before
            // it had its handle open, and the close below takes that handle away. Without the
            // latch, a background reader (the world map's tile thread) would simply reopen
            // what the close had just closed, and the conversion lost the file again.
            flushPending();
            this.conversionPaused = true;
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
            try {
                this.vanillaHandles.pauseForConversion();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
            }
            if (failure != null) {
                throw failure;
            }
            return this.folder;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Ends a conversion's pause: the latch comes off both sides, and anything staged while
     * the folder was being rewritten — writes arrive from the game's own queue, which the
     * command keeps blocked, so this is normally empty — lands in the new files right now.
     * Released storages only unlatch the vanilla side; their folder is vanilla's again.
     */
    public void resumeAfterConversion() throws IOException {
        this.lock.lock();
        try {
            this.conversionPaused = false;
            if (!this.released) {
                flushPending();
            }
            this.vanillaHandles.resumeAfterConversion();
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
        if (this.conversionPaused) {
            // The folder is being rewritten under a conversion. Writing now would reopen the
            // very files it is replacing; the staged data stays in memory and lands right
            // after the resume instead — the pause itself flushes one last time before
            // latching, so nothing waits that existed before the conversion.
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
     * <p>Per-chunk reads from the legacy file still declare no failure of their own, and that
     * is deliberate: a batch that throws stays staged and is retried on a timer, so a single
     * unreadable chunk escaping would stop the world from saving — an unreadable chunk is
     * reported and left out instead, see {@link #legacyChunkBytes(RegionFile, ChunkPos, int, int)}.
     * The legacy file itself is the one deliberate exception: a {@code .mca} that exists but
     * cannot be opened at all propagates from here, precisely because seeding without it would
     * make this bucket authoritative over chunks that are still only in there. The batch stays
     * staged and the timer retries, so the save stalls loudly until the file opens again
     * instead of silently destroying the neighbours (reproduced in-game on 2026-10-04).
     */
    private void seedNewBuckets(
        CsoRegionFile file, ChunkPos sample, int grid, Map<Integer, Map<Integer, byte[]>> byBucket
    ) throws IOException {
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
     * so the world would stop saving altogether — far worse than the one chunk.
     *
     * <p>Swallowing it is not free either, which is why the read is retried first. A failure here
     * may be the bytes or may be the disk, and the two are indistinguishable by type, so a single
     * transient fault would otherwise cost a perfectly good chunk: it would be left out of the seed,
     * its bucket would become authoritative, and the game would regenerate it. Only after the retries
     * are exhausted is the chunk given up on — and even then the message says what was observed
     * rather than claiming the file is at fault, because that is not something this can know.
     */
    private static byte[] legacyChunkBytes(RegionFile legacy, ChunkPos sample, int localX, int localZ) {
        ChunkPos pos = new ChunkPos(
            sample.getRegionX() * CsoFormat.REGION_CHUNKS + localX,
            sample.getRegionZ() * CsoFormat.REGION_CHUNKS + localZ
        );
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= LEGACY_READ_ATTEMPTS; attempt++) {
            try (DataInputStream in = legacy.getChunkDataInputStream(pos)) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < LEGACY_READ_ATTEMPTS) {
                    try {
                        Thread.sleep(LEGACY_READ_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        LOGGER.warn(
            "Could not read chunk {} from the legacy .mca in {} attempts, so it was left out of the"
                + " seed to let the batch through. If the legacy file is sound, this chunk now reads"
                + " as absent and the game will regenerate it — worth checking the .mca",
            pos, LEGACY_READ_ATTEMPTS, lastFailure
        );
        return null;
    }

    // ------------------------------------------------------------------ internals

    private byte[] serialize(CompoundTag tag) throws IOException {
        // Reusing the sink matters: a fresh ByteArrayOutputStream per chunk allocates its buffer and
        // then grows it, so every chunk write paid for two copies plus a fresh array. Serialize now
        // runs outside the storage lock; it is still one-at-a-time because vanilla's IO worker is the
        // only thread that ever writes chunks.
        this.serializeSink.reset();
        try (DataOutputStream out = new DataOutputStream(this.serializeSink)) {
            NbtIo.write(tag, out);
        }
        return this.serializeSink.toByteArray();
    }

    /**
     * Parses NBT straight out of the given bytes, which may be a slice into a cached bucket
     * payload — {@link ByteArrayInputStream} wraps the range without copying, so the hot read
     * path never pays for a chunk-sized copy it would only hand to the parser.
     */
    private static CompoundTag deserialize(byte[] data, int offset, int length) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(data, offset, length)));
    }

    /**
     * The region handle for a pure read: {@code null} when no {@code .cso} exists yet. Opening with
     * CREATE (as {@link #region(ChunkPos)} does) would drop a ~16 KB empty file for every region a
     * read merely touches — litter on the write side and an outright failure on read-only storage.
     * A missing file also answers the hasBucket question for free: nothing here was ever written.
     */
    private CsoRegionFile regionIfExists(ChunkPos pos) throws IOException {
        CsoRegionFile cached = this.regions.get(CsoFormat.coordKey(pos.getRegionX(), pos.getRegionZ()));
        if (cached != null) {
            return cached; // already open — skip the filesystem stat on every read
        }
        if (!Files.isRegularFile(regionPath(pos, ".cso"))) {
            return null;
        }
        return region(pos);
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

    /**
     * Legacy handle for read-through. Null when there is no {@code .mca} to fall back to.
     *
     * <p>A file that exists but cannot be opened propagates its {@link IOException} rather
     * than being answered as absence. Answering absence is what turned a locked or
     * read-only .mca into silent terrain regeneration (reproduced in-game on 2026-10-04: a
     * read-only .mca beside live .cso storage regenerated every chunk over it, with no log
     * line at all): the seeding path treated it as "nothing to migrate", wrote the bucket
     * without it, and the bucket became authoritative over chunks that existed only there —
     * while the read path reported every chunk it held as never-generated. Propagating
     * instead means a batch about to write its first bucket stays staged and is retried on
     * the timer, so the save stalls visibly until the file opens again; a failed read
     * reaches the game the same way vanilla's own read of an unopenable region file does —
     * as an error, never as "this chunk never existed".
     */
    private RegionFile legacy(ChunkPos pos) throws IOException {
        Path path = regionPath(pos, ".mca");
        if (!Files.isRegularFile(path)) {
            return null;
        }
        long key = CsoFormat.coordKey(pos.getRegionX(), pos.getRegionZ());
        RegionFile file = this.legacyRegions.get(key);
        if (file != null) {
            return file;
        }
        file = new RegionFile(this.info, path, this.folder, this.sync);
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
