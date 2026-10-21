package tqk114514.chunkstorageoptimizer.compat.c2me;

import java.lang.reflect.Constructor;
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
 * The vanilla shapes the bridge's mixins claim, against the game this row compiled with — the
 * same level of contract the main mod keeps with its own mixins (see
 * {@code VanillaRegionCacheShapeTest}). One source tree serves every row, so what it asserts has
 * to hold on the legacy rows as well as the modern ones.
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
    void theConstructorTheReadHandlerSigns() {
        assertTrue(constructorTakesPrefix(SimpleRegionStorage.class,
                RegionStorageInfo.class, Path.class, DataFixer.class, boolean.class, DataFixTypes.class),
            "the read-side handler receives the constructor's argument list; a row where not even a"
                + " prefix of it exists is a require failure at apply time");
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
    void theConstructorTheWriteHandlerSigns() {
        assertTrue(constructorTakesPrefix(RecreatingSimpleRegionStorage.class,
                RegionStorageInfo.class, Path.class, RegionStorageInfo.class, Path.class,
                DataFixer.class, boolean.class, DataFixTypes.class),
            "read pair, write pair, then the shared three — the order the bytecode feeds them in");
    }

    /**
     * Whether some constructor of {@code type} takes exactly these arguments, possibly followed by
     * more. Mixin binds an {@code @Inject} on {@code <init>} when the handler's list is a prefix of
     * the target's, so this — not an exact arity — is the contract the bridge depends on.
     *
     * <p>Measured on the 1.21.11 row: the recreate constructor has eight parameters, the extra one a
     * trailing {@code Supplier<LegacyTagFixer>}. The bridge binds fine there, and the exact-arity
     * claim this test used to make went red on a row that ships working — which is also why the
     * module cannot simply be switched into CI before this was settled.
     */
    private static boolean constructorTakesPrefix(Class<?> type, Class<?>... claimed) {
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            Class<?>[] actual = ctor.getParameterTypes();
            if (actual.length < claimed.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < claimed.length; i++) {
                if (!actual[i].equals(claimed[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
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
     * <p>This is the half the decision-table test cannot reach: it proves the shape resource is on
     * the classpath and readable, and that it names a shape the bridge knows — an unreadable or
     * unknown shape makes the plugin refuse to bridge at all, so a row that shipped one would ship
     * a compat that does nothing.
     *
     * <p>It used to assert {@code MODERN} outright, which made the suite fail on every 1.21–1.21.10
     * row (measured: {@code expected: <MODERN> but was: <LEGACY>} on 1.21.1) — invisible because no
     * CI job runs this module's tests. Asserting the row's own shape instead would have been the
     * one-line fix, and is wrong: with the shape read from the resource there is nothing left to
     * check, and the failure worth catching is a jar that carries another row's shape (the
     * up-to-date bug {@code writeShapeResource} exists to avoid). That needs the row identity
     * passed in from the build, and the modern/legacy rule — which is currently written in three
     * places (both loaders' build scripts and this module's matrix test) — narrowed to one. It is
     * not done here, and this test no longer pretends otherwise.
     */
    @Test
    void theShapeResourceIsReadableAndNamesAKnownShape() {
        CsoC2meCompatCore.ClassPresence presence = CsoC2meCompatCore.probe();

        assertFalse(presence.shape() == CsoC2meCompatCore.Shape.UNKNOWN,
            "the build must write " + CsoC2meCompatCore.SHAPE_RESOURCE + " into this row's jar;"
                + " an unknown shape makes the plugin refuse to bridge the row it was built for");
        assertFalse(presence.c2meWorker(), "C2ME is not on this test classpath");
        assertEquals(presence.shape() == CsoC2meCompatCore.Shape.LEGACY,
            CsoC2meCompatCore.splitsChunkPath(presence),
            "only the legacy shape bridges the chunk path through ChunkStorage");
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
