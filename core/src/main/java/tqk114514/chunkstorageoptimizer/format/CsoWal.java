package tqk114514.chunkstorageoptimizer.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The write-ahead log of one region file: the whole in-flight batch, written before any of it
 * is applied, so a crash during the batch replays it on the next open instead of losing it.
 *
 * <p>The granularity is the batch, not the single bucket, on purpose: one forced write per
 * batch keeps the write path fast. A bucket-at-a-time WAL would need a forced write per chunk
 * save, which would throw away most of the speed this format exists to provide.
 *
 * <p>The log is written through a temp name and moved into place: a WAL that exists is a WAL
 * that was written whole. Writing in place left a torn log indistinguishable from a damaged
 * one, and that ambiguity forced the reader to discard every checksum failure quietly — the
 * quiet path being exactly what a damaged log must not get. The reader distinguishes the two
 * eras by the length tail the new format carries (see {@link #replayBucketChanges}).
 */
final class CsoWal {

    private static final byte[] WAL_MAGIC = {'C', 'S', 'O', 'W', 'A', 'L', 0};

    /** Applies one bucket's worth of replayed changes; the caller makes them durable. */
    interface BucketApplier {
        void apply(int bucket, Map<Integer, byte[]> changes) throws IOException;
    }

    private final Path regionPath;
    private final Path walPath;
    private final int bucketCount;
    private final int chunksPerBucket;

    CsoWal(Path regionPath, int bucketCount, int chunksPerBucket) {
        this.regionPath = regionPath;
        this.walPath = regionPath.resolveSibling(regionPath.getFileName() + ".wal");
        this.bucketCount = bucketCount;
        this.chunksPerBucket = chunksPerBucket;
    }

    /** Records a whole batch of bucket changes and forces it to disk, atomically. */
    void write(Map<Integer, Map<Integer, byte[]>> changes) throws IOException {
        if (changes.isEmpty()) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(WAL_MAGIC);
        out.writeShort(CsoFormat.FORMAT_VERSION);
        out.writeInt(changes.size());
        for (Map.Entry<Integer, Map<Integer, byte[]>> bucketEntry : changes.entrySet()) {
            out.writeInt(bucketEntry.getKey());
            out.writeInt(bucketEntry.getValue().size());
            for (Map.Entry<Integer, byte[]> slotEntry : bucketEntry.getValue().entrySet()) {
                out.writeInt(slotEntry.getKey());
                byte[] data = slotEntry.getValue();
                out.writeInt(data == null ? -1 : data.length);
                if (data != null) {
                    out.write(data);
                }
            }
        }
        out.flush();

        byte[] payload = bytes.toByteArray();
        // The length field that makes a damaged log tellable from a torn one: it is the
        // forensic tail the reader checks when the checksum fails (see replayBucketChanges).
        byte[] lengthField = new byte[4];
        CsoFormat.writeInt(lengthField, 0, payload.length);
        CRC32 crc = new CRC32();
        crc.update(payload);
        crc.update(lengthField);
        // Written with CsoFormat.writeInt (little-endian) rather than ByteBuffer.putInt
        // (big-endian) — the reader uses readInt, and a mismatch here silently fails the
        // checksum so the log is discarded and the recovery never happens.
        byte[] checksum = new byte[4];
        CsoFormat.writeInt(checksum, 0, (int) crc.getValue());
        Path tmp = walPath.resolveSibling(walPath.getFileName() + ".tmp");
        try (FileChannel wal = FileChannel.open(
            tmp,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ByteBuffer buffer = ByteBuffer.allocate(payload.length + lengthField.length + checksum.length);
            buffer.put(payload);
            buffer.put(lengthField);
            buffer.put(checksum);
            buffer.flip();
            while (buffer.hasRemaining()) {
                wal.write(buffer);
            }
            wal.force(true);
        }
        try {
            FileMoves.settle(tmp, walPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // The move failing leaves the batch unwritten and the caller must not apply it,
            // so the temp name is dropped too; a stale one is swept at the next open anyway.
            deleteTmpQuietly();
            throw e;
        }
    }

    /** Answers whether a log exists — the blank-first-table heuristic uses it. */
    boolean exists() {
        return Files.isRegularFile(walPath);
    }

    /** Drops the WAL. Only safe once the batch it describes is already durable. */
    void clear() throws IOException {
        FileMoves.settleDelete(walPath);
    }

    /**
     * Replays a WAL left behind by a crash into {@code applier}, which the caller then makes
     * durable (its flush) before {@link #clear()}. Idempotent — applying the same changes
     * twice produces the same bytes — so dying during replay is harmless; it runs again next
     * time. Damage policy is the reader's contract with the two write eras, kept here:
     *
     * <ul>
     * <li>an unopenable log fails loudly — it is the only record of a batch that may not
     * have been applied, the same rule a locked .mca follows;
     * <li>a damaged log of the atomic era fails loudly and is kept for manual recovery —
     * the batch it describes may sit half-applied;
     * <li>a log without the length tail can only be a legacy one, written in place, where
     * a torn write was the ordinary crash outcome: it is discarded quietly, and a legacy
     * log damaged after the fact is indistinguishable from a torn one — the very ambiguity
     * the format moved on from.
     * </ul>
     */
    void replayBucketChanges(BucketApplier applier) throws IOException {
        if (!Files.isRegularFile(walPath)) {
            // A crash during the log's own write leaves the temp name behind; it was never
            // committed, so it is swept without ceremony.
            deleteTmpQuietly();
            return;
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(walPath);
        } catch (IOException e) {
            // An unopenable WAL is not discarded: it is the only record of a batch that may
            // not have been applied yet. On Windows this is usually a backup tool holding
            // the file; refusing here stalls the open visibly until it can be read again.
            throw new CsoCorruptedException("Cannot read the write-ahead log for " + regionPath, e);
        }
        if (raw.length < WAL_MAGIC.length + 2 + 4 + 4) {
            deleteQuietly();
            return;
        }
        CRC32 crc = new CRC32();
        crc.update(raw, 0, raw.length - 4);
        if ((int) crc.getValue() != CsoFormat.readInt(raw, raw.length - 4)) {
            int declaredLength = raw.length >= 8 ? CsoFormat.readInt(raw, raw.length - 8) : -1;
            if (declaredLength == raw.length - 8) {
                throw new CsoCorruptedException("The write-ahead log for " + regionPath
                    + " is damaged: it describes a batch that may be half-applied, so the file is not"
                    + " opened over it. The log is kept beside the file for manual recovery.");
            }
            deleteQuietly();
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte[] magic = new byte[WAL_MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, WAL_MAGIC)) {
                // A valid checksum over someone else's bytes: neither era ever wrote
                // anything but this magic whole, so this is not a crash artifact but
                // corruption.
                throw new CsoCorruptedException(
                    "The write-ahead log for " + regionPath + " does not carry this format's magic");
            }
            in.readShort(); // format version
            int buckets = in.readInt();
            for (int i = 0; i < buckets; i++) {
                int bucket = in.readInt();
                // The WAL checksum only proves the log was written whole, not that its
                // contents are legal for this file. An out-of-range ordinal must fail as
                // corruption, not escape as an unchecked array-index crash.
                if (bucket < 0 || bucket >= bucketCount) {
                    throw new CsoCorruptedException(
                        "WAL for " + regionPath + " names bucket " + bucket + " but this file has "
                            + bucketCount + " buckets");
                }
                int entries = in.readInt();
                Map<Integer, byte[]> changes = new HashMap<>();
                for (int j = 0; j < entries; j++) {
                    int slot = in.readInt();
                    int length = in.readInt();
                    if (slot < 0 || slot >= chunksPerBucket) {
                        throw new CsoCorruptedException(
                            "WAL for " + regionPath + " names slot " + slot + " but a bucket here holds "
                                + chunksPerBucket + " chunks");
                    }
                    if (length < -1 || length > raw.length) {
                        throw new CsoCorruptedException(
                            "WAL for " + regionPath + " declares a " + length + "-byte chunk");
                    }
                    byte[] data = length < 0 ? null : new byte[length];
                    if (data != null) {
                        in.readFully(data);
                    }
                    changes.put(slot, data);
                }
                applier.apply(bucket, changes);
            }
        } catch (IOException e) {
            throw new CsoCorruptedException("Failed to replay WAL for " + regionPath, e);
        }
    }

    private void deleteQuietly() {
        // No retry here on purpose: this path cleans up leftovers where patience has nothing
        // to protect — a delete that loses to a holder simply leaves the file for the next
        // open, and seconds of backoff behind a "quiet" name is its own kind of failure.
        try {
            Files.deleteIfExists(walPath);
        } catch (IOException ignored) {
            // A leftover WAL is harmless: it gets replayed or ignored on the next open.
        }
    }

    private void deleteTmpQuietly() {
        try {
            Files.deleteIfExists(walPath.resolveSibling(walPath.getFileName() + ".tmp"));
        } catch (IOException ignored) {
            // Never committed, so nothing it could have said is being lost by leaving it.
        }
    }
}
