package tqk114514.chunkstorageoptimizer.compat.c2me;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every C2ME release the bridge can meet, checked against everything the bridge assumes about
 * it — on both of the game's class shapes, because the bridge has a different number of mixins
 * per shape.
 *
 * <p>The bridge deliberately depends on very little of C2ME: the class of the worker its
 * redirect installs (recognised by name, its only handle on the object), the vanilla classes
 * the redirect hijacks the worker construction of, and the fact that the redirect really
 * installs that worker into that field — asserted here through the redirect handler's own
 * signature, because the handler's argument triple is the vanilla call it replaced.
 *
 * <p><b>Two shapes, two levels of assertion.</b> 26.x ships unobfuscated, so its jars name
 * {@code SimpleRegionStorage} directly and the modern half asserts exact targets. 1.21 through
 * 1.21.11 are obfuscated, so their jars carry intermediary names ({@code class_9172}); the exact
 * spelling cannot be checked without that row's mapping file, and inventing one here would make
 * the test a second copy of the mapping table. The legacy half therefore asserts the
 * <em>structure</em> the bridge depends on: four distinct rewrites, each one a worker-construction
 * redirect, each returning the class the recognised worker sits on. The identity of those four
 * classes is covered by the runtime smoke on a legacy row (tools/c2me-smoke.sh), which is where
 * a mapping mismatch would actually surface.
 */
class C2meVersionMatrixTest {

    private static final String WORKER =
        "com/ishland/c2me/rewrites/chunkio/common/C2MEStorageVanillaInterface";

    /** The argument triple the vanilla constructor feeds its worker with, on both shapes. */
    private static final String REDIRECT_HANDLER_DESC =
        "(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Z)"
            + "Lnet/minecraft/world/level/chunk/storage/IOWorker;";

    private static final String SIMPLE_REGION_STORAGE =
        "net/minecraft/world/level/chunk/storage/SimpleRegionStorage";
    private static final String RECREATING_SIMPLE_REGION_STORAGE =
        "net/minecraft/world/level/chunk/storage/RecreatingSimpleRegionStorage";

    /**
     * Rows whose chunk path is still {@code ChunkStorage}. 1.21.11 deleted that class and moved
     * {@code ChunkMap} onto {@code SimpleRegionStorage}, which is the modern shape.
     */
    private static final Set<String> LEGACY_ROWS = Set.of(
        "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
        "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10"
    );

    @Test
    void everyC2meReleaseKeepsTheContractTheBridgeDependsOn() throws IOException {
        Path dir = Path.of(System.getProperty("cso.targetMods.c2me", "build/target-mods/c2me"));
        assertTrue(Files.isDirectory(dir), "the download task must have run first");

        List<String> releases = Files.readAllLines(dir.resolve("manifest.tsv")).stream()
            .filter(line -> !line.isBlank())
            .toList();
        assertFalse(releases.isEmpty(),
            "the matrix holds no release — either Modrinth moved, or -PcsoTargetModsOffline"
                + " was used with an empty cache");

        boolean sawLegacy = false;
        boolean sawModern = false;
        for (String line : releases) {
            String[] columns = line.split("\t");
            String row = columns[3];
            String release = columns[1] + " (" + row + ", " + columns[2] + ")";
            Path module = dir.resolve(
                "c2me-" + columns[2] + "__" + columns[4].replaceAll("\\.jar$", "") + ".rewrites-chunkio.jar");
            assertTrue(Files.isRegularFile(module), "the extracted module for " + release);

            if (LEGACY_ROWS.contains(row)) {
                sawLegacy = true;
                checkLegacyShape(module, release);
            } else {
                sawModern = true;
                checkModernShape(module, release);
            }
        }

        assertTrue(sawModern, "the matrix must cover the modern shape");
        assertTrue(sawLegacy, "the matrix must cover the legacy shape — the bridge has mixins"
            + " that only apply there, and nothing else would notice if C2ME stopped rewriting"
            + " the chunk path");
    }

    /** 1.21.11 and 26.x: one storage class carries chunks, entities and POI. */
    private static void checkModernShape(Path module, String release) throws IOException {
        try (JarFile jar = new JarFile(module.toFile())) {
            assertWorkerSitsOn(jar, "net/minecraft/world/level/chunk/storage/IOWorker", release);

            ClassNode readSide = read(jar,
                "com/ishland/c2me/rewrites/chunkio/mixin/MixinVersionedChunkStorage");
            assertTargets(readSide, SIMPLE_REGION_STORAGE, release);
            assertExactRedirectHandler(readSide, release);

            ClassNode writeSide = read(jar,
                "com/ishland/c2me/rewrites/chunkio/mixin/MixinRecreationStorage");
            assertTargets(writeSide, RECREATING_SIMPLE_REGION_STORAGE, release);
            assertExactRedirectHandler(writeSide, release);
        }
    }

