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
 * Admin command {@code /scruby-admin-inventory-keep <true/false> [player]}
 * — sets the keep-inventory-on-death flag for a companion.
 */
public final class ScrubyAdminInventoryKeepCommand extends AbstractPlayerCommand {

    private final ScrubyAdminService adminService;
    private final ScrubyBindingService bindingService;

    public ScrubyAdminInventoryKeepCommand(
            @Nonnull ScrubyAdminService adminService,
            @Nonnull ScrubyBindingService bindingService
    ) {
        super("scruby-admin-inventory-keep", "Admin: Set keep-inventory-on-death for a companion.");
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
            ctx.sendMessage(Message.raw("[Scruby-Admin] Usage: /scruby-admin-inventory-keep <true/false> [player]"));
            return;
        }

        String valueStr = args[0].toLowerCase();
        if (!valueStr.equals("true") && !valueStr.equals("false")) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] Usage: /scruby-admin-inventory-keep <true/false> [player]"));
            return;
        }
        boolean value = valueStr.equals("true");

        // Resolve target player
        ScrubyOwnerBindingComponent binding;
        CompanionProfile profile;
        String targetName;
        AdminPlayerContext target = null;

        if (args.length >= 2) {
            // Explicit player name
            String playerName = args[1];
            target = adminService.resolvePlayer(playerName);
            if (target == null) {
                ctx.sendMessage(Message.raw("[Scruby-Admin] Player not found or not online: " + playerName));
                return;
            }
            binding = target.getBinding();
            targetName = target.getPlayerName();
        } else {
            // Target self
            binding = bindingService.getBindingOrNull(store, ref);
            if (binding == null) {
                ctx.sendMessage(Message.raw("[Scruby-Admin] You have no companion binding."));
                return;
            }
            targetName = "you";
        }

        profile = binding.getActiveProfile();
        profile.setKeepInventoryOnDeath(value);
        binding.setActiveProfile(profile);

        ctx.sendMessage(Message.raw("[Scruby-Admin] keep-inventory-on-death set to " + value + " for " + targetName + "."));

        if (target != null) {
            ScrubyCompanionPlugin.savePlayerAsync(target.getStore(), target.getPlayerRef(), target.getPlayerUuid());
        } else {
            ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
        }
    }
}
