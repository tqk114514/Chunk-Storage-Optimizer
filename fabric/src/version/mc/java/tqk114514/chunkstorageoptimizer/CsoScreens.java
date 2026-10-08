package tqk114514.chunkstorageoptimizer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The screen-switch seam for the rows whose client still has {@code Minecraft.setScreen} —
 * everything through the 26.1 line. The 26.2+ counterpart lives in
 * {@code fabric/src/version/gui/java}; which one compiles is decided by the screen column of
 * supported-versions.csv.
 */
public final class CsoScreens {

    private CsoScreens() {
    }

    public static void open(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }
}
