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
 * Admin command {@code /scruby-admin-give-item <player> <slot> <itemId> <qty>}
 * — adds an item directly to a companion's inventory.
 */
public final class ScrubyAdminGiveItemCommand extends AbstractPlayerCommand {

    private final ScrubyAdminService adminService;
    private final ScrubyInventoryService inventoryService;

    public ScrubyAdminGiveItemCommand(
            @Nonnull ScrubyAdminService adminService,
            @Nonnull ScrubyInventoryService inventoryService
    ) {
        super("scruby-admin-give-item", "Admin: Give item to companion inventory.");
        setAllowsExtraArguments(true);
        this.adminService = Objects.requireNonNull(adminService);
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

        if (args.length < 4) {
            ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.giveitem.usage")));
            return;
        }

        String playerName = args[0];
        int slot;
        try {
            slot = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] Invalid slot number: " + args[1]));
            return;
        }
        String itemId = args[2];
        int qty;
        try {
            qty = Integer.parseInt(args[3]);
            if (qty <= 0) {
                ctx.sendMessage(Message.raw("[Scruby-Admin] Quantity must be positive."));
                return;
            }
        } catch (NumberFormatException e) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] Invalid quantity: " + args[3]));
            return;
        }

        AdminPlayerContext target = adminService.resolvePlayer(playerName);
        if (target == null) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] Player not found or not online: " + playerName));
            return;
        }

        ScrubyOwnerBindingComponent binding = target.getBinding();
        if (binding == null) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] Player has no companion binding."));
            return;
        }

        // getProfiles() deserializes from JSON each call — must hold reference to list
        List<CompanionProfile> profiles = binding.getProfiles();
        int slotIndex = slot - 1;
        if (slotIndex < 0 || slotIndex >= profiles.size()) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] Invalid slot. Player has " + profiles.size() + " companion(s)."));
            return;
        }
        CompanionProfile profile = profiles.get(slotIndex);

        int remaining = inventoryService.smartStack(profile, itemId, qty, ScrubyInventoryService.DEFAULT_MAX_STACK);
        int added = qty - remaining;

        if (remaining == 0) {
            ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.giveitem.success", qty, itemId, target.getPlayerName())));
        } else if (added > 0) {
            ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.giveitem.partial", added, itemId, target.getPlayerName(), remaining)));
        } else {
            ctx.sendMessage(Message.raw(ScrubyLang.get("en", "cmd.admin.giveitem.full", qty, itemId)));
        }

        // Write modified list back — getProfiles() returns a fresh copy each time
        binding.setProfiles(profiles);
        ScrubyCompanionPlugin.savePlayerAsync(target.getStore(), target.getPlayerRef(), target.getPlayerUuid());
    }
}
