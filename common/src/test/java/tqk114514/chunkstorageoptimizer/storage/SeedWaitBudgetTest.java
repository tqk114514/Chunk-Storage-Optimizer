package tqk114514.chunkstorageoptimizer.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

/**
 * The bound on how long one batch may wait for unreadable legacy chunks, measured on a clock the
 * test owns.
 *
 * <p>The arithmetic is what needed its own seam: the same batch cost 1.0 s on a dev machine and
 * 1.85 s on a shared CI runner, and the difference was the reading itself, not the waiting — so any
 * wall-clock bound either fails on a loaded runner or is so loose it no longer proves the cap is
 * doing anything. With the clock supplied, the cap is a number that can be asserted exactly.
 */
class SeedWaitBudgetTest {

    private static final long RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);

    @Test
    void patienceIsGrantedUntilTheBudgetIsGone() {
        long[] now = {0L};
        LongSupplier clock = () -> now[0];
        SeedWaitBudget budget = new SeedWaitBudget(300L, clock);

        assertEquals(RETRY_NANOS, budget.reserve(RETRY_NANOS), "the first wait is granted whole");
        now[0] += RETRY_NANOS;
        assertEquals(RETRY_NANOS, budget.reserve(RETRY_NANOS));
        now[0] += RETRY_NANOS;
        assertEquals(RETRY_NANOS, budget.reserve(RETRY_NANOS));

        now[0] += RETRY_NANOS;
        assertEquals(0L, budget.reserve(RETRY_NANOS),
            "a fourth wait on a 300 ms budget is refused — that is the caller's signal to stop"
                + " retrying and report the chunk instead");
    }

    @Test
    void theLastWaitIsTrimmedToWhatIsLeftRatherThanSkippedWhole() {
        long[] now = {0L};
        LongSupplier clock = () -> now[0];
        SeedWaitBudget budget = new SeedWaitBudget(150L, clock);

        now[0] = TimeUnit.MILLISECONDS.toNanos(120L);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(30L), budget.reserve(RETRY_NANOS),
            "the remaining headroom is granted, so the patience still applies to the chunk that"
                + " happens to straddle the limit");
        now[0] = TimeUnit.MILLISECONDS.toNanos(150L);
        assertEquals(0L, budget.reserve(RETRY_NANOS));
    }

    /**
     * The property the cap exists for: however many chunks a batch cannot read, the waiting it can
     * cause is one budget, not one per chunk. Asserted as a ratio rather than a duration, so a slow
     * machine can only make it MORE true.
     */
    @Test
    void oneBudgetCoversTheWholeBatchHoweverManyChunksFail() {
        long[] now = {0L};
        LongSupplier clock = () -> now[0];
        SeedWaitBudget budget = new SeedWaitBudget(1_000L, clock);

        int asks = 200;
        int granted = 0;
        for (int i = 0; i < asks; i++) {
            long wait = budget.reserve(RETRY_NANOS);
            if (wait == 0L) {
                continue;
            }
            granted++;
            now[0] += wait;   // the caller sleeps what it was granted
        }

        assertTrue(granted <= 11,
            "a 1 s budget hands out at most ten 100 ms waits; granted " + granted + " of " + asks);
        assertTrue(granted < asks / 5,
            "the point of the cap: " + granted + " waits for " + asks + " unreadable chunks,"
                + " where without it every chunk would get its own retries");
    }
}
