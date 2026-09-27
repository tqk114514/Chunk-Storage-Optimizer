package tqk114514.chunkstorageoptimizer;

import java.util.ArrayList;
import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.storage.CsoSettings;

/**
 * Option descriptions live in the lang files as {@code <modid>.configuration.<key>.tooltip}, not
 * here: a config comment can only ever be one language, while the tooltip has a translation each.
 *
 * <p>Consequence worth knowing: NeoForge fills the on-disk {@code #} comments from the spec's own
 * comment list, so the generated {@code chunkstorageoptimizer-common.toml} carries no explanations.
 * The meanings are documented in the README table, the lang files, and the in-game
 * Mods &gt; Config screen.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
        .define("enabled", true);

    // Range rather than an allowed-value list: settings() snaps a typo down to the nearest legal
    // power of two, so a bad grid never stops the world from loading.
    public static final ModConfigSpec.IntValue GRID = BUILDER
        .defineInRange("grid", 16, 1, 32);

    // defineInList builds its validator as `acceptableValues::contains`, and ModConfigSpec queries
    // that with null while writing out the default config. List.of(...) throws NPE on a null query
    // (verified: List.of("a","b").contains(null) -> NullPointerException), so the allowed set has to
    // be a collection that answers false instead.
    private static final List<String> COMPRESSION_VALUES = new ArrayList<>(List.of("zstd", "none"));

    public static final ModConfigSpec.ConfigValue<String> COMPRESSION = BUILDER
        .defineInList("compression", "zstd", COMPRESSION_VALUES);

    public static final ModConfigSpec.IntValue ZSTD_LEVEL = BUILDER
        .defineInRange("zstdLevel", 3, 1, 22);

    public static final ModConfigSpec.IntValue CACHED_BUCKETS = BUILDER
        .defineInRange("cachedBuckets", 4, 0, 64);

    public static final ModConfigSpec.BooleanValue VERIFY_CRC = BUILDER
        .define("verifyCrc", true);

    public static final ModConfigSpec.BooleanValue FALLBACK_TO_MCA = BUILDER
        .define("fallbackToMca", true);

    public static final ModConfigSpec.LongValue COMPACTION_MIN_BYTES = BUILDER
        .defineInRange("compactionMinBytes", 4L * 1024 * 1024, 4096L, Long.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue COMPACTION_RATIO = BUILDER
        .defineInRange("compactionRatio", 0.25, 0.01, 10.0);

    public static final ModConfigSpec.IntValue BATCH_MAX_CHUNKS = BUILDER
        .defineInRange("batchMaxChunks", 16, 1, 1024);

    public static final ModConfigSpec.LongValue BATCH_MAX_DELAY_MS = BUILDER
        .defineInRange("batchMaxDelayMs", 5000L, 100L, 600000L);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public static CsoSettings settings() {
        return new CsoSettings(
            snapGrid(GRID.getAsInt()),
            compressionId(),
            ZSTD_LEVEL.getAsInt(),
            CACHED_BUCKETS.getAsInt(),
            VERIFY_CRC.getAsBoolean(),
            FALLBACK_TO_MCA.getAsBoolean(),
            COMPACTION_MIN_BYTES.getAsLong(),
            COMPACTION_RATIO.getAsDouble(),
            BATCH_MAX_CHUNKS.getAsInt(),
            BATCH_MAX_DELAY_MS.getAsLong()
        );
    }

    private static int compressionId() {
        return switch (COMPRESSION.get()) {
            case "none" -> CsoFormat.COMPRESSION_NONE;
            default -> CsoFormat.COMPRESSION_ZSTD;
        };
    }

    /**
     * Rounds down to a legal bucket grid (power of two within [1, 32]).
     *
     * <p>Rounding down rather than rejecting: a typo in the config must not stop the world from
     * loading. It just picks a somewhat smaller bucket and keeps going.
     */
    private static int snapGrid(int value) {
        return Integer.highestOneBit(Math.clamp(value, 1, 32));
    }
}
