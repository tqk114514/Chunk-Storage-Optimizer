package tqk114514.chunkstorageoptimizer.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The per-world opt-out: a file named {@code cso.disabled} next to a world's {@code level.dat}.
 *
 * <p>A world converted back to Anvil must stay that way, and "stays" needs something on disk to
 * say so — the process's memory does not survive a restart, and switching the global config key off
 * would take every other world down with it. The marker travels with the save, so copying a world
 * elsewhere keeps it on vanilla storage.
 *
 * <p>The save root is found by walking up from a storage folder until a {@code level.dat} appears,
 * which covers every layout the game has shipped: {@code <root>/region}, {@code <root>/DIM-1/region}
 * and {@code <root>/dimensions/minecraft/<dim>/region}. Nothing here knows which of those it is in.
 *
 * <p>Deleting the file by hand is a supported way to change your mind.
 */
public final class CsoWorldMarker {

    public static final String FILE_NAME = "cso.disabled";
    private static final String LEVEL_DAT = "level.dat";
    /** Deepest layout is four folders under the root; one spare keeps a renamed dimension working. */
    private static final int MAX_ROOT_WALK = 5;

    private CsoWorldMarker() {
    }

    /**
     * The world a storage folder belongs to, or empty when no {@code level.dat} is nearby.
     *
     * <p>Normalised, because the game hands out roots like {@code .\world\.} and a marker written
     * under such a path would never match the {@code .\world\region} a storage reports.
     */
    public static Optional<Path> worldRoot(Path folder) {
        Path current = folder;
        for (int walked = 0; current != null && walked <= MAX_ROOT_WALK; walked++, current = current.getParent()) {
            if (Files.exists(current.resolve(LEVEL_DAT))) {
                return Optional.of(current.normalize());
            }
        }
        return Optional.empty();
    }

    public static boolean isDisabled(Path folder) {
        return worldRoot(folder).map(CsoWorldMarker::isDisabledRoot).orElse(false);
    }

    public static boolean isDisabledRoot(Path root) {
        return Files.exists(root.resolve(FILE_NAME));
    }

    /**
     * Removes the opt-out marker, if any. The running session is unaffected: the marker is
     * consulted when a world attaches, so the change takes effect at the next re-entry.
     */
    public static void clear(Path root) throws IOException {
        Files.deleteIfExists(root.resolve(FILE_NAME));
    }

    /**
     * Writes the marker and returns its path. The reason is text for whoever opens the file, and
     * the way back has to be in there too: {@code /cso convert cso} clears the marker on its
     * first run, and the conversion itself runs after a re-entry, because the storage files
     * the game already has open cannot be handed to CSO mid-session.
     */
    public static Path disable(Path root, String reason) throws IOException {
        Path marker = root.resolve(FILE_NAME);
        Files.writeString(marker, """
            Chunk Storage Optimizer does not serve this world.
            reason: %s
            written: %s
            To switch back: run /cso convert cso to clear this marker, re-enter the world, then
            run it again to move the region files onto the .cso format.
            """.formatted(reason, Instant.now().truncatedTo(ChronoUnit.SECONDS)));
        return marker;
    }
}
