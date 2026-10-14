package tqk114514.chunkstorageoptimizer;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import tqk114514.chunkstorageoptimizer.commands.CsoCommands;

@Mod(ChunkStorageOptimizer.MODID)
public class ChunkStorageOptimizer {
    public static final String MODID = "chunkstorageoptimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** C2ME rewrites chunk IO wholesale and would bypass this mod's storage layer. */
    private static final String C2ME_MOD_ID = "c2me";

    /**
     * Given explicitly rather than left to the config type, which decides it otherwise.
     *
     * <p>FancyModLoader 12 renamed that type — {@code COMMON} became {@code LOCAL} — and the name is
     * what the default file name is built from, so leaving it implicit would have moved the file to
     * {@code chunkstorageoptimizer-local.toml} on 26.3 and silently stopped reading the existing one.
     * See {@link CsoConfigType} for the seam itself.
     */
    private static final String CONFIG_FILE = "chunkstorageoptimizer-common.toml";

    public ChunkStorageOptimizer(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(CsoConfigType.common(), Config.SPEC, CONFIG_FILE);
        // Everything below the loader reads config through these two suppliers, so the storage and
        // command layers never name a ModConfigSpec.
        CsoRuntime.install(Config.ENABLED::getAsBoolean, Config::settings);
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> CsoCommands.register(event.getDispatcher()));
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        if (!Config.ENABLED.getAsBoolean()) {
            LOGGER.info("Chunk Storage Optimizer disabled by config; using vanilla Anvil storage.");
            return;
        }
        if (ModList.get().isLoaded(C2ME_MOD_ID)) {
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
