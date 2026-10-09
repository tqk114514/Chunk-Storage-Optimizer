package tqk114514.chunkstorageoptimizer.compat.xaero;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The compat mod's entrypoint. It holds no behaviour of its own — the mixins do the work —
 * and exists to report which state the compat is in, and only ever to the players it
 * concerns: the mod ships nested inside the main mod's jar to every player, and the ones
 * without Xaero's World Map should not hear about it at all.
 */
public final class CsoXaeroCompat implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("CSO Xaero Compat");

    @Override
    public void onInitialize() {
        if (!FabricLoader.getInstance().isModLoaded(CsoXaeroCompatCore.XAERO_WORLD_MAP)) {
            return;
        }
        CsoXaeroCompatCore.report(LOGGER);
    }
}
