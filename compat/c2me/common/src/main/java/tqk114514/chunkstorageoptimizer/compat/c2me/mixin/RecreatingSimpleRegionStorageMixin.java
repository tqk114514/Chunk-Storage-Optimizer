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

import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.RecreatingSimpleRegionStorage;

import tqk114514.chunkstorageoptimizer.CsoRuntime;
import tqk114514.chunkstorageoptimizer.compat.c2me.CsoC2meCompatCore;

/**
 * The bridge, write side: the copy a world recreation writes gets the vanilla worker too.
 *
 * <p>{@code RecreatingSimpleRegionStorage} reads one save and writes a copy of it into another
 * folder; C2ME's rewrite replaces both workers — its read half is bridged by the sibling mixin
 * through the shared super-constructor, this one takes care of the worker the subclass builds
 * for the copy. Left to C2ME over a folder this mod serves, the copy would land as {@code .mca}
 * while the session considers the very same folder its own: a recreated world opened through
 * the storage layer would then read half its regions back as missing — the split-by-copy the
 * read-side bridge alone cannot prevent. The swap is per folder again, on the copy's
 * destination: a destination this mod does not serve keeps C2ME's worker, and a fresh
 * destination is served the moment it is created, so a recreated CSO world arrives as a CSO
 * world, regions and all.
 */
@Mixin(RecreatingSimpleRegionStorage.class)
public abstract class RecreatingSimpleRegionStorageMixin {

    @Shadow
    @Final
    @Mutable
    private IOWorker writeWorker;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cso$vanillaWriteWorkerForServedFolders(RegionStorageInfo readInfo, Path readFolder,
        RegionStorageInfo writeInfo, Path writeFolder, DataFixer fixer, boolean sync, DataFixTypes type,
        CallbackInfo ci) {
        if (!CsoC2meCompatCore.isC2meWorker(this.writeWorker) || !CsoRuntime.isActive(writeFolder)) {
            return;
        }
        // The same call the subclass would have made, with the destination's own pair of
        // arguments — the worker vanilla builds for the copy, served like any other folder.
        this.writeWorker = IOWorkerAccessor.cso$construct(writeInfo, writeFolder, sync);
    }
}
