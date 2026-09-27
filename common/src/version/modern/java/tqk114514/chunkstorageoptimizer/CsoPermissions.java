package tqk114514.chunkstorageoptimizer;

import java.util.function.Predicate;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;

/**
 * Version seam: this Minecraft replaced the numeric permission level with a {@link PermissionCheck}
 * tree.
 *
 * <p>The counterpart lives in {@code common/src/version/legacy/java}. Each loader build puts
 * exactly one of the two on the compile path, so both must expose the same signature and the same
 * bar (op level 3).
 */
public final class CsoPermissions {

    private CsoPermissions() {
    }

    public static Predicate<CommandSourceStack> operatorOnly() {
        return Commands.hasPermission(new PermissionCheck.Require(Permissions.COMMANDS_ADMIN));
    }
}
