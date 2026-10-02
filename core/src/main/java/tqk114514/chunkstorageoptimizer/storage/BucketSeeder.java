package tqk114514.chunkstorageoptimizer.storage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import tqk114514.chunkstorageoptimizer.format.CsoFormat;

/**
 * Gives a bucket that has never been written the chunks it still has in the legacy {@code .mca},
 * so that "this bucket has been written" really does mean every slot in it has a decided value.
 *
 * <p>The read path treats a written bucket as authoritative: an empty slot in one is a deletion and
 * it will not fall back to the {@code .mca} for it. That is what stops a chunk the game deleted
 * from coming back. But a save only writes the chunks it was asked to write, so a bucket created
 * from a single save would hold just those and leave its neighbours — still only in the
 * {@code .mca} — reading back as absent. The game reads "absent" as "never generated" and answers
 * by regenerating terrain over them, which is the most destructive thing this format could do.
 *
 * <p>Seeding closes that gap: before a bucket's first write, every slot the caller is not changing
 * takes the legacy chunk. A slot the caller <em>is</em> changing keeps its value, a {@code null}
 * deletion included, so a chunk that is genuinely gone stays gone.
 *
 * <p>Pure and Minecraft-free, which is what makes the rule testable without a game: the legacy
 * bytes arrive through {@link LegacySource}. It also means the seeding is a one-off cost per
 * bucket — at grid 16 a bucket covers four chunks, so at most three extra reads, once ever.
 */
public final class BucketSeeder {

    private BucketSeeder() {
    }

    /** Reads one region-local chunk from the legacy {@code .mca}; null when it is not there. */
    @FunctionalInterface
    public interface LegacySource {
        byte[] read(int localX, int localZ) throws IOException;
    }

    /**
     * @param changes slot -> new bytes, a null value meaning "deleted"
     * @return a copy of {@code changes} in which every slot the bucket covers is decided: slots the
     *         caller is changing are left alone, the rest take the legacy chunk where there is one
     */
    public static Map<Integer, byte[]> seed(
        Map<Integer, byte[]> changes, int bucket, int grid, LegacySource legacy
    ) throws IOException {
        int span = CsoFormat.span(grid);
        int bucketX = bucket % grid;
        int bucketZ = bucket / grid;
        Map<Integer, byte[]> out = new HashMap<>(changes);
        for (int dz = 0; dz < span; dz++) {
            for (int dx = 0; dx < span; dx++) {
                int localX = bucketX * span + dx;
                int localZ = bucketZ * span + dz;
                int slot = CsoFormat.chunkIndexInBucket(localX, localZ, grid);
                if (out.containsKey(slot)) {
                    // The live change wins, a deletion included: seeding must never undo one.
                    continue;
                }
                byte[] bytes = legacy.read(localX, localZ);
                if (bytes != null) {
                    out.put(slot, bytes);
                }
            }
        }
        return out;
    }
}
