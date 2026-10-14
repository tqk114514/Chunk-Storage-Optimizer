package tqk114514.chunkstorageoptimizer.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The fail-closed backstop for a folder this session cannot serve but the disk says it owns.
 *
 * <p>Vanilla Anvil storage answers a {@code .cso} region with "no such file" and regenerates the
 * terrain on top of it — silently and irreversibly. Every path that ends in "the custom format is
 * off for this folder" reaches {@link #refuseOrphanedCso}: the C2ME guard's session-wide disable,
 * the config switch, a zstd native that failed to load, a storage layer that threw while
 * initialising — and, because the RegionFileStorage constructor runs under mods that build their
 * own worker to drive RegionFile directly (C2ME's chunk IO rewrite does exactly that), it covers
 * the bypassing reader too, not just the vanilla one.
 *
 * <p>Folders that never met the format are untouched: no {@code .cso} files, no refusal. A world
 * that converted back never meets it either — the conversion deletes its {@code .cso} files
 * before the opt-out marker is written, so the refusal cannot strand a world that legitimately
 * left the format. What it strands is exactly the case that used to lose data: a converted world
 * opened through a storage stack that cannot read it.
 *
 * <p>The exception unwinds the whole worker construction, so the world fails to open with this
 * message in the log rather than opening into a regenerated copy of itself. Loud beats quiet: the
 * alternative was the silent terrain loss the storage layer exists to make impossible.
 */
public final class CsoOrphanedRegionGuard {

    private CsoOrphanedRegionGuard() {
    }

    /**
     * Throws when the folder holds {@code .cso} files this session will not serve.
     *
     * @param folder the region folder about to be handed to a worker
     * @param why what keeps this session from serving it, phrased for the log
     */
    public static void refuseOrphanedCso(Path folder, String why) {
        if (!hasCsoFiles(folder)) {
            return;
        }
        throw new IllegalStateException("Chunk Storage Optimizer: " + folder + " contains .cso"
            + " region files, but this session is not serving them (" + why + "). Opening the"
            + " folder through vanilla Anvil would treat every .cso region as missing and"
            + " silently regenerate its terrain, which cannot be undone. Refusing to continue."
            + " Re-enable the storage layer — remove the conflicting mod or setting that"
            + " disabled it — enter the world once more, and convert back with /cso convert-back"
            + " before switching storage again.");
    }

    /** True when the folder holds at least one {@code .cso} file; a missing folder holds none. */
    public static boolean hasCsoFiles(Path folder) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.cso")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }
}
