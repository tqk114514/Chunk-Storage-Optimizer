package tqk114514.chunkstorageoptimizer.storage;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import tqk114514.chunkstorageoptimizer.format.AnvilRegionFile;
import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.format.CsoRegionFile;

/**
 * What one unreadable chunk in a legacy {@code .mca} costs the storage layer, measured.
 *
 * <p>{@code CsoStorage.legacyChunkBytes} retries a failing read three times with a 100 ms sleep
 * between them, on the deliberate reasoning that a transient disk fault must not cost a chunk. The
 * reasoning is right; what this measures is where that waiting happens: the seed runs inside
 * {@code flushPending}, which runs inside the storage lock, which the periodic flush of EVERY
 * storage in the process shares one single-threaded timer for.
 */
class CsoLegacySeedRetryCostTest {

    private static final int GRID = 16;          // four chunks per bucket, so (1,0) seeds beside (0,0)
    private static final long RETRY_DELAY_MS = 100L;
    private static final int ATTEMPTS = 3;

    @BeforeAll
    static void bootstrapTheGameStatics() throws Exception {
        java.lang.reflect.Field bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true);
        bootstrapped.setBoolean(null, true);
    }

    @Test
    void anUnreadableLegacyChunkIsWaitedOutInsideTheWriteLock(@TempDir Path world) throws IOException {
        Path region = world.resolve("region");
        Files.createDirectories(region);
        Path mca = region.resolve("r.0.0.mca");
        writeMca(mca, 1);

        CsoStorage storage = new CsoStorage(
            new RegionStorageInfo("world", null, "region"), region, false,
            // One chunk per batch: the write below is then its own flush, so the elapsed time is
            // the cost of the seed and nothing else.
            CsoSettings.normalized(GRID, CsoFormat.COMPRESSION_ZSTD, 3, 4, true, true,
                4L * 1024 * 1024, 0.25, 1, CsoSettings.MAX_BATCH_DELAY_MS),
            new CsoStorage.VanillaHandles() {
                @Override
                public void pauseForConversion() {
                }

                @Override
                public void resumeAfterConversion() {
                }
            });

        long startedAt = System.nanoTime();
        storage.write(new ChunkPos(0, 0), chunk("the chunk being saved"));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        try {
            assertTrue(elapsedMs >= (ATTEMPTS - 1) * RETRY_DELAY_MS,
                "measured " + elapsedMs + " ms for one unreadable neighbour: the retry sleeps run"
                    + " inside flushPending, under the storage lock");
        } finally {
            storage.close();
        }

        // And what the waiting buys: the batch went through, the unreadable neighbour did not.
        try (CsoRegionFile written = CsoRegionFile.openReadOnly(region.resolve("r.0.0.cso"), 3, 4, true)) {
            assertNotNull(written.readChunk(0, 0), "the chunk being saved landed");
            assertNull(written.readChunk(1, 0),
                "the chunk this build could not read is now absent from a bucket that is"
                    + " authoritative for it — its .mca copy is never consulted again");
            assertTrue(written.hasBucket(1, 0),
                "the bucket was written, so it answers for slot (1,0) even though nothing was read");
        }
    }

    /**
     * An {@code .mca} whose given slots are named by the header but cannot be decoded: the length is
     * still plausible and the stream still opens, so the failure surfaces only on the read — the
     * shape {@code legacyChunkBytes} exists for, and the one a crash mid-write leaves behind.
     */
    private static void writeMca(Path mca, int... unreadableSlots) throws IOException {
        List<AnvilRegionFile.Chunk> chunks = new java.util.ArrayList<>();
        for (int slot = 0; slot < 34; slot++) {
            chunks.add(new AnvilRegionFile.Chunk(slot, nbt(chunk("chunk-" + slot))));
        }
        AnvilRegionFile.write(mca, chunks);

        byte[] bytes = Files.readAllBytes(mca);
        for (int slot : unreadableSlots) {
            int location = ((bytes[slot * 4] & 0xFF) << 16)
                | ((bytes[slot * 4 + 1] & 0xFF) << 8)
                | (bytes[slot * 4 + 2] & 0xFF);
            int versionAt = location * 4096 + 4;  // [length:4][version:1][compressed body...]
            bytes[versionAt] = 2;                 // zlib: the stream opens, the inflater then fails
            for (int i = versionAt + 1; i < versionAt + 48; i++) {
                bytes[i] = (byte) (i * 31 + 7);
            }
        }
        Files.write(mca, bytes, StandardOpenOption.WRITE);
    }

    /** The chunk's raw NBT bytes, the way the format layer stores them. */
    private static byte[] nbt(CompoundTag tag) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(sink)) {
            NbtIo.write(tag, out);
        }
        return sink.toByteArray();
    }

    private static CompoundTag chunk(String marker) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", "minecraft:full");
        tag.putString("cso:test", marker);
        return tag;
    }
}
