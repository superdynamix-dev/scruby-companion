package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

public final class ScrubyDeleteCommand extends AbstractPlayerCommand {

    private static final String CMD_PREFIX = "scruby-delete";

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyHudManager hudManager;

    public ScrubyDeleteCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyHudManager hudManager
    ) {
        super("scruby-delete", "Loescht einen Companion permanent aus einem Slot (1-5).");
        setAllowsExtraArguments(true);
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.hudManager = Objects.requireNonNull(hudManager, "hudManager");
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ref);
        if (binding == null || !binding.hasBinding()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.delete.no_binding")));
            return;
        }

        String locale = binding.getActiveProfile() != null
                ? binding.getActiveProfile().getLocale() : ScrubyLang.DEFAULT_LOCALE;

        String slotArg = parseArg(ctx.getInputString());
        if (slotArg == null) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.delete.usage")));
            return;
        }

        int targetSlot;
        try {
            targetSlot = Integer.parseInt(slotArg) - 1;
        } catch (NumberFormatException e) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.delete.invalid")));
            return;
        }

        if (targetSlot < 0 || targetSlot > 4) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.delete.invalid")));
            return;
        }

        UUID ownerUuid = playerRef.getUuid();

        // If deleting the active slot and companion is spawned, despawn first
        if (binding.getActiveSlot() == targetSlot) {
            Ref<EntityStore> companionRef = registry.getCompanionRef(ownerUuid);
            if (companionRef != null && companionRef.isValid()) {
                NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
                if (npcEntity != null) {
                    resetService.clearLockedTarget(store, companionRef);
                    npcEntity.remove();
                }
                registry.unregister(ownerUuid);
                hudManager.removeHud(store, ref, playerRef, ownerUuid);
            }
        }

        // Get companion name before deletion for the message
        CompanionProfile targetProfile = null;
        for (CompanionProfile p : binding.getProfiles()) {
            if (p.getSlotId() == targetSlot) {
                targetProfile = p;
                break;
            }
        }

        if (targetProfile == null) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.delete.empty", targetSlot + 1)));
            return;
        }

        String deletedName = targetProfile.getCompanionName();
        int deletedLevel = targetProfile.getLevel();

        boolean deleted = bindingService.deleteSlot(store, ref, targetSlot);
        if (!deleted) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.delete.failed")));
            return;
        }

        ScrubyCompanionPlugin.instance().getSoundService().playDelete(playerRef);
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.delete.success_detail",
                deletedName, deletedLevel, targetSlot + 1)));

        // Show remaining companions
        ScrubyOwnerBindingComponent updatedBinding = bindingService.getBindingOrNull(store, ref);
        if (updatedBinding != null && updatedBinding.hasBinding()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.delete.active_slot",
                    updatedBinding.getActiveSlot() + 1)));
        } else {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.delete.no_remaining")));
        }
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }

    @Nullable
    private static String parseArg(@Nonnull String inputString) {
        String trimmed = inputString.trim();
        if (trimmed.isEmpty()) return null;
        String lower = trimmed.toLowerCase();
        if (lower.startsWith(CMD_PREFIX)) {
            String rest = trimmed.substring(CMD_PREFIX.length()).trim();
            return rest.isEmpty() ? null : rest;
        }
        return trimmed;
    }
}
