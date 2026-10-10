package tqk114514.chunkstorageoptimizer.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.mojang.logging.LogUtils;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.storage.LevelResource;

import tqk114514.chunkstorageoptimizer.CsoBossBars;
import tqk114514.chunkstorageoptimizer.CsoPermissions;
import tqk114514.chunkstorageoptimizer.CsoRuntime;
import tqk114514.chunkstorageoptimizer.format.AnvilRegionFile;
import tqk114514.chunkstorageoptimizer.metrics.CsoLatency;
import tqk114514.chunkstorageoptimizer.metrics.CsoStats;
import tqk114514.chunkstorageoptimizer.storage.CsoRegistry;
import tqk114514.chunkstorageoptimizer.storage.CsoSettings;
import tqk114514.chunkstorageoptimizer.storage.CsoStorage;
import tqk114514.chunkstorageoptimizer.storage.CsoWorldMarker;
import tqk114514.chunkstorageoptimizer.tools.Converter;

/**
 * {@code /cso stats | reset | compact} — the only way to see whether the format is actually
 * helping on a given world, since the vanilla JFR region hooks are bypassed.
 */
public final class CsoCommands {

    /**
     * slf4j, like the rest of common — deliberately not {@link System.Logger}: without a
     * JUL-to-log4j bridge on the classpath (Fabric ships none), System.Logger output never
     * reaches the game log, and this class's failure reports are the ones that have to.
     */
    private static final Logger LOGGER = LogUtils.getLogger();

    private CsoCommands() {
    }

