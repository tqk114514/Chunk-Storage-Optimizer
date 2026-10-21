package tqk114514.chunkstorageoptimizer.compat.voxy;

import java.io.File;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The compat mod's entrypoint. It holds no behaviour of its own — the mixins do the work — and
 * exists to report which state the compat is in, and only ever to the players it concerns: the
 * mod ships nested inside the main mod's jar to every player, and the ones without Voxy should
 * not hear about it at all.
 *
 * <p>The mixins apply with {@code require} 0 behind a plugin that stands them down when Voxy is
 * absent, so a Voxy update that moves a target degrades to "the import ignores .cso" instead of
 * crashing the game. That failure mode is invisible without the line below: an import that
 * quietly misses data looks exactly like a finished one.
 */
public final class CsoVoxyCompat implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("CSO Voxy Compat");

    @Override
    public void onInitialize() {
        if (!FabricLoader.getInstance().isModLoaded("voxy")) {
            return;
        }
        // Nothing in here may reach the loader as a failure: the compat ships inside every
        // player's jar, and an entrypoint that throws aborts the game start. Measured on a
        // dedicated server with Voxy installed — this class used to die exactly that way, at the
        // probe below, taking the server with it.
        String reason;
        try {
            reason = checkVoxyImporter();
        } catch (Throwable unexpected) {
            LOGGER.warn("Voxy compat inactive: probing Voxy's importer raised {} ({})."
                    + " The import runs without .cso support.",
                unexpected.getClass().getName(), unexpected.getMessage());
            return;
        }
        if (reason == null) {
            LOGGER.info("Voxy compat active: /voxy import reads .cso region files");
        } else {
            LOGGER.warn("Voxy compat inactive: {}. The import runs without .cso support.", reason);
        }
    }

    /**
     * Null when every mixin target is where the mixins expect it, otherwise what moved.
     *
     * <p>Probed with {@code initialize=false}, because these classes are not ours to run: Voxy's
     * {@code WorldImporter} links commons-compress, which is not reachable in every environment
     * (a dedicated server is the one that showed up in practice), and running a static initialiser
     * here would raise a {@code NoClassDefFoundError} — an {@code Error}, which the reflective
     * catch below does not cover, and which the loader turns into a failed game start.
     * {@code CsoC2meCompatCore} already does this for exactly the same reason.
     *
     * <p>Method lookups are structural, so they need no initialisation either.
     */
    private static String checkVoxyImporter() {
        try {
            Class<?> importer = probe("me.cortex.voxy.commonImpl.importers.WorldImporter");
            Class<?> buffer = probe("me.cortex.voxy.common.util.MemoryBuffer");
            if (importer == null || buffer == null) {
                return "Voxy's importer classes are not reachable in this environment";
            }
            importer.getDeclaredMethod("importRegionDirectoryAsync", File.class);
            importer.getDeclaredMethod("importRegionFile", File.class);
            importer.getDeclaredMethod("importRegion", buffer, int.class, int.class);
            return null;
        } catch (ReflectiveOperationException | LinkageError moved) {
            return "Voxy's WorldImporter does not match the expected shape (" + moved + ")";
        }
    }

    /** Loads the class for inspection without running its initialiser; null when unreachable. */
    private static Class<?> probe(String name) {
        try {
            return Class.forName(name, false, CsoVoxyCompat.class.getClassLoader());
        } catch (Throwable unreachable) {
            // ClassNotFoundException is expected here, and so is a LinkageError from a class whose
            // own dependencies are absent — both mean "stand aside", never "fail the launch".
            return null;
        }
    }
}
