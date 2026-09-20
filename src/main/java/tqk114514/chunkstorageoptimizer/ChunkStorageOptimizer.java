package tqk114514.chunkstorageoptimizer;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(ChunkStorageOptimizer.MODID)
public class ChunkStorageOptimizer {
    public static final String MODID = "chunkstorageoptimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** C2ME rewrites chunk IO wholesale and would bypass this mod's storage layer. */
    private static final String C2ME_MOD_ID = "c2me";

    public ChunkStorageOptimizer(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        if (!Config.ENABLED.getAsBoolean()) {
            LOGGER.info("Chunk Storage Optimizer disabled by config; using vanilla Anvil storage.");
            return;
        }
        if (ModList.get().isLoaded(C2ME_MOD_ID)) {
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
        LOGGER.info(
            "Chunk Storage Optimizer active: grid={}, compression={}, level={}, cachedBuckets={}, fallbackToMca={}",
            Config.GRID.getAsInt(),
            Config.COMPRESSION.get(),
            Config.ZSTD_LEVEL.getAsInt(),
            Config.CACHED_BUCKETS.getAsInt(),
            Config.FALLBACK_TO_MCA.getAsBoolean()
        );
    }
}
