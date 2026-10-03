package tqk114514.chunkstorageoptimizer;

import net.neoforged.fml.config.ModConfig;

/**
 * Version seam: which config type this mod's single config is registered under.
 *
 * <p>FancyModLoader 12 — which arrived with NeoForge's 26.3 line — replaced {@code Type.COMMON} with
 * {@code Type.LOCAL}. The meaning is the same ("loaded on both servers and clients, not synced"), but
 * the constant is gone, so shared code cannot name it. The counterpart of this file lives in
 * {@code neoforge/src/version/fml12/java}; the build puts exactly one of the two on the compile path,
 * chosen by the csv's FML column.
 *
 * <p>The file name is passed explicitly where the config is registered, so the rename does not move
 * the config: it stays {@code chunkstorageoptimizer-common.toml} on every version, and an existing
 * file keeps being read.
 */
public final class CsoConfigType {

    private CsoConfigType() {
    }

    /** The type whose semantics this mod wants: present on both sides, never synced. */
    public static ModConfig.Type common() {
        return ModConfig.Type.COMMON;
    }
}
