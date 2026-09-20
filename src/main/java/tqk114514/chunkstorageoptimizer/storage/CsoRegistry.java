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
 * the game first touches that storage.
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
     * Flushes and closes every storage, returning the folders they manage.
     * Call before rewriting region files from outside the storage layer.
     */
    public static List<Path> pauseAll() throws IOException {
        List<Path> folders = new ArrayList<>();
        IOException failure = null;
        for (CsoStorage storage : all()) {
            try {
                folders.add(storage.pauseForConversion());
            } catch (IOException e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
        return folders;
    }

    /** Compacts every open region file. Returns how many were processed. */
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
}
