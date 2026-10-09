package tqk114514.chunkstorageoptimizer.compat.xaero;

import org.slf4j.Logger;

/**
 * The loader-independent half of the compat: the identifiers, the probe that checks whether
 * Xaero's World Map still has the shape this compat mixins into, and the one report that says
 * which state the compat is in. Each loader jar carries this class and calls it from its own
 * entrypoint.
 *
 * <p>The report goes only to the players it concerns: the compat ships nested inside the main
 * mod's jar to every player, and the entrypoints call this only when Xaero's World Map is
 * installed — the ones without it should not hear about it at all.
 */
public final class CsoXaeroCompatCore {

    /**
     * This compat mod's id, the same on every loader it ships for. Underscore, because
     * NeoForge restricts mod ids to {@code [a-z0-9_]} (Fabric would also accept hyphens —
     * the Voxy compat, which ships for Fabric only, uses one).
     */
    public static final String COMPAT_ID = "chunkstorageoptimizer_xaerocompat";

    /** Xaero's World Map's mod id, the same on every loader it ships for. */
    public static final String XAERO_WORLD_MAP = "xaeroworldmap";

    private CsoXaeroCompatCore() {
    }

    /**
     * Reports which state the compat is in. The mixins are {@code require} 0 with no
     * installed-check of their own — none is needed: their only target class is Xaero's own
     * MapSaveLoad, which never loads without Xaero's World Map, so a Xaero update that moves
     * a target makes the application fail (and require 0 swallows that) and degrades to "the
     * map records nothing from .cso" instead of crashing the game. That failure mode is
     * invisible without the log line: a foggy map looks exactly like a world nobody explored.
     */
    public static void report(Logger logger) {
        String reason = checkXaeroWorldMap();
        if (reason == null) {
            logger.info("Xaero World Map compat active: the world map detects .cso region files");
        } else {
            logger.warn("Xaero World Map compat inactive: {}. The world map runs without .cso support.", reason);
        }
    }

    /**
     * Null when the compat is fully working, otherwise what moved. Two separate questions are
     * answered here, because they fail in different worlds:
     *
     * <ul>
     * <li>do the mixin targets still exist where the mixins expect them — probing the target
     * class loads it, which applies the mixins, so a broken mixin surfaces here as the probe
     * throwing, and the catch is deliberately Throwable: a probe that crashes the game would be
     * the only way this compat ever takes a world down;
     * <li>did the mixins actually apply — a target class loaded by a loader that does not
     * transform it keeps its original shape, passes the first check and still records nothing
     * from .cso. The mixin's own members on the loaded class are the evidence: added by the
     * mixin, absent without it.
     * </ul>
     */
    private static String checkXaeroWorldMap() {
        try {
            Class<?> mapSaveLoad = Class.forName("xaero.map.file.MapSaveLoad");
            mapSaveLoad.getDeclaredMethod(
                "detectRegionsFromFiles",
                Class.forName("xaero.map.world.MapDimension"), String.class, String.class, String.class,
                java.nio.file.Path.class, String.class, int.class, int.class, int.class, int.class,
                java.util.function.Consumer.class);
            mapSaveLoad.getDeclaredMethod("getFile", Class.forName("xaero.map.region.MapRegion"));
            if (!mixinApplied(mapSaveLoad)) {
                return "the mixins did not apply — MapSaveLoad loaded unmodified by "
                    + mapSaveLoad.getClassLoader();
            }
            return null;
        } catch (Throwable e) {
            return "Xaero's MapSaveLoad does not match the expected shape (" + e + ")";
        }
    }

    /**
     * Whether the loaded target carries this compat's members. The mixin adds its injector
     * handlers and {@code @Unique} constants to the class it transforms, so one of them being
     * present means the whole mixin — both hooks, one mixin class, one application — went in;
     * require 0 would have left all of them out on a failed application.
     */
    private static boolean mixinApplied(Class<?> mapSaveLoad) {
        for (java.lang.reflect.Method method : mapSaveLoad.getDeclaredMethods()) {
            if (method.getName().startsWith("cso$")) {
                return true;
            }
        }
        for (java.lang.reflect.Field field : mapSaveLoad.getDeclaredFields()) {
            if (field.getName().contains("CSO_WORLD_SAVE_SCAN")) {
                return true;
            }
        }
        return false;
    }
}