    /** Called by each loader's command hook; nothing here knows which loader invoked it. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("cso")
                // The permission API is one of the few things Minecraft changed across the versions
                // this mod ships for, so the check itself lives in common/src/version/<family>.
                .requires(CsoPermissions.operatorOnly())
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
        // Whether this world is served is a per-world question, so the state is read against the
        // world the command is running in.
        Path root = context.getSource().getServer().getWorldPath(LevelResource.ROOT).normalize();
        String report = format(CsoStats.snapshot(), CsoRuntime.reason(root));
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
        Path root = server.getWorldPath(LevelResource.ROOT).normalize();
        try {
            // Same precaution as convert: get the game's own queue onto disk and drop our handles,
            // so the sample is not read out from under a half-written file.
            saveAll(source);
            CsoRegistry.pauseWorld(root);
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
     * <p>Converting to {@code mca} is the way off the mod, and the decision is written into the save
     * as {@code cso.disabled} so a restart keeps honouring it. The world's storages are released
     * before any byte moves: a storage that stays attached goes on writing {@code .cso} straight into
     * the directories being converted, and that split is the whole reason this command exists.
     *
     * <p>Both directions work off the store directories on disk rather than off the storages the game
     * happens to have open, so a dimension never visited this session is not skipped.
     */
    private static int convertWorld(CommandContext<CommandSourceStack> context) {
        String target = StringArgumentType.getString(context, "target");
        if (!"cso".equals(target) && !"mca".equals(target)) {
            context.getSource().sendFailure(Component.literal("Target must be 'cso' or 'mca'."));
            return 0;
        }
        CommandSourceStack source = context.getSource();
        Path root = source.getServer().getWorldPath(LevelResource.ROOT).normalize();
        if ("cso".equals(target) && !CsoRuntime.isActive(root)) {
            // A world the mod is not serving has its region files open in the vanilla storage, and
            // those keep writing .mca for the rest of the session. Converted now, the newer .mca
            // data would sit behind the fresh .cso files and read as missing chunks, so the switch
            // back has to happen after the world is re-entered.
            if (CsoWorldMarker.isDisabledRoot(root)) {
                // The opt-out is the one reason this command can act on itself: the marker is
                // read when a world attaches, so clearing it now changes nothing about the
                // running session and saves the player a manual file deletion. The conversion
                // itself still waits — this session's own .mca handles are why it cannot run —
                // but the way back is now two commands and zero file browsing.
                try {
                    CsoWorldMarker.clear(root);
                } catch (IOException e) {
                    source.sendFailure(Component.literal("Could not remove the opt-out marker "
                        + root.resolve(CsoWorldMarker.FILE_NAME) + ": " + e
                        + ". Delete it by hand, re-enter the world, then run /cso convert cso."));
                    return 0;
                }
                source.sendSuccess(() -> Component.literal(
                    "CSO is not serving this world: it opted out, and the game's storage still"
                    + " holds the .mca files open for this session, so converting now would put"
                    + " the newer .mca chunks behind the fresh .cso ones. The opt-out marker is"
                    + " cleared — leave and re-enter the world, then run /cso convert cso again."),
                    false);
                return 0;
            }
            source.sendFailure(Component.literal("CSO is not serving this world (" + CsoRuntime.reason(root)
                + "). Re-enter it with that condition gone, then run /cso convert cso — otherwise the"
                + " .mca files this session still holds open would hide behind the new .cso ones."));
            return 0;
        }
        String pruneArg = pruneArgument(context);
        if (pruneArg != null && !"prune".equalsIgnoreCase(pruneArg)) {
            // An unrecognized second word ran the conversion WITHOUT pruning before, which is the
            // worst outcome of a typo: the user believes the originals are gone while they linger.
            context.getSource().sendFailure(Component.literal(
                "Unknown convert option '" + pruneArg + "' — did you mean 'prune'?"));
            return 0;
        }
        boolean prune = pruneArg != null;
        ConvertProgress progress = null;
        long startedAt = System.nanoTime();
        try {
            // The game keeps its own queue of unwritten chunks. Get those to disk before moving any
            // bytes, otherwise the conversion silently misses them. The work list is taken after
            // it, so files a save creates are converted too.
            saveAll(source);
            String extension = "cso".equals(target) ? ".mca" : ".cso";
            List<Path> inputs = new ArrayList<>();
            for (Path folder : storeDirectories(root)) {
                inputs.addAll(Converter.listFiles(folder, extension));
            }
            progress = ConvertProgress.start(source, target, inputs.size());

            if ("mca".equals(target)) {
                // Detached for the rest of the session BEFORE any byte moves: a storage that
                // stays attached goes on writing .cso into the directories being converted.
                // The durable marker deliberately does NOT happen here — see below the loop.
                CsoRegistry.releaseWorld(root);
            } else {
                CsoRegistry.pauseWorld(root);
            }

            int files = 0;
            int chunks = 0;
            int deleted = 0;
            int unreadableFiles = 0;
            int unreadableChunks = 0;
            int heldFiles = 0;
            // One task per region file, on a bounded pool, while this thread — the server
            // thread, the caller of the command — waits on completions. The freeze is kept:
            // a blocked server thread is the guarantee that nothing writes the region folders
            // mid-conversion, and that guarantee is what makes it safe to hand them to
            // workers. The per-file pipeline (read, convert, write, verify) runs entirely
            // through stateless statics, so files parallelize cleanly. Measured single-
            // threaded on 8.11 GB across 2061 files (2026-10-09, dedicated server): 232 s —
            // the pool divides that by its width. The width bound is memory, not CPU: one
            // task holds a whole region decompressed, and a 32×32 region of large chunks can
            // reach hundreds of MB, so six at once is the ceiling a default heap takes
            // standing still.
            int workers = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));
            java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(workers);
            CsoSettings settings = CsoRuntime.settings();
            try {
                java.util.concurrent.CompletionService<ConvertOutcome> done =
                    new java.util.concurrent.ExecutorCompletionService<>(pool);
                boolean toCso = "cso".equals(target);
                for (Path file : inputs) {
                    done.submit(() -> toCso
                        ? convertOneMcaToCso(file, prune, settings)
                        : convertOneCsoToMca(file, prune));
                }
                for (int i = 0; i < inputs.size(); i++) {
                    try {
                        ConvertOutcome outcome = done.take().get();
                        files += outcome.files();
                        chunks += outcome.chunks();
                        deleted += outcome.deleted();
                        unreadableFiles += outcome.unreadableFiles();
                        unreadableChunks += outcome.unreadableChunks();
                    } catch (java.util.concurrent.ExecutionException e) {
                        if (e.getCause() instanceof java.nio.file.AccessDeniedException) {
                            // Held open by someone else — the world map reading its tiles, a
                            // backup tool, a scanner. Skipping one file is safe in both
                            // directions: its source is untouched and the union read serves
                            // both formats, so a re-run after the holder lets go converges.
                            // Failing the whole run over one busy file is what turned it into
                            // a cascade (measured: the run aborted, its workers did not stop,
                            // and the re-entered world met them mid-file).
                            heldFiles++;
                            continue;
                        }
                        // Any other failure ends the run exactly the way the sequential
                        // loop's would have: what already landed stays converted, the rest is
                        // untouched, and the catch below reports that. shutdownNow only sets
                        // an interrupt flag these tasks do not check, so the pool is DRAINED
                        // before anything unblocks: a worker still inside a file operation is
                        // a writer the save outlives the command by, and the world must not
                        // be un-paused beside it.
                        pool.shutdownNow();
                        try {
                            if (!pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IOException("A conversion worker would not stop within 60 s");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted waiting for the conversion workers");
                        }
                        if (e.getCause() instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        if (e.getCause() instanceof IOException io) {
                            throw io;
                        }
                        if (e.getCause() instanceof Error error) {
                            throw error;
                        }
                        throw e;
                    }
                    progress.step();
                }
            } finally {
                // Success and held-skip paths leave nothing running; the hard-fail path drained
                // above. This is for every other way out — the pool must never outlive the
                // command, because the world un-pauses the moment it returns.
                pool.shutdownNow();
                try {
                    pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            if ("mca".equals(target)) {
                // The durable marker only once every file made it — and none was skipped: a
                // skipped region still lives on the union this mod serves, but the marker
                // hands the world to vanilla, which reads only .mca and would hide the
                // .cso side of that region. Staying unmarked on CSO is the safe half-state;
                // the message says how to finish.
                if (unreadableFiles > 0 || heldFiles > 0) {
                    // fall through without the marker
                } else
                // The durable marker only once every file made it. Written up front, a
                // mid-conversion failure left the save claiming a conversion that never
                // finished: a restart served vanilla .mca for the files that had been
                // converted while the rest of the world still lived only in .cso — half a
                // world, silently chosen for the player (reproduced in-game on 2026-10-04:
                // the marker's timestamp matched the failed run to the second). Released
                // without a marker is safe for the failure case: the released storages stay
                // detached for this session, and a restart re-enters with the mod serving
                // the .cso/.mca union — exactly the pre-conversion state.
                CsoRuntime.disableWorld(root, "converted back to .mca");
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
            if (unreadableFiles > 0) {
                // These files were neither converted nor deleted. Saying so is the whole point:
                // silently reporting success over a partial conversion is how a world gets lost.
                message.append(" SKIPPED ").append(unreadableFiles).append(" file(s) holding ")
                    .append(unreadableChunks).append(" chunk(s) this build cannot decode")
                    .append(" (external .mcc, unknown compression, or unreadable stream) — their")
                    .append(" originals were left untouched.");
            }
            if (heldFiles > 0) {
                message.append(" SKIPPED ").append(heldFiles)
                    .append(" file(s) another program holds open (the world map reading its")
                    .append(" tiles, a backup tool, a scanner) — theirs are untouched; close the")
                    .append(" holder and run the command again to convert just those.");
            }
            if ("mca".equals(target)) {
                if (unreadableFiles > 0 || heldFiles > 0) {
                    message.append(" The switch to vanilla storage is NOT recorded yet: ")
                        .append(unreadableFiles + heldFiles)
                        .append(" region(s) above are still served from their .cso, which vanilla"
                            + " cannot read. Resolve their cause, run /cso convert mca again, and")
                        .append(" the marker lands once every file has made it.");
                } else {
                    message.append(" This world now stays on vanilla storage: the marker ")
                        .append(root.resolve(CsoWorldMarker.FILE_NAME))
                        .append(" survives a restart. To switch back later: run /cso convert cso to clear")
                        .append(" the marker, re-enter the world, then run it again to convert.");
                }
            }
            String text = message.toString();
            source.sendSuccess(() -> Component.literal(text), false);
            return files;
        } catch (Exception e) {
            String message = "CSO conversion failed: " + e
                + ". The world keeps its current storage — nothing was switched by the failed"
                + " run; fix the cause and run /cso convert again.";
            // Chat is where the player reads it; the log is where it has to survive. A
            // conversion aborted mid-way leaves the save in a state only the message
            // explains: files before the failure converted, the rest untouched.
            LOGGER.error(message, e);
            source.sendFailure(Component.literal(message));
            return 0;
        } finally {
            // Whatever happened — finished, failed, anything — the bar goes away; the chat
            // message is what carries the outcome.
            if (progress != null) {
                progress.close();
            }
        }
    }

    /** One region file converted, as a worker reports it back to the waiting command thread. */
    private record ConvertOutcome(
        int files, int chunks, int deleted, int unreadableFiles, int unreadableChunks) {
    }

    /**
     * The mca→cso half of one region file, in worker-thread context. The skip rules are the
     * conversion's safety contract and are documented at each branch; the write itself goes
     * through the same temp-and-atomic-move path every writer here uses, and the full-strength
     * verify backs it before any prune may delete the source.
     */
    private static ConvertOutcome convertOneMcaToCso(Path mcaFile, boolean prune, CsoSettings settings)
        throws IOException {
        // A slot the header names but this parser cannot decode is still a chunk. Counting only
        // the readable ones is what let prune delete a .mca while leaving those chunks behind
        // with no copy anywhere.
        AnvilRegionFile.ReadResult in = AnvilRegionFile.readReporting(mcaFile);
        if (in.unreadable() > 0) {
            // Never touch the source: it is the only copy of those chunks. Any .cso written now
            // would hold a strict subset, so it is skipped as well.
            return new ConvertOutcome(0, 0, 0, 1, in.unreadable());
        }
        if (in.chunks().isEmpty()) {
            // Nothing to carry over. Under prune this file would linger forever, and once CSO
            // is off there is no second chance to clean it up.
            if (prune) {
                Files.delete(mcaFile);
                return new ConvertOutcome(0, 0, 1, 0, 0);
            }
            return new ConvertOutcome(0, 0, 0, 0, 0);
        }
        Path out = mcaFile.resolveSibling(Converter.swapExtension(mcaFile.getFileName().toString(), ".cso"));
        // A half-migrated world already has a .cso for this region holding chunks the .mca
        // never saw. Writing the .mca over it would drop them, so the two are unioned with
        // the .cso winning — the same order the live reader uses.
        List<AnvilRegionFile.Chunk> merged = Files.exists(out)
            ? Converter.prefer(Converter.readCso(out), in.chunks())
            : in.chunks();
        Converter.writeCso(out, merged, settings.grid(), settings.level());
        verify(out, merged.size(), "cso");
        if (prune) {
            Files.delete(mcaFile);
            return new ConvertOutcome(1, merged.size(), 1, 0, 0);
        }
        return new ConvertOutcome(1, merged.size(), 0, 0, 0);
    }

    /** The cso→mca half of one region file; same contract as {@link #convertOneMcaToCso}. */
    private static ConvertOutcome convertOneCsoToMca(Path csoFile, boolean prune) throws IOException {
        List<AnvilRegionFile.Chunk> in = Converter.readCso(csoFile);
        if (in.isEmpty()) {
            // An empty file carries nothing, so prune removes it instead of leaving it behind.
            if (prune) {
                Files.delete(csoFile);
                return new ConvertOutcome(0, 0, 1, 0, 0);
            }
            return new ConvertOutcome(0, 0, 0, 0, 0);
        }
        Path out = csoFile.resolveSibling(Converter.swapExtension(csoFile.getFileName().toString(), ".mca"));
        // A .mca already on disk may carry chunks this .cso never had. It must be read with
        // the reporting reader, not plain read(): a slot that cannot decode is still a chunk,
        // and unioning without it drops those bytes — the offline Converter hard-stops on
        // exactly this case. Leave both files untouched and report the skip.
        AnvilRegionFile.ReadResult existing = Files.exists(out)
            ? AnvilRegionFile.readReporting(out)
            : null;
        if (existing != null && existing.unreadable() > 0) {
            return new ConvertOutcome(0, 0, 0, 1, existing.unreadable());
        }
        List<AnvilRegionFile.Chunk> merged = existing != null
            ? Converter.prefer(in, existing.chunks())
            : in;
        AnvilRegionFile.write(out, merged);
        verify(out, merged.size(), "mca");
        if (prune) {
            Files.delete(csoFile);
            return new ConvertOutcome(1, merged.size(), 1, 0, 0);
        }
        return new ConvertOutcome(1, merged.size(), 0, 0, 0);
    }

    /** The optional second word after {@code convert <target>}, or null when omitted. */
    private static String pruneArgument(CommandContext<CommandSourceStack> context) {
        try {
            return StringArgumentType.getString(context, "prune");
        } catch (IllegalArgumentException e) {
            return null; // optional argument not supplied
        }
    }

    /**
     * Reads the result back before anything is deleted. Deletion is irreversible, so it must never
     * rest on the assumption that the write worked.
     *
     * <p>For the {@code .mca} direction this also insists the read-back was complete: a file whose
     * header names chunks this parser could not decode is not a verified file, even when the count
     * of the ones it could read happens to match.
     */
    private static void verify(Path written, int expectedChunks, String target) throws IOException {
        if ("cso".equals(target)) {
            int actual = Converter.readCso(written).size();
            if (actual != expectedChunks) {
                throw new IOException(
                    "verification failed for " + written.getFileName() + ": wrote " + expectedChunks
                        + " chunks but read back " + actual + " — nothing was deleted"
                );
            }
            return;
        }
        AnvilRegionFile.ReadResult back = AnvilRegionFile.readReporting(written);
        if (back.unreadable() > 0) {
            throw new IOException(
                "verification failed for " + written.getFileName() + ": " + back.unreadable()
                    + " slot(s) could not be read back — nothing was deleted"
            );
        }
        if (back.chunks().size() != expectedChunks) {
            throw new IOException(
                "verification failed for " + written.getFileName() + ": wrote " + expectedChunks
                    + " chunks but read back " + back.chunks().size() + " — nothing was deleted"
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

    private static String format(CsoStats.Snapshot s, String state) {
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

    /**
     * The conversion's progress bar, in the vanilla style Chunky and Voxy use: a boss bar,
     * darkening off, music off, fog off — nothing but progress. It exists only for the run:
     * the {@code finally} in {@link #convertWorld} removes it whether the run finished,
     * failed, or anything between.
     *
     * <p>The conversion stays on the server thread, by design: a blocked thread is the one
     * simple guarantee that nothing writes the region folders while they are being rewritten.
     * The bar works regardless — boss bar updates go out the moment they are set, and the
     * client renders them without waiting for server ticks, so the player watches a frozen
     * server make progress instead of wondering whether it hung.
     *
     * <p>The audience is everyone who could have run the conversion themselves — the same
     * {@link CsoPermissions#operatorOnly()} gate the command requires, so a shared server's
     * other players see why it froze instead of staring at a hang. A conversion started from
     * the console broadcasts the same way; with nobody permitted online there is no bar, and
     * the chat summary still says everything.
     */
    private static final class ConvertProgress {

        private final ServerBossEvent bar;
        private final String target;
        private final int total;
        private int done;

        private static ConvertProgress start(CommandSourceStack source, String target, int total) {
            ServerBossEvent bar = null;
            // The runner, if a player: the pattern variable in an assignment loses its flow
            // scoping, so the reference is taken out first.
            ServerPlayer player = source.getEntity() instanceof ServerPlayer any ? any : null;
            java.util.List<ServerPlayer> online = source.getServer().getPlayerList().getPlayers();
            if (total > 0 && (player != null || !online.isEmpty())) {
                bar = CsoBossBars.create(
                    Component.literal("CSO: converting to ." + target),
                    BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
                bar.setDarkenScreen(false);
                bar.setPlayBossMusic(false);
                bar.setCreateWorldFog(false);
                if (player != null) {
                    bar.addPlayer(player);
                }
                java.util.function.Predicate<CommandSourceStack> gate = CsoPermissions.operatorOnly();
                for (ServerPlayer other : online) {
                    if ((player != null && other == player) || !gate.test(other.createCommandSourceStack())) {
                        continue;
                    }
                    bar.addPlayer(other);
                }
            }
            return new ConvertProgress(bar, target, total);
        }

        private ConvertProgress(ServerBossEvent bar, String target, int total) {
            this.bar = bar;
            this.target = target;
            this.total = total;
        }

        /** One file handled — converted, or deliberately skipped; both move the bar. */
        private void step() {
            this.done++;
            if (this.bar == null) {
                return;
            }
            this.bar.setName(Component.literal("CSO: converting to ." + this.target + " — "
                + this.done + "/" + this.total + " files ("
                + Math.round(this.done * 100.0 / this.total) + "%)"));
            this.bar.setProgress(this.done / (float) this.total);
        }

        private void close() {
            if (this.bar != null) {
                this.bar.removeAllPlayers();
            }
        }
    }
}
