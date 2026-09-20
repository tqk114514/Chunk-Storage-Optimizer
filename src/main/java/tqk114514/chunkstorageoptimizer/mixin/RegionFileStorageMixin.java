package tqk114514.chunkstorageoptimizer.mixin;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.logging.LogUtils;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import tqk114514.chunkstorageoptimizer.Config;
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

    @Unique
    private CsoStorage cso$storage;

    @Unique
    private static Boolean cso$zstdAvailable;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cso$init(RegionStorageInfo info, Path folder, boolean sync, CallbackInfo ci) {
        if (!CsoRuntime.isActive() || !cso$zstdUsable()) {
            return;
        }
        try {
            this.cso$storage = new CsoStorage(info, folder, sync, Config.settings());
        } catch (Throwable t) {
            // Never let storage init kill the world: fall back to vanilla Anvil.
            LOGGER.error("Chunk Storage Optimizer failed to initialise for {}; using vanilla storage", folder, t);
            this.cso$storage = null;
        }
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void cso$read(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) throws IOException {
        if (this.cso$storage == null) {
            return;
        }
        cir.setReturnValue(this.cso$storage.read(pos));
        cir.cancel();
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void cso$write(ChunkPos pos, CompoundTag value, CallbackInfo ci) throws IOException {
        if (this.cso$storage == null) {
            return;
        }
        this.cso$storage.write(pos, value);
        ci.cancel();
    }

    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void cso$scanChunk(ChunkPos pos, StreamTagVisitor visitor, CallbackInfo ci) throws IOException {
        if (this.cso$storage == null) {
            return;
        }
        this.cso$storage.scanChunk(pos, visitor);
        ci.cancel();
    }

    @Inject(method = "flush", at = @At("HEAD"), cancellable = true)
    private void cso$flush(CallbackInfo ci) throws IOException {
        if (this.cso$storage == null) {
            return;
        }
        this.cso$storage.flush();
        ci.cancel();
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void cso$close(CallbackInfo ci) throws IOException {
        if (this.cso$storage == null) {
            return;
        }
        this.cso$storage.close();
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
