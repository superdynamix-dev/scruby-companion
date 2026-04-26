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

public final class ScrubyUnbindCommand extends AbstractPlayerCommand {

    private static final String CMD_PREFIX = "scruby-unbind";

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyHudManager hudManager;

    public ScrubyUnbindCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyHudManager hudManager
    ) {
        super("scruby-unbind", "Removes a companion from a slot (1-5). No argument = active slot.");
        setAllowsExtraArguments(true);
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.hudManager = Objects.requireNonNull(hudManager, "hudManager");
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
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
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.unbind.no_binding")));
            return;
        }

        String locale = binding.getActiveProfile() != null
                ? binding.getActiveProfile().getLocale() : ScrubyLang.DEFAULT_LOCALE;

        // Determine target slot: argument or active slot
        String slotArg = parseArg(ctx.getInputString());
        int targetSlot;

        if (slotArg == null) {
            // No argument — unbind the active slot
            targetSlot = binding.getActiveSlot();
        } else {
            try {
                targetSlot = Integer.parseInt(slotArg) - 1;
            } catch (NumberFormatException e) {
                ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.unbind.invalid")));
                return;
            }
            if (targetSlot < 0 || targetSlot > 4) {
                ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.unbind.slot_range")));
                return;
            }
        }

        // Check that the target slot actually has a companion
        CompanionProfile targetProfile = null;
        for (CompanionProfile p : binding.getProfiles()) {
            if (p.getSlotId() == targetSlot) {
                targetProfile = p;
                break;
            }
        }

        if (targetProfile == null) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.unbind.slot_empty", targetSlot + 1)));
            return;
        }

        UUID ownerUuid = playerRef.getUuid();

        // If unbinding the active slot and companion is spawned, despawn first
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

        String removedName = targetProfile.getCompanionName();
        int removedLevel = targetProfile.getLevel();

        boolean deleted = bindingService.deleteSlot(store, ref, targetSlot);
        if (!deleted) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.unbind.failed")));
            return;
        }

        ScrubyCompanionPlugin.instance().getSoundService().playDelete(playerRef);
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.unbind.success_detail",
                removedName, removedLevel, targetSlot + 1)));

        // Show remaining companions
        ScrubyOwnerBindingComponent updatedBinding = bindingService.getBindingOrNull(store, ref);
        if (updatedBinding != null && updatedBinding.hasBinding()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.unbind.active_slot",
                    updatedBinding.getActiveSlot() + 1)));
        } else {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.unbind.no_remaining")));
        }
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
