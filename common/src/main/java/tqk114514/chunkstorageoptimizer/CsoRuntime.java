package tqk114514.chunkstorageoptimizer;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import tqk114514.chunkstorageoptimizer.storage.CsoSettings;

/**
 * Runtime state that every layer needs but no layer may own: the user's switch, the settings in
 * force, and the kill switch that overrides both.
 *
 * <p>The loader entrypoint calls {@link #install} once at mod construction. Until then the mod is
 * inactive, so a storage class created before the loader had a chance to speak up falls back to
 * vanilla Anvil rather than guessing at settings.
 *
 * <p>This indirection is what keeps {@code common} free of any loader: the NeoForge and Fabric
 * entries each read their own config format and hand the same two suppliers over.
 */
public final class CsoRuntime {

    private static volatile BooleanSupplier enabledByConfig = () -> false;
    private static volatile Supplier<CsoSettings> settingsSource = CsoSettings::defaults;
    private static volatile boolean installed;
    private static volatile boolean disabled;
    private static volatile String reason;

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

    /** Disables the custom format for the rest of the session. First call wins. */
    public static synchronized void disable(String why) {
        if (!disabled) {
            disabled = true;
            reason = why;
        }
    }

    /** True only when a loader has installed its config, the user enabled it, and nothing forced it off. */
    public static boolean isActive() {
        return installed && !disabled && enabledByConfig.getAsBoolean();
    }

    /** The settings in force, as read through the loader's config each time. */
    public static CsoSettings settings() {
        return settingsSource.get();
    }

    public static String reason() {
        if (reason != null) {
            return reason;
        }
        return installed ? "disabled by config" : "no loader installed its config";
    }
}
