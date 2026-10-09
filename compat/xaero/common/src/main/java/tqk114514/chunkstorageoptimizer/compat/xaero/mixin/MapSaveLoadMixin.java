package tqk114514.chunkstorageoptimizer.compat.xaero.mixin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import xaero.map.MapProcessor;
import xaero.map.file.MapSaveLoad;
import xaero.map.file.RegionDetection;
import xaero.map.file.worldsave.WorldDataHandler;
import xaero.map.region.MapRegion;
import xaero.map.world.MapDimension;

/**
 * Teaches Xaero's world-save region detection that region files can be {@code .cso}.
 *
 * <p>In singleplayer the world map runs in its world-save mode, where every session starts by
 * listing the save's region folder for {@code r.X.Z.mca} files and builds its terrain from the
 * chunks behind them. A Chunk Storage Optimizer world holds its regions as {@code r.X.Z.cso},
 * so that scan finds nothing: the map starts each session with an empty region set, and
 * everything recorded before — cleared at the previous exit as derived data, "Save not
 * required for world save map" — comes back as unexplored fog. Two hooks fix it:
 *
 * <ul>
 * <li>the detection scan's pattern, widened to also accept {@code .cso}. The detected region's
 * backing file then is the {@code .cso}, whose modification time is the real freshness signal
 * the outdated checks compare against, so those need no changes of their own;
 * <li>{@code getFile}'s fallback for regions without a detected file, which resolves a vanilla
 * {@code .mca} that does not exist — pointed at the {@code .cso} sibling when one is there.
 * </ul>
 *
 * <p>The tile rebuild itself is untouched: it reads chunks through the ordinary chunk-storage
 * path, which the main mod already serves. Every injector is {@code require} 0 behind a plugin
 * that stands the mixins down when Xaero's World Map is absent, so a version of it that moves
 * a target disables the {@code .cso} support with a log line instead of crashing the game.
 */
@Mixin(MapSaveLoad.class)
public abstract class MapSaveLoadMixin {

    /** The scan Xaero's world-save mode runs over the save's region folder. */
    @Unique
    private static final String CSO_WORLD_SAVE_SCAN = "^r\\.(-{0,1}[0-9]+)\\.(-{0,1}[0-9]+)\\.mc[ar]$";

    /** The same scan, also matching the region files this mod writes. */
    @Unique
    private static final String CSO_WORLD_SAVE_SCAN_WITH_CSO =
        "^r\\.(-{0,1}[0-9]+)\\.(-{0,1}[0-9]+)\\.(mc[ar]|cso)$";

    @Shadow
    private MapProcessor mapProcessor;

    @Shadow
    public abstract void detectRegionsFromFiles(
        MapDimension mapDimension, String worldId, String dimId, String mwId, Path folder, String regex,
        int xIndex, int zIndex, int emptySize, int attempts, Consumer<RegionDetection> detectionConsumer);

    /**
     * The one change of the detection: the world-save scan's file pattern, widened to also
     * match {@code .cso}. The call is redirected at every site in {@code detectRegions} and
     * gated by the pattern here, because the other sites scan Xaero's own map folders with a
     * different pattern that must not start matching save files.
     */
    @Redirect(
        method = "detectRegions",
        at = @At(value = "INVOKE", target =
            "Lxaero/map/file/MapSaveLoad;detectRegionsFromFiles("
                + "Lxaero/map/world/MapDimension;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                + "Ljava/nio/file/Path;Ljava/lang/String;IIIILjava/util/function/Consumer;)V"))
    private void cso$detectCsoRegions(
        MapSaveLoad self, MapDimension mapDimension, String worldId, String dimId, String mwId, Path folder, String regex,
        int xIndex, int zIndex, int emptySize, int attempts, Consumer<RegionDetection> detectionConsumer) {
        if (CSO_WORLD_SAVE_SCAN.equals(regex)) {
            regex = CSO_WORLD_SAVE_SCAN_WITH_CSO;
        }
        self.detectRegionsFromFiles(
            mapDimension, worldId, dimId, mwId, folder, regex, xIndex, zIndex, emptySize, attempts, detectionConsumer);
    }

    /**
     * {@code getFile}'s fallback for a world-save region with no detected file resolves a
     * vanilla {@code r.X.Z.mca}. When this mod wrote the region instead, resolve the
     * {@code .cso} sibling; mixed worlds with a real {@code .mca} keep the vanilla behavior.
     */
    @Inject(method = "getFile", at = @At("HEAD"), cancellable = true)
    private void cso$preferCsoBackingFile(MapRegion region, CallbackInfoReturnable<File> cir) {
        if (region.getWorldId() == null || region.isNormalMapData() || region.getRegionFile() != null) {
            return;
        }
        WorldDataHandler worldDataHandler = this.mapProcessor.getWorldDataHandler();
        Path worldDir = worldDataHandler == null ? null : worldDataHandler.getWorldDir();
        if (worldDir == null) {
            return;
        }
        Path cso = worldDir.resolve("region")
            .resolve("r." + region.getRegionX() + "." + region.getRegionZ() + ".cso");
        if (Files.isRegularFile(cso)) {
            cir.setReturnValue(cso.toFile());
            cir.cancel();
        }
        // No .cso there: let the original resolve the .mca, exactly as before.
    }
}
