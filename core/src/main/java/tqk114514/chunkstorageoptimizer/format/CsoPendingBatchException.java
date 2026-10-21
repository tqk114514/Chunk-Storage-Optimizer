package tqk114514.chunkstorageoptimizer.format;

import java.io.IOException;

/**
 * A {@code .cso} whose writer still has a batch in flight: the log beside the file describes bytes
 * that are not in the file yet.
 *
 * <p>Only a reader meets this. The writer replays and clears the log as it opens, because recovering
 * that batch is its job; a reader must not, because replaying someone else's batch would take the
 * durability of it away from the holder that staged it. So a reader either waits or says so — the
 * choice is never made silently, since the stale view is exactly what a conversion would write back
 * over the file it came from, and the batch would be gone.
 *
 * <p>The state is transient by construction: the log exists between a batch being logged and that
 * batch being forced, and the writer clears it there. A log that survives the writer's own process
 * is a crash leftover, and the game replays it the next time it opens the file — which is the one
 * thing that must happen before this file is converted or imported.
 */
public class CsoPendingBatchException extends IOException {

    private static final long serialVersionUID = 1L;

    public CsoPendingBatchException(String message) {
        super(message);
    }
}
