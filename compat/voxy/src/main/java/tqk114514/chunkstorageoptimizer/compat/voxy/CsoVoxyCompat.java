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
        String reason = checkVoxyImporter();
        if (reason == null) {
            LOGGER.info("Voxy compat active: /voxy import reads .cso region files");
        } else {
            LOGGER.warn("Voxy compat inactive: {}. The import runs without .cso support.", reason);
        }
    }

    /** Null when every mixin target is where the mixins expect it, otherwise what moved. */
    private static String checkVoxyImporter() {
        try {
            Class<?> importer = Class.forName("me.cortex.voxy.commonImpl.importers.WorldImporter");
            importer.getDeclaredMethod("importRegionDirectoryAsync", File.class);
            importer.getDeclaredMethod("importRegionFile", File.class);
            importer.getDeclaredMethod(
                "importRegion",
                Class.forName("me.cortex.voxy.common.util.MemoryBuffer"), int.class, int.class);
            return null;
        } catch (ReflectiveOperationException e) {
            return "Voxy's WorldImporter does not match the expected shape (" + e + ")";
        }
    }
}
