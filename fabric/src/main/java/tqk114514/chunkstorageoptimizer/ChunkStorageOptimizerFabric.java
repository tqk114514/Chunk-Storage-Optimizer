package tqk114514.chunkstorageoptimizer;

import java.io.IOException;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;

import tqk114514.chunkstorageoptimizer.commands.CsoCommands;
import tqk114514.chunkstorageoptimizer.storage.CsoSettings;

/**
 * Fabric entrypoint. Loads the properties config, hands it to {@link CsoRuntime} as two suppliers,
 * and registers {@code /cso}. Everything below this class is loader-free and shared with NeoForge
 * through the {@code common} source root.
 */
public final class ChunkStorageOptimizerFabric implements ModInitializer {

    public static final Logger LOGGER = LogUtils.getLogger();

    /** C2ME rewrites chunk IO wholesale and would bypass this mod's storage layer. */
    private static final String C2ME_MOD_ID = "c2me";

    private static volatile FabricConfig config;

    /** The config the screen edits. Empty before {@link #onInitialize()} has run. */
    public static FabricConfig config() {
        return config;
    }

    @Override
    public void onInitialize() {
        FabricConfig loaded = FabricConfig.load();
        config = loaded;
        CsoRuntime.install(loaded::enabled, loaded::settings);

        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> CsoCommands.register(dispatcher));

        if (!loaded.enabled()) {
            LOGGER.info("Chunk Storage Optimizer disabled by config; using vanilla Anvil storage.");
            return;
        }
        if (FabricLoader.getInstance().isModLoaded(C2ME_MOD_ID)) {
            if (CsoRuntime.isC2meBridge()) {
                LOGGER.info("Chunk Storage Optimizer + C2ME: the compat bridge is in place. Worlds"
                    + " this mod serves keep the vanilla chunk IO worker through it; worlds on"
                    + " plain Anvil keep C2ME's rewritten IO. Nothing to configure on either side.");
            } else {
                // C2ME's chunk IO rewrite drives RegionFile directly, past every hook this mod
                // has: it would read .mca files that are not there while the .cso ones sit
                // unread, and the world regenerates on top of them. The compat bridge that
                // swaps CSO-served worlds back to the vanilla worker did not apply — its probe
                // of C2ME's rewrite failed, most likely a C2ME release this build predates.
                // Disable rather than risk the split; the storage layer's orphaned-.cso
                // refusal then makes a converted world fail to open with an explanation
                // instead of opening into a regenerated copy of itself.
                CsoRuntime.disable("C2ME is installed");
                LOGGER.error(
                    "Chunk Storage Optimizer disabled: C2ME is installed and its chunk IO rewrite"
                        + " would drive the region files straight past this mod's storage layer, so"
                        + " a .cso world opened through it would have its terrain silently"
                        + " regenerated. The compat bridge that reconciles the two did not apply;"
                        + " update Chunk Storage Optimizer. Vanilla Anvil storage will be used, and"
                        + " converted worlds will refuse to open rather than regenerate."
                );
                return;
            }
        }
        CsoSettings settings = loaded.settings();
        LOGGER.info(
            "Chunk Storage Optimizer active: grid={}, compression={}, level={}, cachedBuckets={}, "
                + "fallbackToMca={}, config={}",
            settings.grid(),
            CsoSettings.compressionName(settings.compressionId()),
            settings.level(),
            settings.cachedBuckets(),
            settings.fallbackToMca(),
            loaded.file()
        );
    }

    /** Kept off the startup path: a config that cannot be written must not stop the game. */
    static void save() {
        try {
            config.save();
        } catch (IOException e) {
            LOGGER.error("Chunk Storage Optimizer: could not write the config", e);
        }
    }
}
