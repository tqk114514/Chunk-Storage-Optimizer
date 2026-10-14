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
}