    /**
     * 1.21 through 1.21.10: chunks on {@code ChunkStorage}, entities and POI on
     * {@code SimpleRegionStorage}, and both recreating siblings. All four are rewritten by C2ME,
     * so the bridge must cover all four — this is the check that keeps that true.
     *
     * <p>Names are not asserted here; see the class comment. What is asserted is what the bridge
     * actually relies on: four <em>distinct</em> storage classes, each rewritten by a redirect
     * that returns the very class the recognised worker extends. A release that merged two of
     * them, dropped one, or changed the redirect's shape fails this build.
     */
    private static void checkLegacyShape(Path module, String release) throws IOException {
        try (JarFile jar = new JarFile(module.toFile())) {
            String workerSuper = read(jar, WORKER).superName;
            assertNotNull(workerSuper, release + ": the recognised worker must extend something");

            List<ClassNode> rewrites = List.of(
                read(jar, "com/ishland/c2me/rewrites/chunkio/mixin/MixinVersionedChunkStorage"),
                read(jar, "com/ishland/c2me/rewrites/chunkio/mixin/MixinRecreatedChunkStorage"),
                read(jar, "com/ishland/c2me/rewrites/chunkio/mixin/MixinChunkPosKeyedStorage"),
                read(jar, "com/ishland/c2me/rewrites/chunkio/mixin/MixinRecreationStorage")
            );

            Set<String> targets = new HashSet<>();
            for (ClassNode rewrite : rewrites) {
                String target = singleTarget(rewrite, release);
                assertTrue(targets.add(target),
                    release + ": two rewrites target " + target + " — the bridge's two-pair"
                        + " model assumes chunks and entity/POI storage are separate classes");
                assertEquals(workerSuper, storageRedirectReturnType(rewrite, release),
                    release + ": a rewrite installs something other than the recognised worker,"
                        + " so the swap and the redirect no longer agree");
            }
            assertEquals(4, targets.size(),
                release + ": the legacy shape must rewrite four distinct storage classes");
        }
    }

    private static void assertWorkerSitsOn(JarFile jar, String expectedSuper, String release)
        throws IOException {
        ClassNode worker = read(jar, WORKER);
        assertEquals(expectedSuper, worker.superName,
            release + ": the recognised worker must sit on the vanilla worker the swap"
                + " reconstructs — a different super means the swap's replacement and the"
                + " redirect's replacement no longer agree");
    }

    private static ClassNode read(JarFile jar, String internalName) throws IOException {
        JarEntry entry = jar.getJarEntry(internalName + ".class");
        assertNotNull(entry, "C2ME must still ship " + internalName);

        ClassNode node = new ClassNode();
        try (InputStream stream = jar.getInputStream(entry)) {
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    /** The one class a legacy rewrite targets. */
    private static String singleTarget(ClassNode mixin, String release) {
        List<String> targets = targets(mixin);
        assertEquals(1, targets.size(), release + ": expected exactly one @Mixin target, got " + targets);
        return targets.get(0);
    }

    /** The @Mixin target list, however the annotation's retention handed it over. */
    private static List<String> targets(ClassNode mixin) {
        // @Mixin is CLASS-retention, so ASM hands it over as an "invisible" annotation —
        // visibleAnnotations holds only the runtime-retention ones (the @Redirect on the
        // handlers among them). Both lists are walked to stay true to either retention.
        List<AnnotationNode> annotations = new ArrayList<>();
        if (mixin.visibleAnnotations != null) {
            annotations.addAll(mixin.visibleAnnotations);
        }
        if (mixin.invisibleAnnotations != null) {
            annotations.addAll(mixin.invisibleAnnotations);
        }

        List<String> targets = new ArrayList<>();
        for (AnnotationNode annotation : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc) || annotation.values == null) {
                continue;
            }
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (!"value".equals(annotation.values.get(i))
                    || !(annotation.values.get(i + 1) instanceof List<?> values)) {
                    continue;
                }
                for (Object value : values) {
                    targets.add(value instanceof Type type ? type.getInternalName() : String.valueOf(value));
                }
            }
        }
        return targets;
    }

    /** The @Mixin target list must still contain the vanilla class the swap hooks. */
    private static void assertTargets(ClassNode mixin, String expectedTarget, String release) {
        assertTrue(targets(mixin).contains(expectedTarget),
            release + ": the redirect no longer targets " + expectedTarget);
    }

    /**
     * The redirect handler's own signature is the strongest bytecode witness that the rewrite
     * still replaces the vanilla worker construction the bridge re-does: its argument triple
     * is the vanilla call's, and its return type is the field it lands in.
     */
    private static void assertExactRedirectHandler(ClassNode mixin, String release) {
        boolean found = mixin.methods.stream().anyMatch(method ->
            "redirectStorageIoWorker".equals(method.name) && REDIRECT_HANDLER_DESC.equals(method.desc));
        assertTrue(found, release + ": the redirect handler the bridge undoes has moved");
    }

    /**
     * The same witness on an obfuscated jar, where the names are intermediary: the handler must
     * still take {@code (<?>, Path, boolean)} and return the worker class. Returns the return
     * type's internal name so the caller can compare it with the recognised worker's super.
     */
    private static String storageRedirectReturnType(ClassNode mixin, String release) {
        MethodNode handler = mixin.methods.stream()
            .filter(method -> "redirectStorageIoWorker".equals(method.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError(release + ": the redirect handler the bridge"
                + " undoes has moved"));

        Type[] arguments = Type.getArgumentTypes(handler.desc);
        assertEquals(3, arguments.length,
            release + ": the redirect handler no longer takes the vanilla constructor's triple");
        assertEquals("java/nio/file/Path", arguments[1].getInternalName(), release);
        assertEquals(Type.BOOLEAN_TYPE, arguments[2], release);

        Type returned = Type.getReturnType(handler.desc);
        assertEquals(Type.OBJECT, returned.getSort(), release + ": the redirect must return the worker");
        return returned.getInternalName();
    }
}
