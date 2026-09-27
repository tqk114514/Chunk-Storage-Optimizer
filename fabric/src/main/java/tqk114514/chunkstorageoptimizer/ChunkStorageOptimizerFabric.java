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
            // C2ME's ioSystem.replaceImpl takes over region IO. If it reads .mca while we write
            // .cso, the world splits and terrain silently regenerates. Refuse to take that risk.
            CsoRuntime.disable("C2ME is installed");
            LOGGER.error(
                "Chunk Storage Optimizer disabled: C2ME is installed and would bypass this mod, "
                    + "which can split a world between .mca and .cso. To use both, set "
                    + "ioSystem.replaceImpl=false in config/c2me.toml, then remove this mod's guard "
                    + "only if you understand the risk. Vanilla Anvil storage will be used."
            );
            return;
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
