package tqk114514.chunkstorageoptimizer.format;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Systematic destruction of a written file, in the shape the two real-world killers come in:
 * a power cut truncates the file at an arbitrary byte, a torn disk or a bad cable flips bytes.
 * The reader's contract under both is the same, and it is what this pins:
 *
 * <ul>
 * <li>every read answers with the exact bytes of a state the file legitimately held — the
 * newest one, or an older one a torn table entry legitimately fell back to — or reports
 * corruption as an IOException;
 * <li>no read, at any damage point, ever answers with bytes that were never written, and no
 * damage crashes with anything but an IOException.
 * </ul>
 *
 * Every sweep is deterministic and the damage lands in the failure message, so a failure
 * names its exact byte. Truncation is dense over the header and the two bucket tables —
 * where every byte is load-bearing — and sampled over the data blocks.
 */
class CsoRegionFileFuzzTest {

    private static final int GRID = 16;
    private static final int COMPRESSION = CsoFormat.COMPRESSION_ZSTD;
    private static final int LEVEL = 3;
    private static final long SEED = 20261008L;

    /** The slots both write waves touch, keyed like the read checks: {@code x * 32 + z}. */
    private static final int[] PROBED_SLOTS = probedSlots();

    private static int[] probedSlots() {
        int[] slots = new int[32];
        for (int x = 0; x < 24; x++) {
            slots[x] = slot(x, 0);
        }
        for (int x = 0; x < 8; x++) {
            slots[24 + x] = slot(x, 1);
        }
        return slots;
    }

    private static int slot(int x, int z) {
        return x * 32 + z;
    }

    private static int slotX(int slot) {
        return slot / 32;
    }

    private static int slotZ(int slot) {
        return slot % 32;
    }

    /**
     * The file under attack: two write waves, so a torn newest table copy has an older
     * legitimate state to fall back to — the exact situation the fallback exists for. Wave 2
     * overwrites a third of wave 1, deletes another third and writes fresh chunks on a second
     * row of buckets, so every fallback path carries state that differs between the waves.
     */
    private record States(byte[] pristine, Map<Integer, byte[]> wave1, Map<Integer, byte[]> wave2) {
    }

    private States buildStates(Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.cso");
        Map<Integer, byte[]> wave1 = new HashMap<>();
        try (CsoRegionFile region = open(file)) {
            for (int i = 0; i < 24; i++) {
                byte[] data = chunkData(100 + i, 300 + i * 37);
                region.writeChunk(i, 0, data);
                wave1.put(slot(i, 0), data);
            }
        }
        Map<Integer, byte[]> wave2 = new HashMap<>(wave1);
        try (CsoRegionFile region = open(file)) {
            for (int i = 0; i < 8; i++) {
                byte[] data = chunkData(900 + i, 250 + i * 51);
                region.writeChunk(i, 0, data);
                wave2.put(slot(i, 0), data);
            }
            for (int i = 8; i < 16; i++) {
                region.writeChunk(i, 0, null);
                wave2.remove(slot(i, 0));
            }
            for (int i = 0; i < 8; i++) {
                byte[] data = chunkData(500 + i, 600 + i * 13);
                region.writeChunk(i, 1, data);
                wave2.put(slot(i, 1), data);
            }
        }
        return new States(Files.readAllBytes(file), wave1, wave2);
    }

    @Test
    void noTruncationPointEverAnswersWithBytesThatWereNeverWritten(@TempDir Path dir) throws IOException {
        States states = buildStates(dir);
        List<String> violations = new ArrayList<>();
        for (int length : truncationOffsets(states.pristine().length)) {
            byte[] damaged = Arrays.copyOf(states.pristine(), length);
            probe(dir, damaged, states, "truncated to " + length + " of " + states.pristine().length, violations);
            if (violations.size() >= 8) {
                break;
            }
        }
        assertTrue(violations.isEmpty(),
            "reads answered with bytes no legitimate state ever held:\n" + String.join("\n", violations));
    }

