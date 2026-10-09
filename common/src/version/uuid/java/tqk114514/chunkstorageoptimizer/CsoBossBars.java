package tqk114514.chunkstorageoptimizer;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;

/**
 * The 26.x shape of the boss-bar seam (see supported-versions.csv's boss column): the bar's
 * id moved from an internal field into the constructor, so creation takes it up front.
 */
public final class CsoBossBars {

    private CsoBossBars() {
    }

    public static ServerBossEvent create(Component name, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
        return new ServerBossEvent(UUID.randomUUID(), name, color, overlay);
    }
}
