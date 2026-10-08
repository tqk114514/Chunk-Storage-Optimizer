package tqk114514.chunkstorageoptimizer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The screen-switch seam for the rows whose client navigates through
 * {@code Minecraft.gui.setScreen} — 26.2 removed the Minecraft-level {@code setScreen} and moved
 * screen management into {@code Gui}. The pre-26.2 counterpart lives in
 * {@code fabric/src/version/mc/java}; which one compiles is decided by the screen column of
 * supported-versions.csv.
 */
public final class CsoScreens {

    private CsoScreens() {
    }

    public static void open(Minecraft minecraft, Screen screen) {
        minecraft.gui.setScreen(screen);
    }
}
