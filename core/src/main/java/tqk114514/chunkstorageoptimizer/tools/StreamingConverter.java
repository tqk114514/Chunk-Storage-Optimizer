package tqk114514.chunkstorageoptimizer.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import tqk114514.chunkstorageoptimizer.format.AnvilRegionFile;
import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.format.CsoRegionFile;
import tqk114514.chunkstorageoptimizer.format.FileMoves;

/**
 * The conversion command's per-file pipeline, one <em>bucket</em> of chunks at a time.
 *
 * <p>The bulk path this replaces held a whole region decompressed in memory — every chunk of
 * the source file, plus the union partner when one existed — and that shape was what capped the
 * conversion's worker pool: a 32×32 region of large chunks reaches hundreds of megabytes per
 * worker, so six workers was the ceiling a default heap could stand. This class keeps at most
 * one bucket of chunks live (four at the default grid), the union reads its partners slot by
 * slot, and the pool's width becomes a CPU decision rather than a memory one.
 *
 * <p>Every rule of the bulk path is preserved exactly, and each is stated where it is enforced:
 * <ul>
 * <li><b>Union.</b> A half-migrated region can hold chunks on both formats. The copy the live
 * reader serves — {@code .cso} in the mca→cso direction, the input in the cso→mca direction —
 * wins per slot, and the other format fills only the slots it never saw. The rewrite happens
 * even when a slot exists on both sides, so a fresh file at the command's settings is what
 * lands.</li>
 * <li><b>Refusal.</b> A slot that names a chunk this parser cannot decode is a chunk that
 * would be lost, never one that is absent. Either direction that finds such a slot discards
 * everything it built and refuses the file; the target on disk is left byte-identical.</li>
 * <li><b>Nothing empty is written.</b> A source with no chunks produces no target file, so a
 * later union does not have to see through an empty shell.</li>
 * <li><b>Durability.</b> All output lands in a sibling {@code .tmp} and is moved over the
 * target atomically only on a complete, unrefused run.</li>
 * </ul>
 */
public final class StreamingConverter {

    private StreamingConverter() {
    }

    /**
     * One file's outcome.
     *
     * @param written      chunks in the file that was written; zero means no file was written
     * @param sourceChunks chunks the source actually contributed — after the union, some of
     *                     them may have been shadowed by the partner's copy
     * @param unreadable   slots that name a chunk this parser cannot decode; non-zero means
     *                     nothing was written and the target is untouched
     */
    public record Outcome(int written, int sourceChunks, int unreadable) {
    }

