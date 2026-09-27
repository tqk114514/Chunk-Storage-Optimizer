package tqk114514.chunkstorageoptimizer.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import tqk114514.chunkstorageoptimizer.format.AnvilRegionFile;
import tqk114514.chunkstorageoptimizer.format.CsoFormat;
import tqk114514.chunkstorageoptimizer.format.CsoRegionFile;
import tqk114514.chunkstorageoptimizer.metrics.CsoStats;

/**
 * Command-line converter and benchmark for the CSO format.
 *
 * <p>Works purely on bytes: it never parses NBT, so it needs no Minecraft runtime and can be run
 * against any world folder on disk.
 *
 * <pre>
 *   bench   &lt;regionDir&gt; [--grid 16] [--level 3]         // report sizes, change nothing
 *   convert &lt;regionDir&gt; --to cso [--grid 16] [--level 3] // write .cso next to each .mca
 *   convert &lt;regionDir&gt; --to mca                        // write .mca next to each .cso
 * </pre>
 *
 * {@code bench} converts into a temp directory and deletes it, so it is safe on a live world.
 */
public final class Converter {

    private Converter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
            return;
        }
        String command = args[0];
        Path dir = null;
        // Matches Config.GRID's default so an unparameterized run reports what the mod ships.
        int grid = 16;
        int level = 3;
        String to = "cso";
        String from = "auto";

        // The directory is the first non-option argument, or --dir. Real save folders often contain
        // spaces ("Los Perrito"), so the path MUST arrive as one argument — the csoTool Gradle task
        // passes it via -PcsoDir to avoid shell word-splitting.
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--") && i + 1 < args.length) {
                switch (arg) {
                    case "--grid" -> grid = parseInt(args[++i], 16);
                    case "--level" -> level = parseInt(args[++i], 3);
                    case "--to" -> to = args[++i];
                    case "--from" -> from = args[++i];
                    case "--dir" -> dir = Path.of(args[++i]);
                    default -> { /* unknown option: ignore rather than crash */ }
                }
            } else if (!arg.startsWith("--") && dir == null) {
                dir = Path.of(arg);
            }
        }

        if (dir == null) {
            System.err.println("Missing directory. Pass --dir <path>; paths with spaces must be one argument.");
            usage();
            return;
        }
        if (!Files.isDirectory(dir)) {
            System.err.println("Not a directory: " + dir);
            return;
        }
        switch (command) {
            case "bench" -> bench(dir, grid, level, from);
            case "ab" -> ab(dir, grid, level, from);
            case "amp" -> amp(dir, level, from);
            case "walcost" -> walCost(dir, level, from);
            case "count" -> count(dir);
            case "convert" -> convert(dir, to, grid, level);
            default -> usage();
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void usage() {
        System.out.println("""
            Chunk Storage Optimizer — converter

              bench   --dir <regionDir> [--grid 16] [--level 3]
              ab      --dir <regionDir> [--grid 16] [--level 3]   // write+read timing vs vanilla
              amp     --dir <regionDir> [--level 3]               // write volume per grid size
              walcost --dir <regionDir> [--level 3]               // cost of the write-ahead log
              count   --dir <regionDir>                           // chunk census, changes nothing
              convert --dir <regionDir> --to cso [--grid 16] [--level 3]
              convert --dir <regionDir> --to mca

            bench/ab/amp/walcost read whichever format is in the directory; force one with
            --from mca|cso. A save that has already been converted is still measurable.

            The directory must be one argument — save folders usually contain spaces.
            """);
    }

    // ------------------------------------------------------------------ corpus

    /**
     * A directory's benchmark corpus: the chunks it holds, as raw NBT bytes.
     *
     * @param kind        which format supplied the corpus, {@code mca} or {@code cso}
     * @param byFile      non-empty region files and their chunks, in filename order
     * @param onDiskBytes bytes those files occupy right now
     * @param totalFiles  how many candidate files the directory held before {@code maxFiles} cut in
     */
    record Corpus(
        String kind,
        Map<Path, List<AnvilRegionFile.Chunk>> byFile,
        long onDiskBytes,
        int totalFiles
    ) {
        boolean fromAnvil() {
            return "mca".equals(kind);
        }
    }

    /** Which format a directory should be measured from: Anvil wins when both are present. */
    static String kindOf(Path dir, String from) throws IOException {
        return switch (from) {
            case "mca" -> "mca";
            case "cso" -> "cso";
            default -> listFiles(dir, ".mca").isEmpty() ? "cso" : "mca";
        };
    }

    /** Passed to {@link #corpus} when the caller really does want every file in memory. */
    static final int ALL_FILES = Integer.MAX_VALUE;

    /**
     * Loads the corpus from whichever format is present.
     *
     * <p>Anvil wins when both exist: it is the baseline every comparison is expressed against, and
     * its bytes are what vanilla actually wrote. Rebuilding Anvil bytes from {@code .cso} uses our
     * own writer, which is not byte-identical to vanilla's, so that number is an estimate and the
     * callers say so.
     *
     * @param from      {@code auto}, {@code mca} or {@code cso}
     * @param maxFiles  how many files may be held at once; {@link #ALL_FILES} is deliberate, since
     *                  a whole overworld will not fit in the heap decompressed
     */
    static Corpus corpus(Path dir, String from, int maxFiles) throws IOException {
        String kind = kindOf(dir, from);
        List<Path> candidates = listFiles(dir, "cso".equals(kind) ? ".cso" : ".mca");
        Map<Path, List<AnvilRegionFile.Chunk>> byFile = new LinkedHashMap<>();
        long onDisk = 0;
        for (Path source : candidates) {
            if (byFile.size() >= maxFiles) {
                break;
            }
            List<AnvilRegionFile.Chunk> chunks = "cso".equals(kind)
                ? readCso(source) : AnvilRegionFile.read(source);
            if (!chunks.isEmpty()) {
                byFile.put(source, chunks);
                onDisk += Files.size(source);
            }
        }
        return new Corpus(kind, byFile, onDisk, candidates.size());
    }

    /** The first non-empty region file, or null after reporting that the directory has none. */
    private static Map.Entry<Path, List<AnvilRegionFile.Chunk>> firstFile(Path dir, String from)
        throws IOException {
        Corpus corpus = corpus(dir, from, 1);
        if (corpus.byFile().isEmpty()) {
            System.out.println("No readable region files in " + dir);
            return null;
        }
        return corpus.byFile().entrySet().iterator().next();
    }

    // ------------------------------------------------------------------ bench

    private static void bench(Path dir, int grid, int level, String from) throws IOException {
        String kind = kindOf(dir, from);
        boolean anvil = "mca".equals(kind);
        List<Path> files = listFiles(dir, anvil ? ".mca" : ".cso");
        if (files.isEmpty()) {
            System.out.println("No readable region files in " + dir);
            return;
        }
        Path scratch = Files.createTempDirectory("cso-bench");
        try {
            long anvilTotal = 0;
            long csoTotal = 0;
            long liveBytes = 0;
            long chunkCount = 0;
            int measured = 0;
            long startedAt = System.nanoTime();

            // One file at a time, deliberately: bench reports the directory total, and a 1.2 GB
            // overworld does not fit in the heap once its chunks are decompressed.
            for (Path file : files) {
                List<AnvilRegionFile.Chunk> chunks = anvil
                    ? AnvilRegionFile.read(file) : readCso(file);
                if (chunks.isEmpty()) {
                    continue;
                }
                measured++;
                String name = file.getFileName().toString();
                Path target = scratch.resolve(swapExtension(name, ".cso"));
                writeCso(target, chunks, grid, level);
                csoTotal += Files.size(target);
                chunkCount += chunks.size();
                // From .mca the baseline is the real file vanilla wrote; from .cso it has to be
                // rebuilt, and our writer is only an approximation of what vanilla would have done.
                if (anvil) {
                    anvilTotal += Files.size(file);
                    liveBytes += Files.size(file);
                } else {
                    Path rebuilt = scratch.resolve(swapExtension(name, ".mca"));
                    AnvilRegionFile.write(rebuilt, chunks);
                    anvilTotal += Files.size(rebuilt);
                    liveBytes += Files.size(file);
                }
            }

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            if (anvilTotal == 0) {
                System.out.println("No readable chunks found.");
                return;
            }
            double saved = 100.0 * (1.0 - (double) csoTotal / anvilTotal);
            System.out.printf("source       : .%s in %s (%d of %d files, %d chunks)%n",
                kind, dir, measured, files.size(), chunkCount);
            if (!anvil) {
                System.out.printf("cso on disk  : %s (as written)%n", human(liveBytes));
            }
            System.out.printf("anvil (.mca) : %s (%d bytes)%s%n", human(anvilTotal), anvilTotal,
                anvil ? "" : "  [rebuilt by this tool — an estimate]");
            System.out.printf("cso  (grid=%d, zstd L%d): %s (%d bytes)%n", grid, level, human(csoTotal), csoTotal);
            System.out.printf("saved        : %.1f%%  (ratio %.2fx)%n", saved, (double) anvilTotal / csoTotal);
            System.out.printf("convert time : %d ms%n", elapsedMs);
        } finally {
            deleteRecursively(scratch);
        }
    }

    // ------------------------------------------------------------------ a/b benchmark

    /**
     * Times both formats doing the same job — write every chunk, then read every chunk back —
     * using real chunks taken from the save's own region files. Synthetic filler would compress
     * unrealistically well and make both formats look better than they are.
     */
    private static void ab(Path dir, int grid, int level, String from) throws IOException {
        // A full save is hundreds of MB; decompressed it would not fit in the heap. A slice of
        // region files is representative enough and keeps this runnable against real worlds.
        Corpus corpus = corpus(dir, from, 8);
        Map<Path, List<AnvilRegionFile.Chunk>> inputs = corpus.byFile();
        if (inputs.isEmpty()) {
            System.out.println("No readable region files in " + dir);
            return;
        }
        if (corpus.totalFiles() > inputs.size()) {
            System.out.println("(using " + inputs.size() + " of " + corpus.totalFiles()
                + " region files — the whole save would not fit in memory)");
        }
        int totalChunks = inputs.values().stream().mapToInt(List::size).sum();

        // A single pass in a cold JVM is noise — repeated runs varied by 60%. Warm up, then take
        // the median of several rounds so the numbers mean something.
        int rounds = 6;
        long[] anvilWrite = new long[rounds];
        long[] csoWrite = new long[rounds];
        long[] anvilRead = new long[rounds];
        long[] csoRead = new long[rounds];
        long anvilBytes = 0;
        long csoBytes = 0;

        Path anvilDir = Files.createTempDirectory("cso-ab-anvil");
        Path csoDir = Files.createTempDirectory("cso-ab-cso");
        try {
            for (int round = 0; round < rounds; round++) {
                deleteRecursively(anvilDir);
                deleteRecursively(csoDir);
                Files.createDirectories(anvilDir);
                Files.createDirectories(csoDir);

                // Alternate which format runs first. Whichever goes first in a round absorbs more
                // residual warm-up, so a fixed order would bias the comparison in one direction.
                boolean anvilFirst = round % 2 == 0;
                if (anvilFirst) {
                    anvilWrite[round] = timeWriteAnvil(inputs, anvilDir);
                    csoWrite[round] = timeWriteCso(inputs, csoDir, grid, level);
                    anvilRead[round] = timeReadAnvil(anvilDir);
                    csoRead[round] = timeReadCso(csoDir);
                } else {
                    csoWrite[round] = timeWriteCso(inputs, csoDir, grid, level);
                    anvilWrite[round] = timeWriteAnvil(inputs, anvilDir);
                    csoRead[round] = timeReadCso(csoDir);
                    anvilRead[round] = timeReadAnvil(anvilDir);
                }

                if (round == rounds - 1) {
                    anvilBytes = sizeOf(anvilDir);
                    csoBytes = sizeOf(csoDir);
                }
            }
        } finally {
            deleteRecursively(anvilDir);
            deleteRecursively(csoDir);
        }

        long anvilWriteMs = millis(median(anvilWrite, rounds));
        long csoWriteMs = millis(median(csoWrite, rounds));
        long anvilReadMs = millis(median(anvilRead, rounds));
        long csoReadMs = millis(median(csoRead, rounds));

        System.out.printf("chunks       : %d   (%d rounds, warm-up discarded, median)%n", totalChunks, rounds);
        System.out.printf("write anvil  : %5d ms   %s%n", anvilWriteMs, human(anvilBytes));
        System.out.printf("write cso    : %5d ms   %s   (%.2fx vs anvil, %.2fx smaller)%n",
            csoWriteMs, human(csoBytes), ratio(anvilWriteMs, csoWriteMs), ratio(anvilBytes, csoBytes));
        System.out.printf("read  anvil  : %5d ms%n", anvilReadMs);
        System.out.printf("read  cso    : %5d ms   (%.2fx vs anvil)%n", csoReadMs, ratio(anvilReadMs, csoReadMs));
        System.out.println();
        System.out.println("Caveat: the read pass is a full sequential sweep, which flatters CSO's");
        System.out.println("        bucket cache. Isolated random access costs one decompress per bucket.");
    }

    private static long timeWriteAnvil(Map<Path, List<AnvilRegionFile.Chunk>> inputs, Path dir) throws IOException {
        long started = System.nanoTime();
        for (Map.Entry<Path, List<AnvilRegionFile.Chunk>> entry : inputs.entrySet()) {
            // Named from the corpus extension, which is not always .mca: writing Anvil bytes into
            // a file called r.0.0.cso would make the read pass below find nothing to read.
            String name = swapExtension(entry.getKey().getFileName().toString(), ".mca");
            AnvilRegionFile.write(dir.resolve(name), entry.getValue());
        }
        return System.nanoTime() - started;
    }

    private static long timeWriteCso(Map<Path, List<AnvilRegionFile.Chunk>> inputs, Path dir, int grid, int level)
        throws IOException {
        long started = System.nanoTime();
        for (Map.Entry<Path, List<AnvilRegionFile.Chunk>> entry : inputs.entrySet()) {
            writeCso(
                dir.resolve(swapExtension(entry.getKey().getFileName().toString(), ".cso")),
                entry.getValue(), grid, level
            );
        }
        return System.nanoTime() - started;
    }

    private static long timeReadAnvil(Path dir) throws IOException {
        long started = System.nanoTime();
        for (Path path : listFiles(dir, ".mca")) {
            AnvilRegionFile.read(path);
        }
        return System.nanoTime() - started;
    }

    private static long timeReadCso(Path dir) throws IOException {
        long started = System.nanoTime();
        for (Path path : listFiles(dir, ".cso")) {
            readCso(path);
        }
        return System.nanoTime() - started;
    }

    /** Median of all rounds except the first, which pays JIT warm-up. */
    private static long median(long[] values, int rounds) {
        long[] sample = Arrays.copyOfRange(values, 1, rounds);
        Arrays.sort(sample);
        return sample[sample.length / 2];
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }

    private static double ratio(long baseline, long candidate) {
        return candidate == 0 ? 0.0 : (double) baseline / candidate;
    }

    private static long sizeOf(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        }
    }

    // ------------------------------------------------------------------ write amplification

    /**
     * Measures write amplification for every grid size: bytes that actually reach disk per byte of
     * chunk data the game asked to save.
     *
     * <p>This is the number that decides the default grid. Compression ratio barely moves with grid
     * (already measured), so write amplification is the trade that actually matters.
     */
    private static void amp(Path dir, int level, String from) throws IOException {
        Map.Entry<Path, List<AnvilRegionFile.Chunk>> source = firstFile(dir, from);
        if (source == null) {
            return;
        }
        List<AnvilRegionFile.Chunk> chunks = source.getValue();
        System.out.println("source : " + source.getKey().getFileName() + " (" + chunks.size() + " chunks)");
        System.out.println("churn  : 64 chunks rewritten per round, 6 rounds, sequential window");
        System.out.println();
        System.out.println("logical = raw chunk bytes the game asked to save");
        System.out.println("written = compressed bytes actually written to disk");
        System.out.println("The written/logical ratio is below 1 because compression is folded in.");
        System.out.println("It is NOT pure write amplification — compare it ACROSS grid values.");
        System.out.println();
        System.out.printf("%-6s %-11s %-11s %-14s %-11s %s%n",
            "grid", "logical", "written", "written/log", "finalSize", "time");
        for (int grid : new int[] {1, 2, 4, 8, 16, 32}) {
            try {
                measureAmplification(chunks, grid, level);
            } catch (Exception e) {
                System.out.printf("%-6d FAILED: %s%n", grid, e);
            }
        }
    }

    private static void measureAmplification(List<AnvilRegionFile.Chunk> chunks, int grid, int level)
        throws IOException {
        int window = 64;
        int rounds = 6;
        Path scratch = Files.createTempDirectory("cso-amp");
        try {
            Path target = scratch.resolve("r.0.0.cso");
            CsoStats.reset();
            long logical = 0;
            try (CsoRegionFile file = CsoRegionFile.open(
                target, grid, CsoFormat.COMPRESSION_ZSTD, level, 4, true, Long.MAX_VALUE, 10.0
            )) {
                for (Map.Entry<Integer, Map<Integer, byte[]>> entry : groupByBucket(chunks, grid, false).entrySet()) {
                    file.writeChunks(entry.getKey(), entry.getValue());
                }
                long baseline = CsoStats.snapshot().storedBytes();

                long started = System.nanoTime();
                for (int round = 0; round < rounds; round++) {
                    // A sequential window mimics a player working through one area: the realistic
                    // pattern, and the one that hammers a small number of buckets hardest.
                    int span = Math.max(1, chunks.size() - window);
                    int from = (round * window) % span;
                    List<AnvilRegionFile.Chunk> touched =
                        chunks.subList(from, Math.min(from + window, chunks.size()));
                    for (AnvilRegionFile.Chunk chunk : touched) {
                        logical += chunk.nbt().length;
                    }
                    for (Map.Entry<Integer, Map<Integer, byte[]>> entry
                        : groupByBucket(touched, grid, true).entrySet()) {
                        file.writeChunks(entry.getKey(), entry.getValue());
                    }
                }
                long elapsed = System.nanoTime() - started;
                long written = CsoStats.snapshot().storedBytes() - baseline;

                System.out.printf("%-6d %-11s %-11s %-14.2f %-11s %d ms%n",
                    grid, human(logical), human(written),
                    logical == 0 ? 0.0 : (double) written / logical,
                    human(Files.size(target)), millis(elapsed));
            }
        } finally {
            deleteRecursively(scratch);
        }
    }

    private static Map<Integer, Map<Integer, byte[]>> groupByBucket(
        List<AnvilRegionFile.Chunk> chunks, int grid, boolean mutate
    ) {
        Map<Integer, Map<Integer, byte[]>> byBucket = new HashMap<>();
        for (AnvilRegionFile.Chunk chunk : chunks) {
            int localX = chunk.index() % 32;
            int localZ = chunk.index() / 32;
            int bucket = CsoFormat.bucketIndex(localX, localZ, grid);
            int slot = CsoFormat.chunkIndexInBucket(localX, localZ, grid);
            byBucket.computeIfAbsent(bucket, k -> new HashMap<>())
                .put(slot, mutate ? mutate(chunk.nbt()) : chunk.nbt());
        }
        return byBucket;
    }

    /** Small in-place change, standing in for "the player modified this chunk". */
    private static byte[] mutate(byte[] data) {
        byte[] out = data.clone();
        if (out.length > 8) {
            out[0] ^= 0x01;
            out[out.length / 2] ^= 0x02;
        }
        return out;
    }

    // ------------------------------------------------------------------ wal cost

    /**
     * What durability actually costs. The WAL adds a forced write for the log itself, plus a forced
     * write before the log may be discarded. That is not free, so it is measured rather than
     * assumed — especially since this format's whole justification is being fast at writing.
     */
    private static void walCost(Path dir, int level, String from) throws IOException {
        Map.Entry<Path, List<AnvilRegionFile.Chunk>> source = firstFile(dir, from);
        if (source == null) {
            return;
        }
        List<AnvilRegionFile.Chunk> chunks = source.getValue();
        int grid = 16;
        int rounds = 20;
        int batchSize = 32;
        System.out.println("source: " + source.getKey().getFileName() + "  grid=" + grid
            + "  " + batchSize + " chunks/batch  " + rounds + " batches (median)");

        long[] without = new long[rounds];
        long[] with = new long[rounds];
        Path scratch = Files.createTempDirectory("cso-wal");
        try {
            try (CsoRegionFile file = CsoRegionFile.open(
                scratch.resolve("plain.cso"), grid, CsoFormat.COMPRESSION_ZSTD, level, 4, true,
                Long.MAX_VALUE, 10.0
            )) {
                for (int round = 0; round < rounds; round++) {
                    Map<Integer, Map<Integer, byte[]>> batch = slidingBatch(chunks, batchSize, grid, round);
                    long started = System.nanoTime();
                    for (Map.Entry<Integer, Map<Integer, byte[]>> entry : batch.entrySet()) {
                        file.writeChunks(entry.getKey(), entry.getValue());
                    }
                    file.flush();
                    without[round] = System.nanoTime() - started;
                }
            }
            try (CsoRegionFile file = CsoRegionFile.open(
                scratch.resolve("logged.cso"), grid, CsoFormat.COMPRESSION_ZSTD, level, 4, true,
                Long.MAX_VALUE, 10.0
            )) {
                for (int round = 0; round < rounds; round++) {
                    Map<Integer, Map<Integer, byte[]>> batch = slidingBatch(chunks, batchSize, grid, round);
                    long started = System.nanoTime();
                    file.writeWal(batch);
                    for (Map.Entry<Integer, Map<Integer, byte[]>> entry : batch.entrySet()) {
                        file.writeChunks(entry.getKey(), entry.getValue());
                    }
                    file.flush();
                    file.clearWal();
                    with[round] = System.nanoTime() - started;
                }
            }
        } finally {
            deleteRecursively(scratch);
        }

        long plain = median(without, rounds);
        long logged = median(with, rounds);
        System.out.printf("without WAL : %d ms per batch%n", millis(plain));
        System.out.printf("with    WAL : %d ms per batch%n", millis(logged));
        System.out.printf("overhead    : %.2fx%n", ratio(plain, logged));
    }

    private static Map<Integer, Map<Integer, byte[]>> slidingBatch(
        List<AnvilRegionFile.Chunk> chunks, int count, int grid, int round
    ) {
        int span = Math.max(1, chunks.size() - count);
        int from = (round * count) % span;
        return groupByBucket(chunks.subList(from, Math.min(from + count, chunks.size())), grid, true);
    }

    // ------------------------------------------------------------------ census

    /**
     * Read-only census of a directory: how many chunks it holds and the average chunk size.
     *
     * <p>Exists because comparing two saves by file size alone is meaningless unless they hold the
     * same chunks. This never writes anything.
     */
    private static void count(Path dir) throws IOException {
        List<Path> mcaFiles = listFiles(dir, ".mca");
        if (!mcaFiles.isEmpty()) {
            int chunks = 0;
            long bytes = 0;
            for (Path file : mcaFiles) {
                chunks += AnvilRegionFile.read(file).size();
                bytes += Files.size(file);
            }
            System.out.printf("anvil (.mca) : %d files, %d chunks, %s   avg %d B/chunk%n",
                mcaFiles.size(), chunks, human(bytes), chunks == 0 ? 0 : bytes / chunks);
        }
        List<Path> csoFiles = listFiles(dir, ".cso");
        if (!csoFiles.isEmpty()) {
            int chunks = 0;
            long bytes = 0;
            for (Path file : csoFiles) {
                chunks += readCso(file).size();
                bytes += Files.size(file);
            }
            System.out.printf("cso   (.cso) : %d files, %d chunks, %s   avg %d B/chunk%n",
                csoFiles.size(), chunks, human(bytes), chunks == 0 ? 0 : bytes / chunks);
        }
        if (mcaFiles.isEmpty() && csoFiles.isEmpty()) {
            System.out.println("No .mca or .cso files in " + dir);
        }
    }

    // ------------------------------------------------------------------ estimate

    /**
     * What a directory's chunks would weigh at several bucket grids, measured on a sample.
     *
     * @param filesSampled how many region files the sample covers
     * @param totalFiles   how many the directory holds
     * @param kind         {@code mca} or {@code cso}, whichever format supplied the sample
     * @param currentBytes bytes those sampled files occupy right now
     * @param bytesByGrid  grid -> bytes the same chunks need when rewritten at that grid
     */
    public record Estimate(
        int filesSampled,
        int totalFiles,
        String kind,
        int chunks,
        long currentBytes,
        Map<Integer, Long> bytesByGrid
    ) {
    }

    /** Rewrites a sample of {@code dir} into a scratch folder at each grid and weighs the result. */
    public static Estimate estimate(Path dir, String from, int maxFiles, int[] grids) throws IOException {
        Corpus corpus = corpus(dir, from, maxFiles);
        if (corpus.byFile().isEmpty()) {
            return new Estimate(0, 0, from, 0, 0L, Map.of());
        }
        int chunks = 0;
        long current = 0;
        for (Map.Entry<Path, List<AnvilRegionFile.Chunk>> entry : corpus.byFile().entrySet()) {
            chunks += entry.getValue().size();
            current += Files.size(entry.getKey());
        }
        Map<Integer, Long> byGrid = new LinkedHashMap<>();
        Path scratch = Files.createTempDirectory("cso-estimate");
        try {
            for (int grid : grids) {
                long total = 0;
                for (Map.Entry<Path, List<AnvilRegionFile.Chunk>> entry : corpus.byFile().entrySet()) {
                    Path target = scratch.resolve(swapExtension(entry.getKey().getFileName().toString(), ".cso"));
                    Files.deleteIfExists(target);
                    writeCso(target, entry.getValue(), grid, 3);
                    total += Files.size(target);
                }
                byGrid.put(grid, total);
            }
        } finally {
            deleteRecursively(scratch);
        }
        return new Estimate(corpus.byFile().size(), corpus.totalFiles(), corpus.kind(), chunks, current, byGrid);
    }

    // ------------------------------------------------------------------ convert

    private static void convert(Path dir, String target, int grid, int level) throws IOException {
        if ("cso".equals(target)) {
            List<Path> sources = listFiles(dir, ".mca");
            long total = 0;
            for (Path source : sources) {
                List<AnvilRegionFile.Chunk> chunks = AnvilRegionFile.read(source);
                if (chunks.isEmpty()) {
                    continue;
                }
                Path out = dir.resolve(swapExtension(source.getFileName().toString(), ".cso"));
                writeCso(out, chunks, grid, level);
                total += chunks.size();
                System.out.println(out.getFileName() + " <- " + chunks.size() + " chunks");
            }
            System.out.println("Converted " + total + " chunks to .cso. Originals left untouched.");
            return;
        }
        if ("mca".equals(target)) {
            List<Path> sources = listFiles(dir, ".cso");
            long total = 0;
            for (Path source : sources) {
                List<AnvilRegionFile.Chunk> chunks = readCso(source);
                if (chunks.isEmpty()) {
                    continue;
                }
                Path out = dir.resolve(swapExtension(source.getFileName().toString(), ".mca"));
                AnvilRegionFile.write(out, chunks);
                total += chunks.size();
                System.out.println(out.getFileName() + " <- " + chunks.size() + " chunks");
            }
            System.out.println("Converted " + total + " chunks back to .mca. Originals left untouched.");
            return;
        }
        System.err.println("--to must be cso or mca");
    }

    // ------------------------------------------------------------------ io

    /** Writes chunks into a fresh CSO file, one compress per bucket. */
    public static void writeCso(Path target, List<AnvilRegionFile.Chunk> chunks, int grid, int level)
        throws IOException {
        CsoFormat.validateGrid(grid);
        Map<Integer, Map<Integer, byte[]>> byBucket = new HashMap<>();
        for (AnvilRegionFile.Chunk chunk : chunks) {
            int localX = chunk.index() % 32;
            int localZ = chunk.index() / 32;
            int bucket = CsoFormat.bucketIndex(localX, localZ, grid);
            int slot = CsoFormat.chunkIndexInBucket(localX, localZ, grid);
            byBucket.computeIfAbsent(bucket, k -> new HashMap<>()).put(slot, chunk.nbt());
        }
        // Compaction disabled: a freshly converted file is already laid out optimally.
        try (CsoRegionFile file = CsoRegionFile.open(
            target, grid, CsoFormat.COMPRESSION_ZSTD, level, 4, true, Long.MAX_VALUE, 10.0
        )) {
            for (Map.Entry<Integer, Map<Integer, byte[]>> entry : byBucket.entrySet()) {
                file.writeChunks(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Reads every chunk out of a CSO file, walking bucket by bucket so each payload is
     * decompressed once instead of once per chunk.
     */
    public static List<AnvilRegionFile.Chunk> readCso(Path source) throws IOException {
        List<AnvilRegionFile.Chunk> out = new ArrayList<>();
        try (CsoRegionFile file = CsoRegionFile.open(
            source, 8, CsoFormat.COMPRESSION_ZSTD, 3, 64, true, Long.MAX_VALUE, 10.0
        )) {
            int grid = file.grid();
            int span = CsoFormat.span(grid);
            for (int bucket = 0; bucket < CsoFormat.bucketCount(grid); bucket++) {
                int bucketX = bucket % grid;
                int bucketZ = bucket / grid;
                for (int slot = 0; slot < CsoFormat.chunksPerBucket(grid); slot++) {
                    int localX = bucketX * span + (slot % span);
                    int localZ = bucketZ * span + (slot / span);
                    byte[] nbt = file.readChunk(localX, localZ);
                    if (nbt != null) {
                        out.add(new AnvilRegionFile.Chunk(localX + localZ * 32, nbt));
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    public static List<Path> listFiles(Path dir, String extension) throws IOException {
        // A dimension may have a region/ or poi/ directory but no entities/ one (nothing has been
        // written there yet). Missing is not an error — it just means there is nothing to convert.
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                .filter(p -> p.getFileName().toString().endsWith(extension))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        }
    }

    public static String swapExtension(String name, String extension) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + extension;
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.2f %s", value, units[unit]);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
