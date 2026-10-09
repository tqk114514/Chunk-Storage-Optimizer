package tqk114514.chunkstorageoptimizer.format;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The free-space view of one region file: best-fit allocation and waste accounting, both
 * computed from the live table entries rather than kept as separate bookkeeping — the table
 * is the only authority on what is in use, so nothing can drift away from it.
 *
 * <p>The bucket being rewritten keeps its old extent marked as IN USE. Reusing that space
 * would be faster to reclaim, but it destroys the only intact copy of the bucket if the
 * process dies between writing the new block and updating the table. Reclaiming happens at
 * compaction instead, which is the whole point of having a compaction pass.
 */
final class BlockAllocator {

    private final long dataStart;

    // The extents are collected into a pool allocated once per file. Allocation runs on every
    // bucket write, and at grid=1 it would otherwise create a thousand short-lived arrays
    // per chunk save.
    private final long[][] rangePool;
    private final List<long[]> usedRanges = new ArrayList<>();

    BlockAllocator(long dataStart, int bucketCount) {
        this.dataStart = dataStart;
        this.rangePool = new long[bucketCount + 1][];
        for (int i = 0; i < this.rangePool.length; i++) {
            this.rangePool[i] = new long[2];
        }
    }

    /** Best-fit offset for a {@code size}-byte block, never aliasing a live one. */
    long allocate(TableEntry[] entries, int size, long fileEnd) {
        List<long[]> used = this.usedRanges;
        used.clear();
        this.rangePool[0][0] = 0L;
        this.rangePool[0][1] = this.dataStart;
        used.add(this.rangePool[0]);
        int slot = 1;
        for (TableEntry e : entries) {
            if (e.offset != 0 && e.compressedLength > 0) {
                long[] range = this.rangePool[slot++];
                range[0] = e.offset;
                range[1] = e.offset + e.compressedLength;
                used.add(range);
            }
        }
        used.sort(Comparator.comparingLong(a -> a[0]));

        long cursor = 0L;
        long best = -1L;
        long bestSize = Long.MAX_VALUE;

        for (long[] range : used) {
            if (range[0] > cursor) {
                long free = range[0] - cursor;
                if (free >= size && free < bestSize) {
                    best = cursor;
                    bestSize = free;
                }
            }
            if (range[1] > cursor) {
                cursor = range[1];
            }
        }
        if (fileEnd > cursor) {
            long free = fileEnd - cursor;
            if (free >= size && free < bestSize) {
                best = cursor;
            }
        }
        return best >= 0 ? best : fileEnd;
    }

    /**
     * Bytes occupied by blocks no table entry still points at — interior gaps only.
     *
     * <p>The free span after the last used block is excluded on purpose: allocate() hands it
     * out to future writes, so it is reclaimable without rewriting the file — unlike the
     * interior gaps superseded blocks leave behind, which only compaction can reclaim.
     * Stranded tails do exist in practice: a crash between the data write and the table
     * update leaves bytes past the last extent the table knows about.
     */
    long wastedBytes(TableEntry[] entries) {
        List<long[]> used = new ArrayList<>(entries.length);
        for (TableEntry e : entries) {
            if (e.offset != 0 && e.compressedLength > 0) {
                used.add(new long[] {e.offset, e.offset + e.compressedLength});
            }
        }
        used.sort(Comparator.comparingLong(a -> a[0]));
        long cursor = this.dataStart;
        long wasted = 0;
        for (long[] range : used) {
            if (range[0] > cursor) {
                wasted += range[0] - cursor;
            }
            if (range[1] > cursor) {
                cursor = range[1];
            }
        }
        return wasted;
    }
}
