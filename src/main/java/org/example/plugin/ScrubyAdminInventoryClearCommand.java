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
 * Admin command {@code /scruby-admin-inventory-clear [player]}
 * — clears all items from a companion's inventory.
 */
public final class ScrubyAdminInventoryClearCommand extends AbstractPlayerCommand {

    private final ScrubyAdminService adminService;
    private final ScrubyBindingService bindingService;
    private final ScrubyInventoryService inventoryService;

    public ScrubyAdminInventoryClearCommand(
            @Nonnull ScrubyAdminService adminService,
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyInventoryService inventoryService
    ) {
        super("scruby-admin-inventory-clear", "Admin: Clear companion inventory.");
        setAllowsExtraArguments(true);
        this.adminService = Objects.requireNonNull(adminService);
        this.bindingService = Objects.requireNonNull(bindingService);
        this.inventoryService = Objects.requireNonNull(inventoryService);
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

        // Resolve target player
        ScrubyOwnerBindingComponent binding;
        CompanionProfile profile;
        String targetName;
        AdminPlayerContext target = null;

        if (args.length >= 1 && !args[0].isEmpty()) {
            // Explicit player name
            String playerName = args[0];
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
        inventoryService.clearInventory(profile);
        binding.setActiveProfile(profile);

        ctx.sendMessage(Message.raw("[Scruby-Admin] Inventory cleared for " + targetName + "."));

        if (target != null) {
            ScrubyCompanionPlugin.savePlayerAsync(target.getStore(), target.getPlayerRef(), target.getPlayerUuid());
        } else {
            ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
        }
    }
}
