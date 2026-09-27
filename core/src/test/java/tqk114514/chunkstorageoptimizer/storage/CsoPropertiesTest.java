package tqk114514.chunkstorageoptimizer.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tqk114514.chunkstorageoptimizer.format.CsoFormat;

/**
 * The text format is what a player edits by hand, so every failure path here has to end in a value
 * that still works plus a line naming the key that failed. Ranges are {@link CsoSettings}'s
 * business; these tests check that this layer only rejects what it cannot even type as a number.
 */
class CsoPropertiesTest {

    private static CsoProperties.Values parse(String text, List<String> warnings) {
        CsoProperties.Values values = new CsoProperties.Values();
        warnings.addAll(CsoProperties.parse(new StringReader(text), values));
        return values;
    }

    @Test
    void anEmptyFileLeavesEveryKeyAtItsDefault() {
        List<String> warnings = new ArrayList<>();
        CsoProperties.Values values = parse("", warnings);
        assertEquals(CsoSettings.defaults(), values.settings(), "an empty file is the default config");
        assertTrue(values.enabled, "a missing file must not silently turn the mod off");
        assertTrue(warnings.isEmpty(), String.valueOf(warnings));
    }

    @Test
    void everyKeyIsActuallyRead() {
        CsoProperties.Values values = parse("""
            enabled=false
            grid=4
            compression=none
            zstdLevel=9
            cachedBuckets=2
            verifyCrc=false
            fallbackToMca=false
            compactionMinBytes=8192
            compactionRatio=1.5
            batchMaxChunks=64
            batchMaxDelayMs=250
            """, new ArrayList<>());
        assertEquals(false, values.enabled);
        assertEquals(4, values.grid);
        assertEquals("none", values.compression);
        assertEquals(9, values.zstdLevel);
        assertEquals(2, values.cachedBuckets);
        assertEquals(false, values.verifyCrc);
        assertEquals(false, values.fallbackToMca);
        assertEquals(8192L, values.compactionMinBytes);
        assertEquals(1.5, values.compactionRatio, 0.0);
        assertEquals(64, values.batchMaxChunks);
        assertEquals(250L, values.batchMaxDelayMs);

        CsoSettings settings = values.settings();
        assertEquals(4, settings.grid());
        assertEquals(CsoFormat.COMPRESSION_NONE, settings.compressionId());
        assertEquals(9, settings.level());
        assertEquals(2, settings.cachedBuckets());
        assertEquals(false, settings.verifyCrc());
        assertEquals(false, settings.fallbackToMca());
        assertEquals(8192L, settings.compactionMinBytes());
        assertEquals(1.5, settings.compactionRatio(), 0.0);
        assertEquals(64, settings.batchMaxChunks());
        assertEquals(250L, settings.batchMaxDelayMs());
    }

    @Test
    void oneUnreadableLineOnlyCostsThatKey() {
        List<String> warnings = new ArrayList<>();
        CsoProperties.Values values = parse("grid=abc\nzstdLevel=7\n", warnings);
        assertEquals(CsoSettings.defaults().grid(), values.grid, "a typo must not change the grid");
        assertEquals(7, values.zstdLevel, "the next key is still read");
        assertEquals(List.of("grid: 'abc' is not a whole number, keeping 16"), warnings);
    }

    @Test
    void aBadBooleanNamesItselfInsteadOfGuessing() {
        List<String> warnings = new ArrayList<>();
        CsoProperties.Values values = parse("verifyCrc=yes\n", warnings);
        assertTrue(values.verifyCrc);
        assertEquals(List.of("verifyCrc: 'yes' is not true or false, keeping true"), warnings);
    }

    @Test
    void compressionAcceptsBothNamesAndRejectsACodecThatDoesNotExist() {
        assertEquals("none", parse("compression=NONE\n", new ArrayList<>()).compression,
            "case is not part of the value");
        List<String> warnings = new ArrayList<>();
        CsoProperties.Values values = parse("compression=brotli\n", warnings);
        assertEquals("zstd", values.compression, "an unknown codec would be read back as zstd anyway");
        assertEquals(List.of("compression: 'brotli' is not zstd or none, keeping zstd"), warnings);
    }

    @Test
    void aValueThatIsTheWrongSizeStillReachesTheRangeItBelongsIn() {
        // This layer keeps out-of-range numbers; CsoSettings.normalized is what clamps them, so the
        // file never gets rewritten behind the reader's back and the storage still sees a legal grid.
        CsoProperties.Values values = parse("zstdLevel=99\ngrid=20\n", new ArrayList<>());
        assertEquals(99, values.zstdLevel);
        assertEquals(20, values.grid);
        assertEquals(CsoSettings.MAX_LEVEL, values.settings().level());
        assertEquals(16, values.settings().grid());
    }

    @Test
    void commentsAndUnknownKeysAreLeftAlone() {
        List<String> warnings = new ArrayList<>();
        CsoProperties.Values values = parse("# a note\nbogus=1\n\ngrid=\n", warnings);
        assertEquals(CsoSettings.defaults(), values.settings());
        assertTrue(warnings.isEmpty(), "an empty value is a key nobody finished typing, not a typo: " + warnings);
    }

    @Test
    void anUnreadableStreamIsAWarningNotAFailure() {
        CsoProperties.Values values = new CsoProperties.Values();
        List<String> warnings = CsoProperties.parse(new Reader() {
            @Override
            public int read(char[] cbuf, int offset, int length) throws IOException {
                throw new IOException("disk gone");
            }

            @Override
            public void close() {
            }
        }, values);
        assertEquals(CsoSettings.defaults(), values.settings());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("the file could not be read: "), warnings.get(0));
        assertTrue(warnings.get(0).contains("disk gone"), "the reason has to survive into the log");
    }

    @Test
    void formatAndParseAgreeOnEveryKeyName() {
        CsoProperties.Values written = new CsoProperties.Values();
        written.enabled = false;
        written.grid = 8;
        written.compression = "none";
        written.zstdLevel = 11;
        written.cachedBuckets = 5;
        written.verifyCrc = false;
        written.fallbackToMca = false;
        written.compactionMinBytes = 9_000_000L;
        written.compactionRatio = 3.25;
        written.batchMaxChunks = 33;
        written.batchMaxDelayMs = 4_321L;

        String text = CsoProperties.format(written);
        List<String> warnings = new ArrayList<>();
        CsoProperties.Values readBack = parse(text, warnings);
        assertEquals(text, CsoProperties.format(readBack), "the file is stable across a rewrite");
        assertTrue(warnings.isEmpty(), "the comments must not be parsed as values: " + warnings);
        assertEquals(false, readBack.enabled);
        assertEquals(8, readBack.grid);
        assertEquals("none", readBack.compression);
        assertEquals(11, readBack.zstdLevel);
        assertEquals(5, readBack.cachedBuckets);
        assertEquals(false, readBack.verifyCrc);
        assertEquals(false, readBack.fallbackToMca);
        assertEquals(9_000_000L, readBack.compactionMinBytes);
        assertEquals(3.25, readBack.compactionRatio, 0.0);
        assertEquals(33, readBack.batchMaxChunks);
        assertEquals(4_321L, readBack.batchMaxDelayMs);
    }
}
