package tqk114514.chunkstorageoptimizer.compat.c2me;

/**
 * The loader-independent core of the C2ME compatibility: the names all of its pieces agree on.
 *
 * <p>What the compat does. C2ME's chunk IO rewrite replaces the vanilla IO worker with its own
 * thread, which drives {@code RegionFile} objects directly — straight past every hook the main
 * mod has on {@code RegionFileStorage}. For a folder the main mod serves, that rewrite would
 * read {@code .mca} files that are not there while the {@code .cso} ones sit unread, and the
 * world regenerates on top of them. The compat's mixins swap the worker of a served folder back
 * to the vanilla one at the end of the storage's constructor, per folder: worlds this mod serves
 * keep their format, worlds on plain Anvil keep C2ME's rewrite.
 *
 * <p>The three parties stay honest through this class's names. The mixin plugin refuses to let
 * the mixins exist unless every one of the probed classes resolves, so a C2ME (or a Minecraft)
 * that moved a target stands the mixins down — and the main mod's entrypoint then keeps its
 * fail-safe: disable the custom format rather than let a bypassing reader near it, with the
 * storage layer's orphaned-region refusal making a converted world fail to open with an
 * explanation instead of opening into a regenerated copy of itself.
 *
 * <p>C2ME's rewrite class is referenced by name, never linked at compile time: its jar nests the
 * module jars inside itself, so a plain compile dependency cannot even see the class — and a
 * name is also all the runtime check needs, since the bridge never calls a member on the object
 * it replaces, it only recognises it and drops it.
 */
public final class CsoC2meCompatCore {

    /** Both halves of this compat share one mod id, underscored for NeoForge's {@code [a-z0-9_]} rule. */
    public static final String COMPAT_ID = "chunkstorageoptimizer_c2mecompat";

    /**
     * The worker C2ME's redirect hands the vanilla storage in place of the real IO worker. This
     * is the one string the plugin's probe and the mixins' recognition share, so the plugin can
     * never approve a C2ME whose replacement the handlers then cannot recognise. Verified against
     * every C2ME release for every 26.x Minecraft on both loaders by the version matrix test
     * (26.1's 0.3.7 line carries the same class).
     */
    public static final String C2ME_WORKER = "com.ishland.c2me.rewrites.chunkio.common.C2MEStorageVanillaInterface";

    /**
     * The vanilla classes the mixins name, 26.x spellings. The 1.21 rows call the same classes
     * {@code VersionedChunkStorage} and {@code StorageIoWorker} and have no recreating storage;
     * there the probe fails and the mixins stand down, which keeps the guard's fail-safe in
     * charge instead of half-applying a bridge.
     */
    public static final String SIMPLE_REGION_STORAGE =
        "net.minecraft.world.level.chunk.storage.SimpleRegionStorage";

    /** The storage the recreate flow writes its copy through; the read side is the class above. */
    public static final String RECREATING_REGION_STORAGE =
        "net.minecraft.world.level.chunk.storage.RecreatingSimpleRegionStorage";

    /** The vanilla worker the bridge constructs in place of C2ME's replacement. */
    public static final String VANILLA_IO_WORKER =
        "net.minecraft.world.level.chunk.storage.IOWorker";

    /**
     * The probed class, resolved on first use — never earlier. Resolving it in this class's
     * static initialiser would define C2ME's interface (and, through its super chain, the
     * vanilla {@code IOWorker}) while mixins are still being prepared, which is the "target
     * was loaded too early" crash the plugin's resource-only probe exists to avoid. The first
     * use is the bridge handler, which runs per storage construction — by then every mixin has
     * applied and an ordinary {@code Class.forName} is safe. The holder-class idiom keeps
     * that laziness thread-safe; a class that still does not resolve answers "not a C2ME
     * worker", which skips the swap and leaves the storage vanilla's.
     */
    private static final class C2meWorkerClass {
        static final Class<?> CLASS = resolve();
    }

    private CsoC2meCompatCore() {
    }

    /** True when the object is the worker C2ME's redirect installed in place of the vanilla one. */
    public static boolean isC2meWorker(Object worker) {
        Class<?> c2meWorker = C2meWorkerClass.CLASS;
        return c2meWorker != null && c2meWorker.isInstance(worker);
    }

    private static Class<?> resolve() {
        try {
            return Class.forName(C2ME_WORKER, false, CsoC2meCompatCore.class.getClassLoader());
        } catch (Throwable absent) {
            return null;
        }
    }
}
