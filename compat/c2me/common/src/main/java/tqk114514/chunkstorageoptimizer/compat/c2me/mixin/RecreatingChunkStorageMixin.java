package tqk114514.chunkstorageoptimizer.compat.c2me.mixin;

import java.nio.file.Path;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.datafixers.DataFixer;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import tqk114514.chunkstorageoptimizer.CsoRuntime;
import tqk114514.chunkstorageoptimizer.compat.c2me.CsoC2meCompatCore;

/**
 * The bridge, legacy write side: the copy a world recreation writes on 1.21 through 1.21.10.
 *
 * <p>{@code RecreatingChunkStorage} extends {@code ChunkStorage}, so its read worker is built by
 * the super-constructor and swapped by the sibling mixin; this one takes care of the
 * {@code writeWorker} the subclass builds for the copy's destination. Left to C2ME, that copy
 * would land as {@code .mca} while the session considers the destination its own — a recreated
 * world opened through the storage layer would then read half its regions back as missing.
 *
 * <p>String target for the same reason as the read side, and a six-parameter constructor: the
 * legacy pair carries no {@code DataFixTypes}, which the 26.x sibling has. Everything else is
 * the 26.x mixin's shape, including the per-destination check — a destination this mod does not
 * serve keeps C2ME's worker, and a fresh destination is served the moment it is created.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.storage.RecreatingChunkStorage")
public abstract class RecreatingChunkStorageMixin {

    @Shadow
    @Final
    @Mutable
    private IOWorker writeWorker;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cso$vanillaWriteWorkerForServedFolders(RegionStorageInfo readInfo, Path readFolder,
        RegionStorageInfo writeInfo, Path writeFolder, DataFixer fixer, boolean sync, CallbackInfo ci) {
        if (!CsoC2meCompatCore.isC2meWorker(this.writeWorker) || !CsoRuntime.isActive(writeFolder)) {
            return;
        }
        this.writeWorker = IOWorkerAccessor.cso$construct(writeInfo, writeFolder, sync);
    }
}
