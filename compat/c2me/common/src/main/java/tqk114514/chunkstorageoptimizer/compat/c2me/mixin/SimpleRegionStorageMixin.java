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
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

import tqk114514.chunkstorageoptimizer.CsoRuntime;
import tqk114514.chunkstorageoptimizer.compat.c2me.CsoC2meCompatCore;
/**
 * The bridge, read side: a folder this mod serves gets the vanilla IO worker back, per folder.
 *
 * <p>C2ME's chunk IO rewrite {@code @Redirect}s the {@code new IOWorker(...)} call inside this
 * constructor to its own replacement, which drives {@code RegionFile} objects directly — past
 * every hook the main mod has on {@code RegionFileStorage}. Left in place over a served folder,
 * it reads {@code .mca} files that are not there while the {@code .cso} ones sit unread, and the
 * world regenerates on top of them. This mixin runs at the constructor's tail and swaps the
 * worker once more, back to the exact call vanilla would have made, whenever the folder is one
 * {@link CsoRuntime#isActive} serves. Worlds on plain Anvil keep C2ME's rewrite untouched — the
 * per-folder check is the whole point, and it is what a global
 * {@code ioSystem.replaceImpl = false} in C2ME's config could never offer.
 *
 * <p>Why the tail of the constructor and not C2ME's redirect handler itself: a mixin cannot
 * reliably apply to another mod's mixin class — mixin classes are raw material to the
 * transformer, not ordinary loadable classes — so the bridge re-does the decision after C2ME
 * has made theirs, at a site the compat owns alone. The cost of losing that race is one
 * discarded worker: its constructor ran, its vanilla fields were nulled by C2ME's own mixin,
 * and it is dropped here. Two side effects of the discarded construction are deliberately not
 * this mixin's to fix — the orphaned storages it makes the main mod's registry dedupe (their
 * wrappers are gone with the worker, so nothing else would ever close them), and the vanilla
 * consecutive-executor thread its super-constructor started is the same thread C2ME leaks for
 * every worker it keeps, nulled-but-running by its own design.
 *
 * <p>The field write is what {@link Mutable} is for: {@code worker} is final in vanilla, and
 * the swap has to replace the whole reference rather than patch the object behind it, because
 * the object behind it is C2ME's class. The handler's {@code require} stays at the default on
 * purpose: a Minecraft that moves this constructor breaks the bridge silently at best, and a
 * broken bridge over a served folder is terrain regeneration, not a missing feature — the
 * game should fail the load and say so. The plugin keeps the mixin off the 1.21 rows entirely,
 * where the class this mixin targets does not exist under this name.
 *
 * <p>The tail also serves the subclass: a {@code RecreatingSimpleRegionStorage} builds its read
 * worker through this constructor, so a recreated world's source is bridged here before the
 * subclass's own half (see the sibling mixin) takes care of the copy's write side.
 */
@Mixin(SimpleRegionStorage.class)
public abstract class SimpleRegionStorageMixin {

    @Shadow
    @Final
    @Mutable
    private IOWorker worker;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cso$vanillaWorkerForServedFolders(RegionStorageInfo info, Path folder, DataFixer fixer,
        boolean sync, DataFixTypes type, CallbackInfo ci) {
        if (!CsoC2meCompatCore.isC2meWorker(this.worker) || !CsoRuntime.isActive(folder)) {
            return;
        }
        // The same call vanilla would have made, with the same arguments C2ME's redirect saw:
        // through it the main mod's hooks serve the folder exactly as they would without C2ME.
        this.worker = IOWorkerAccessor.cso$construct(info, folder, sync);
    }
}
