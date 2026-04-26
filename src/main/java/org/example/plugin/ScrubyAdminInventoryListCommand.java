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
import java.util.List;
import java.util.Objects;

/**
 * Admin command {@code /scruby-admin-inventory-list <player> [slot]}
 * — lists the contents of a companion's inventory.
 */
public final class ScrubyAdminInventoryListCommand extends AbstractPlayerCommand {

    private final ScrubyAdminService adminService;
    private final ScrubyBindingService bindingService;
    private final ScrubyInventoryService inventoryService;

    public ScrubyAdminInventoryListCommand(
            @Nonnull ScrubyAdminService adminService,
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyInventoryService inventoryService
    ) {
        super("scruby-admin-inventory-list", "Admin: List companion inventory contents.");
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

        ScrubyOwnerBindingComponent binding;
        CompanionProfile profile;
        String targetName;

        if (args.length >= 1 && !args[0].isEmpty()) {
            String playerName = args[0];
            AdminPlayerContext target = adminService.resolvePlayer(playerName);
            if (target == null) {
                ctx.sendMessage(Message.raw("[Scruby-Admin] Player not found or not online: " + playerName));
                return;
            }
            binding = target.getBinding();
            targetName = target.getPlayerName();

            // Optional slot argument (1-based)
            if (args.length >= 2) {
                try {
                    List<CompanionProfile> profiles = binding.getProfiles();
                    int slot = Integer.parseInt(args[1]) - 1;
                    if (slot < 0 || slot >= profiles.size()) {
                        ctx.sendMessage(Message.raw("[Scruby-Admin] Invalid slot. Player has " + profiles.size() + " companion(s)."));
                        return;
                    }
                    profile = profiles.get(slot);
                } catch (NumberFormatException e) {
                    ctx.sendMessage(Message.raw("[Scruby-Admin] Invalid slot number: " + args[1]));
                    return;
                }
            } else {
                profile = binding.getActiveProfile();
            }
        } else {
            binding = bindingService.getBindingOrNull(store, ref);
            if (binding == null) {
                ctx.sendMessage(Message.raw("[Scruby-Admin] You have no companion binding."));
                return;
            }
            profile = binding.getActiveProfile();
            targetName = "you";
        }

        List<ScrubyInventoryService.InventoryEntry> items = inventoryService.getItems(profile);
        if (items.isEmpty()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.invlist.empty")));
            return;
        }

        int occupied = inventoryService.getOccupiedSlotCount(profile);
        ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.invlist.header", targetName, occupied)));
        for (ScrubyInventoryService.InventoryEntry entry : items) {
            ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.invlist.slot",
                    entry.getSlotIndex(), entry.getItemId(), entry.getQuantity())));
        }
    }
}
