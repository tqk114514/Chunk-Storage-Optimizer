package tqk114514.chunkstorageoptimizer.compat.voxy;

import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Decides whether the compat's mixins apply at all: they must not exist as far as Mixin is
 * concerned unless Voxy is installed.
 *
 * <p>This is the gate that lets the compat ship as a nested mod inside the main mod's jar —
 * which every player downloads, most of whom do not have Voxy. The loader-level way of doing
 * this, a {@code "voxy"} entry in {@code depends}, is not available: an unsatisfied required
 * dependency of a nested mod would fail the whole jar's load for everyone. Voxy's presence is
 * therefore checked here, and the mixins' own {@code require} 0 covers the other half — a Voxy
 * that is present but shaped differently than the mixins expect.
 */
public final class CsoVoxyMixinPlugin implements IMixinConfigPlugin {

    /**
     * The compat's mixins that have actually bound, by mixin class name. Distinct names, because
     * Mixin may ask about the same class more than once and a repeat must not stand in for a
     * missing application.
     *
     * <p>This is the evidence the compat's report is built on. The alternative — reflecting over
     * the target class to see whether its methods still look right — cannot work here and was
     * measured failing: {@code getDeclaredMethods} resolves the parameter and return types of
     * <em>every</em> declared method, and Voxy's importer carries a signature naming
     * {@code org.apache.commons.compress}, which Voxy does not ship. So the reflection answered
     * {@code NoClassDefFoundError} about an unrelated method while these mixins were binding
     * perfectly — a compat reporting itself inactive on a working install is worse than silent.
     */
    private static final Set<String> APPLIED = new java.util.HashSet<>();

    /** How many of this compat's mixins have bound to their target so far. */
    public static int appliedCount() {
        synchronized (APPLIED) {
            return APPLIED.size();
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FabricLoader.getInstance().isModLoaded("voxy");
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    /**
     * Records the binding. Mixin calls this after a mixin's members have gone into the target, so
     * one call means this compat is live on the class it hooks — which is the only claim worth
     * making at startup.
     */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        synchronized (APPLIED) {
            APPLIED.add(mixinClassName);
        }
    }
}
