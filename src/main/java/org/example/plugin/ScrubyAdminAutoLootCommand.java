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

/**
 * Admin command {@code /scruby-admin-autoloot <on/off/reset> [player]}
 * — overrides kill-loot per player.
 */
public final class ScrubyAdminAutoLootCommand extends AbstractPlayerCommand {

    private final ScrubyAdminService adminService;
    private final ScrubyBindingService bindingService;

    public ScrubyAdminAutoLootCommand(
            @Nonnull ScrubyAdminService adminService,
            @Nonnull ScrubyBindingService bindingService
    ) {
        super("scruby-admin-autoloot", "Admin: Override kill-loot per player.");
        setAllowsExtraArguments(true);
        this.adminService = Objects.requireNonNull(adminService);
        this.bindingService = Objects.requireNonNull(bindingService);
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

        if (args.length < 1 || args[0].isEmpty()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.autoloot.usage")));
            return;
        }

        String mode = args[0].toLowerCase();
        if (!"on".equals(mode) && !"off".equals(mode) && !"reset".equals(mode)) {
            ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.autoloot.usage")));
            return;
        }

        // Resolve target player
        ScrubyOwnerBindingComponent binding;
        String targetName;
        AdminPlayerContext target = null;

        if (args.length >= 2) {
            String playerName = args[1];
            target = adminService.resolvePlayer(playerName);
            if (target == null) {
                ctx.sendMessage(Message.raw("[Scruby-Admin] Player not found or not online: " + playerName));
                return;
            }
            binding = target.getBinding();
            targetName = target.getPlayerName();
        } else {
            binding = bindingService.getBindingOrNull(store, ref);
            if (binding == null) {
                ctx.sendMessage(Message.raw("[Scruby-Admin] You have no companion binding."));
                return;
            }
            targetName = "you";
        }

        CompanionProfile profile = binding.getActiveProfile();

        switch (mode) {
            case "on":
                profile.setAutoLootOverride(Boolean.TRUE);
                ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.autoloot.on", targetName)));
                break;
            case "off":
                profile.setAutoLootOverride(Boolean.FALSE);
                ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.autoloot.off", targetName)));
                break;
            case "reset":
                profile.setAutoLootOverride(null);
                ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.autoloot.reset", targetName)));
                break;
        }

        binding.setActiveProfile(profile);

        if (target != null) {
            ScrubyCompanionPlugin.savePlayerAsync(target.getStore(), target.getPlayerRef(), target.getPlayerUuid());
        } else {
            ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
        }
    }
}
