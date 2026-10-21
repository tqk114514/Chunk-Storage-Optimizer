package tqk114514.chunkstorageoptimizer.format;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The decompressed-bucket cache of one region file: least-recently-used, bounded by both a
 * bucket count and a byte budget.
 *
 * <p>The count knob alone is the wrong bound at small grids: a bucket holds
 * {@code (32/grid)^2} chunks, so a grid=1 bucket is a whole region's worth of them and
 * decompresses to megabytes — "N buckets" can mean kilobytes at grid 32 and gigabytes at
 * grid 1. The byte budget only binds where payloads are big; at the default grid=16 the count
 * knob stays the effective limit.
 */
final class BucketCache {

    /** Ceiling on the decompressed bytes one region file may pin here. */
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    // Access-ordered so iteration walks least-recently-used first; the bounds themselves are
    // enforced after every put, which evicts by count AND bytes.
    private final Map<Integer, byte[]> payloads = new LinkedHashMap<>(16, 0.75f, true);
    private final int maxBuckets;
    private long bytes;

    BucketCache(int maxBuckets) {
        this.maxBuckets = maxBuckets;
    }

    /** The cached payload, or null — the caller counts the hit; the cache stays stats-free. */
    byte[] get(int bucket) {
        return payloads.get(bucket);
    }

    /**
     * Caches a decompressed payload, then evicts least-recently-used entries until both bounds
     * hold. Runs after every put, so one huge bucket can evict even the entry just inserted —
     * a payload larger than the whole budget is simply not cacheable, which is the honest
     * answer at grid 1, where a bucket is an entire region.
     *
     * <p>Re-caching a bucket that is already here REPLACES its payload, so the budget has to
     * release the length of the array being superseded, not just absorb the new one. Every
     * bucket write goes through this twice — {@code getPayload} puts what it decompressed, then
     * {@code storeBucket} puts what it rebuilt — so counting arrivals only made the ledger grow
     * by a full payload per save while the cache itself held one copy. The drift could never be
     * paid back either: eviction subtracts the lengths of the arrays still in the map, so the
     * phantom surplus outlived every entry that caused it, and once it crossed the ceiling the
     * loop below drained the cache on every put, permanently, for the rest of the session.
     */
    void put(int bucket, byte[] payload) {
        byte[] superseded = payloads.put(bucket, payload);
        bytes += payload.length - (superseded == null ? 0 : superseded.length);
        var it = payloads.entrySet().iterator();
        while (payloads.size() > maxBuckets || bytes > MAX_BYTES) {
            if (!it.hasNext()) {
                break;
            }
            var eldest = it.next();
            it.remove();
            bytes -= eldest.getValue().length;
        }
    }

    /** How many decompressed payloads are currently held. Diagnostic, for the tests. */
    int size() {
        return payloads.size();
    }
}
