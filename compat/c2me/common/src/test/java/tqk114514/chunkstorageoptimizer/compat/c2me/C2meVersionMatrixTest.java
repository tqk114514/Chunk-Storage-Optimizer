package tqk114514.chunkstorageoptimizer.compat.c2me;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every C2ME release the bridge can meet, checked against everything the bridge assumes about
 * it — the same stance the Xaero compat's matrix test takes, so a C2ME release that moves a
 * target fails this build the day it appears, not a player's world later.
 *
 * <p>The bridge deliberately depends on very little of C2ME: the class of the worker its
 * redirect installs (recognised by name, its only handle on the object), the vanilla class
 * the redirect hijacks the worker construction of, and the fact that the redirect really
 * installs that worker into that field — asserted here through the redirect handler's own
 * signature, because the handler's argument triple is the vanilla call it replaced. Both
 * halves of the rewrite are held: the read side on SimpleRegionStorage, the write side on the
 * recreating storage.
 */
class C2meVersionMatrixTest {

    private static final String WORKER =
        "com/ishland/c2me/rewrites/chunkio/common/C2MEStorageVanillaInterface";

    /** The argument triple the vanilla constructor feeds its worker with. */
    private static final String REDIRECT_HANDLER_DESC =
        "(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Z)"
            + "Lnet/minecraft/world/level/chunk/storage/IOWorker;";

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

        for (String line : releases) {
            String[] columns = line.split("\t");
            String release = columns[1] + " (" + columns[2] + ", " + columns[3] + ")";
            Path module = dir.resolve(
                "c2me-" + columns[2] + "__" + columns[4].replaceAll("\\.jar$", "") + ".rewrites-chunkio.jar");
            assertTrue(Files.isRegularFile(module), "the extracted module for " + release);

            checkRelease(module, release);
        }
    }

    private static void checkRelease(Path module, String release) throws IOException {
        try (JarFile jar = new JarFile(module.toFile())) {
            ClassNode worker = read(jar, WORKER);
            assertEquals("net/minecraft/world/level/chunk/storage/IOWorker", worker.superName,
                release + ": the recognised worker must sit on the vanilla worker the swap"
                    + " reconstructs — a different super means the swap's replacement and the"
                    + " redirect's replacement no longer agree");

            ClassNode readSide = read(jar, "com/ishland/c2me/rewrites/chunkio/mixin/MixinVersionedChunkStorage");
            assertTargets(readSide, "net/minecraft/world/level/chunk/storage/SimpleRegionStorage", release);
            assertRedirectHandler(readSide, release);

            ClassNode writeSide = read(jar, "com/ishland/c2me/rewrites/chunkio/mixin/MixinRecreationStorage");
            assertTargets(writeSide, "net/minecraft/world/level/chunk/storage/RecreatingSimpleRegionStorage", release);
            assertRedirectHandler(writeSide, release);
        }
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

    /** The @Mixin target list must still contain the vanilla class the swap hooks. */
    private static void assertTargets(ClassNode mixin, String expectedTarget, String release) {
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

        boolean targeted = false;
        for (AnnotationNode annotation : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc) || annotation.values == null) {
                continue;
            }
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (!"value".equals(annotation.values.get(i))
                    || !(annotation.values.get(i + 1) instanceof List<?> targets)) {
                    continue;
                }
                for (Object target : targets) {
                    String name = target instanceof Type type
                        ? type.getInternalName()
                        : String.valueOf(target);
                    if (expectedTarget.equals(name)) {
                        targeted = true;
                    }
                }
            }
        }
        assertTrue(targeted, release + ": the redirect no longer targets " + expectedTarget);
    }

    /**
     * The redirect handler's own signature is the strongest bytecode witness that the rewrite
     * still replaces the vanilla worker construction the bridge re-does: its argument triple
     * is the vanilla call's, and its return type is the field it lands in.
     */
    private static void assertRedirectHandler(ClassNode mixin, String release) {
        boolean found = mixin.methods.stream().anyMatch(method ->
            "redirectStorageIoWorker".equals(method.name) && REDIRECT_HANDLER_DESC.equals(method.desc));
        assertTrue(found, release + ": the redirect handler the bridge undoes has moved");
    }
}
