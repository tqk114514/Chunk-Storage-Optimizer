package tqk114514.chunkstorageoptimizer.mixin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.logging.LogUtils;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import tqk114514.chunkstorageoptimizer.CsoRuntime;
import tqk114514.chunkstorageoptimizer.format.Compressor;
import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.storage.CsoOrphanedRegionGuard;
import tqk114514.chunkstorageoptimizer.storage.CsoStorage;

/**
 * Redirects {@link RegionFileStorage} to the CSO format.
 *
 * <p>This is the single choke point for all three storage types — chunks, entities and POI all
 * reach disk through {@code SimpleRegionStorage -> IOWorker -> RegionFileStorage}, so intercepting
 * this one class covers every one of them.
 *
 * <p>Every handler bails out when the storage failed to initialise (for example because the zstd
 * native library could not load), so a broken codec degrades to vanilla behaviour instead of
 * taking the world down.
 */
@Mixin(RegionFileStorage.class)
public class RegionFileStorageMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The vanilla side's open-region cache. The field's name and raw type are stable from 1.21
     * through 26.3, but its value type is not: 1.21 stores a bare {@code RegionFile}, 26.x wraps
     * each entry in an {@code Optional} so a failed open is memoized as empty. The generic is
     * erased at mixin-apply time either way, so this shadow deliberately claims nothing about
     * the values — everything that walks them goes through {@link CsoStorage#regionFileOf}.
     */
    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<?> regionCache;

    @Unique
    private CsoStorage cso$storage;

    /**
     * Latched while a conversion rewrites this folder. Set before the vanilla handles are
     * closed and cleared, so a read that arrives mid-conversion cannot reopen what the
     * conversion is replacing — through either side: this flag gates the vanilla path too,
     * which is the one a background reader (the world map's tile thread) lands on once the
     * storage is released. Volatile because the pause is set from the server thread while
     * the reads it gates arrive on the IO worker threads.
     */
    @Unique
    private volatile boolean cso$conversionPaused;

    @Unique
    private static Boolean cso$zstdAvailable;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cso$init(RegionStorageInfo info, Path folder, boolean sync, CallbackInfo ci) {
        if (!CsoRuntime.isActive(folder)) {
            cso$refuseOrphanedCso(folder, CsoRuntime.reason(folder));
            return;
        }
        if (!cso$zstdUsable()) {
            cso$refuseOrphanedCso(folder, "zstd is unavailable in this session");
            return;
        }
        try {
            this.cso$storage = new CsoStorage(info, folder, sync, CsoRuntime.settings(), new CsoStorage.VanillaHandles() {
                @Override
                public void pauseForConversion() throws IOException {
                    RegionFileStorageMixin self = RegionFileStorageMixin.this;
                    self.cso$conversionPaused = true;
                    IOException failure = null;
                    List<IOException> failures = new ArrayList<>();
                    for (Object value : self.regionCache.values()) {
                        RegionFile file = CsoStorage.regionFileOf(value);
                        if (file == null) {
                            // An empty Optional (26.x memoizes failed opens that way) or a
                            // shape this build does not know: nothing to close either way.
                            continue;
                        }
                        try {
                            file.close();
                        } catch (IOException e) {
                            failures.add(e);
                        }
                    }
                    // Cleared, not just closed: vanilla keeps closed entries in the cache,
                    // and a later use would read through a dead handle. Cleared entries
                    // reopen lazily, exactly like our own.
                    self.regionCache.clear();
                    if (!failures.isEmpty()) {
                        throw failures.get(0);
                    }
                }

                @Override
                public void resumeAfterConversion() {
                    RegionFileStorageMixin.this.cso$conversionPaused = false;
                }
            });
        } catch (Throwable t) {
            // Never let storage init kill the world: fall back to vanilla Anvil.
            LOGGER.error("Chunk Storage Optimizer failed to initialise for {}; using vanilla storage", folder, t);
            this.cso$storage = null;
            // Falling back hands the folder to vanilla Anvil — fine for a folder without .cso
            // files, but one that holds them would have every .cso region read as missing.
            cso$refuseOrphanedCso(folder, "the storage layer failed to initialise");
        }
    }

    /**
     * The fail-closed backstop for a folder this session cannot serve but the disk says it owns
     * — see {@link CsoOrphanedRegionGuard}. Every path that ends in "the custom format is off
     * for this folder" reaches it, and because this constructor also runs under mods that build
     * their own RegionFileStorage to drive RegionFile directly (C2ME's chunk IO rewrite does
     * exactly that), it covers the bypassing reader too, not just the vanilla one.
     */
    @Unique
    private static void cso$refuseOrphanedCso(Path folder, String why) {
        CsoOrphanedRegionGuard.refuseOrphanedCso(folder, why);
    }

    /**
     * The storage to serve this call, or null to let vanilla handle the folder.
     *
     * <p>A released storage still returns null here — a world that opted out mid-session has to
     * go back to its own {@code .mca} files at once, and every handler reaches this method, so
     * the release takes effect on the next chunk touched. The reference itself is kept on purpose:
     * the close handler needs it to take the storage out of the registry when the world unloads.
     * Nulling it here used to orphan every released storage in a static registry forever, and
     * the next conversion in the same game process then latched — and cast through — instances
     * of a session that no longer existed (the ClassCastException of 1.1.5, reproduced headless).
     */
    @Unique
    private CsoStorage cso$active() {
        CsoStorage storage = this.cso$storage;
        if (storage == null || storage.isReleased()) {
            return null;
        }
        return storage;
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void cso$read(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) throws IOException {
        // Gated BEFORE the storage check on purpose: a released folder is served by vanilla, and a
        // vanilla read would reopen the handle a conversion is trying to replace.
        //
        // What it must not do is answer "this chunk is not on disk". Absent is the game's
        // instruction to generate a chunk, and a chunk generated during a pause gets saved after the
        // resume, into the file the conversion had just written — which is the silent terrain loss
        // this whole storage layer is built to make impossible. So a read that arrives here waits
        // for the conversion to let go and then is served for real; only a pause that will not end
        // is refused, and refused out loud.
        if (this.cso$conversionPaused
            && CsoStorage.awaitConversionEnd(() -> this.cso$conversionPaused)) {
            LOGGER.warn("Chunk Storage Optimizer: a read of {} arrived at a folder still being"
                    + " converted on thread {}; refused rather than answered absent",
                pos, Thread.currentThread().getName());
            throw new java.io.IOException("Chunk Storage Optimizer: this region folder is being"
                + " converted and did not finish in time; refusing to answer " + pos
                + " as a missing chunk.");
        }
        CsoStorage storage = cso$active();
        if (storage == null) {
            return;
        }
        cir.setReturnValue(storage.read(pos));
        cir.cancel();
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void cso$write(ChunkPos pos, CompoundTag value, CallbackInfo ci) throws IOException {
        CsoStorage storage = cso$active();
        if (storage == null) {
            return;
        }
        storage.write(pos, value);
        ci.cancel();
    }

    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void cso$scanChunk(ChunkPos pos, StreamTagVisitor visitor, CallbackInfo ci) throws IOException {
        // The same rule as the read: a scan told "there is no chunk here" is the same lie, and a
        // caller that decides what to write from what it saw would act on it. Wait, then scan the
        // real bytes; refuse only a pause that will not end.
        if (this.cso$conversionPaused
            && CsoStorage.awaitConversionEnd(() -> this.cso$conversionPaused)) {
            LOGGER.warn("Chunk Storage Optimizer: a scan of {} arrived at a folder still being"
                    + " converted on thread {}; refused rather than answered as no chunk",
                pos, Thread.currentThread().getName());
            throw new java.io.IOException("Chunk Storage Optimizer: this region folder is being"
                + " converted and did not finish in time; refusing to scan " + pos
                + " as if it held nothing.");
        }
        CsoStorage storage = cso$active();
        if (storage == null) {
            return;
        }
        storage.scanChunk(pos, visitor);
        ci.cancel();
    }

    @Inject(method = "flush", at = @At("HEAD"), cancellable = true)
    private void cso$flush(CallbackInfo ci) throws IOException {
        CsoStorage storage = cso$active();
        if (storage == null) {
            return;
        }
        storage.flush();
        ci.cancel();
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void cso$close(CallbackInfo ci) throws IOException {
        // The reference, not cso$active(): a released storage must be closed here too —
        // this is the one moment it can leave the registry. Skipping it for released
        // storages left them registered past their world's unload, and the registry is
        // process-wide: a singleplayer client that re-enters a world carries every
        // released storage of every previous session into the next command.
        CsoStorage storage = this.cso$storage;
        if (storage == null) {
            return;
        }
        try {
            storage.close();
        } finally {
            // Only a live storage swallows the call. A released folder's files are
            // vanilla's again, so its close has to run; ours (long empty by now) does not.
            if (!storage.isReleased()) {
                ci.cancel();
            }
        }
    }

    /**
     * Probes zstd once. zstd-jni loads a native library, which is the one runtime dependency that
     * can plausibly fail inside a bundled (jarJar) mod jar.
     */
    @Unique
    private static synchronized boolean cso$zstdUsable() {
        if (cso$zstdAvailable == null) {
            try {
                Compressor probe = Compressor.create(CsoFormat.COMPRESSION_ZSTD, 3);
                byte[] round = probe.decompress(probe.compress(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}), 8);
                cso$zstdAvailable = round != null && round.length == 8;
            } catch (Throwable t) {
                LOGGER.error("Chunk Storage Optimizer: zstd is unavailable, disabling the custom format", t);
                cso$zstdAvailable = false;
            }
        }
        return cso$zstdAvailable;
    }
}
