package tqk114514.chunkstorageoptimizer.storage;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.format.CsoRegionFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * Traffic, not construction order, decides which instance owns a folder.
     *
     * <p>The registry used to assume the newcomer was the one that would serve and the incumbent was
     * another mod's throwaway. That order is not something this mod controls, and when it was wrong
     * the storage the game actually holds lost its periodic flush and dropped out of reach of the
     * pause a conversion depends on — while going on serving chunks as if nothing had happened. A
     * folder whose real writer is invisible is worse than a folder carrying an orphan, so the
     * incumbent that has served keeps the folder and the newcomer that has served nothing waits.
     */
    @Test
    void theStorageThatIsServingKeepsItsFolderWhenAnotherOneIsConstructed(@TempDir Path world)
        throws IOException {
        Path region = world.resolve("region");
        first = attachServing(region);
        first.write(new ChunkPos(0, 0), tag("traffic-before-the-orphan"));
        first.flush();

        second = attach(region);   // constructed second, has served nothing

        assertTrue(CsoRegistry.all().contains(first),
            "the instance the game is writing through keeps the folder");
        assertFalse(CsoRegistry.all().contains(second),
            "a newcomer that has served nothing does not get to retire the writer in place");
        assertEquals(1, CsoRegistry.all().stream()
            .filter(storage -> storage.folder().equals(region)).count(),
            "exactly one owner per folder, whichever order they were built in");

        // The quiesce guarantee survives: the pause reaches the storage that holds the data. It
        // writes out what was already staged on the way in — that is the pause's last chance to
        // land a batch — and then takes nothing more until the folder is handed back.
        first.write(new ChunkPos(3, 4), tag("staged-before-the-pause"));
        assertNotNull(first.read(new ChunkPos(3, 4)), "and it still serves what it was handed");
        CsoRegistry.pauseWorld(world);
        assertTrue(chunkOnDisk(region, 3, 4), "the pause flushes what was staged before it latched");

        first.write(new ChunkPos(6, 7), tag("written-into-a-quieted-folder"));
        first.flush();
        assertFalse(chunkOnDisk(region, 6, 7),
            "a folder reported quiesced must not take further writes; the batch waits");
        first.resumeAfterConversion();
        assertTrue(chunkOnDisk(region, 6, 7), "and lands once the conversion is done");
    }

    /**
     * An instance left off the list because it had served nothing claims its folder back the moment
     * it is used. Being written through is the only evidence that matters, and the game holds it,
     * not the constructor.
     */
    @Test
    void anUnlistedStorageThatStartsServingClaimsItsFolderBack(@TempDir Path world) throws IOException {
        Path region = world.resolve("region");
        first = attachServing(region);
        first.write(new ChunkPos(0, 0), tag("the-incumbent"));
        first.flush();

        second = attach(region);
        assertFalse(CsoRegistry.all().contains(second),
            "unregistered while it has served nothing");

        second.write(new ChunkPos(1, 2), tag("the-newcomer-started-serving"));
        second.flush();
        assertTrue(CsoRegistry.all().contains(second),
            "the instance the game chose to write through is reachable by the commands again");
    }

    private static CsoStorage attachServing(Path region) {
        // The longest batch delay there is, so a staged chunk stays staged until the test says
        // flush: whether the bytes reached the disk then answers only the question asked here.
        return new CsoStorage(
            new RegionStorageInfo("world", null, "region"),
            region,
            false,
            CsoSettings.normalized(16, CsoFormat.COMPRESSION_ZSTD, 3, 4, true, true,
                4L * 1024 * 1024, 0.25, 256, CsoSettings.MAX_BATCH_DELAY_MS),
            nullHandles());
    }

    /** The chunk as its writer left it, read straight off the disk by a handle that owns nothing. */
    private static boolean chunkOnDisk(Path region, int x, int z) throws IOException {
        Path file = region.resolve("r.0.0.cso");
        if (!java.nio.file.Files.isRegularFile(file)) {
            return false;
        }
        try (CsoRegionFile reader = CsoRegionFile.openReadOnly(file, 3, 4, true)) {
            return reader.readChunk(x, z) != null;
        }
    }

    private static CompoundTag tag(String marker) {
        CompoundTag chunk = new CompoundTag();
        chunk.putString("Status", "minecraft:full");
        chunk.putString("cso:test", marker);
        return chunk;
    }

    private static CsoStorage attach(Path region) {
        return new CsoStorage(
            new RegionStorageInfo("world", null, "region"),
            region,
            false,
            CsoSettings.defaults(),
            nullHandles());
    }

    private static CsoStorage.VanillaHandles nullHandles() {
        return new CsoStorage.VanillaHandles() {
            @Override
            public void pauseForConversion() {
            }

            @Override
            public void resumeAfterConversion() {
            }
        };
    }
}
