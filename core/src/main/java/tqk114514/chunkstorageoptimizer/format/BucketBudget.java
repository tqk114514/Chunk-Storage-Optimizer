package tqk114514.chunkstorageoptimizer.format;

/**
 * The process-wide ceiling on decompressed bucket payloads.
 *
 * <p>{@link BucketCache} bounds each region file on its own: a count of buckets and a byte ceiling.
 * Those are the right shape of limit — a busy file must not starve a quiet one — but they are not a
 * bound on the process. The arithmetic that makes them one: up to {@code MAX_OPEN_REGIONS} files
 * open per storage, three storages per dimension, and a handful of dimensions, each file free to
 * hold its full ceiling. The shipped defaults put that at gigabytes of heap the mod never
 * promised anyone, and the count knob (which is what players actually see in the config) reads as
 * if it were the whole story.
 *
 * <p>So the ceiling is enforced twice: per file, to keep one region's working set from eating the
 * rest of the cache, and here, to keep the cache from eating the heap. Over the global limit a
 * payload is simply not cached — the same honest outcome a bucket bigger than the whole per-file
 * ceiling already gets — which costs a decompression on the next read, never correctness.
 */
public final class BucketBudget {

    /**
     * What every {@link BucketCache} in this JVM may hold together. A quarter of a gigabyte is
     * deliberate: it is more than a server's working set at the default grid (a handful of
     * dimensions around each player, a few megabytes of decompressed buckets), and small enough
     * that the mod cannot be the reason an instance runs out.
     */
    static final long MAX_BYTES = 256L * 1024 * 1024;

    private static final BucketBudget SHARED = new BucketBudget(MAX_BYTES);

    private final long limit;
    private long bytes;

    BucketBudget(long limit) {
        this.limit = limit;
    }

    /** The one every region file uses; the constructor exists so a test can set its own scale. */
    static BucketBudget shared() {
        return SHARED;
    }

    /**
     * Bytes of decompressed buckets every region file in this process is holding right now. Public
     * for {@code /cso stats}: a ceiling nobody can read is a claim, not a bound, and the count is
     * also what tells an operator whether the cap is being reached in their instance.
     */
    public static long heldBytes() {
        return SHARED.held();
    }

    /** The process-wide ceiling {@link #heldBytes()} is bounded by. */
    public static long limitBytes() {
        return MAX_BYTES;
    }

    /**
     * Takes {@code delta} off the headroom, which may be negative (a payload being replaced or
     * evicted). Refusing is decided by the caller, which knows what it wanted to keep.
     */
    synchronized void add(long delta) {
        this.bytes += delta;
        // The ledger cannot go negative; if it ever did, the limit would stop binding silently
        // and the cache would grow without end — a wrong count is worse than a conservative one.
        if (this.bytes < 0) {
            this.bytes = 0;
        }
    }

    synchronized boolean overLimit() {
        return this.bytes > this.limit;
    }

    synchronized long held() {
        return this.bytes;
    }
}
