package tqk114514.chunkstorageoptimizer.compat.c2me;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import tqk114514.chunkstorageoptimizer.CsoRuntime;

/**
 * Decides whether the compat's mixins apply at all, and only then whether the bridge is complete.
 *
 * <p>This is the gate that lets the compat ship as a nested mod inside the main mod's jar —
 * which every player downloads, most of whom do not have C2ME. The loader-level way of doing
 * this, a {@code "c2me"} entry in the dependencies, is not available: an unsatisfied required
 * dependency of a nested mod would fail the whole jar's load for everyone. The probe finds
 * bytecode without defining classes — {@link CsoC2meCompatCore#resolves} is a resource lookup on
 * purpose, because a probe that defined a mixin target before its mixins applied would crash
 * every other mixin on that class, C2ME's own included.
 *
 * <p><b>Two questions, in order.</b> First, may this jar bridge at all: C2ME's rewrite is here,
 * and the build wrote a shape it understands (see {@link CsoC2meCompatCore.Shape}). If not, no
 * mixin is applied. Second, has the bridge actually covered the row: Mixin prepares every mixin
 * in a config at bootstrap and asks this plugin about each one, so the approved set can be
 * counted against {@link CsoC2meCompatCore.Shape#expectedMixins()} — the accessor plus one per
 * storage class the row has. Only when that count is reached is the bridge marked in
 * {@link CsoRuntime}, the flag the loader entrypoints consult before choosing between
 * coexistence and the fail-safe disable.
 *
 * <p>Counting is what makes a half bridge impossible. The mixin config lists both shapes on
 * every row; the legacy pair's targets simply do not resolve on a modern row, so three bind
 * there and five on a legacy row. If a target that should have resolved did not — a remap that
 * produced a name the game does not have, say — the count stays short, the flag stays false, and
 * the entrypoint disables the format instead of leaving chunks on C2ME's worker while entities
 * and POI move to ours.
 *
 * <p>The count is over <em>distinct</em> mixin names: Mixin may consult the plugin more than once
 * for the same class, and a repeated call must not stand in for a missing one.
 */
public final class CsoC2meMixinPlugin implements IMixinConfigPlugin {

    private final Set<String> approved = new HashSet<>();
    private Boolean bridgePossible;
    private boolean marked;

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
            this.bridgePossible = CsoC2meCompatCore.bridgeCoversEveryRewrittenPath(CsoC2meCompatCore.probe());
        }
        if (!this.bridgePossible) {
            return false;
        }
        if (!CsoC2meCompatCore.resolves(targetClassName)) {
            // A mixin whose target this row does not have: the other shape's half. Not an error,
            // and deliberately not counted — the expected count is what the row's shape needs.
            return false;
        }
        if (this.approved.add(mixinClassName) && !this.marked
            && this.approved.size() >= CsoC2meCompatCore.shape().expectedMixins()) {
            this.marked = true;
            CsoRuntime.markC2meBridge();
        }
        return true;
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
}
