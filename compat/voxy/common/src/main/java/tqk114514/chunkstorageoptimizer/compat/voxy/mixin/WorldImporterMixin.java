package tqk114514.chunkstorageoptimizer.compat.voxy.mixin;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import tqk114514.chunkstorageoptimizer.compat.voxy.CsoAnvilRegionSynth;
import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.format.CsoRegionFile;

/**
 * Teaches Voxy's world importer that {@code .cso} region files exist.
 *
 * <p>Three hooks, all in this one class, and none of them touch Voxy's parsing: the .cso
 * contents are materialised as an in-memory Anvil region (see {@link CsoAnvilRegionSynth}) and
 * handed to the unmodified {@code importRegion}, so decompression checks, the job queue, the
 * chunk counters and the voxelization path are all Voxy's own.
 *
 * <p>Every injector is {@code require} 0 (see the mixin config): a Voxy update that moves a
 * target disables the .cso support with a log line from the init self-check, never a crash.
 */
@Mixin(WorldImporter.class)
public abstract class WorldImporterMixin {

    @Unique
    private static final Logger CSO_LOGGER = LoggerFactory.getLogger("CSO Voxy Compat");

    @Invoker("importRegion")
    abstract void cso$importRegion(MemoryBuffer regionFile, int x, int z);

    /**
     * Widens the region listing to include {@code .cso} files. The original filter is not
     * reused: it logs an error line for every file it does not recognise, which is what .cso
     * files have been getting all along.
     */
    @Redirect(
        method = "importRegionDirectoryAsync",
        at = @At(value = "INVOKE", target = "Ljava/io/File;listFiles(Ljava/io/FilenameFilter;)[Ljava/io/File;"))
    private File[] cso$listRegionFiles(File directory, FilenameFilter vanilla) {
        File[] all = directory.listFiles();
        if (all == null) {
            return new File[0];
        }
        List<File> regions = new ArrayList<>();
        for (File file : all) {
            String name = file.getName();
            if (cso$isRegion(name, "mca") || cso$isRegion(name, "cso")) {
                regions.add(file);
            }
        }
        return regions.toArray(new File[0]);
    }

    /**
     * Keeps Voxy's deterministic file order but, for a region present in both formats, imports
     * the {@code .mca} before the {@code .cso}. Mid-migration worlds carry the stale copy in
     * the .mca; alphabetical order would import the .cso first and let the stale chunks
     * overwrite the current ones.
     */
    @Redirect(
        method = "importRegionDirectoryAsync",
        at = @At(value = "INVOKE", target = "Ljava/util/Arrays;sort([Ljava/lang/Object;Ljava/util/Comparator;)V"))
    private void cso$sortMcaBeforeCso(Object[] files, Comparator<?> vanilla) {
        Arrays.sort(files, cso$regionOrder());
    }

    /**
     * The {@code .cso} branch of the per-file import: read every chunk through the main mod's
     * format layer, synthesise an Anvil region in memory, hand it to Voxy's own importer, free
     * the buffer — the same lifecycle the {@code .mca} path gives itself.
     */
    @Inject(method = "importRegionFile", at = @At("HEAD"), cancellable = true)
    private void cso$importCsoFile(File file, CallbackInfo ci) {
        String name = file.getName();
        if (!name.endsWith(".cso")) {
            return;
        }
        ci.cancel();
        int[] regionPos = cso$regionPos(name);
        if (regionPos == null) {
            CSO_LOGGER.warn("Skipping {}: not a region file name", name);
            return;
        }
        try (CsoRegionFile region = CsoRegionFile.open(
            file.toPath(), 16, CsoFormat.COMPRESSION_ZSTD, 3, 64, true, 4194304, 0.25)) {
            CsoAnvilRegionSynth.Synthesis synthesis = CsoAnvilRegionSynth.toAnvilRegion(region);
            if (synthesis.skipped() > 0) {
                CSO_LOGGER.warn("{}: skipped {} chunk(s) too large for the Anvil format",
                    name, synthesis.skipped());
            }
            if (synthesis.anvil() == null) {
                return;
            }
            MemoryBuffer buffer = new MemoryBuffer(synthesis.anvil().length);
            try {
                buffer.asByteBuffer().put(synthesis.anvil());
                this.cso$importRegion(buffer, regionPos[0], regionPos[1]);
            } finally {
                buffer.free();
            }
        } catch (IOException | RuntimeException e) {
            CSO_LOGGER.warn("Skipping {}: could not read as .cso ({})", name, e.toString());
        }
    }

    @Unique
    private static boolean cso$isRegion(String name, String extension) {
        String[] parts = name.split("\\.");
        return parts.length == 4 && parts[0].equals("r") && parts[3].equals(extension);
    }

    /** The region coordinates from a {@code r.<x>.<z>.<ext>} name, or null when unparseable. */
    @Unique
    private static int[] cso$regionPos(String name) {
        String[] parts = name.split("\\.");
        if (!cso$isRegion(name, "cso")) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Voxy's natural file order, except that a .mca and a .cso of the same region are ordered
     * .mca first (see the sort redirect). The extension comparison only fires for files that
     * share a base name, which on a region listing is exactly such a pair.
     */
    @Unique
    private static Comparator<Object> cso$regionOrder() {
        return (a, b) -> {
            if (a instanceof File fileA && b instanceof File fileB) {
                String nameA = fileA.getName();
                String nameB = fileB.getName();
                if (nameA.equals(nameB)) {
                    return 0;
                }
                if (cso$base(nameA).equals(cso$base(nameB))) {
                    return Boolean.compare(nameA.endsWith(".cso"), nameB.endsWith(".cso"));
                }
                return nameA.compareTo(nameB);
            }
            return 0;
        };
    }

    @Unique
    private static String cso$base(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
