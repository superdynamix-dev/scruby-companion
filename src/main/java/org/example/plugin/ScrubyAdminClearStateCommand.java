package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Objects;

public final class ScrubyAdminClearStateCommand extends AbstractPlayerCommand {

    private final ScrubyAdminService adminService;

    public ScrubyAdminClearStateCommand(@Nonnull ScrubyAdminService adminService) {
        super("scruby-admin-clear-state", "Admin: Clear stuck state flags on a companion.");
        setAllowsExtraArguments(true);
        this.adminService = Objects.requireNonNull(adminService);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        if (!PermissionsModule.get().getGroupsForUser(playerRef.getUuid()).contains("OP")) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] No permission."));
            return;
        }

        String input = ctx.getInputString().trim();
        String afterPrefix = input.contains(" ") ? input.substring(input.indexOf(' ') + 1).trim() : "";
        String[] args = afterPrefix.split("\\s+");

        if (args.length < 2 || args[0].isEmpty()) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] Usage: /scruby-admin-clear-state <player> <slot>"));
            return;
        }

        String targetName = args[0];
        int slot;
        try {
            slot = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] Invalid slot: " + args[1]));
            return;
        }

        AdminPlayerContext target = adminService.resolvePlayer(targetName);
        if (target == null) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] Player not found or not online: " + targetName));
            return;
        }

        ctx.sendMessage(Message.raw(adminService.clearState(target, slot)));
    }
}
