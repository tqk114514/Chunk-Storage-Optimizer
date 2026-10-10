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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conversion lifecycle's state machine, the way the command drives it: live, released
 * (in-session opt-out), latched (conversion in flight), and the unload that must forget a
 * storage for good.
 *
 * <p>The zombie half of the 1.1.6 crash lived in the mixin — {@code cso$active} nulled the
 * reference {@code cso$close} needed, so a released storage never left this registry and the
 * next command in the same game process latched a dead instance. This test pins the registry
 * and storage side of that contract: the gates the command consults, the latch the pause
 * holds, the staging a latched write defers, and the removal the close owes. The mixin's half
 * — that the game's own close actually reaches {@code storage.close()} for a released
 * storage — is only reachable inside the transformed game, and belongs to the headless
 * same-process smoke sequence instead.
 */
class CsoStorageLifecycleTest {

    /** Records the vanilla side's latch, so the tests can see what the command's finally relies on. */
    private static final class RecordingHandles implements CsoStorage.VanillaHandles {
        int pauses;
        int resumes;

        @Override
        public void pauseForConversion() {
            pauses++;
        }

        @Override
        public void resumeAfterConversion() {
            resumes++;
        }
    }

    private CsoStorage storage;
    private RecordingHandles handles;

    /**
     * Lets {@link ChunkPos} static-initialise on a plain JVM. Between 1.21.2 and 26.1 its
     * initialisation pulls {@code ChunkPyramid -> ChunkStatus -> BuiltInRegistries}, and the
     * first registry registration refuses to run outside a bootstrapped game ("Not
     * bootstrapped", called from the game_event registry) — the check is a single static
     * flag, and that is all this suite needs: no registry content, no datafixers, no version
     * detection, none of which would even work here, since the fabric game's own bootstrap
     * wants version detection first and NeoForge's patched {@code SharedConstants} demands a
     * live FML loader the moment its class loads. Faking the flag lets the registrations the
     * initialisation actually performs succeed; a row that never gates on them (and so may
     * not even have the field) passes through the catch untouched.
     */
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

    @AfterEach
    void forgetStorage() throws IOException {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    void aLiveStorageServesItsWorldAndTheGatesSaySo(@TempDir Path world) throws IOException {
        attach(world);
        assertTrue(CsoRegistry.hasLiveStorage(world), "an attached storage means the session serves this world");
        assertFalse(CsoRegistry.hasReleasedStorage(world), "nothing has opted out yet");
    }

    @Test
    void releaseIsTheSessionsOptOutAndUnloadForgetsIt(@TempDir Path world) throws IOException {
        attach(world);
        storage.release();
        assertTrue(storage.isReleased());
        assertFalse(CsoRegistry.hasLiveStorage(world), "a released storage no longer counts as serving");
        assertTrue(CsoRegistry.hasReleasedStorage(world), "but it does count as this session's opt-out");
        assertEquals(1, handles.pauses, "release latches the vanilla side, so no handle outlives it");
        // What the command's finally does for a released storage — the latch must be able to
        // come off even though the storage itself is done.
        storage.resumeAfterConversion();
        assertEquals(1, handles.resumes);
        // The unload: whatever else the session did, closing must take the storage out of the
        // process-wide registry. This is the line the 1.1.5 code broke from the mixin side —
        // released storages stayed past their world, and the next command latched dead state.
        storage.close();
        assertFalse(CsoRegistry.hasReleasedStorage(world), "a closed storage must not answer for its world any more");
    }

    @Test
    void aLatchedReadAnswersEmptyAndALatchedWriteLandsAfterTheResume(@TempDir Path world) throws IOException {
        attach(world);
        ChunkPos before = new ChunkPos(0, 0);
        ChunkPos during = new ChunkPos(1, 0);
        storage.write(before, chunk("written before the pause"));
        assertEquals(chunk("written before the pause"), storage.read(before));

        storage.pauseForConversion();
        assertEquals(1, handles.pauses);
        assertNull(storage.read(before), "a latched read answers empty — the file behind it is being replaced");
        assertEquals(0, storage.pendingCount(), "the pause flushed what was staged before latching");

        storage.write(during, chunk("written during the pause"));
        assertEquals(1, storage.pendingCount(), "a latched write stays staged rather than reopening the file");

        storage.resumeAfterConversion();
        assertEquals(1, handles.resumes);
        assertEquals(0, storage.pendingCount(), "the resume lands what the pause kept");
        assertEquals(chunk("written during the pause"), storage.read(during));
        assertEquals(chunk("written before the pause"), storage.read(before), "the pre-pause chunk survived the cycle");
    }

    private void attach(Path world) throws IOException {
        handles = new RecordingHandles();
        storage = new CsoStorage(
            new RegionStorageInfo("world", null, "region"),
            world.resolve("region"),
            false,
            CsoSettings.defaults(),
            handles
        );
    }

    private static CompoundTag chunk(String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("payload", value);
        return tag;
    }
}
