package tqk114514.chunkstorageoptimizer.compat.xaero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The detection scan's file pattern, checked against the names a region folder actually holds.
 * The pattern must keep accepting every vanilla name (Xaero's behavior for un-migrated worlds
 * depends on it), start accepting the .cso names this mod writes, and keep extracting the
 * region coordinates from the same groups — Xaero reads them at fixed indices.
 */
class WorldSaveRegionScanTest {

    private static final String ORIGINAL = "^r\\.(-{0,1}[0-9]+)\\.(-{0,1}[0-9]+)\\.mc[ar]$";
    private static final String WIDENED = "^r\\.(-{0,1}[0-9]+)\\.(-{0,1}[0-9]+)\\.(mc[ar]|cso)$";

    @Test
    void widenedScanAcceptsCsoRegionFiles() {
        Pattern pattern = Pattern.compile(WIDENED);
        assertTrue(pattern.matcher("r.0.0.cso").matches());
        assertTrue(pattern.matcher("r.-1.-1.cso").matches());
        assertTrue(pattern.matcher("r.12.-34.cso").matches());
    }

    @Test
    void widenedScanKeepsEveryVanillaName() {
        Pattern pattern = Pattern.compile(WIDENED);
        assertTrue(pattern.matcher("r.0.0.mca").matches());
        assertTrue(pattern.matcher("r.-3.21.mcr").matches());
    }

    @Test
    void widenedScanRejectsNonRegionFiles() {
        Pattern pattern = Pattern.compile(WIDENED);
        assertFalse(pattern.matcher("r.0.0.mcb").matches());
        assertFalse(pattern.matcher("r.0.0.cso.tmp").matches());
        assertFalse(pattern.matcher("r.0.0.cso.wal").matches());
        assertFalse(pattern.matcher("sessions.lock").matches());
        assertFalse(pattern.matcher("data").matches());
    }

    @Test
    void regionCoordinatesComeFromTheSameGroups() {
        Pattern pattern = Pattern.compile(WIDENED);
        Matcher matcher = pattern.matcher("r.-12.34.cso");
        assertTrue(matcher.matches());
        assertEquals("-12", matcher.group(1));
        assertEquals("34", matcher.group(2));
    }

    @Test
    void originalPatternIsTheWorldSaveScan() {
        // The mixin matches Xaero's own pattern by string identity; this pins the literal it
        // must equal, so a Xaero rename degrades to "scan unchanged" instead of a bad match.
        Pattern.compile(ORIGINAL);
        assertFalse(Pattern.compile(ORIGINAL).matcher("r.0.0.cso").matches());
    }
}
