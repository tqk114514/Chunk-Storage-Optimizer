package tqk114514.chunkstorageoptimizer.storage;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry's one-live-storage-per-folder invariant, and what enforces it.
 *
 * <p>The game's own flow never constructs a second storage for one folder while the first is
 * live; the flow that does is another mod's throwaway worker (C2ME's chunk IO rewrite builds
 * one, the C2ME compat swaps in the real one after it), and the orphan it leaves behind has
 * no unload path at all — its wrapper went away with the worker, so nothing would ever close
 * it, its timer and its registry entry surviving forever. That is the zombie-storages shape
 * the 1.1.5 crash came from, arriving by a new road. The registry closes a superseded storage
 * on the way in; what must NOT be closed on the way in is a released one, which stays
 * registered on purpose until its world unloads — the conversion that released it still
 * needs to reach it.
 */
class CsoRegistrySupersededStorageTest {

    @BeforeAll
    static void letTheRegistryInitialize() throws ReflectiveOperationException {
        try {
            Field bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
            bootstrapped.setAccessible(true);
            bootstrapped.setBoolean(null, true);
        } catch (NoSuchFieldException absentOnThisRow) {
            // A row without the flag does not gate registrations on it either.
        }
    }

    private CsoStorage first;
    private CsoStorage second;

    @AfterEach
    void forgetStorages() throws IOException {
        // Closeable twice over: a superseded storage is already closed by the registry, and
        // close() must tolerate being the second caller — the same double-close the world
        // unload and the registry could now race to make.
        if (first != null) {
            first.close();
        }
        if (second != null) {
            second.close();
        }
    }

    @Test
    void theSecondStorageForOneFolderRetiresTheFirst(@TempDir Path world) throws IOException {
        Path region = world.resolve("region");
        first = attach(region);
        second = attach(region);

        assertTrue(CsoRegistry.all().contains(second), "the storage the wrapper will serve stays");
        assertFalse(CsoRegistry.all().contains(first),
            "the orphan the throwaway worker initialised must not outlive its replacement");
        assertEquals(1, CsoRegistry.all().stream()
            .filter(storage -> storage.folder().equals(region)).count(),
            "exactly one live storage per folder, by construction");
    }

    @Test
    void aReleasedStorageIsNeverRetired(@TempDir Path world) throws IOException {
        Path region = world.resolve("region");
        first = attach(region);
        first.release();
        second = attach(region);

        assertTrue(CsoRegistry.all().contains(first),
            "a released storage stays reachable until its world unloads — the conversion that"
                + " released it still needs to latch and unlatch its vanilla side");
        assertTrue(CsoRegistry.all().contains(second));
        assertTrue(CsoRegistry.hasReleasedStorage(world));
        assertTrue(CsoRegistry.hasLiveStorage(world));
    }

    private static CsoStorage attach(Path region) {
        return new CsoStorage(
            new RegionStorageInfo("world", null, "region"),
            region,
            false,
            CsoSettings.defaults(),
            new CsoStorage.VanillaHandles() {
                @Override
                public void pauseForConversion() {
                }

                @Override
                public void resumeAfterConversion() {
                }
            }
        );
    }
}
