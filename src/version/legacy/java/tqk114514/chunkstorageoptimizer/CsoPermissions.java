package tqk114514.chunkstorageoptimizer;

import java.util.function.Predicate;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Version seam: this Minecraft still gates commands on a numeric permission level.
 *
 * <p>The counterpart lives in {@code src/version/modern/java}; build.gradle puts exactly one of the
 * two on the compile path, so both must expose the same signature and the same bar (op level 3).
 */
public final class CsoPermissions {

    private CsoPermissions() {
    }

    public static Predicate<CommandSourceStack> operatorOnly() {
        return source -> source.hasPermission(Commands.LEVEL_ADMINS);
    }
}
