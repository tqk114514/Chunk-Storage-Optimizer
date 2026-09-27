package tqk114514.chunkstorageoptimizer.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A wrong root here means the opt-out is written into the wrong place, so the three save layouts
 * the game has actually shipped are each checked, along with the depth cap that stops the walk from
 * picking up somebody else's {@code level.dat}.
 */
class CsoWorldMarkerTest {

    private static void makeWorld(Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("level.dat"), "not really nbt");
    }

    @Test
    void theOverworldRegionFolderResolvesToTheSaveRoot(@TempDir Path tmp) throws IOException {
        makeWorld(tmp);
        Path region = Files.createDirectories(tmp.resolve("region"));
        assertEquals(Optional.of(tmp), CsoWorldMarker.worldRoot(region));
        assertEquals(Optional.of(tmp), CsoWorldMarker.worldRoot(tmp.resolve("entities")));
    }

    @Test
    void legacyDimensionFoldersResolveToTheSameRoot(@TempDir Path tmp) throws IOException {
        makeWorld(tmp);
        Path nether = Files.createDirectories(tmp.resolve("DIM-1/region"));
        Path end = Files.createDirectories(tmp.resolve("DIM1/poi"));
        assertEquals(Optional.of(tmp), CsoWorldMarker.worldRoot(nether));
        assertEquals(Optional.of(tmp), CsoWorldMarker.worldRoot(end));
    }

    @Test
    void newLayoutDimensionFoldersResolveToTheSameRoot(@TempDir Path tmp) throws IOException {
        makeWorld(tmp);
        Path dimension = Files.createDirectories(tmp.resolve("dimensions/minecraft/the_nether/region"));
        assertEquals(Optional.of(tmp), CsoWorldMarker.worldRoot(dimension));
    }

    @Test
    void aFolderWithNoLevelDatAboveItHasNoRoot(@TempDir Path tmp) throws IOException {
        Path orphan = Files.createDirectories(tmp.resolve("somewhere/region"));
        assertEquals(Optional.empty(), CsoWorldMarker.worldRoot(orphan));
        assertFalse(CsoWorldMarker.isDisabled(orphan), "a world being created has no marker yet");
    }

    @Test
    void theWalkStopsBeforeReachingSomeoneElsesWorld(@TempDir Path tmp) throws IOException {
        Path outer = tmp.resolve("world");
        makeWorld(outer);
        // Six folders down is past the deepest layout; an unrelated level.dat that far up must not
        // decide anything for this folder.
        Path deep = Files.createDirectories(outer.resolve("a/b/c/d/e/f/region"));
        assertEquals(Optional.empty(), CsoWorldMarker.worldRoot(deep));
        assertFalse(CsoWorldMarker.isDisabled(deep));
    }

    @Test
    void aMarkerNextToLevelDatDisablesEveryStoreOfThatWorld(@TempDir Path tmp) throws IOException {
        makeWorld(tmp);
        Path region = Files.createDirectories(tmp.resolve("region"));
        Path entities = Files.createDirectories(tmp.resolve("DIM-1/entities"));

        assertFalse(CsoWorldMarker.isDisabled(region));
        Path marker = CsoWorldMarker.disable(tmp, "converted back to .mca");
        assertEquals(tmp.resolve("cso.disabled"), marker);
        assertTrue(CsoWorldMarker.isDisabled(region), "region storage is switched off");
        assertTrue(CsoWorldMarker.isDisabled(entities), "and so is a dimension's entity store");
    }

    @Test
    void theMarkerSaysWhyAndHowToUndoIt(@TempDir Path tmp) throws IOException {
        makeWorld(tmp);
        String text = Files.readString(CsoWorldMarker.disable(tmp, "converted back to .mca"));
        assertTrue(text.contains("reason: converted back to .mca"), text);
        assertTrue(text.contains("delete this file"), "a player needs the way back from the file alone");
        assertTrue(text.contains("re-enter the world"), "the order matters: open files cannot be handed over");
        assertTrue(text.contains("/cso convert cso"), text);
    }

    @Test
    void aRootSpelledWithDotsStillLinesUpWithAStorageFolder(@TempDir Path tmp) throws IOException {
        // The game hands out a world root as ".\world\." while a storage reports ".\world\region",
        // so an unnormalised root neither matches the folder nor shows the marker in one piece.
        Path world = tmp.resolve("world");
        makeWorld(world);
        Path dotted = world.resolve(".").resolve("region");
        assertEquals(Optional.of(world), CsoWorldMarker.worldRoot(dotted));

        CsoWorldMarker.disable(world, "converted back to .mca");
        assertTrue(CsoWorldMarker.isDisabled(dotted), "the dot must not hide the marker");
    }

    @Test
    void removingTheFileByHandBringsTheWorldBack(@TempDir Path tmp) throws IOException {
        makeWorld(tmp);
        Path region = Files.createDirectories(tmp.resolve("region"));
        CsoWorldMarker.disable(tmp, "converted back to .mca");
        assertTrue(CsoWorldMarker.isDisabled(region));
        Files.delete(tmp.resolve(CsoWorldMarker.FILE_NAME));
        assertFalse(CsoWorldMarker.isDisabled(region), "deleting the marker is the documented way back");
    }
}
