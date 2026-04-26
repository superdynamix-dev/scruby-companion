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
import java.util.Objects;
import java.util.UUID;

public final class ScrubyPathDpsCommand extends AbstractPlayerCommand {

    private static final int PATH_UNLOCK_LEVEL = 5;

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyPendingRespawnQueue pendingRespawnQueue;
    private final ScrubyEvolutionService evolutionService;

    public ScrubyPathDpsCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyPendingRespawnQueue pendingRespawnQueue,
            @Nonnull ScrubyEvolutionService evolutionService
    ) {
        super("scruby-path-dps", "Waehle den DPS-Pfad fuer deinen Companion (ab Level 5).");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.pendingRespawnQueue = Objects.requireNonNull(pendingRespawnQueue, "pendingRespawnQueue");
        this.evolutionService = Objects.requireNonNull(evolutionService, "evolutionService");
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(
            @Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        ScrubyOwnerBindingComponent binding = this.bindingService.getBindingOrNull(store, ref);

        if (binding == null || !binding.hasBinding()) {
            commandContext.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.path.no_binding")));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();

        if (profile.getLevel() < PATH_UNLOCK_LEVEL) {
            commandContext.sendMessage(Message.raw(
                    ScrubyLang.get(locale, "cmd.path.level_info", profile.getLevel())
            ));
            return;
        }

        if (!"NONE".equals(profile.getPathChoice())) {
            commandContext.sendMessage(Message.raw(
                    ScrubyLang.get(locale, "cmd.path.permanent",
                            ScrubyColors.pathDisplayName(profile.getPathChoice(), locale))
            ));
            return;
        }

        profile.setPathChoice("DPS");
        binding.setActiveProfile(profile);

        com.hypixel.hytale.math.vector.Vector3d ownerPos = playerRef.getTransform().getPosition();
        ScrubyCompanionPlugin.instance().getSoundService().playPathChoice(playerRef,
                ownerPos.getX(), ownerPos.getY(), ownerPos.getZ());
        commandContext.sendMessage(Message.raw(
                ScrubyLang.get(locale, "cmd.path.success", ScrubyColors.pathDisplayName("DPS", locale))));

        // Trigger evolution respawn with new path appearance
        UUID ownerUuid = playerRef.getUuid();
        Ref<EntityStore> companionRef = registry.getCompanionRef(ownerUuid);
        if (companionRef != null && companionRef.isValid()) {
            int newStage = evolutionService.calculateEvolutionStage(profile.getLevel());
            profile.setEvolutionStage(newStage);
            binding.setActiveProfile(profile);

            commandContext.sendMessage(Message.raw(
                    ScrubyLang.get(locale, "cmd.path.evolution",
                            profile.getCompanionName(), evolutionService.getAppearanceForProfile(profile))));

            // 1. Unregister first
            registry.unregister(ownerUuid);

            // 2. Clear entity reference
            profile.setCompanionEntityUuid("");
            profile.setEntityMissing(false);
            profile.setManuallyDespawned(false);
            binding.setActiveProfile(profile);

            // 3. Queue respawn
            pendingRespawnQueue.enqueue(ownerUuid, System.currentTimeMillis());

            // 4. Remove old NPC
            NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
            if (npcEntity != null) {
                resetService.clearLockedTarget(store, companionRef);
                npcEntity.remove();
            }
        }
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }
}
