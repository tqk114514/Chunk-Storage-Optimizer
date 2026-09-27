package tqk114514.chunkstorageoptimizer.format;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

import java.io.IOException;
import java.util.Arrays;

/**
 * Compression codec for bucket payloads.
 *
 * <p>Operates on whole arrays: a bucket payload is always built as one contiguous
 * {@code byte[]}, so there is no need for offset/length variants.
 */
public interface Compressor {

    /**
     * Compresses into {@code dst} and returns how many bytes it used. Callers that write a bucket
     * on every save keep a scratch buffer precisely because this does not allocate: at grid 16 a
     * bucket is around a hundred kilobytes, and a fresh array per save is a fresh GC candidate.
     *
     * @param dst at least {@link #compressBound(int)} bytes long
     */
    int compress(byte[] input, byte[] dst);

    /** Convenience for callers that do not keep a scratch buffer. */
    default byte[] compress(byte[] input) {
        byte[] dst = new byte[compressBound(input.length)];
        int used = compress(input, dst);
        return used == dst.length ? dst : Arrays.copyOf(dst, used);
    }

    /** Bytes {@code dst} must hold to take the compressed form of {@code inputLength} bytes. */
    int compressBound(int inputLength);

    /**
     * @param expectedRawLength exact uncompressed size recorded in the bucket table;
     *                          used to detect corruption rather than to size the output
     */
    byte[] decompress(byte[] input, int expectedRawLength) throws IOException;

    /** Compression id stored in the file header. */
    int id();

    static Compressor create(int compressionId, int level) {
        return switch (compressionId) {
            case CsoFormat.COMPRESSION_NONE -> new NoneCompressor();
            case CsoFormat.COMPRESSION_ZSTD -> new ZstdCompressor(level);
            default -> throw new IllegalArgumentException("Unsupported compression id: " + compressionId);
        };
    }
}

/**
 * zstd via zstd-jni.
 */
final class ZstdCompressor implements Compressor {
    private final int level;

    /**
     * Native zstd contexts are expensive to allocate and are not thread-safe, so one is reused per
     * thread. This is not a micro-optimisation: allocating a context per call measured writes at
     * roughly 3x slower than vanilla Anvil, which defeated the point of the whole format.
     */
    private final ThreadLocal<ZstdCompressCtx> compressContext;
    private final ThreadLocal<ZstdDecompressCtx> decompressContext;

    ZstdCompressor(int level) {
        if (level < 1 || level > 22) {
            throw new IllegalArgumentException("zstd level must be within [1, 22], got " + level);
        }
        this.level = level;
        this.compressContext = ThreadLocal.withInitial(() -> new ZstdCompressCtx().setLevel(level));
        this.decompressContext = ThreadLocal.withInitial(ZstdDecompressCtx::new);
    }

    @Override
    public int compress(byte[] input, byte[] dst) {
        int used;
        try {
            used = this.compressContext.get().compressByteArray(dst, 0, dst.length, input, 0, input.length);
        } catch (RuntimeException e) {
            throw new IllegalStateException("zstd compression failed for " + input.length + " bytes", e);
        }
        if (used <= 0) {
            throw new IllegalStateException(
                "zstd compression failed for " + input.length + " bytes (returned " + used + ")"
            );
        }
        return used;
    }

    @Override
    public int compressBound(int inputLength) {
        return (int) Zstd.compressBound(inputLength);
    }

    @Override
    public byte[] decompress(byte[] input, int expectedRawLength) throws IOException {
        byte[] out;
        try {
            out = this.decompressContext.get().decompress(input, expectedRawLength);
        } catch (RuntimeException | Error e) {
            // zstd-jni reports malformed input through an unchecked ZstdException. Convert it so
            // callers see one typed, checked failure mode for all corruption.
            throw new CsoCorruptedException(
                "zstd decompression failed for " + input.length + " compressed bytes: " + e.getMessage(), e
            );
        }
        if (out == null) {
            throw new CsoCorruptedException("zstd decompression failed for " + input.length + " compressed bytes");
        }
        if (out.length != expectedRawLength) {
            throw new CsoCorruptedException(
                "decompressed size mismatch: expected " + expectedRawLength + ", got " + out.length
            );
        }
        return out;
    }

    int level() {
        return this.level;
    }

    @Override
    public int id() {
        return CsoFormat.COMPRESSION_ZSTD;
    }
}

/** Pass-through codec; useful as a control when benchmarking. */
final class NoneCompressor implements Compressor {
    @Override
    public int compress(byte[] input, byte[] dst) {
        if (dst.length < input.length) {
            throw new IllegalArgumentException(
                "destination holds " + dst.length + " bytes, needs " + input.length
            );
        }
        System.arraycopy(input, 0, dst, 0, input.length);
        return input.length;
    }

    @Override
    public int compressBound(int inputLength) {
        return inputLength;
    }

    @Override
    public byte[] decompress(byte[] input, int expectedRawLength) throws IOException {
        if (input.length != expectedRawLength) {
            throw new CsoCorruptedException(
                "raw size mismatch: expected " + expectedRawLength + ", got " + input.length
            );
        }
        return input;
    }

    @Override
    public int id() {
        return CsoFormat.COMPRESSION_NONE;
    }
}
