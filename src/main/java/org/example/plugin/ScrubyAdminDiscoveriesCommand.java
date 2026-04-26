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

public final class ScrubyAdminDiscoveriesCommand extends AbstractPlayerCommand {

    private final ScrubyAdminService adminService;

    public ScrubyAdminDiscoveriesCommand(@Nonnull ScrubyAdminService adminService) {
        super("scruby-admin-discoveries", "Admin: List discovered mob types for a player.");
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

        if (afterPrefix.isEmpty()) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] Usage: /scruby-admin-discoveries <player>"));
            return;
        }

        AdminPlayerContext target = adminService.resolvePlayer(afterPrefix);
        if (target == null) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] Player not found or not online: " + afterPrefix));
            return;
        }

        ctx.sendMessage(Message.raw(adminService.listDiscoveries(target)));
    }
}
