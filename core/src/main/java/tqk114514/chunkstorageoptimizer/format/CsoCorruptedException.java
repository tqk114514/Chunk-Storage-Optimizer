package tqk114514.chunkstorageoptimizer.format;

import java.io.IOException;

/**
 * Thrown when a CSO region file fails validation: bad magic, unsupported version, CRC mismatch,
 * or out-of-range offsets.
 *
 * <p>This is deliberately an {@link IOException} so it propagates through the vanilla chunk IO
 * path and surfaces as a loud failure. Never silently treat a corrupt region as empty — that is
 * how terrain gets regenerated.
 */
public class CsoCorruptedException extends IOException {
    public CsoCorruptedException(String message) {
        super(message);
    }

    public CsoCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
