package tqk114514.chunkstorageoptimizer.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CsoStorage#regionFileOf} over every value a region cache has been seen to hold.
 *
 * <p>The Optional crash of 1.1.6 was a bare cast compiled against the 1.21 shape; these tests
 * pin both shapes the walk claims to recognise, so removing either branch — or breaking the
 * instanceof order — fails here rather than in a conversion.
 */
class CsoRegionFileUnwrapTest {

    private RegionFile openFile;

    @AfterEach
    void closeFile() throws Exception {
        if (openFile != null) {
            openFile.close();
        }
    }

    @Test
    void bareRegionFileUnwrapsToItself(@TempDir Path dir) throws Exception {
        RegionFile file = openRegionFile(dir);
        assertEquals(file, CsoStorage.regionFileOf(file));
    }

    @Test
    void optionalWrappedRegionFileUnwraps(@TempDir Path dir) throws Exception {
        RegionFile file = openRegionFile(dir);
        assertEquals(file, CsoStorage.regionFileOf(Optional.of(file)));
    }

    @Test
    void emptyOptionalIsNothingToClose(@TempDir Path dir) throws Exception {
        assertNull(CsoStorage.regionFileOf(Optional.empty()));
    }

    @Test
    void optionalOfSomethingElseIsNothingToClose(@TempDir Path dir) throws Exception {
        // 26.x memoizes a failed open as Optional.empty, but a wrapper holding anything that
        // is not a RegionFile must never be cast on faith either.
        assertNull(CsoStorage.regionFileOf(Optional.of("not a region file")));
        assertNull(CsoStorage.regionFileOf(new Object()));
        assertNull(CsoStorage.regionFileOf(null));
    }

    private RegionFile openRegionFile(Path dir) throws Exception {
        if (openFile != null) {
            return openFile;
        }
        Path regionDir = dir.resolve("region");
        Files.createDirectories(regionDir);
        openFile = new RegionFile(
            new RegionStorageInfo("world", null, "region"),
            regionDir.resolve("r.0.0.mca"),
            regionDir,
            false
        );
        return openFile;
    }
}
