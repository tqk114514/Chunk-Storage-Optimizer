package tqk114514.chunkstorageoptimizer.compat.c2me;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.mojang.datafixers.DataFixer;

import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RecreatingSimpleRegionStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vanilla shapes the bridge's mixins claim, against the pinned 26.3 game this module
 * compiles with — the same level of contract the main mod keeps with its own mixins
 * (see VanillaRegionCacheShapeTest).
 *
 * <p>The mixins apply with the default {@code require}: a Minecraft that moves any of these
 * breaks the bridge with a hard failure rather than a silent one — deliberately, because a
 * bridge that quietly stops swapping is terrain regeneration over served folders, not a
 * missing feature. This test is the early-warning half of that stance: it fails the build on
 * the pinned row the moment a shape the mixins still claim stops existing, before the hard
 * failure ships to a player.
 *
 * <p>Reflection, not construction: none of these lookups initialises a game class, so the
 * suite runs on a plain JVM the same way the main mod's shape tests do.
 */
class C2meBridgeVanillaShapeTest {

    @Test
    void theWorkerFieldIsWhatTheShadowClaims() throws NoSuchFieldException {
        Field worker = SimpleRegionStorage.class.getDeclaredField("worker");

        assertEquals(IOWorker.class, worker.getType(),
            "the swap writes the whole field, so its type is the contract");
        assertTrue(Modifier.isFinal(worker.getModifiers()),
            "final is expected — it is what @Mutable is there to override");
    }

    @Test
    void theConstructorTheReadHandlerSigns() throws NoSuchMethodException {
        assertNotNull(SimpleRegionStorage.class.getDeclaredConstructor(
            RegionStorageInfo.class, Path.class, DataFixer.class, boolean.class, DataFixTypes.class),
            "the read-side handler receives the constructor's whole argument list; a change here"
                + " is a require failure at apply time");
    }

    @Test
    void theRecreationStorageCarriesItsOwnWorker() throws NoSuchFieldException {
        Field writeWorker = RecreatingSimpleRegionStorage.class.getDeclaredField("writeWorker");
        assertEquals(IOWorker.class, writeWorker.getType());
        assertTrue(Modifier.isFinal(writeWorker.getModifiers()));

        assertEquals(Path.class, RecreatingSimpleRegionStorage.class.getDeclaredField("writeFolder").getType(),
            "the write-side handler swaps per destination, so the folder field is part of the claim");
    }

    @Test
    void theConstructorTheWriteHandlerSigns() throws NoSuchMethodException {
        assertNotNull(RecreatingSimpleRegionStorage.class.getDeclaredConstructor(
            RegionStorageInfo.class, Path.class, RegionStorageInfo.class, Path.class,
            DataFixer.class, boolean.class, DataFixTypes.class),
            "read pair, write pair, then the shared three — the order the bytecode feeds them in");
    }

    @Test
    void theReplacementCallVanillaWouldHaveMade() throws NoSuchMethodException {
        assertNotNull(IOWorker.class.getDeclaredConstructor(
            RegionStorageInfo.class, Path.class, boolean.class),
            "the swap reconstructs the vanilla worker with exactly these three; the redirect the"
                + " bridge undoes saw the same argument triple");
    }

    /**
     * The probe, against the real class files and the resource this build wrote.
     *
     * <p>This is the half the decision-table test cannot reach: it proves the shape resource is
     * on the classpath and says what this module was built for. The pinned row is the modern
     * shape — 26.3 has no {@code ChunkStorage} — so the legacy mixins must stand down here.
     */
    @Test
    void thePinnedRowIsTheModernShape() {
        CsoC2meCompatCore.ClassPresence presence = CsoC2meCompatCore.probe();

        assertEquals(CsoC2meCompatCore.Shape.MODERN, presence.shape(),
            "the build writes the shape into the jar; this module compiles against 26.3, where"
                + " ChunkStorage no longer exists");
        assertFalse(presence.c2meWorker(), "C2ME is not on this test classpath");
        assertFalse(CsoC2meCompatCore.splitsChunkPath(presence));
    }

    /**
     * The same probe with C2ME present — the shape a player's 26.x instance actually has.
     *
     * <p>C2ME cannot be a test dependency (its jar nests its modules, so the rewrite class is not
     * even visible to a plain compile), which is why the flag is set by hand here. Everything else
     * comes from the real classloader and the build's own resource.
     */
    @Test
    void thePinnedRowIsBridgedOnceC2meIsPresent() {
        CsoC2meCompatCore.ClassPresence real = CsoC2meCompatCore.probe();

        assertTrue(CsoC2meCompatCore.bridgeCoversEveryRewrittenPath(
                new CsoC2meCompatCore.ClassPresence(true, real.shape())),
            "a known shape plus C2ME is exactly what the bridge needs");
    }
}
