package tqk114514.chunkstorageoptimizer.compat.c2me;

import org.junit.jupiter.api.Test;

import tqk114514.chunkstorageoptimizer.compat.c2me.CsoC2meCompatCore.ClassPresence;
import tqk114514.chunkstorageoptimizer.compat.c2me.CsoC2meCompatCore.Shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that decides coexistence versus the fail-safe, driven through every shape.
 *
 * <p>The rule used to be a probe of four named vanilla classes. That could not survive the
 * per-row build: on an obfuscated Fabric row those names do not exist at runtime, so a named
 * probe answers "absent" on a row that has the classes — and the bridge would stand down for the
 * wrong reason. The build now proves the vanilla classes (it compiles against that exact game),
 * so what is left at runtime is C2ME's presence plus the shape the build wrote. Both are pure
 * inputs here, which is the point: the shape that decides "how much has to bind" can be
 * enumerated instead of discovered in a player's world.
 */
class C2meBridgeCoverageTest {

    @Test
    void aKnownShapeWithC2mePresentIsBridgeable() {
        assertTrue(CsoC2meCompatCore.bridgeCoversEveryRewrittenPath(
            new ClassPresence(true, Shape.MODERN)));
        assertTrue(CsoC2meCompatCore.bridgeCoversEveryRewrittenPath(
            new ClassPresence(true, Shape.LEGACY)));
    }

    @Test
    void withoutC2meThereIsNothingToBridge() {
        assertFalse(CsoC2meCompatCore.bridgeCoversEveryRewrittenPath(
            new ClassPresence(false, Shape.MODERN)));
        assertFalse(CsoC2meCompatCore.bridgeCoversEveryRewrittenPath(
            new ClassPresence(false, Shape.LEGACY)));
    }

    @Test
    void anUnknownShapeRefusesRatherThanGuessing() {
        assertFalse(CsoC2meCompatCore.bridgeCoversEveryRewrittenPath(
                new ClassPresence(true, Shape.UNKNOWN)),
            "a jar that cannot say what it was built for must stand down: guessing 'modern' on a"
                + " legacy row is a half bridge, and a half bridge regenerates chunks");
    }

    @Test
    void onlyTheLegacyShapeSplitsTheChunkPath() {
        assertTrue(CsoC2meCompatCore.splitsChunkPath(new ClassPresence(true, Shape.LEGACY)));
        assertFalse(CsoC2meCompatCore.splitsChunkPath(new ClassPresence(true, Shape.MODERN)));
        assertFalse(CsoC2meCompatCore.splitsChunkPath(new ClassPresence(true, Shape.UNKNOWN)));
    }

    /**
     * The count the plugin has to reach before it may mark the bridge: the accessor plus one per
     * storage class the row has. A wrong number here is a wrong answer to "is the bridge
     * complete", in one direction or the other, so it is pinned rather than computed.
     */
    @Test
    void eachShapeKnowsHowManyMixinsMustBind() {
        assertEquals(3, Shape.MODERN.expectedMixins(),
            "accessor + SimpleRegionStorage + RecreatingSimpleRegionStorage");
        assertEquals(5, Shape.LEGACY.expectedMixins(),
            "the modern pair plus ChunkStorage and RecreatingChunkStorage");
        assertEquals(Integer.MAX_VALUE, Shape.UNKNOWN.expectedMixins(),
            "an unknown shape can never reach its count, which is how it refuses");
    }
}
