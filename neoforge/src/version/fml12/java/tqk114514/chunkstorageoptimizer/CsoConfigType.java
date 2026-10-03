package tqk114514.chunkstorageoptimizer;

import net.neoforged.fml.config.ModConfig;

/**
 * Version seam: which config type this mod's single config is registered under.
 *
 * <p>FancyModLoader 12 renamed {@code Type.COMMON} to {@code Type.LOCAL} — the documentation is
 * explicit that it means the same thing, "Local mod config for configuration that needs to be loaded
 * on both environments ... not synced". The old name no longer exists to compile against. The
 * counterpart of this file lives in {@code neoforge/src/version/fml11/java}.
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
        return ModConfig.Type.LOCAL;
    }
}
