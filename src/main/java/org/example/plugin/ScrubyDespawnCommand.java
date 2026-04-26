// Datei: ScrubyDespawnCommand.java
package org.example.plugin;

import com.hypixel.hytale.component.ComponentType;
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

public final class ScrubyDespawnCommand extends AbstractPlayerCommand {

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyHudManager hudManager;

    public ScrubyDespawnCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyHudManager hudManager
    ) {
        super("scruby-despawn", "Despawns the currently bound Scruby MVP test companion.");
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
            @Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {

        ScrubyOwnerBindingComponent binding = this.bindingService.getBindingOrNull(store, ref);

        if (binding == null || !binding.hasBinding()) {
            commandContext.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.despawn.no_binding")));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();

        if (!profile.hasCompanionEntityReference()) {
            this.resetService.resetOwnerSession(playerRef.getUuid());
            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.despawn.no_ref")));
            return;
        }

        UUID companionUuid;

        try {
            companionUuid = UUID.fromString(profile.getCompanionEntityUuid());
        } catch (IllegalArgumentException ex) {

            this.bindingService.markCompanionEntityMissing(store, ref);
            this.resetService.resetOwnerSession(playerRef.getUuid());

            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.despawn.invalid_uuid")));
            return;
        }

        EntityStore entityStore = store.getExternalData();

        Ref<EntityStore> companionRef = entityStore.getRefFromUUID(companionUuid);

        if (companionRef == null) {

            this.bindingService.markCompanionEntityMissing(store, ref);
            this.resetService.resetOwnerSession(playerRef.getUuid());

            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.despawn.not_found")));
            return;
        }

        ComponentType<EntityStore, NPCEntity> npcEntityComponentType = NPCEntity.getComponentType();

        if (npcEntityComponentType == null) {
            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.despawn.npc_unavailable")));
            return;
        }

        NPCEntity npcEntity = store.getComponent(companionRef, npcEntityComponentType);

        if (npcEntity == null) {

            this.bindingService.markCompanionEntityMissing(store, ref);
            this.resetService.resetOwnerSession(playerRef.getUuid());

            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.despawn.npc_missing")));
            return;
        }

        this.resetService.clearLockedTarget(store, companionRef);
        boolean removed = npcEntity.remove();

        if (!removed) {
            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.despawn.remove_failed")));
            return;
        }

        this.bindingService.markCompanionEntityMissing(store, ref);

        CompanionProfile updatedProfile = binding.getActiveProfile();
        updatedProfile.setManuallyDespawned(true);
        binding.setActiveProfile(updatedProfile);

        this.resetService.resetOwnerSession(playerRef.getUuid());
        this.hudManager.removeHud(store, ref, playerRef, playerRef.getUuid());

        com.hypixel.hytale.math.vector.Vector3d playerPos = playerRef.getTransform().getPosition();
        ScrubyCompanionPlugin.instance().getSoundService().playDespawn(playerRef,
                playerPos.getX(), playerPos.getY(), playerPos.getZ());

        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.despawn.success")));
        commandContext.sendMessage(Message.raw("Owner UUID: " + binding.getOwnerPlayerUuid()));
        commandContext.sendMessage(Message.raw("Binding ID: " + binding.getBindingId()));
        commandContext.sendMessage(Message.raw("Companion UUID: " + updatedProfile.getCompanionEntityUuid()));
        commandContext.sendMessage(Message.raw("Entity Missing: " + updatedProfile.isEntityMissing()));
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }
}
