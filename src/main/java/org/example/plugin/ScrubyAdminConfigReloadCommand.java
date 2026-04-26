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

public final class ScrubyAdminConfigReloadCommand extends AbstractPlayerCommand {

    private final ScrubyConfigService configService;

    public ScrubyAdminConfigReloadCommand(@Nonnull ScrubyConfigService configService) {
        super("scruby-admin-config-reload", "Admin: Reload the Scruby config file.");
        this.configService = Objects.requireNonNull(configService);
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

        configService.reload();
        ScrubyLang.clearCache();
        ctx.sendMessage(Message.raw("[Scruby-Admin] Config reloaded."));
    }
}
