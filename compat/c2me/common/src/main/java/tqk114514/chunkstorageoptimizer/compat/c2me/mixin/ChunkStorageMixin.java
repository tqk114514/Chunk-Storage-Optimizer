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
 * The bridge, legacy read side: the chunk storage of Minecraft 1.21 through 1.21.10.
 *
 * <p>Those rows split what 26.x keeps in one class. {@code ChunkMap} extends {@code ChunkStorage},
 * so every chunk region file is served through the {@code worker} field built here, while
 * entities and POI live on {@code SimpleRegionStorage} (see the sibling mixin). C2ME rewrites
 * both constructions, which is why the bridge needs both mixins: swapping only the
 * {@code SimpleRegionStorage} one would leave every chunk on C2ME's worker, reading {@code .mca}
 * files that a converted world does not have.
 *
 * <p>The target is a string on purpose. The compat compiles against one pinned game — 26.3 —
 * which no longer has this class at all; a {@code @Mixin(ChunkStorage.class)} reference could
 * not compile there, and even a bytecode-level reference would have to be resolved while the
 * mixin config is prepared. A string target is resolved at apply time, and only after the plugin
 * has said yes, so the same jar rides rows that have the class and rows that do not.
 *
 * <p>The constructor is the whole contract: on 1.21.1 it assigns {@code fixerUpper}, builds the
 * worker with {@code new IOWorker(info, folder, sync)} and returns — nothing else reads the
 * worker before this handler runs at the tail, so replacing the reference is enough. The
 * signature differs from the 26.x sibling by one parameter ({@code DataFixTypes} did not exist
 * on this class), which is why this is a separate mixin rather than a second injection.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.storage.ChunkStorage")
public abstract class ChunkStorageMixin {

    @Shadow
    @Final
    @Mutable
    private IOWorker worker;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cso$vanillaWorkerForServedFolders(RegionStorageInfo info, Path folder, DataFixer fixer,
        boolean sync, CallbackInfo ci) {
        if (!CsoC2meCompatCore.isC2meWorker(this.worker) || !CsoRuntime.isActive(folder)) {
            return;
        }
        // The same call vanilla would have made, with the same arguments C2ME's redirect saw:
        // through it the main mod's hooks serve the folder exactly as they would without C2ME.
        this.worker = IOWorkerAccessor.cso$construct(info, folder, sync);
    }
}