    /**
     * The mca→cso half of one region file, in bucket order.
     *
     * @param mca   the source {@code .mca}
     * @param out   the {@code .cso} to replace; a file already there is unioned with its own
     *              copy winning per slot, exactly as the live reader would choose
     * @param grid  bucket grid for the written file
     * @param level zstd level for the written file
     */
    public static Outcome mcaToCso(Path mca, Path out, int grid, int level) throws IOException {
        CsoFormat.validateGrid(grid);
        try (AnvilRegionFile.SlotReader source = AnvilRegionFile.SlotReader.open(mca)) {
            // An .mca whose header names nothing has nothing to convert, and the .cso beside
            // it (if any) is the one already being served — leave it exactly as it is.
            if (source.occupied() == 0) {
                return new Outcome(0, 0, 0);
            }
            int written = 0;
            int sourceChunks = 0;
            int unreadable = 0;
            int span = CsoFormat.span(grid);
            Path temp = out.resolveSibling(out.getFileName() + ".tmp");
            Files.deleteIfExists(temp);
            // The union partner is read through a live handle with a one-bucket cache: the
            // walk touches one row of buckets at a time, so one slot of cache carries each
            // payload once. Read-only, because this file is the game's own: the writer's open
            // would recover its staged batch here and allocate over the blocks it committed.
            try (CsoRegionFile partner = Files.isRegularFile(out)
                    ? CsoRegionFile.openReadOnly(out, 3, 1, true)
                    : null;
                 CsoRegionFile target = CsoRegionFile.open(
                     temp, grid, CsoFormat.COMPRESSION_ZSTD, level, 4, true, Long.MAX_VALUE, 10.0)) {
                // The source is swept in slot order — the .mca's own layout, so the reads stay
                // sequential — while the target is written a completed bucket at a time. A
                // bucket's last slot in slot order is its last row's last column, so each
                // bucket is emitted exactly there; the frontier held live is the current row
                // of buckets (measured: the bucket-major order this replaced paid ~11% per
                // file in seeked single-queue reads).
                Map<Integer, Map<Integer, byte[]>> pendingBuckets = new HashMap<>();
                for (int index = 0; index < 32 * 32; index++) {
                    int localX = index % 32;
                    int localZ = index / 32;
                    AnvilRegionFile.Slot read = source.read(index);
                    if (read.unreadable()) {
                        unreadable++;
                    } else if (read.nbt() != null) {
                        sourceChunks++;
                    }
                    byte[] chosen = partner != null ? partner.readChunk(localX, localZ) : null;
                    if (chosen == null) {
                        chosen = read.nbt();
                    }
                    int bucket = CsoFormat.bucketIndex(localX, localZ, grid);
                    if (chosen != null) {
                        pendingBuckets.computeIfAbsent(bucket, k -> new HashMap<>())
                            .put(CsoFormat.chunkIndexInBucket(localX, localZ, grid), chosen);
                    }
                    boolean lastSlotOfItsBucket =
                        localX % span == span - 1 && localZ % span == span - 1;
                    if (lastSlotOfItsBucket) {
                        Map<Integer, byte[]> into = pendingBuckets.remove(bucket);
                        if (into != null && !into.isEmpty()) {
                            target.writeChunks(bucket, into);
                            written += into.size();
                        }
                    }
                }
                for (Map.Entry<Integer, Map<Integer, byte[]>> entry : pendingBuckets.entrySet()) {
                    // Unreachable while every grid divides 32: every bucket's last slot is a
                    // real index. Emitted anyway, so a hypothetical grid that does not tile
                    // the region cannot become silent data loss.
                    target.writeChunks(entry.getKey(), entry.getValue());
                    written += entry.getValue().size();
                }
                pendingBuckets.clear();
            }
            if (unreadable > 0) {
                // A slot that cannot be decoded is a chunk the written file would be missing.
                // The temp is the only thing this run created, and the target keeps whatever
                // it held — the union read of the partner did not touch it either.
                Files.deleteIfExists(temp);
                return new Outcome(0, sourceChunks, unreadable);
            }
            if (written == 0) {
                // The header names slots but none decoded into content and the partner holds
                // nothing either. An empty .cso shell would answer "no chunks" the same way a
                // missing file does, but it would also survive every later union as a partner
                // that has to be opened and dismissed — so it is not written at all.
                Files.deleteIfExists(temp);
                return new Outcome(0, sourceChunks, 0);
            }
            FileMoves.settle(temp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // The partner read above refuses a file whose batch is still in flight, so a log naming
            // one at this point describes the file that was just replaced — and its deletions must
            // not be applied to the union written here.
            CsoRegionFile.clearOrphanedWal(out);
            return new Outcome(written, sourceChunks, 0);
        }
    }

    /**
     * The cso→mca half of one region file, in bucket order.
     *
     * @param cso the source {@code .cso}; its chunks win per slot
     * @param out the {@code .mca} to replace; a file already there is unioned with the input
     *            winning per slot, and a slot of it that cannot be decoded refuses the file
     */
    public static Outcome csoToMca(Path cso, Path out) throws IOException {
        // Read-only: this source is the game's own file, and the writer's open would recover a
        // batch its holder still has staged. A pending batch refuses the file outright — see
        // CsoPendingBatchException — because converting from the pre-batch view and then pruning
        // the source would drop exactly that batch.
        try (CsoRegionFile source = CsoRegionFile.openReadOnly(cso, 3, 1, true)) {
            // Same early out as the other direction: a source with nothing to convert must
            // not rewrite the partner it would union with. The bucket table answers this
            // without decompressing anything.
            int grid = source.grid();
            boolean anyBucket = false;
            for (int bucket = 0; bucket < CsoFormat.bucketCount(grid); bucket++) {
                if (source.hasBucketIndex(bucket)) {
                    anyBucket = true;
                    break;
                }
            }
            if (!anyBucket) {
                return new Outcome(0, 0, 0);
            }
            int written = 0;
            int sourceChunks = 0;
            int unreadable = 0;
            int span = CsoFormat.span(grid);
            int perBucket = CsoFormat.chunksPerBucket(grid);
            // The partner is opened INSIDE the writer's scope and closed before the writer:
            // on Windows, the writer's settle cannot replace a file a read handle still holds,
            // and a try-with-resources would close the writer first.
            try (AnvilRegionFile.SlotWriter target = AnvilRegionFile.SlotWriter.open(out)) {
                try (AnvilRegionFile.SlotReader partner = Files.isRegularFile(out)
                        ? AnvilRegionFile.SlotReader.open(out)
                        : null) {
                    // Every bucket, not only the source's: the union carries the partner's
                    // chunks wherever they live, and any of its slots that cannot decode
                    // refuses the file exactly as the bulk path's whole-file read would.
                    for (int bucket = 0; bucket < CsoFormat.bucketCount(grid); bucket++) {
                        int bucketX = bucket % grid;
                        int bucketZ = bucket / grid;
                        for (int slot = 0; slot < perBucket; slot++) {
                            int localX = bucketX * span + (slot % span);
                            int localZ = bucketZ * span + (slot / span);
                            byte[] chosen = source.readChunk(localX, localZ);
                            if (chosen != null) {
                                sourceChunks++;
                            }
                            if (chosen == null && partner != null) {
                                AnvilRegionFile.Slot read = partner.read(localX + localZ * 32);
                                if (read.unreadable()) {
                                    unreadable++;
                                } else {
                                    chosen = read.nbt();
                                }
                            }
                            if (chosen != null) {
                                target.write(localX + localZ * 32, chosen);
                                written++;
                            }
                        }
                    }
                }
                if (unreadable > 0) {
                    // The existing .mca holds a chunk this parser cannot decode; unioning
                    // without it would drop those bytes with no copy anywhere. The writer's
                    // abort discards the temp, and the .mca on disk is untouched.
                    target.abort();
                    return new Outcome(0, sourceChunks, unreadable);
                }
                if (written == 0) {
                    // Buckets exist but no slot decoded into content and the partner holds
                    // nothing: same reasoning as the other direction, no empty shell.
                    target.abort();
                    return new Outcome(0, sourceChunks, 0);
                }
            }
            return new Outcome(written, sourceChunks, 0);
        }
    }
}
