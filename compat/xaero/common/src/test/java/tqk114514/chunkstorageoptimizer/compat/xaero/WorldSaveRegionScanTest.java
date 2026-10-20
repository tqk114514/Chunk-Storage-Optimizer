package tqk114514.chunkstorageoptimizer.compat.xaero;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detection scan's file patterns, read out of the mixin that actually uses them.
 *
 * <p>An earlier version of this test kept its own copy of both literals. That made it a test of
 * the test: editing the mixin's constant left the suite green, which is precisely the drift the
 * suite exists to catch.
 *
 * <p>The constants are read out of the mixin's <em>class file</em>, not reflectively. Reflection
 * cannot do it: the mixin's fields are typed with Xaero's own classes, which are {@code
 * compileOnly} in this module, so {@code getDeclaredFields()} resolves a field descriptor of
 * {@code Lxaero/map/MapProcessor;} and dies with {@code NoClassDefFoundError} before it returns
 * anything. The class file carries the same two literals in its constant pool — {@code static
 * final String} constants get a {@code ConstantValue} attribute — and reading it loads no class
 * at all, so the test stays honest without dragging Xaero onto the classpath. A rename still
 * fails here rather than silently un-testing the scan.
 *
 * <p>What has to hold: every vanilla name keeps matching (Xaero's behaviour for un-migrated
 * worlds depends on it), the {@code .cso} names this mod writes start matching, the region
 * coordinates stay in the same groups (Xaero reads them at fixed indices), and nothing else in
 * a region folder matches.
 */
class WorldSaveRegionScanTest {

    /** The literal Xaero's world-save scan passes in; the mixin matches it by string identity. */
    private static final String ORIGINAL = "^r\\.(-{0,1}[0-9]+)\\.(-{0,1}[0-9]+)\\.mc[ar]$";

    private static final String MIXIN_CLASS =
        "tqk114514/chunkstorageoptimizer/compat/xaero/mixin/MapSaveLoadMixin.class";

    /**
     * The {@code ConstantValue} of a {@code static final String} field in the mixin's class
     * file. Looked up by resource name so the mixin class itself is never loaded — loading it
     * would be the very thing that fails.
     */
    private static String mixinConstant(String name) throws IOException {
        ClassLoader loader = WorldSaveRegionScanTest.class.getClassLoader();
        String[] found = new String[1];
        try (InputStream in = loader.getResourceAsStream(MIXIN_CLASS)) {
            assertNotNull(in, "the mixin's class file must be on this module's test classpath");
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(
                    int access, String fieldName, String descriptor, String signature, Object value) {
                    if (name.equals(fieldName) && value instanceof String) {
                        found[0] = (String) value;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertNotNull(found[0],
            "the mixin must still declare a static final String " + name
                + "; a rename leaves the scan un-tested");
        return found[0];
    }

    @Test
    void theMixinMatchesTheWorldSaveScanXaeroActuallyUses() throws IOException {
        assertEquals(ORIGINAL, mixinConstant("CSO_WORLD_SAVE_SCAN"),
            "the redirect compares this literal to the regex Xaero passes; a different string"
                + " would never match and the scan would silently stay unchanged");
    }

    @Test
    void widenedScanAcceptsCsoRegionFiles() throws IOException {
        Pattern pattern = Pattern.compile(mixinConstant("CSO_WORLD_SAVE_SCAN_WITH_CSO"));
        assertTrue(pattern.matcher("r.0.0.cso").matches());
        assertTrue(pattern.matcher("r.-1.-1.cso").matches());
        assertTrue(pattern.matcher("r.12.-34.cso").matches());
    }

    @Test
    void widenedScanKeepsEveryVanillaName() throws IOException {
        Pattern original = Pattern.compile(mixinConstant("CSO_WORLD_SAVE_SCAN"));
        Pattern widened = Pattern.compile(mixinConstant("CSO_WORLD_SAVE_SCAN_WITH_CSO"));

        // A superset check rather than a list of remembered names: whatever the original
        // accepts must keep working, including spellings nobody thought to write down.
        for (String name : new String[] {
            "r.0.0.mca", "r.0.0.mcr", "r.-3.21.mca", "r.31.-31.mcr", "r.100.200.mca"
        }) {
            assertTrue(original.matcher(name).matches(), "test premise: " + name);
            assertTrue(widened.matcher(name).matches(), "widened scan dropped " + name);
        }
    }

    @Test
    void widenedScanRejectsNonRegionFiles() throws IOException {
        Pattern pattern = Pattern.compile(mixinConstant("CSO_WORLD_SAVE_SCAN_WITH_CSO"));
        assertFalse(pattern.matcher("r.0.0.mcb").matches());
        assertFalse(pattern.matcher("r.0.0.cso.tmp").matches());
        assertFalse(pattern.matcher("r.0.0.cso.wal").matches());
        assertFalse(pattern.matcher("sessions.lock").matches());
        assertFalse(pattern.matcher("data").matches());
        assertFalse(pattern.matcher("r.0.cso").matches());
    }

    @Test
    void regionCoordinatesComeFromTheSameGroups() throws IOException {
        Pattern pattern = Pattern.compile(mixinConstant("CSO_WORLD_SAVE_SCAN_WITH_CSO"));
        Matcher matcher = pattern.matcher("r.-12.34.cso");
        assertTrue(matcher.matches());
        assertEquals("-12", matcher.group(1));
        assertEquals("34", matcher.group(2));
    }

    @Test
    void originalPatternIsTheWorldSaveScan() {
        // The pinned literal, kept as a second opinion: if the mixin's constant and this one
        // ever disagree the test above fails first, with a message naming the redirect.
        assertFalse(Pattern.compile(ORIGINAL).matcher("r.0.0.cso").matches());
        assertTrue(Pattern.compile(ORIGINAL).matcher("r.0.0.mca").matches());
    }
}
