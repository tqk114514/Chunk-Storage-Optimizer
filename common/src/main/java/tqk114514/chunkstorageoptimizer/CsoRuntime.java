package tqk114514.chunkstorageoptimizer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import tqk114514.chunkstorageoptimizer.storage.CsoSettings;
import tqk114514.chunkstorageoptimizer.storage.CsoWorldMarker;

/**
 * Runtime state that every layer needs but no layer may own: the user's switch, the settings in
 * force, and the two ways a world can be taken off the custom format.
 *
 * <p>The loader entrypoint calls {@link #install} once at mod construction. Until then the mod is
 * inactive, so a storage class created before the loader had a chance to speak up falls back to
 * vanilla Anvil rather than guessing at settings.
 *
 * <p>Two scopes of opt-out exist on purpose. {@link #disable} is for a conflict with the whole
 * environment (another mod rewriting chunk IO), so nothing may use the format. {@link #disableWorld}
 * is one save's own decision and is written into that save as {@code cso.disabled}, because a world
 * that was converted back to Anvil has to stay converted after a restart — and a decision about one
 * world must not reach into the others. {@link #isActive} consults both, plus the config switch.
 *
 * <p>This indirection is what keeps {@code common} free of any loader: the NeoForge and Fabric
 * entries each read their own config format and hand the same two suppliers over.
 */
public final class CsoRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile BooleanSupplier enabledByConfig = () -> false;
    private static volatile Supplier<CsoSettings> settingsSource = CsoSettings::defaults;
    private static volatile boolean installed;
    private static volatile String forcedOff;

    private CsoRuntime() {
    }

    /** Called once by the loader entrypoint. Later calls are ignored. */
    public static synchronized void install(BooleanSupplier enabledFlag, Supplier<CsoSettings> settings) {
        if (installed) {
            return;
        }
        installed = true;
        enabledByConfig = enabledFlag;
        settingsSource = settings;
    }

    /** Disables the custom format for the rest of the session, in every world. First call wins. */
    public static synchronized void disable(String why) {
        if (forcedOff == null) {
            forcedOff = why;
        }
    }

    /**
     * Takes one world off the custom format, on disk. The marker lives next to the world's
     * {@code level.dat}, so it travels with the save and survives a restart.
     *
     * <p>A marker that cannot be written still takes effect for the running game — {@link #isActive}
     * would then disagree with the disk after a restart, which is why the failure is logged as an
     * error rather than swallowed.
     */
    public static void disableWorld(Path root, String why) {
        try {
            LOGGER.info("Chunk Storage Optimizer: {} stays on vanilla Anvil storage; wrote {}", root,
                CsoWorldMarker.disable(root, why));
        } catch (IOException e) {
            LOGGER.error("Chunk Storage Optimizer: could not write the {} marker for {}, so this"
                + " world is only switched off until the game restarts", CsoWorldMarker.FILE_NAME, root, e);
        }
    }

    /** True only when this storage folder should be served by the custom format right now. */
    public static boolean isActive(Path folder) {
        return whyNot(folder) == null;
    }

    /** The settings in force, as read through the loader's config each time. */
    public static CsoSettings settings() {
        return settingsSource.get();
    }

    /** Null when the folder is served, otherwise the reason it is not; for {@code /cso stats}. */
    public static String reason(Path folder) {
        String why = whyNot(folder);
        return why == null ? "active" : why;
    }

    private static String whyNot(Path folder) {
        if (!installed) {
            return "no loader installed its config";
        }
        if (forcedOff != null) {
            return forcedOff;
        }
        if (!enabledByConfig.getAsBoolean()) {
            return "disabled by config";
        }
        Optional<Path> root = CsoWorldMarker.worldRoot(folder);
        // A folder with no level.dat above it is a world being created: nothing has opted out yet.
        if (root.isPresent() && CsoWorldMarker.isDisabledRoot(root.get())) {
            return "this world opted out (" + root.get().resolve(CsoWorldMarker.FILE_NAME) + ")";
        }
        return null;
    }
}
