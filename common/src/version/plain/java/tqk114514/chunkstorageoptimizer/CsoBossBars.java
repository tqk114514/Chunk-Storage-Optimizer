package tqk114514.chunkstorageoptimizer;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;

/**
 * The 1.21 line's shape of the boss-bar seam (see supported-versions.csv's boss column): the
 * bar's id is an internal field here, so creation takes three arguments.
 */
public final class CsoBossBars {

    private CsoBossBars() {
    }

    public static ServerBossEvent create(Component name, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
        return new ServerBossEvent(name, color, overlay);
    }
}
