package tqk114514.chunkstorageoptimizer.compat.voxy;

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
 * The mixin contract, checked against every release of Voxy that exists for a Minecraft this
 * mod ships for. Voxy ships for Fabric only so far, which is why this matrix has one loader
 * in it — the day it ships for NeoForge, the download task picks that up by itself.
 */
class VoxyVersionMatrixTest {

    @TestFactory
    Stream<DynamicTest> everyReleaseHoldsTheMixinContract() throws IOException {
        Path dir = Path.of(System.getProperty("cso.targetMods.voxy"));
        Path manifest = dir.resolve("manifest.tsv");
        if (!Files.isRegularFile(manifest)) {
            fail("no matrix manifest in " + dir + " — did downloadTargetMods run?");
        }
        MixinContracts contract = MixinContracts.from(
            new String[] {"tqk114514/chunkstorageoptimizer/compat/voxy/mixin/WorldImporterMixin"},
            Map.of());
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(manifest)) {
            if (!line.isBlank()) {
                rows.add(line.split("\t", -1));
            }
        }
        if (rows.isEmpty()) {
            fail("the matrix manifest is empty — Voxy has releases for these Minecrafts, "
                + "so an empty matrix means the download found nothing");
        }
        return rows.stream().map(row -> dynamicTest(
            "voxy " + row[1] + " (" + row[2] + ", MC " + row[3] + ")",
            () -> {
                List<MixinContracts.Violation> violations =
                    contract.verifyAgainstJar(dir.resolve(row[4]));
                assertTrue(violations.isEmpty(),
                    () -> violations.stream().map(MixinContracts.Violation::description)
                        .collect(java.util.stream.Collectors.joining("\n")));
            }));
    }
}
