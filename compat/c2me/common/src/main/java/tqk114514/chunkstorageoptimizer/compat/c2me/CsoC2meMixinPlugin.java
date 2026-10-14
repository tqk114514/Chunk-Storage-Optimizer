package tqk114514.chunkstorageoptimizer.compat.c2me;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import tqk114514.chunkstorageoptimizer.CsoRuntime;

/**
 * Decides whether the compat's mixins apply at all: they must not exist as far as Mixin is
 * concerned unless every class they name is on the classpath.
 *
 * <p>This is the gate that lets the compat ship as a nested mod inside the main mod's jar —
 * which every player downloads, most of whom do not have C2ME. The loader-level way of doing
 * this, a {@code "c2me"} entry in the dependencies, is not available: an unsatisfied required
 * dependency of a nested mod would fail the whole jar's load for everyone. The probes find
 * bytecode without defining classes — {@link #resolves} is a resource lookup on purpose,
 * because a probe that defined a mixin target before its mixins applied would crash every
 * other mixin on that class, C2ME's own included.
 *
 * <p>All-or-nothing is a correctness requirement, not a style choice. The read side and the
 * write side of a served folder must swap together — a world that read through the vanilla
 * worker while C2ME's thread wrote the same regions as {@code .mca} is exactly the split
 * storage this compat exists to prevent — so one combined probe decides for every mixin, and
 * the 1.21 rows (whose vanilla classes carry different names) stand the whole compat down
 * rather than half of it.
 *
 * <p>A successful probe also marks the bridge in {@link CsoRuntime}, which is what keeps the
 * entrypoint guards from disabling the custom format for the whole session. The flag is set
 * here — before any world opens, after the classes were proven present — because the mixins
 * apply with the default {@code require}: they either install, or the game fails loudly. The
 * one way the flag could outlive a dead bridge, a mixin that applies but whose swap condition
 * never fires, would need C2ME to ship the probed class while redirecting to something else
 * entirely; the version matrix test holds that line.
 */
public final class CsoC2meMixinPlugin implements IMixinConfigPlugin {

    private Boolean bridgePossible;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (this.bridgePossible == null) {
            this.bridgePossible = everyProbedClassResolves();
            if (this.bridgePossible) {
                CsoRuntime.markC2meBridge();
            }
        }
        return this.bridgePossible;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
        IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
        IMixinInfo mixinInfo) {
    }

    private static boolean everyProbedClassResolves() {
        return resolves(CsoC2meCompatCore.C2ME_WORKER)
            && resolves(CsoC2meCompatCore.SIMPLE_REGION_STORAGE)
            && resolves(CsoC2meCompatCore.RECREATING_REGION_STORAGE)
            && resolves(CsoC2meCompatCore.VANILLA_IO_WORKER);
    }

    /**
     * Looks for the class without defining it. This is the difference between a probe and a
     * bug: {@code Class.forName} would run the name through the transformer and DEFINE the
     * class, and a target defined before its mixins apply is a "target was loaded too early"
     * crash for every other mixin on it — C2ME's own accessor on {@code IOWorker} included,
     * measured. The resource lookup finds the same bytecode without touching the classloader's
     * defined-class set.
     */
    private static boolean resolves(String name) {
        String internal = name.replace('.', '/');
        return CsoC2meMixinPlugin.class.getClassLoader().getResource(internal + ".class") != null;
    }
}
