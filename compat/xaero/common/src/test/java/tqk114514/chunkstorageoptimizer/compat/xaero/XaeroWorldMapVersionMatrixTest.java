package tqk114514.chunkstorageoptimizer.compat.xaero;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import tqk114514.chunkstorageoptimizer.compat.testkit.MixinContracts;

/**
 * The mixin contract, checked against every release of Xaero's World Map that exists for a
 * Minecraft this mod ships for, on both loaders it builds for. The jars are fetched by the
 * downloadTargetMods task in this module's build script; a release that moves any target
 * fails here instead of only in a player's log.
 */
class XaeroWorldMapVersionMatrixTest {

    @TestFactory
    Stream<DynamicTest> everyReleaseHoldsTheMixinContract() throws IOException {
        Path dir = Path.of(System.getProperty("cso.targetMods.xaero"));
        Path manifest = dir.resolve("manifest.tsv");
        if (!Files.isRegularFile(manifest)) {
            fail("no matrix manifest in " + dir + " — did downloadTargetMods run?");
        }
        MixinContracts contract = MixinContracts.from(
            new String[] {"tqk114514/chunkstorageoptimizer/compat/xaero/mixin/MapSaveLoadMixin"},
            Map.of("CSO_WORLD_SAVE_SCAN", "detectRegions"));
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(manifest)) {
            if (!line.isBlank()) {
                rows.add(line.split("\t", -1));
            }
        }
        if (rows.isEmpty()) {
            fail("the matrix manifest is empty — Xaero's World Map has releases for these Minecrafts, "
                + "so an empty matrix means the download found nothing");
        }
        return rows.stream().map(row -> dynamicTest(
            "xaeros-world-map " + row[1] + " (" + row[2] + ", MC " + row[3] + ")",
            () -> {
                List<MixinContracts.Violation> violations =
                    contract.verifyAgainstJar(dir.resolve(row[4]));
                assertTrue(violations.isEmpty(),
                    () -> violations.stream().map(MixinContracts.Violation::description)
                        .collect(java.util.stream.Collectors.joining("\n")));
            }));
    }
}
