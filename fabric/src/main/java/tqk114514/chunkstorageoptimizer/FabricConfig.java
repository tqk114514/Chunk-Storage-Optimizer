package tqk114514.chunkstorageoptimizer;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.fabricmc.loader.api.FabricLoader;

import tqk114514.chunkstorageoptimizer.storage.CsoProperties;
import tqk114514.chunkstorageoptimizer.storage.CsoSettings;

/**
 * {@code config/chunkstorageoptimizer.properties} — the Fabric counterpart of NeoForge's
 * {@code chunkstorageoptimizer-common.toml}.
 *
 * <p>Fabric has no config subsystem of its own, so this class is both the storage (load, save) and
 * the live state that {@link CsoRuntime} reads through. The text format and the per-key parsing
 * rules live in {@link CsoProperties}, which is loader-free and Minecraft-free and therefore covered
 * by the unit tests in core.
 */
public final class FabricConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "chunkstorageoptimizer.properties";

    private final Path file;
    private final CsoProperties.Values values = new CsoProperties.Values();

    private FabricConfig(Path file) {
        this.file = file;
    }

    /** Reads the file when present; a first run writes the defaults out so the options are on disk. */
    public static FabricConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        FabricConfig config = new FabricConfig(file);
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file)) {
                for (String warning : CsoProperties.parse(in, config.values)) {
                    LOGGER.warn("Chunk Storage Optimizer: {}", warning);
                }
            } catch (IOException e) {
                LOGGER.warn("Chunk Storage Optimizer: could not read {}, using defaults", file, e);
            }
        } else {
            try {
                config.save();
            } catch (IOException e) {
                LOGGER.warn("Chunk Storage Optimizer: could not write {}", file, e);
            }
        }
        return config;
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, CsoProperties.format(values));
    }

    /** Where {@link #save()} writes, logged at startup so the file can be found. */
    public Path file() {
        return file;
    }

    /** The settings as the storage layer sees them: clamped, with the grid snapped. */
    public CsoSettings settings() {
        return values.settings();
    }

    public boolean enabled() {
        return values.enabled;
    }

    public void setEnabled(boolean value) {
        values.enabled = value;
    }

    public int grid() {
        return values.grid;
    }

    public void setGrid(int value) {
        values.grid = value;
    }

    public String compression() {
        return values.compression;
    }

    public void setCompression(String value) {
        values.compression = value;
    }

    public int zstdLevel() {
        return values.zstdLevel;
    }

    public void setZstdLevel(int value) {
        values.zstdLevel = value;
    }

    public int cachedBuckets() {
        return values.cachedBuckets;
    }

    public void setCachedBuckets(int value) {
        values.cachedBuckets = value;
    }

    public boolean verifyCrc() {
        return values.verifyCrc;
    }

    public void setVerifyCrc(boolean value) {
        values.verifyCrc = value;
    }

    public boolean fallbackToMca() {
        return values.fallbackToMca;
    }

    public void setFallbackToMca(boolean value) {
        values.fallbackToMca = value;
    }

    public long compactionMinBytes() {
        return values.compactionMinBytes;
    }

    public void setCompactionMinBytes(long value) {
        values.compactionMinBytes = value;
    }

    public double compactionRatio() {
        return values.compactionRatio;
    }

    public void setCompactionRatio(double value) {
        values.compactionRatio = value;
    }

    public int batchMaxChunks() {
        return values.batchMaxChunks;
    }

    public void setBatchMaxChunks(int value) {
        values.batchMaxChunks = value;
    }

    public long batchMaxDelayMs() {
        return values.batchMaxDelayMs;
    }

    public void setBatchMaxDelayMs(long value) {
        values.batchMaxDelayMs = value;
    }
}
