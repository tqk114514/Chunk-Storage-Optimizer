package tqk114514.chunkstorageoptimizer.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the orphaned-region refusal answers on, and what it must never fire on.
 *
 * <p>The refusal is the storage layer's backstop against the one loss it exists to prevent:
 * a folder full of {@code .cso} files handed to a worker that reads {@code .mca}. Its whole
 * contract is "any {@code .cso} in the folder and only then" — everything else about the
 * folder, and every other file in it, is none of its business.
 */
class CsoOrphanedRegionGuardTest {

    @Test
    void aFolderWithCsoFilesIsRefusedWithTheReason(@TempDir Path region) throws IOException {
        Files.createFile(region.resolve("r.0.0.cso"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> CsoOrphanedRegionGuard.refuseOrphanedCso(region, "a reason for the log"),
            "a .cso file in an unserved folder must stop the worker construction");

        assertTrue(refused.getMessage().contains(region.toString()),
            "the message must name the folder it refuses");
        assertTrue(refused.getMessage().contains("a reason for the log"),
            "the message must carry the reason it was given");
        assertTrue(refused.getMessage().contains("convert-back"),
            "the message must tell the player the way out");
    }

    @Test
    void anyCsoFileDecidesEvenBesideMcaFiles(@TempDir Path region) throws IOException {
        // The mixed shape a partially failed conversion leaves behind: regions in .cso beside
        // regions the converter could not touch. The .mca half would load; the .cso half
        // would silently regenerate — so the folder is refused as a whole.
        Files.createFile(region.resolve("r.0.0.mca"));
        Files.createFile(region.resolve("r.1.0.cso"));

        assertThrows(IllegalStateException.class,
            () -> CsoOrphanedRegionGuard.refuseOrphanedCso(region, "why"));
    }

    @Test
    void foldersWithoutCsoPass(@TempDir Path region) throws IOException {
        Files.createFile(region.resolve("r.0.0.mca"));
        Files.createFile(region.resolve("r.0.0.cso.tmp"));
        Files.createFile(region.resolve("level.dat"));

        assertDoesNotThrow(() -> CsoOrphanedRegionGuard.refuseOrphanedCso(region, "why"),
            "neither a plain Anvil folder nor a temp file named like ours may trip the refusal");
    }

    @Test
    void aFolderThatDoesNotExistHoldsNothing(@TempDir Path world) {
        Path absent = world.resolve("region");

        assertFalse(CsoOrphanedRegionGuard.hasCsoFiles(absent),
            "a folder vanilla has not created yet cannot hold anything, and must not refuse");
    }

    @Test
    void theScanFindsCsoAndNothingElse(@TempDir Path region) throws IOException {
        assertFalse(CsoOrphanedRegionGuard.hasCsoFiles(region));
        Files.createFile(region.resolve("r.0.0.cso"));
        assertTrue(CsoOrphanedRegionGuard.hasCsoFiles(region));
        Files.createFile(region.resolve("r.0.0.mca"));
        assertTrue(CsoOrphanedRegionGuard.hasCsoFiles(region),
            "the scan answers for the folder, not for one file");
    }
}