    @Test
    void noSingleBitFlipEverAnswersWithBytesThatWereNeverWritten(@TempDir Path dir) throws IOException {
        States states = buildStates(dir);
        List<String> violations = new ArrayList<>();
        Random random = new Random(SEED);
        byte[] damaged = new byte[states.pristine().length];
        for (int i = 0; i < 400 && violations.isEmpty(); i++) {
            System.arraycopy(states.pristine(), 0, damaged, 0, damaged.length);
            int offset = random.nextInt(damaged.length);
            damaged[offset] ^= (byte) (i % 2 == 0 ? 0x01 : 0x80);
            probe(dir, damaged, states,
                "byte " + offset + " flipped with 0x" + (i % 2 == 0 ? "01" : "80") + " (seed " + SEED + ")",
                violations);
        }
        assertTrue(violations.isEmpty(),
            "reads answered with bytes no legitimate state ever held:\n" + String.join("\n", violations));
    }

    private static int[] truncationOffsets(int length) {
        // Dense over the structural prefix — header, write-ahead log, both table copies —
        // then a prime stride over the data blocks, plus the very last byte.
        int dense = Math.min(length, 4096);
        List<Integer> offsets = new ArrayList<>();
        for (int i = 1; i < dense; i++) {
            offsets.add(i);
        }
        for (int i = dense; i < length - 1; i += 97) {
            offsets.add(i);
        }
        offsets.add(length - 1);
        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The one place the whole contract is checked. Every probed slot must answer with null,
     * an IOException (corruption reported — any subtype), or the bytes of one of the two
     * states that file passed through; a slot no state ever held must answer null or throw.
     */
    private void probe(Path dir, byte[] damaged, States states, String damage, List<String> violations) {
        Path file = dir.resolve("damaged.cso");
        try {
            Files.write(file, damaged);
        } catch (IOException e) {
            throw new AssertionError("cannot stage the damaged copy", e);
        }
        try (CsoRegionFile region = open(file)) {
            for (int slot : PROBED_SLOTS) {
                byte[] read;
                try {
                    read = region.readChunk(slotX(slot), slotZ(slot));
                } catch (IOException e) {
                    continue; // corruption reported — allowed
                }
                String got = read == null ? null : chunkFingerprint(read);
                if (read != null
                    && !matches(read, states.wave2().get(slot))
                    && !matches(read, states.wave1().get(slot))) {
                    violations.add(damage + ": slot " + slot + " returned a chunk neither state ever held ("
                        + read.length + " bytes)");
                }
            }
            byte[] neverWritten;
            try {
                neverWritten = region.readChunk(31, 31);
            } catch (IOException e) {
                neverWritten = null;
            }
            if (neverWritten != null) {
                violations.add(damage + ": a slot no state ever wrote returned " + neverWritten.length + " bytes");
            }
        } catch (IOException e) {
            // The open itself refused the file — allowed. Any RuntimeException from open or
            // read escapes the probe and fails the test: corruption must be an IOException.
        }
    }

    private static boolean matches(byte[] read, byte[] expected) {
        return expected != null && Arrays.equals(read, expected);
    }

    /** Cheap identity for failure messages; the comparison itself uses {@link #matches}. */
    private static String chunkFingerprint(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum = sum * 31 + b;
        }
        return Integer.toHexString(sum) + ":" + data.length;
    }

    private static CsoRegionFile open(Path path) throws IOException {
        return CsoRegionFile.open(path, GRID, COMPRESSION, LEVEL, 4, true, 1 << 20, 0.5);
    }

    /** Semi-compressible blob that stands in for serialized chunk NBT. */
    private static byte[] chunkData(int seed, int size) {
        byte[] out = new byte[size];
        Random random = new Random(seed);
        int p = 0;
        while (p < size) {
            int run = Math.min(size - p, 16 + random.nextInt(32));
            byte value = (byte) random.nextInt();
            for (int i = 0; i < run; i++) {
                out[p++] = value;
            }
        }
        return out;
    }
}
