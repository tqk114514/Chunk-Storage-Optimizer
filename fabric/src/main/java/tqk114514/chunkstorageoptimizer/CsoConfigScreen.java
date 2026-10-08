package tqk114514.chunkstorageoptimizer;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The config screen Mod Menu opens for this mod.
 *
 * <p>Every option is a button that cycles through the values that option accepts, and a click
 * writes the file immediately. That keeps this screen to the two widget calls that have not changed
 * shape across the Minecraft versions the mod ships for ({@code Button.builder} and
 * {@code Screen.addRenderableWidget}) plus the one navigation call, which lives behind the
 * per-row {@link CsoScreens} seam because the 26.2 client moved {@code Minecraft.setScreen} to
 * {@code Minecraft.gui.setScreen} — no text fields, no custom rendering, and nothing else to
 * re-touch per Minecraft version. Values outside the cycle list can still be written by hand in
 * the properties file; the screen shows the nearest listed value for them.
 *
 * <p>Names and descriptions are the same lang keys the NeoForge config screen uses
 * ({@code <modid>.configuration.<key>}), so both loaders are translated by one edit.
 */
public final class CsoConfigScreen extends Screen {

    private static final String LANG = "chunkstorageoptimizer.configuration.";
    private static final int OPTION_WIDTH = 170;
    private static final int OPTION_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int COLUMN_GAP = 6;
    private static final int TOP = 40;
    private static final int ROWS = 6;

    private final Screen parent;

    public CsoConfigScreen(Screen parent) {
        super(Component.translatable(LANG + "title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int blockWidth = 2 * OPTION_WIDTH + COLUMN_GAP;
        int left = (this.width - blockWidth) / 2;
        int right = left + OPTION_WIDTH + COLUMN_GAP;
        FabricConfig config = ChunkStorageOptimizerFabric.config();

        addOption(left, TOP, "enabled", List.of(true, false), config::enabled, config::setEnabled);
        addOption(left, row(1), "grid", List.of(1, 2, 4, 8, 16, 32), config::grid, config::setGrid);
        addOption(left, row(2), "compression", List.of("zstd", "none"), config::compression, config::setCompression);
        addOption(left, row(3), "zstdLevel", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 15, 18, 22),
            config::zstdLevel, config::setZstdLevel);
        addOption(left, row(4), "cachedBuckets", List.of(0, 1, 2, 4, 8, 16, 32, 64),
            config::cachedBuckets, config::setCachedBuckets);
        addOption(left, row(5), "verifyCrc", List.of(true, false), config::verifyCrc, config::setVerifyCrc);
        addOption(right, TOP, "fallbackToMca", List.of(true, false), config::fallbackToMca, config::setFallbackToMca);
        addOption(right, row(1), "compactionMinBytes",
            List.of(4096L, 65536L, 1048576L, 4194304L, 16777216L, 67108864L),
            config::compactionMinBytes, config::setCompactionMinBytes);
        addOption(right, row(2), "compactionRatio", List.of(0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 4.0),
            config::compactionRatio, config::setCompactionRatio);
        addOption(right, row(3), "batchMaxChunks", List.of(1, 2, 4, 8, 16, 32, 64, 256, 1024),
            config::batchMaxChunks, config::setBatchMaxChunks);
        addOption(right, row(4), "batchMaxDelayMs",
            List.of(100L, 500L, 1000L, 2000L, 5000L, 10000L, 30000L, 60000L),
            config::batchMaxDelayMs, config::setBatchMaxDelayMs);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> close())
            .bounds(left, row(ROWS) + 8, blockWidth, OPTION_HEIGHT)
            .build());
    }

    private static int row(int index) {
        return TOP + index * (OPTION_HEIGHT + ROW_GAP);
    }

    private <T> void addOption(int x, int y, String key, List<T> choices, Supplier<T> current, Consumer<T> apply) {
        Component name = Component.translatable(LANG + key);
        addRenderableWidget(Button.builder(text(name, current.get()), button -> {
            apply.accept(next(choices, current.get()));
            ChunkStorageOptimizerFabric.save();
            button.setMessage(text(name, current.get()));
        })
            .bounds(x, y, OPTION_WIDTH, OPTION_HEIGHT)
            .tooltip(Tooltip.create(Component.translatable(LANG + key + ".tooltip")))
            .build());
    }

    private static Component text(Component name, Object value) {
        return name.plainCopy().append(": ").append(String.valueOf(value));
    }

    /**
     * Advances one step, wrapping at the end. A hand-written value that is not in the list sits at
     * index -1, so the first click lands on the list's first entry.
     */
    private static <T> T next(List<T> choices, T current) {
        return choices.get((choices.indexOf(current) + 1) % choices.size());
    }

    private void close() {
        if (this.minecraft != null) {
            CsoScreens.open(this.minecraft, parent);
        }
    }

    @Override
    public void onClose() {
        close();
    }
}
