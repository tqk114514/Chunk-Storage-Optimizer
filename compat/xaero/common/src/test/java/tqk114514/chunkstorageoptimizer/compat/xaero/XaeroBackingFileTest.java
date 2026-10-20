package tqk114514.chunkstorageoptimizer.compat.xaero;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The backing-file decision the {@code getFile} hook makes, without Xaero or a game on the
 * classpath.
 *
 * <p>Three outcomes matter, and two of them are "do nothing": a world this mod wrote has a
 * {@code .cso} where Xaero looks for an {@code .mca}, and the hook has to hand it the sibling;
 * a world that still has the {@code .mca} — or a region neither file backs — must fall through
 * to Xaero's own resolution unchanged, because a mixed world's vanilla regions are still the
 * map's business. The third "do nothing" is the one a naive implementation gets wrong: a
 * directory that happens to carry the {@code .cso} name is not a region file.
 */
class XaeroBackingFileTest {

    @TempDir
    Path dir;

    private Path region(String name) throws IOException {
        Path region = dir.resolve("region");
        Files.createDirectories(region);
        return region.resolve(name);
    }

    @Test
    void aCsoRegionIsPreferredOverTheAbsentMca() throws IOException {
        Path cso = region("r.3.-7.cso");
        Files.write(cso, new byte[] {1, 2, 3});

        assertEquals(cso, CsoXaeroCompatCore.csoBackingFile(dir, 3, -7));
    }

    @Test
    void aVanillaRegionFallsThroughToXaerosOwnResolution() throws IOException {
        Files.write(region("r.1.1.mca"), new byte[] {1});

        assertNull(CsoXaeroCompatCore.csoBackingFile(dir, 1, 1),
            "an .mca-only region must keep Xaero's behaviour, not be replaced by a guess");
    }

    @Test
    void aRegionNeitherFileBacksAnswersNothing() throws IOException {
        Files.createDirectories(dir.resolve("region"));

        assertNull(CsoXaeroCompatCore.csoBackingFile(dir, 0, 0));
    }

    @Test
    void aDirectoryNamedLikeACsoFileIsNotOne() throws IOException {
        Files.createDirectories(region("r.2.2.cso"));

        assertNull(CsoXaeroCompatCore.csoBackingFile(dir, 2, 2));
    }

    @Test
    void noWorldDirectoryAnswersNothing() {
        assertNull(CsoXaeroCompatCore.csoBackingFile(null, 0, 0));
    }

    @Test
    void theNameIsBuiltFromTheRegionCoordinates() throws IOException {
        Path cso = region("r.-12.34.cso");
        Files.write(cso, new byte[] {1});

        assertEquals(cso, CsoXaeroCompatCore.csoBackingFile(dir, -12, 34));
        assertNull(CsoXaeroCompatCore.csoBackingFile(dir, 12, 34),
            "coordinates are not interchangeable: a different region has no file here");
    }
}
