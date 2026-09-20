package tqk114514.chunkstorageoptimizer;

/**
 * Runtime kill switch, separate from the user config.
 *
 * <p>The config says what the user wants; this says what is actually safe. A conflicting mod or a
 * missing zstd native flips this off, and the storage layer then falls back to vanilla Anvil
 * instead of risking a split world.
 */
public final class CsoRuntime {

    private static volatile boolean disabled;
    private static volatile String reason;

    private CsoRuntime() {
    }

    /** Disables the custom format for the rest of the session. First call wins. */
    public static synchronized void disable(String why) {
        if (!disabled) {
            disabled = true;
            reason = why;
        }
    }

    /** True only when the user enabled it and nothing forced it off. */
    public static boolean isActive() {
        return !disabled && Config.ENABLED.getAsBoolean();
    }

    public static String reason() {
        return reason == null ? "disabled by config" : reason;
    }
}
