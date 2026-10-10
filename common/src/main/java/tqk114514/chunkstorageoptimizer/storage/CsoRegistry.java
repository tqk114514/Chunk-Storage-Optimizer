package tqk114514.chunkstorageoptimizer.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Live {@link CsoStorage} instances, so commands can act on them.
 *
 * <p>One instance exists per storage type (region / entities / poi) per level, created lazily when
 * the game first touches that storage. Everything a command may do to the files is scoped to one
 * world: a session can hold several worlds, and the other ones are none of its business.
 */
public final class CsoRegistry {

    private static final List<CsoStorage> STORAGES = Collections.synchronizedList(new ArrayList<>());

    private CsoRegistry() {
    }

    public static void add(CsoStorage storage) {
        STORAGES.add(storage);
    }

    public static void remove(CsoStorage storage) {
        STORAGES.remove(storage);
    }

    public static List<CsoStorage> all() {
        synchronized (STORAGES) {
            return List.copyOf(STORAGES);
        }
    }

    /**
     * Flushes and closes the storages of one world. Call before rewriting region files from outside
     * the storage layer, so no file is touched while it is still open.
     */
    public static void pauseWorld(Path root) throws IOException {
        actOnWorld(root, CsoStorage::pauseForConversion);
    }

    /**
     * Ends what {@link #pauseWorld} started: the latches come off, so reads may open files
     * again and anything the pause kept staged lands in the rewritten files. The conversion
     * command calls this once its work is done — failed or not, a world must never stay
     * latched behind it.
     */
    public static void resumeWorld(Path root) throws IOException {
        actOnWorld(root, CsoStorage::resumeAfterConversion);
    }

    /**
     * Detaches one world's storages for good. After this the game's own Anvil files serve that
     * world, which is what keeps a converted world from growing new {@code .cso} files beside
     * the {@code .mca} ones it was just written into.
     */
    public static void releaseWorld(Path root) throws IOException {
        actOnWorld(root, CsoStorage::release);
    }

    /** Compacts every open region file, in every world. Returns how many were processed. */
    public static int compactAll() throws IOException {
        int count = 0;
        IOException failure = null;
        for (CsoStorage storage : all()) {
            try {
                count += storage.compactAll();
            } catch (IOException e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
        return count;
    }

    private interface OnStorage {
        void run(CsoStorage storage) throws IOException;
    }

    /**
     * Runs one action over a world's storages. The first failure is thrown once all the others have
     * been tried: leaving one store holding its files open is bad enough, but stopping at the first
     * one would leave the rest holding theirs too.
     *
     * <p>Both sides are normalised: the game reports a world root as {@code .\world\.}, which is not
     * a prefix of the {@code .\world\region} a storage reports.
     */
    private static void actOnWorld(Path root, OnStorage action) throws IOException {
        Path world = root.normalize();
        IOException failure = null;
        for (CsoStorage storage : all()) {
            if (!storage.folder().normalize().startsWith(world)) {
                continue;
            }
            try {
                action.run(storage);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
