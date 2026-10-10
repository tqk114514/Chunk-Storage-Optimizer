package tqk114514.chunkstorageoptimizer.format;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Moves a freshly written temp file onto its target, tolerating the transient Windows lock.
 *
 * <p>Every writer in this format settles its output through a temp name and a move — the
 * region files both ways, the write-ahead log, the offline converter. On Windows those moves
 * can fail with an {@link AccessDeniedException} that is nobody's bug: the file was created,
 * filled and closed milliseconds ago, and a real-time scanner (Defender, the search indexer)
 * opens brand-new files the moment their last handle closes, so the rename lands on a handle
 * open without FILE_SHARE_DELETE. Measured in-game (2026-10-09): a {@code /cso convert mca}
 * that had been converting for seconds died on exactly this, on one file — with nothing else
 * holding it, and a target that did not even exist before the run started. The scan releases
 * in tens of milliseconds, so the move is retried with backoff rather than reported: a
 * storage engine must not fail a world conversion over an antivirus's peek at its own output.
 *
 * <p>Only {@code AccessDeniedException} is retried — every other failure means what it says.
 * The backoff is bounded (25+50+100+200+400 ms), and it only ever runs on the thread that was
 * about to block on the file anyway.
 */
public final class FileMoves {

    private FileMoves() {
    }

    /**
     * Deletes with the same tolerance the moves get: Windows denies deleting a file any other
     * handle has open, and the write-ahead log's clear runs on the live save path, where the
     * holder can be a scanner's peek (measured in-game: a chunk store failed outright over
     * one). Same backoff, same refusal to report what a retry can survive.
     */
    public static void settleDelete(Path file) throws IOException {
        AccessDeniedException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (AccessDeniedException e) {
                last = e;
                try {
                    Thread.sleep(Math.min(500L, 25L << attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    public static void settle(Path temp, Path target, StandardCopyOption... options) throws IOException {
        AccessDeniedException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Files.move(temp, target, options);
                return;
            } catch (AccessDeniedException e) {
                last = e;
                try {
                    Thread.sleep(Math.min(500L, 25L << attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }
}
