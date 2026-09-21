package tqk114514.chunkstorageoptimizer.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import tqk114514.chunkstorageoptimizer.ChunkStorageOptimizer;
import tqk114514.chunkstorageoptimizer.Config;
import tqk114514.chunkstorageoptimizer.CsoRuntime;
import tqk114514.chunkstorageoptimizer.format.AnvilRegionFile;
import tqk114514.chunkstorageoptimizer.metrics.CsoLatency;
import tqk114514.chunkstorageoptimizer.metrics.CsoStats;
import tqk114514.chunkstorageoptimizer.storage.CsoRegistry;
import tqk114514.chunkstorageoptimizer.storage.CsoStorage;
import tqk114514.chunkstorageoptimizer.tools.Converter;

/**
 * {@code /cso stats | reset | compact} — the only way to see whether the format is actually
 * helping on a given world, since the vanilla JFR region hooks are bypassed.
 */
@EventBusSubscriber(modid = ChunkStorageOptimizer.MODID)
public final class CsoCommands {

    private CsoCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cso")
                // 26.1 replaced the numeric permission level with a PermissionCheck tree.
                .requires(Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_ADMIN)))
                .then(Commands.literal("stats").executes(CsoCommands::stats))
                .then(Commands.literal("reset").executes(CsoCommands::reset))
                .then(Commands.literal("compact").executes(CsoCommands::compact))
                .then(Commands.literal("report")
                    .executes(context -> report(context, 2))
                    .then(Commands.argument("files", IntegerArgumentType.integer(1, 8))
                        .executes(context -> report(context, IntegerArgumentType.getInteger(context, "files")))))
                .then(Commands.literal("convert")
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("cso", "mca"), builder))
                        .executes(CsoCommands::convertWorld)
                        .then(Commands.argument("prune", StringArgumentType.word())
                            .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("prune"), builder))
                            .executes(CsoCommands::convertWorld))))
        );
    }

    private static int stats(CommandContext<CommandSourceStack> context) {
        String report = format(CsoStats.snapshot());
        context.getSource().sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        CsoStats.reset();
        for (CsoStorage storage : CsoRegistry.all()) {
            storage.resetStats();
        }
        context.getSource().sendSuccess(() -> Component.literal("CSO stats reset."), false);
        return 1;
    }

    private static int compact(CommandContext<CommandSourceStack> context) {
        long startedAt = System.nanoTime();
        try {
            int files = CsoRegistry.compactAll();
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            String message = "CSO: compacted " + files + " region files in " + millis + " ms.";
            context.getSource().sendSuccess(() -> Component.literal(message), false);
            return files;
        } catch (Exception e) {
            String message = "CSO compaction failed: " + e;
            context.getSource().sendFailure(Component.literal(message));
            return 0;
        }
    }

    private static final int[] REPORT_GRIDS = {1, 8, 16, 32};
    private static final Set<String> STORE_DIRECTORIES = Set.of("region", "poi", "entities");

    /**
     * {@code /cso report [files]} — samples each store directory and weighs what the same chunks
     * would occupy at several bucket grids.
     *
     * <p>Runs on a worker thread: rewriting a sample of a live world would otherwise stall the tick
     * loop for as long as it takes, so the finished text is handed back through the server thread.
     */
    private static int report(CommandContext<CommandSourceStack> context, int sampleFiles) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Path root = server.getWorldPath(LevelResource.ROOT);
        try {
            // Same precaution as convert: get the game's own queue onto disk and drop our handles,
            // so the sample is not read out from under a half-written file.
            saveAll(source);
            CsoRegistry.pauseAll();
        } catch (IOException e) {
            source.sendFailure(Component.literal("CSO report aborted: " + e));
            return 0;
        }
        source.sendSuccess(() -> Component
            .literal("CSO: sampling " + root + " (" + sampleFiles + " files per directory)..."), false);

        Thread.startVirtualThread(() -> {
            StringBuilder text = new StringBuilder("CSO report — up to ").append(sampleFiles)
                .append(" files per directory, zstd L3; percentages are of the sampled bytes now on disk\n");
            try {
                for (Path dir : storeDirectories(root)) {
                    Converter.Estimate estimate = Converter.estimate(dir, "auto", sampleFiles, REPORT_GRIDS);
                    if (estimate.filesSampled() > 0) {
                        text.append(reportLine(root, dir, estimate)).append('\n');
                    }
                }
            } catch (Exception e) {
                text.append("CSO report failed: ").append(e).append('\n');
            }
            String finished = text.toString();
            server.execute(() -> source.sendSuccess(() -> Component.literal(finished), false));
        });
        return 1;
    }

    private static List<Path> storeDirectories(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, 5)) {
            return walk.filter(Files::isDirectory)
                .filter(dir -> STORE_DIRECTORIES.contains(String.valueOf(dir.getFileName())))
                .filter(CsoCommands::holdsRegionFiles)
                .sorted()
                .toList();
        }
    }

    private static boolean holdsRegionFiles(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.anyMatch(file -> {
                String name = file.getFileName().toString();
                return name.endsWith(".mca") || name.endsWith(".cso");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static String reportLine(Path root, Path dir, Converter.Estimate estimate) {
        StringBuilder sizes = new StringBuilder();
        estimate.bytesByGrid().forEach((grid, bytes) -> sizes
            .append("g=").append(grid).append(' ').append(human(bytes))
            .append(String.format(" (%.0f%%) ", 100.0 * bytes / estimate.currentBytes())));
        return "  " + root.relativize(dir) + String.format(" [%s %d/%d files, %d chunks] now=%s | ",
            estimate.kind(), estimate.filesSampled(), estimate.totalFiles(), estimate.chunks(),
            human(estimate.currentBytes())) + sizes;
    }

    /**
     * {@code /cso convert cso|mca} — migrates region files in place without leaving the game.
     *
     * <p>Converting to {@code mca} is the way off the mod: it disables CSO when it finishes, so the
     * world cannot end up being written in two formats at once.
     */
    private static int convertWorld(CommandContext<CommandSourceStack> context) {
        String target = StringArgumentType.getString(context, "target");
        if (!"cso".equals(target) && !"mca".equals(target)) {
            context.getSource().sendFailure(Component.literal("Target must be 'cso' or 'mca'."));
            return 0;
        }
        boolean prune = isPruneRequested(context);
        CommandSourceStack source = context.getSource();
        long startedAt = System.nanoTime();
        try {
            // The game keeps its own queue of unwritten chunks. Get those to disk before moving any
            // bytes, otherwise the conversion silently misses them.
            saveAll(source);

            List<Path> folders = CsoRegistry.pauseAll();
            int files = 0;
            int chunks = 0;
            int deleted = 0;
            for (Path folder : folders) {
                if ("cso".equals(target)) {
                    for (Path mcaFile : Converter.listFiles(folder, ".mca")) {
                        List<AnvilRegionFile.Chunk> in = AnvilRegionFile.read(mcaFile);
                        if (in.isEmpty()) {
                            // Nothing to carry over. Under prune this file would linger forever,
                            // and once CSO is disabled there is no second chance to clean it up.
                            if (prune) {
                                Files.delete(mcaFile);
                                deleted++;
                            }
                            continue;
                        }
                        Path out = folder.resolve(Converter.swapExtension(mcaFile.getFileName().toString(), ".cso"));
                        Converter.writeCso(out, in, Config.GRID.getAsInt(), Config.ZSTD_LEVEL.getAsInt());
                        verify(out, in.size(), target);
                        if (prune) {
                            Files.delete(mcaFile);
                            deleted++;
                        }
                        files++;
                        chunks += in.size();
                    }
                } else {
                    for (Path csoFile : Converter.listFiles(folder, ".cso")) {
                        List<AnvilRegionFile.Chunk> in = Converter.readCso(csoFile);
                        if (in.isEmpty()) {
                            // Same as above: an empty file carries nothing, so prune removes it
                            // instead of leaving it behind.
                            if (prune) {
                                Files.delete(csoFile);
                                deleted++;
                            }
                            continue;
                        }
                        Path out = folder.resolve(Converter.swapExtension(csoFile.getFileName().toString(), ".mca"));
                        AnvilRegionFile.write(out, in);
                        verify(out, in.size(), target);
                        if (prune) {
                            Files.delete(csoFile);
                            deleted++;
                        }
                        files++;
                        chunks += in.size();
                    }
                }
            }

            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            StringBuilder message = new StringBuilder("CSO: converted ")
                .append(files).append(" files (").append(chunks).append(" chunks) to .").append(target)
                .append(" in ").append(millis).append(" ms.");
            if (prune) {
                message.append(" Deleted ").append(deleted).append(" original file(s).");
            } else {
                message.append(" Originals kept — repeat with 'prune' to delete them.");
            }
            if ("mca".equals(target)) {
                CsoRuntime.disable("converted back to .mca");
                message.append(" CSO disabled — a world must not be written in two formats at once.");
            }
            String text = message.toString();
            source.sendSuccess(() -> Component.literal(text), false);
            return files;
        } catch (Exception e) {
            String message = "CSO conversion failed: " + e;
            source.sendFailure(Component.literal(message));
            return 0;
        }
    }

    private static boolean isPruneRequested(CommandContext<CommandSourceStack> context) {
        try {
            return "prune".equalsIgnoreCase(StringArgumentType.getString(context, "prune"));
        } catch (IllegalArgumentException e) {
            return false; // optional argument not supplied
        }
    }

    /**
     * Reads the result back before anything is deleted. Deletion is irreversible, so it must never
     * rest on the assumption that the write worked.
     */
    private static void verify(Path written, int expectedChunks, String target) throws IOException {
        int actual = "cso".equals(target)
            ? Converter.readCso(written).size()
            : AnvilRegionFile.read(written).size();
        if (actual != expectedChunks) {
            throw new IOException(
                "verification failed for " + written.getFileName() + ": wrote " + expectedChunks
                    + " chunks but read back " + actual + " — nothing was deleted"
            );
        }
    }

    /** Runs {@code /save-all flush} via the dispatcher instead of depending on an internal API. */
    private static void saveAll(CommandSourceStack source) {
        try {
            var dispatcher = source.getServer().getCommands().getDispatcher();
            dispatcher.execute(dispatcher.parse("save-all flush", source));
        } catch (CommandSyntaxException e) {
            // Not fatal: conversion still runs, it just may miss chunks not yet written.
        }
    }

    private static String format(CsoStats.Snapshot s) {
        String state = CsoRuntime.isActive() ? "active" : "inactive (" + CsoRuntime.reason() + ")";
        StringBuilder report = new StringBuilder("CSO [" + state + "]\n")
            .append("  chunks   read=").append(s.chunksRead())
            .append("  written=").append(s.chunksWritten())
            .append("  deleted=").append(s.chunksDeleted()).append('\n')
            .append("  buckets  decompress=").append(s.bucketDecompressions())
            .append("  compress=").append(s.bucketCompressions()).append('\n')
            .append("  cache    hit=").append(percent(s.cacheHitRatio()))
            .append("  (").append(decimal(s.chunksPerDecompression())).append(" chunks per decompression)\n")
            .append("  bytes    rawIn=").append(human(s.rawBytesIn()))
            .append("  stored=").append(human(s.storedBytes()))
            .append("  ratio=").append(decimal(s.writeRatio())).append("x\n")
            .append("  io       read=").append(human(s.ioReadBytes()))
            .append("  write=").append(human(s.ioWriteBytes())).append('\n')
            .append("  time     compress=").append(millis(s.compressNanos()))
            .append("  decompress=").append(millis(s.decompressNanos()))
            .append("  compaction=").append(millis(s.compactionNanos()))
            .append(" (").append(s.compactions()).append(" runs)\n")
            .append("  batch    flushes=").append(s.batchFlushes()).append('\n')
            // Totals hide a single bad save behind a thousand good ones, so the distribution is
            // printed next to them.
            .append("  latency  ").append(latency("flush   ", CsoStats.flushLatency())).append('\n')
            .append("  latency  ").append(latency("compress", CsoStats.compressLatency())).append('\n')
            .append("  latency  ").append(latency("decompress", CsoStats.decompressLatency())).append('\n');

        List<CsoStorage> storages = CsoRegistry.all();
        if (!storages.isEmpty()) {
            report.append("  stores   (percentiles are approximate: the histogram bins are powers of two)\n");
            for (CsoStorage storage : storages) {
                report.append("    ").append(store(storage)).append('\n');
            }
        }
        return report.toString();
    }

    private static String store(CsoStorage storage) {
        return String.format("%-24s read=%-8d write=%-8d pending=%-5d %s",
            storage.label(), storage.chunksReadCount(), storage.chunksWrittenCount(),
            storage.pendingCount(), latency("flush", storage.flushLatency()));
    }

    private static String latency(String name, CsoLatency latency) {
        long samples = latency.count();
        if (samples == 0) {
            return name + " no samples";
        }
        return name + " n=" + samples
            + "  p50=" + ms(latency.percentileNanos(50.0))
            + "  p95=" + ms(latency.percentileNanos(95.0))
            + "  max=" + ms(latency.maxNanos());
    }

    private static String ms(double nanos) {
        return String.format("%.2f ms", nanos / 1_000_000.0);
    }

    private static String percent(double value) {
        return String.format("%.1f%%", value * 100.0);
    }

    private static String decimal(double value) {
        return String.format("%.2f", value);
    }

    private static String millis(long nanos) {
        return String.format("%.2f s", nanos / 1_000_000_000.0);
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
        return String.format("%.1f %s", value, units[unit]);
    }
}
