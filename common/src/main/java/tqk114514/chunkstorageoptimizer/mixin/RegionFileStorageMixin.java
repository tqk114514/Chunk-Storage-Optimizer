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

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;

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
        if (!CsoRuntime.isActive(folder) || !cso$zstdUsable()) {
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
                    for (RegionFile file : self.regionCache.values()) {
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
        }
    }

    /**
     * The storage to serve this call, or null to let vanilla handle the folder.
     *
     * <p>A released storage is forgotten here rather than kept around: a world that opted out
     * mid-session has to go back to its own {@code .mca} files at once, and every handler reaches
     * this method, so the release takes effect on the next chunk touched.
     */
    @Unique
    private CsoStorage cso$active() {
        CsoStorage storage = this.cso$storage;
        if (storage == null) {
            return null;
        }
        if (storage.isReleased()) {
            this.cso$storage = null;
            return null;
        }
        return storage;
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void cso$read(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) throws IOException {
        if (this.cso$conversionPaused) {
            // Empty, and gated BEFORE the storage check on purpose: a released folder is
            // served by vanilla, and a vanilla read would reopen the handle a conversion is
            // trying to replace. The only callers that can arrive here mid-conversion are
            // background ones — the game itself is the thread running the command.
            cir.setReturnValue(null);
            cir.cancel();
            return;
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
        if (this.cso$conversionPaused) {
            // No chunk to walk: the visitor simply is not told about one, which is what an
            // absent chunk already means here.
            ci.cancel();
            return;
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
        CsoStorage storage = cso$active();
        if (storage == null) {
            return;
        }
        storage.close();
        ci.cancel();
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
