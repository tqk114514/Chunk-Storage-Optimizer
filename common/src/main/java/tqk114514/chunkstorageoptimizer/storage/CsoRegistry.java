package tqk114514.chunkstorageoptimizer.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * Live {@link CsoStorage} instances, so commands can act on them.
 *
 * <p>One instance exists per storage type (region / entities / poi) per level, created lazily when
 * the game first touches that storage. Everything a command may do to the files is scoped to one
 * world: a session can hold several worlds, and the other ones are none of its business.
 *
 * <p>Membership is what makes a storage reachable: the periodic flush that bounds a staged batch,
 * {@code /cso compact}, and the pause every conversion depends on all go through this list. So the
 * list must never lose the instance the game is actually writing through, and that is the rule
 * {@link #add} enforces — traffic decides the owner, not construction order.
 */
public final class CsoRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<CsoStorage> STORAGES = Collections.synchronizedList(new ArrayList<>());

    private CsoRegistry() {
    }

    /**
     * Registers a freshly built storage, unless the folder already has one that has served traffic.
     *
     * <p>The flow that produces two storages for one folder is another mod's throwaway worker:
     * C2ME's chunk IO rewrite builds worker objects it then discards (the C2ME compat swaps in the
     * real one), and each throwaway builds a {@code RegionFileStorage} of its own, which initialises
     * a storage of ours on the way through. Which of the two is the throwaway is not knowable from
     * the order they are constructed in — that was the old assumption, and when it was wrong the
     * registry lost the instance the game still held: its batch sat in memory with no timer, and a
     * conversion was told the folder was quiet while that storage went on writing into it.
     *
     * <p>Traffic answers it instead. A throwaway never serves a chunk, and the incumbent that has
     * served is the one the game holds — so the incumbent keeps the folder and the newcomer is left
     * unregistered rather than retiring it. An unregistered storage that does start serving claims
     * itself back through {@link #adopt}, because being used is the only evidence that matters.
     *
     * @return true when this storage is on the list and owns its folder
     */
    public static boolean add(CsoStorage storage) {
        Path folder = storage.folder().normalize();
        for (CsoStorage existing : all()) {
            if (existing.isReleased() || !existing.folder().normalize().equals(folder)) {
                // Released storages stay registered on purpose until their world unloads; the
                // conversion that released them still needs to reach them.
                continue;
            }
            if (existing.hasServedTraffic()) {
                return false;
            }
            // A registered storage that has served nothing is the throwaway: nothing else would
            // ever close it, because its wrapper went with the worker it came from, and its timer
            // and registry entry would survive forever — the zombie-storages shape the 1.1.5 crash
            // came from. Closing it cancels the timer and drops the entry; it has no staged data
            // and no open handle to lose.
            retire(existing, folder);
        }
        STORAGES.add(storage);
        return true;
    }

    /**
     * Puts a storage back on the list because it is being used. Called by a storage that {@link #add}
     * left unregistered, from its own entry points, so a folder's real writer always ends up
     * reachable no matter which instance the game decided to hold on to.
     */
    static void adopt(CsoStorage storage) {
        STORAGES.add(storage);
    }

    private static void retire(CsoStorage existing, Path folder) {
        try {
            existing.close();
        } catch (IOException e) {
            LOGGER.error("Chunk Storage Optimizer: could not retire a superseded storage for {}",
                folder, e);
        }
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
     * True when this session has already taken the world off the custom format: its storages
     * were released, so the game is writing {@code .mca} into the very folders a conversion
     * back to {@code .cso} would be rewriting. Only a re-entry can hand the folders back —
     * the world unload is what removes a released storage from here — so the conversion
     * command asks with this before starting one and refuses while the answer is yes.
     */
    public static boolean hasReleasedStorage(Path root) {
        Path world = root.normalize();
        for (CsoStorage storage : all()) {
            if (storage.isReleased() && storage.folder().normalize().startsWith(world)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when this session is actually serving the world through the custom format: at least
     * one of its storages is live. The attach decision is made once per storage when the game
     * constructs it, so a session that began while the world was opted out has none — clearing
     * the marker mid-session changes the disk, not that session, and converting {@code .cso}
     * then would run beside the session's own open vanilla handles. The command asks with this
     * and answers "re-enter the world" while it is false.
     */
    public static boolean hasLiveStorage(Path root) {
        Path world = root.normalize();
        for (CsoStorage storage : all()) {
            if (!storage.isReleased() && storage.folder().normalize().startsWith(world)) {
                return true;
            }
        }
        return false;
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
