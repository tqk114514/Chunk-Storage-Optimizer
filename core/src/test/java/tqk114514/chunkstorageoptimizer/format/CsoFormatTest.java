package tqk114514.chunkstorageoptimizer.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class CsoFormatTest {

    private static final int[][] SAMPLES = {
        {0, 0}, {31, 31}, {-1, 0}, {0, -1}, {-1, -1}, {-512, 767}, {767, -512},
        {Integer.MAX_VALUE, Integer.MIN_VALUE}, {Integer.MIN_VALUE, Integer.MAX_VALUE},
    };

    @Test
    void coordKeysRoundTripAcrossSigns() {
        for (int[] sample : SAMPLES) {
            long packed = CsoFormat.coordKey(sample[0], sample[1]);
            assertEquals(sample[0], CsoFormat.keyX(packed), "x of " + sample[0] + "," + sample[1]);
            assertEquals(sample[1], CsoFormat.keyZ(packed), "z of " + sample[0] + "," + sample[1]);
        }
    }

    /**
     * The batching maps are keyed by these values, so a collision would silently write one chunk
     * into another chunk's slot.
     */
    @Test
    void coordKeysNeverCollide() {
        for (int[] a : SAMPLES) {
            for (int[] b : SAMPLES) {
                if (a[0] == b[0] && a[1] == b[1]) {
                    continue;
                }
                assertNotEquals(CsoFormat.coordKey(a[0], a[1]), CsoFormat.coordKey(b[0], b[1]),
                    a[0] + "," + a[1] + " vs " + b[0] + "," + b[1]);
            }
        }
    }
}
