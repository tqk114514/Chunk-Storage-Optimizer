package tqk114514.chunkstorageoptimizer.storage;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The text form of the settings: one {@code key=value} per line, {@code #} comments allowed.
 *
 * <p>This is the format a loader without a config subsystem writes for itself, so the keys are
 * deliberately the same ones NeoForge's TOML uses. One meaning per key, one spelling per key, both
 * loaders.
 *
 * <p>Parsing is total. A line that cannot be read leaves that one key at the value it already had
 * and adds a warning naming the key; nothing here throws, and nothing here resets the rest of the
 * file. The values themselves are only type-checked — {@link CsoSettings#normalized} is what puts
 * them inside their ranges, so this class has no opinion about what a legal grid is.
 */
public final class CsoProperties {

    /** One mutable holder per key, so a screen can change a single value without rebuilding all. */
    public static final class Values {
        public boolean enabled;
        public int grid;
        public String compression;
        public int zstdLevel;
        public int cachedBuckets;
        public boolean verifyCrc;
        public boolean fallbackToMca;
        public long compactionMinBytes;
        public double compactionRatio;
        public int batchMaxChunks;
        public long batchMaxDelayMs;

        public Values() {
            CsoSettings defaults = CsoSettings.defaults();
            enabled = true;
            grid = defaults.grid();
            compression = CsoSettings.compressionName(defaults.compressionId());
            zstdLevel = defaults.level();
            cachedBuckets = defaults.cachedBuckets();
            verifyCrc = defaults.verifyCrc();
            fallbackToMca = defaults.fallbackToMca();
            compactionMinBytes = defaults.compactionMinBytes();
            compactionRatio = defaults.compactionRatio();
            batchMaxChunks = defaults.batchMaxChunks();
            batchMaxDelayMs = defaults.batchMaxDelayMs();
        }

        /** The same numbers as {@link CsoSettings}, clamped and with the grid snapped. */
        public CsoSettings settings() {
            return CsoSettings.normalized(
                grid,
                CsoSettings.compressionId(compression),
                zstdLevel,
                cachedBuckets,
                verifyCrc,
                fallbackToMca,
                compactionMinBytes,
                compactionRatio,
                batchMaxChunks,
                batchMaxDelayMs
            );
        }
    }

    private CsoProperties() {
    }

    /**
     * Reads every known key into {@code target}, leaving unknown keys alone. The returned lines
     * describe each key that could not be read; an unreadable stream is one such line rather than
     * an exception, because a config file must never be able to stop the game from starting.
     */
    public static List<String> parse(Reader reader, Values target) {
        List<String> warnings = new ArrayList<>();
        Properties source = new Properties();
        try {
            source.load(reader);
        } catch (IOException e) {
            warnings.add("the file could not be read: " + e);
            return warnings;
        }
        target.enabled = readBoolean(source, "enabled", target.enabled, warnings);
        target.grid = readInt(source, "grid", target.grid, warnings);
        target.compression = readCompression(source, "compression", target.compression, warnings);
        target.zstdLevel = readInt(source, "zstdLevel", target.zstdLevel, warnings);
        target.cachedBuckets = readInt(source, "cachedBuckets", target.cachedBuckets, warnings);
        target.verifyCrc = readBoolean(source, "verifyCrc", target.verifyCrc, warnings);
        target.fallbackToMca = readBoolean(source, "fallbackToMca", target.fallbackToMca, warnings);
        target.compactionMinBytes = readLong(source, "compactionMinBytes", target.compactionMinBytes, warnings);
        target.compactionRatio = readDouble(source, "compactionRatio", target.compactionRatio, warnings);
        target.batchMaxChunks = readInt(source, "batchMaxChunks", target.batchMaxChunks, warnings);
        target.batchMaxDelayMs = readLong(source, "batchMaxDelayMs", target.batchMaxDelayMs, warnings);
        return warnings;
    }

    /** The file text, keys in the order the screen lists them. */
    public static String format(Values values) {
        StringBuilder text = new StringBuilder()
            .append("# Chunk Storage Optimizer. Option meanings: the lang files under\n")
            .append("# assets/chunkstorageoptimizer/lang, or the Config screen in Mod Menu.\n")
            .append("# The grid applies to newly created region files only; existing files keep\n")
            .append("# the grid they were written with. Restart after editing by hand.\n")
            .append("enabled=").append(values.enabled).append('\n')
            .append("# 1, 2, 4, 8, 16 or 32; anything else snaps down to the nearest of these\n")
            .append("grid=").append(values.grid).append('\n')
            .append("# zstd or none\n")
            .append("compression=").append(values.compression).append('\n')
            .append("# 1 to 22\n")
            .append("zstdLevel=").append(values.zstdLevel).append('\n')
            .append("# 0 to 64\n")
            .append("cachedBuckets=").append(values.cachedBuckets).append('\n')
            .append("verifyCrc=").append(values.verifyCrc).append('\n')
            .append("fallbackToMca=").append(values.fallbackToMca).append('\n')
            .append("# bytes, at least 4096\n")
            .append("compactionMinBytes=").append(values.compactionMinBytes).append('\n')
            .append("# wasted/live ratio, 0.01 to 10\n")
            .append("compactionRatio=").append(values.compactionRatio).append('\n')
            .append("# 1 to 1024\n")
            .append("batchMaxChunks=").append(values.batchMaxChunks).append('\n')
            .append("# milliseconds, 100 to 600000\n")
            .append("batchMaxDelayMs=").append(values.batchMaxDelayMs).append('\n');
        return text.toString();
    }

    private static String raw(Properties source, String key) {
        String value = source.getProperty(key);
        return value == null ? null : value.trim();
    }

    private static int readInt(Properties source, String key, int current, List<String> warnings) {
        String value = raw(source, key);
        if (value == null || value.isEmpty()) {
            return current;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return keep(key, value, current, "a whole number", warnings);
        }
    }

    private static long readLong(Properties source, String key, long current, List<String> warnings) {
        String value = raw(source, key);
        if (value == null || value.isEmpty()) {
            return current;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return keep(key, value, current, "a whole number", warnings);
        }
    }

    private static double readDouble(Properties source, String key, double current, List<String> warnings) {
        String value = raw(source, key);
        if (value == null || value.isEmpty()) {
            return current;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return keep(key, value, current, "a number", warnings);
        }
    }

    private static boolean readBoolean(Properties source, String key, boolean current, List<String> warnings) {
        String value = raw(source, key);
        if (value == null || value.isEmpty()) {
            return current;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return keep(key, value, current, "true or false", warnings);
    }

    /** Anything that is not one of the two codec names is a typo, not a request for a new codec. */
    private static String readCompression(Properties source, String key, String current, List<String> warnings) {
        String value = raw(source, key);
        if (value == null || value.isEmpty()) {
            return current;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if ("zstd".equals(lower) || "none".equals(lower)) {
            return lower;
        }
        return keep(key, value, current, "zstd or none", warnings);
    }

    private static <T> T keep(String key, String value, T current, String expected, List<String> warnings) {
        warnings.add(key + ": '" + value + "' is not " + expected + ", keeping " + current);
        return current;
    }
}
