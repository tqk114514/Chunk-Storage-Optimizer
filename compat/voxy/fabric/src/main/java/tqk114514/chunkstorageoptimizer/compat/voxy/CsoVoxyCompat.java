package tqk114514.chunkstorageoptimizer.compat.voxy;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The compat mod's entrypoint. It holds no behaviour of its own — the mixins do the work — and
 * exists to report which state the compat is in, and only ever to the players it concerns: the
 * mod ships nested inside the main mod's jar to every player, and the ones without Voxy should
 * not hear about it at all.
 *
 * <p>The mixins apply with {@code require} 0 behind a plugin that stands them down when Voxy is
 * absent, so a Voxy update that moves a target degrades to "the import ignores .cso" instead of
 * crashing the game. That failure mode is invisible without the line below: an import that
 * quietly misses data looks exactly like a finished one.
 */
public final class CsoVoxyCompat implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("CSO Voxy Compat");

    /** The one class this compat mixes into, named in the same spelling the mixin config uses. */
    private static final String TARGET_CLASS = "me.cortex.voxy.commonImpl.importers.WorldImporter";

    @Override
    public void onInitialize() {
        if (!FabricLoader.getInstance().isModLoaded("voxy")) {
            return;
        }
        // Nothing in here may reach the loader as a failure: the compat ships inside every
        // player's jar, and an entrypoint that throws aborts the game start. Measured on a
        // dedicated server with Voxy installed — this class used to die exactly that way, at the
        // probe below, taking the server with it.
        String reason;
        try {
            reason = checkVoxyImporter();
        } catch (Throwable unexpected) {
            LOGGER.warn("Voxy compat inactive: probing Voxy's importer raised {} ({})."
                    + " The import runs without .cso support.",
                unexpected.getClass().getName(), unexpected.getMessage());
            return;
        }
        if (reason == null) {
            LOGGER.info("Voxy compat active: /voxy import reads .cso region files");
        } else {
            LOGGER.warn("Voxy compat inactive: {}. The import runs without .cso support.", reason);
        }
    }

    /**
     * Null when the compat is live, otherwise what is wrong.
     *
     * <p>The question worth answering is "did my mixins bind", and the only honest witness to that
     * is the mixin plugin being told so at apply time ({@link CsoVoxyMixinPlugin#appliedCount()}).
     * Getting there needs the target class to be transformed, which is what this call does — and
     * it must be {@code initialize=false}: running Voxy's static initialiser here would link
     * classes this environment may not have, and the resulting {@code NoClassDefFoundError} is an
     * {@code Error}, which is exactly how this entrypoint once took a dedicated server down.
     *
     * <p>It also must not reflect over the target's members. {@code getDeclaredMethods} resolves
     * every signature type, and the importer carries a signature naming commons-compress, which
     * Voxy does not ship — measured: that reflection reported the compat inactive while the
     * mixins were binding fine. Checking the binding instead of the shape cannot produce that
     * answer, and it also closes the other half: {@code require} 0 means a shape that moved is
     * swallowed by Mixin, so only the apply-time witness says whether anything is in there.
     */
    private static String checkVoxyImporter() {
        int boundBefore = CsoVoxyMixinPlugin.appliedCount();
        Class<?> importer;
        try {
            importer = Class.forName(TARGET_CLASS, false, CsoVoxyCompat.class.getClassLoader());
        } catch (Throwable absent) {
            return "Voxy's " + TARGET_CLASS + " is not present in this Voxy build (" + absent + ")";
        }
        if (CsoVoxyMixinPlugin.appliedCount() == boundBefore) {
            return "the compat's mixins did not bind to " + TARGET_CLASS
                + ", which loaded from " + importer.getClassLoader();
        }
        return null;
    }
}
