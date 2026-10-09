package tqk114514.chunkstorageoptimizer.compat.xaero;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The compat mod's entrypoint — the NeoForge counterpart of the Fabric half's ModInitializer.
 * It holds no behaviour of its own — the mixins do the work — and exists to report which state
 * the compat is in, and only ever to the players it concerns: the mod ships nested inside the
 * main mod's jar to every player, and the ones without Xaero's World Map should not hear about
 * it at all.
 *
 * <p>The report deliberately does not run in the constructor: {@code ModList.isLoaded} only
 * settles once every mod has been constructed, and Xaero's World Map initializes early enough
 * that a report asked for during construction would say "not installed" on machines that have
 * it (measured). Client setup runs after every mod has settled. The mixins themselves need no
 * scheduling: they are require 0 with no installed-check, and their only target class loads
 * solely when Xaero's World Map does.
 */
@Mod(CsoXaeroCompatCore.COMPAT_ID)
public final class CsoXaeroCompatNeoForge {

    private static final Logger LOGGER = LoggerFactory.getLogger("CSO Xaero Compat");

    public CsoXaeroCompatNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(this::clientSetup);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        if (!ModList.get().isLoaded(CsoXaeroCompatCore.XAERO_WORLD_MAP)) {
            return;
        }
        CsoXaeroCompatCore.report(LOGGER);
    }
}
