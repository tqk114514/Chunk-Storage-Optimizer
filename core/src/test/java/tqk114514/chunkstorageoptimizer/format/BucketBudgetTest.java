package tqk114514.chunkstorageoptimizer.format;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The process-wide bound on decompressed buckets, on a scale a test can reach.
 *
 * <p>Each region file already bounds itself, and that was the whole story while it was also the
 * only ceiling anyone could count against: 256 open files per storage, three storages per
 * dimension, several dimensions, each holding its own full per-file ceiling. The shared budget is
 * what stops that adding up, and these tests are what stops it from being a number in a comment.
 */
class BucketBudgetTest {

    @Test
    void theSharedBudgetStopsTheSecondFileFromAddingToTheFirst() {
        BucketBudget budget = new BucketBudget(3_000);
        // Count and per-file ceilings both set far above what this test feeds them, so only the
        // shared bound can be the reason anything is dropped.
        BucketCache first = new BucketCache(64, budget);
        BucketCache second = new BucketCache(64, budget);

        for (int bucket = 0; bucket < 4; bucket++) {
            first.put(bucket, new byte[1_000]);
        }
        assertEquals(3, first.size(), "the fourth payload evicted the eldest to stay inside the total");
        assertEquals(3_000, budget.held());

        second.put(0, new byte[1_000]);
        assertEquals(0, second.size(),
            "with the budget already held by another file, this one caches nothing rather than"
                + " pushing the total over — a decompression cost, never a correctness one");
        assertEquals(3_000, budget.held(), "the shared count is what both files are bounded by");
    }

    @Test
    void aClosedFileGivesItsBytesBackToTheBudget() {
        BucketBudget budget = new BucketBudget(3_000);
        BucketCache held = new BucketCache(64, budget);
        for (int bucket = 0; bucket < 3; bucket++) {
            held.put(bucket, new byte[1_000]);
        }
        assertEquals(3_000, budget.held());

        // The storage layer evicts region files while a session runs; if their payloads only left
        // through the eviction loop, a closed file's bytes would stay counted forever and the
        // cache would gradually stop being able to hold anything.
        held.clear();
        assertEquals(0, held.size());
        assertEquals(0, budget.held(), "a closed file must not leave bytes charged to the process");

        BucketCache next = new BucketCache(64, budget);
        next.put(0, new byte[1_000]);
        assertEquals(1, next.size(), "and the room it freed has to be usable by the next file");

        // Idempotent, because close() and an eviction can both reach it.
        next.clear();
        next.clear();
        assertEquals(0, budget.held(), "a second release of an emptied cache must not go negative");
    }
}
