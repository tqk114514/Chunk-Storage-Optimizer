package tqk114514.chunkstorageoptimizer;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.storage.CsoSettings;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
        .comment(
            "Master switch. When false, the vanilla Anvil region files are used unchanged.",
            "Turning this off does NOT convert anything - existing .cso files stay on disk,",
            "and chunks stored only in them become invisible to the vanilla reader."
        )
        .define("enabled", true);

    // Deliberately NOT defineInList: handing NeoForge 26.1.2 an immutable List.of(...) as the
    // allowed-value set makes it NPE inside ValueSpec.test while building the default config
    // (ImmutableCollections$ListN.indexOf(null)). Range + snapping in settings() instead.
    public static final ModConfigSpec.IntValue GRID = BUILDER
        .comment(
            "Bucket grid edge length. A 32x32 region is split into grid x grid buckets.",
            "Larger grid = smaller buckets = lower write amplification but weaker compression.",
            "Only applies to NEWLY created region files; existing files keep their own grid.",
            "Measured on a 889 MB city save: grid 16 roughly halves write volume and write time",
            "versus grid 8, for about 7.5% more space. Grid 32 is worse than 16 — one chunk per",
            "bucket loses the benefit of sharing a compression context.",
            "  1  -> whole region is one bucket (best ratio, worst write cost; cold archives)",
            "  16 -> 4 chunks per bucket (default: lowest write cost)",
            "  8  -> 16 chunks per bucket (~7.5% smaller, ~2x the write volume of 16)"
        )
        .defineInRange("grid", 16, 1, 32);

    public static final ModConfigSpec.ConfigValue<String> COMPRESSION = BUILDER
        .comment("Compression codec: zstd or none.")
        .define("compression", "zstd");

    public static final ModConfigSpec.IntValue ZSTD_LEVEL = BUILDER
        .comment(
            "zstd level for the hot write path (1-22).",
            "3 is the default trade-off; higher levels compress better and write slower."
        )
        .defineInRange("zstdLevel", 3, 1, 22);

    public static final ModConfigSpec.IntValue CACHED_BUCKETS = BUILDER
        .comment(
            "Decompressed buckets kept in memory per region file.",
            "Higher values reduce read amplification near the player, at the cost of heap.",
            "A bucket holds up to (32/grid)^2 chunks; at grid=8 that is 16 chunks."
        )
        .defineInRange("cachedBuckets", 4, 0, 64);

    public static final ModConfigSpec.BooleanValue VERIFY_CRC = BUILDER
        .comment(
            "Verify bucket CRC32 on read. Detects corruption instead of silently returning",
            "garbage or empty chunks. Keep this on unless you are benchmarking."
        )
        .define("verifyCrc", true);

    public static final ModConfigSpec.BooleanValue FALLBACK_TO_MCA = BUILDER
        .comment(
            "When a chunk is absent from the .cso file, fall back to reading the legacy .mca.",
            "This makes the mod safe to enable on an existing world: nothing already saved is",
            "lost, and new writes gradually move to the new format."
        )
        .define("fallbackToMca", true);

    public static final ModConfigSpec.LongValue COMPACTION_MIN_BYTES = BUILDER
        .comment("Minimum wasted bytes before a region file is compacted.")
        .defineInRange("compactionMinBytes", 4L * 1024 * 1024, 4096L, Long.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue COMPACTION_RATIO = BUILDER
        .comment("Minimum wasted/live ratio before compaction (0.25 = 25% waste).")
        .defineInRange("compactionRatio", 0.25, 0.01, 10.0);

    public static final ModConfigSpec.IntValue BATCH_MAX_CHUNKS = BUILDER
        .comment(
            "Writing one chunk means recompressing its whole bucket, so writes are staged and",
            "flushed per bucket: N chunks in one bucket cost one compression, not N.",
            "This is how many chunks to stage before flushing early. Set to 1 to disable batching."
        )
        .defineInRange("batchMaxChunks", 16, 1, 1024);

    public static final ModConfigSpec.LongValue BATCH_MAX_DELAY_MS = BUILDER
        .comment(
            "Maximum time staged writes may sit in memory before being forced to disk.",
            "Bounds how much is lost if the process dies. Autosave flushes regardless."
        )
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
