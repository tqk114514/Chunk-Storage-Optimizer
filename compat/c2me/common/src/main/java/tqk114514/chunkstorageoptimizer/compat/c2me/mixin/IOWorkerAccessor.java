package tqk114514.chunkstorageoptimizer.compat.c2me.mixin;

import java.nio.file.Path;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * The vanilla worker's constructor, made callable: it is {@code protected}, and the bridge's
 * handlers live in the storage classes' mixins, not in an IOWorker subclass — so the one
 * construction the swap needs goes through this invoker instead.
 *
 * <p>The invoker is chosen over a public subclass shim on purpose: it keeps the swapped-in
 * worker's runtime type exactly {@code IOWorker}, the same object the vanilla constructor
 * call would have produced — what every instanceof on the other side of the bridge (C2ME's
 * own mixins above all) then sees is precisely what they would see without C2ME installed.
 * The stub body exists for javac; Mixin replaces it with the forwarding call at apply time.
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {

    /** The vanilla {@code new IOWorker(info, folder, dsync)} — by invoker, because it is protected. */
    @Invoker("<init>")
    static IOWorker cso$construct(RegionStorageInfo info, Path folder, boolean sync) {
        throw new AssertionError("replaced by Mixin at apply time");
    }
}
