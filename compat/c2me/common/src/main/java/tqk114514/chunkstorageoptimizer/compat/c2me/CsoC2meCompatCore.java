package tqk114514.chunkstorageoptimizer.compat.c2me;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The loader-independent core of the C2ME compatibility: the names all of its pieces agree on,
 * the class shape each build ships for, and the rule that decides whether the bridge may call
 * itself complete.
 *
 * <p>What the compat does. C2ME's chunk IO rewrite replaces the vanilla IO worker with its own
 * thread, which drives {@code RegionFile} objects directly — straight past every hook the main
 * mod has on {@code RegionFileStorage}. For a folder the main mod serves, that rewrite would
 * read {@code .mca} files that are not there while the {@code .cso} ones sit unread, and the
 * world regenerates on top of them. The compat's mixins swap the worker of a served folder back
 * to the vanilla one at the end of the storage's constructor, per folder: worlds this mod serves
 * keep their format, worlds on plain Anvil keep C2ME's rewrite.
 *
 * <p><b>Two shapes, and the build knows which.</b> Minecraft 1.21 through 1.21.10 keep chunk IO
 * on {@code ChunkStorage} ({@code ChunkMap} extends it) and entity/POI storage on
 * {@code SimpleRegionStorage}; C2ME rewrites both, so the bridge needs a mixin on each, plus
 * their recreating siblings. 1.21.11 deleted {@code ChunkStorage} and made {@code ChunkMap}
 * extend {@code SimpleRegionStorage}, so one pair covers the row — the same shape 26.x has.
 *
 * <p>Which shape a jar is for is decided when it is built, not when it runs, and written into
 * the jar as {@value #SHAPE_RESOURCE}. The runtime cannot work it out from class names: on an
 * obfuscated Fabric row the game's classes are called {@code class_9172} and friends, so a
 * named lookup answers "absent" on a row that has them. The build, meanwhile, compiles against
 * that exact game — if a class were missing, the build would have failed. Splitting the
 * question this way is what lets one source tree serve both pipelines.
 *
 * <p>Bridging only half of a legacy row is the one outcome worse than not bridging it: chunks
 * would keep C2ME's worker while entities and POI moved to ours, the log would say the bridge
 * is in place, and every {@code .cso} chunk would read as absent. So the plugin marks the
 * bridge only once <em>every</em> mixin this shape ships has bound (see
 * {@link Shape#expectedMixins()}), and a jar whose shape is unknown refuses outright.
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
     * The build-generated resource naming the class shape this jar was compiled for. Absent or
     * unreadable reads as {@link Shape#UNKNOWN}, which refuses to bridge.
     */
    public static final String SHAPE_RESOURCE = "/chunkstorageoptimizer_c2mecompat.shape.properties";

    /**
     * The worker C2ME's redirect hands the vanilla storage in place of the real IO worker. This
     * is the one string the plugin's probe and the mixins' recognition share, so the plugin can
     * never approve a C2ME whose replacement the handlers then cannot recognise. Verified against
     * every C2ME release the version matrix fetches, on both loaders and both class shapes.
     *
     * <p>Not remapped by any build: it belongs to C2ME, not to the game, so its name is the same
     * on every row and every loader.
     */
    public static final String C2ME_WORKER = "com.ishland.c2me.rewrites.chunkio.common.C2MEStorageVanillaInterface";

    /**
     * The entity/POI storage class, and the only one carrying chunk IO from 1.21.11 onwards.
     *
     * <p>Named for the mixin sources, which compile against whichever game the row selects. The
     * plugin never looks these up at runtime — see the class comment.
     */
    public static final String SIMPLE_REGION_STORAGE =
        "net.minecraft.world.level.chunk.storage.SimpleRegionStorage";

    /** The storage the recreate flow writes its copy through; the read side is the class above. */
    public static final String RECREATING_REGION_STORAGE =
        "net.minecraft.world.level.chunk.storage.RecreatingSimpleRegionStorage";

    /**
     * The chunk path on Minecraft 1.21 through 1.21.10 only — {@code ChunkMap} extends it, so
     * this is the class C2ME's rewrite hijacks for chunks on those rows. Deleted in 1.21.11.
     */
    public static final String CHUNK_STORAGE =
        "net.minecraft.world.level.chunk.storage.ChunkStorage";

    /** The legacy chunk path's copy worker, the write-side half of {@link #CHUNK_STORAGE}. */
    public static final String RECREATING_CHUNK_STORAGE =
        "net.minecraft.world.level.chunk.storage.RecreatingChunkStorage";

    /** The vanilla worker the bridge constructs in place of C2ME's replacement, on every row. */
    public static final String VANILLA_IO_WORKER =
        "net.minecraft.world.level.chunk.storage.IOWorker";

    /** Which storage classes the row this jar was built for has. */
    public enum Shape {
        /** 1.21.11 and 26.x: one class carries chunks, entities and POI. */
        MODERN(3),
        /** 1.21 through 1.21.10: chunks on ChunkStorage, entities and POI on SimpleRegionStorage. */
        LEGACY(5),
        /**
         * No shape resource in the jar. Refuses to bridge: a jar that cannot say what it was
         * built for must not guess, because guessing "modern" on a legacy row is a half bridge.
         */
        UNKNOWN(Integer.MAX_VALUE);

        private final int expectedMixins;

        Shape(int expectedMixins) {
            this.expectedMixins = expectedMixins;
        }

        /**
         * How many of this compat's mixins have to bind for the bridge to cover the row:
         * {@code IOWorkerAccessor} plus one per storage class the row has. The mixin config
         * lists both shapes' mixins on every row; the plugin approves only those whose target
         * resolves, so this is the count that must be reached.
         */
        public int expectedMixins() {
            return this.expectedMixins;
        }
    }

    /**
     * What the runtime can still answer: whether C2ME's rewrite is present, and which shape this
     * jar was built for. The vanilla classes are not probed here — the build proved them.
     */
    public record ClassPresence(boolean c2meWorker, Shape shape) {
    }

    /**
     * Whether the bridge covers every path C2ME rewrites on this row.
     *
     * <p>All-or-nothing, for the reason in the class comment. A jar of unknown shape refuses:
     * the shape is the only thing that says which mixins had to bind.
     */
    public static boolean bridgeCoversEveryRewrittenPath(ClassPresence presence) {
        return presence.c2meWorker() && presence.shape() != Shape.UNKNOWN;
    }

    /** True when this row splits chunk IO off from the entity/POI path (1.21 through 1.21.10). */
    public static boolean splitsChunkPath(ClassPresence presence) {
        return presence.shape() == Shape.LEGACY;
    }

    /**
     * What this classloader can tell us: C2ME's rewrite, and the shape the build wrote.
     *
     * <p>Lives here rather than in the mixin plugin so the probe can be exercised without Mixin
     * on the classpath.
     */
    public static ClassPresence probe() {
        return new ClassPresence(resolves(C2ME_WORKER), shape());
    }

    /** The shape this jar was built for; {@link Shape#UNKNOWN} when the resource is missing. */
    public static Shape shape() {
        return ShapeHolder.SHAPE;
    }

    /**
     * Looks for the class without defining it. This is the difference between a probe and a
     * bug: {@code Class.forName} would run the name through the transformer and DEFINE the
     * class, and a target defined before its mixins apply is a "target was loaded too early"
     * crash for every other mixin on it — C2ME's own accessor on {@code IOWorker} included,
     * measured. The resource lookup finds the same bytecode without touching the classloader's
     * defined-class set.
     *
     * <p>Mixin hands {@code shouldApplyMixin} a target name in whichever separator the target
     * was declared with — the dotted form for {@code @Mixin(Class.class)}, the internal form for
     * {@code @Mixin(targets = "...")} — so both are accepted here.
     */
    public static boolean resolves(String name) {
        String internal = name.replace('.', '/');
        return CsoC2meCompatCore.class.getClassLoader().getResource(internal + ".class") != null;
    }

    private static final class ShapeHolder {
        static final Shape SHAPE = load();

        private static Shape load() {
            try (InputStream in = CsoC2meCompatCore.class.getResourceAsStream(SHAPE_RESOURCE)) {
                if (in == null) {
                    return Shape.UNKNOWN;
                }
                Properties properties = new Properties();
                properties.load(in);
                return switch (properties.getProperty("shape", "")) {
                    case "modern" -> Shape.MODERN;
                    case "legacy" -> Shape.LEGACY;
                    default -> Shape.UNKNOWN;
                };
            } catch (IOException | RuntimeException unreadable) {
                return Shape.UNKNOWN;
            }
        }
    }

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
