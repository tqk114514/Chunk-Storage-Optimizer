package tqk114514.chunkstorageoptimizer.storage;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one contract the Optional crash proved cannot be left to the compiler: the shape of the
 * values in the vanilla side's region cache, checked against every Minecraft this row builds
 * for.
 *
 * <p>The mixin's {@code @Shadow} of {@code regionCache} matches by name and raw type, and the
 * generic is erased at apply time — so a version that changes the value type (26.x wraps each
 * entry in an {@link Optional} to memoize failed opens, 1.21 stores the bare file) passes every
 * compile on every row and only fails at the first real entry, in a player's game. This test
 * reads the field's actual generic signature from this row's {@link RegionFileStorage} and
 * holds {@link CsoStorage#regionFileOf} to cover it: a future version wrapping the entries in
 * anything else fails the build here, with the shape spelled out in the message, instead of
 * shipping a pause path that skips (or worse) over it.
 */
class VanillaRegionCacheShapeTest {

    /**
     * A real open region file, wrapped the way this row's cache wraps its entries — and what
     * the unwrap must return for it. The two ends of the walk are checked against each other,
     * so the test cannot pass on a stale list of "known shapes": whatever the row declares,
     * the unwrap has to actually take it apart.
     */
    @Test
    void thePauseWalkCoversThisRowsCacheValues(@TempDir Path dir) throws Exception {
        Field cache = RegionFileStorage.class.getDeclaredField("regionCache");
        // One type argument, not two: the key is fastutil's primitive long, so the value type
        // is the whole generic. Assuming [1] here is the same shape error the test exists to
        // catch, made small enough to notice.
        Type valueType = ((ParameterizedType) cache.getGenericType()).getActualTypeArguments()[0];

        Path regionDir = dir.resolve("region");
        Files.createDirectories(regionDir);
        try (RegionFile file = new RegionFile(
            new RegionStorageInfo("world", null, "region"),
            regionDir.resolve("r.0.0.mca"),
            regionDir,
            false
        )) {
            if (isBareRegionFile(valueType)) {
                assertEquals(file, CsoStorage.regionFileOf(file), "a bare entry must unwrap to itself");
            } else if (isOptionalRegionFile(valueType)) {
                assertEquals(file, CsoStorage.regionFileOf(Optional.of(file)), "a wrapped entry must unwrap");
                assertEquals(null, CsoStorage.regionFileOf(Optional.empty()), "a memoized failed open is nothing to close");
            } else {
                // Not an assertion-free skip: a shape the unwrap does not know would silently
                // hold files during a conversion again, which is the exact silence this test
                // exists to break.
                assertTrue(false, "RegionFileStorage.regionCache values are " + valueType
                    + " in this Minecraft — teach CsoStorage.regionFileOf this shape before"
                    + " shipping this row.");
            }
        }
    }

    private static boolean isBareRegionFile(Type valueType) {
        return valueType == RegionFile.class;
    }

    private static boolean isOptionalRegionFile(Type valueType) {
        return valueType instanceof ParameterizedType parameterized
            && parameterized.getRawType() == Optional.class
            && parameterized.getActualTypeArguments()[0] == RegionFile.class;
    }
}
