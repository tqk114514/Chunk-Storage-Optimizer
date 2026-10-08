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

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
