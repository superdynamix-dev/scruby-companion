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

public final class ScrubySwitchCommand extends AbstractPlayerCommand {

    private static final String CMD_PREFIX = "scruby-switch";

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionSpawnService spawnService;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyHudManager hudManager;

    public ScrubySwitchCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionSpawnService spawnService,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyHudManager hudManager
    ) {
        super("scruby-switch", "Wechselt den aktiven Companion-Slot (1-5).");
        setAllowsExtraArguments(true);
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.spawnService = Objects.requireNonNull(spawnService, "spawnService");
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
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.switch.no_binding")));
            return;
        }

        String locale = binding.getActiveProfile() != null
                ? binding.getActiveProfile().getLocale() : ScrubyLang.DEFAULT_LOCALE;

        String slotArg = parseArg(ctx.getInputString());
        if (slotArg == null) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.switch.usage")));
            return;
        }

        int targetSlot;
        try {
            targetSlot = Integer.parseInt(slotArg) - 1;
        } catch (NumberFormatException e) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.switch.invalid", 5)));
            return;
        }

        if (targetSlot < 0 || targetSlot > 4) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.switch.invalid", 5)));
            return;
        }

        if (binding.getActiveSlot() == targetSlot) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.switch.same", targetSlot + 1)));
            return;
        }

        UUID ownerUuid = playerRef.getUuid();

        // Despawn current active companion if spawned
        Ref<EntityStore> currentRef = registry.getCompanionRef(ownerUuid);
        if (currentRef != null && currentRef.isValid()) {
            NPCEntity npcEntity = store.getComponent(currentRef, NPCEntity.getComponentType());
            if (npcEntity != null) {
                resetService.clearLockedTarget(store, currentRef);
                npcEntity.remove();
            }
            CompanionProfile currentProfile = binding.getActiveProfile();
            currentProfile.setCompanionEntityUuid("");
            currentProfile.setEntityMissing(false);
            currentProfile.setManuallyDespawned(true);
            binding.setActiveProfile(currentProfile);
            registry.unregister(ownerUuid);
            hudManager.removeHud(store, ref, playerRef, ownerUuid);
        }

        // Switch slot
        CompanionProfile newProfile = bindingService.switchSlot(store, ref, targetSlot);
        if (newProfile == null) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.switch.empty", targetSlot + 1)));
            return;
        }

        // Update locale from the new profile
        locale = newProfile.getLocale();

        ScrubyCompanionPlugin.instance().getSoundService().playSlotSwitch(playerRef);
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.switch.success",
                targetSlot + 1, newProfile.getCompanionName() + " (Lv" + newProfile.getLevel() + ")")));

        // Clear despawn flag so HUD can be created
        newProfile.setManuallyDespawned(false);
        newProfile.setEntityMissing(false);
        binding.setActiveProfile(newProfile);

        // Auto-spawn if not stationed (always spawn on switch, including fresh companions)
        if (!newProfile.isStationedAtBase()) {
            spawnService.spawnCompanion(store, ref, playerRef, ownerUuid, true);
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.switch.spawned")));
        } else if (newProfile.isStationedAtBase()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.switch.stationed")));
        } else {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.switch.spawn_hint")));
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
