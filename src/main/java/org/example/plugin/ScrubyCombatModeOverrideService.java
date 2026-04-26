// Datei: ScrubyCombatModeOverrideService.java
package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Forces all companions of a player into PASSIVE combat mode while the
 * player is inside a Forgotten Temple instance world, and restores the
 * previous mode when the player leaves again. The previous mode is stored
 * per-profile so multiple companions retain individual state.
 */
public final class ScrubyCombatModeOverrideService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final String TEMPLE_WORLD_PREFIX = "instance-Forgotten_Temple";

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry companionRegistry;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyCompanionSpawnService spawnService;
    private final ScrubyEvolutionService evolutionService;

    public ScrubyCombatModeOverrideService(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry companionRegistry,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyCompanionSpawnService spawnService,
            @Nonnull ScrubyEvolutionService evolutionService
    ) {
        this.bindingService = Objects.requireNonNull(bindingService);
        this.companionRegistry = Objects.requireNonNull(companionRegistry);
        this.resetService = Objects.requireNonNull(resetService);
        this.spawnService = Objects.requireNonNull(spawnService);
        this.evolutionService = Objects.requireNonNull(evolutionService);
    }

    public static boolean isTempleWorld(@Nonnull String worldName) {
        return worldName.startsWith(TEMPLE_WORLD_PREFIX);
    }

    /**
     * Entry point from the world-change event. Runs on the world thread.
     */
    public void handleWorldChange(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull String newWorldName
    ) {
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding == null || !binding.hasBinding()) return;

        boolean enteringTemple = isTempleWorld(newWorldName);
        List<CompanionProfile> profiles = binding.getProfiles();
        if (profiles.isEmpty()) return;

        boolean anyChanged = false;

        for (CompanionProfile profile : profiles) {
            if (enteringTemple) {
                if (applyTempleOverride(store, ownerRef, playerRef, ownerUuid, profile, binding)) {
                    anyChanged = true;
                }
            } else {
                if (restoreFromTempleOverride(store, ownerRef, playerRef, ownerUuid, profile, binding)) {
                    anyChanged = true;
                }
            }
        }

        if (!anyChanged) return;

        binding.setProfiles(profiles);
        ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);
    }

    /**
     * Enter-Flow: if profile is currently aggressive, save the previous mode
     * and force it to PASSIVE. Respawns the companion entity in the current
     * store with the new (passive) role if it currently exists.
     */
    private boolean applyTempleOverride(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull ScrubyOwnerBindingComponent binding
    ) {
        if (!profile.isAggressive()) return false;
        if (profile.hasTempleOverride()) return false;

        profile.setCombatModeBeforeTempleOverride(profile.getCombatMode());
        profile.setCombatModeInternal("PASSIVE");
        LOGGER.atInfo().log("[Scruby] Temple override ENTER: slot=" + profile.getSlotId()
                + " savedMode=" + profile.getCombatModeBeforeTempleOverride());

        respawnCompanionInStore(store, ownerRef, playerRef, ownerUuid, profile, binding);
        return true;
    }

    /**
     * Leave-Flow: if an override is active, restore the saved mode and clear
     * the override. Respawns the companion entity with the restored role if
     * it currently exists in this store.
     */
    private boolean restoreFromTempleOverride(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull ScrubyOwnerBindingComponent binding
    ) {
        if (!profile.hasTempleOverride()) return false;

        String restored = profile.getCombatModeBeforeTempleOverride();
        profile.setCombatModeBeforeTempleOverride(null);
        profile.setCombatModeInternal(restored);
        LOGGER.atInfo().log("[Scruby] Temple override LEAVE: slot=" + profile.getSlotId()
                + " restoredMode=" + restored);

        respawnCompanionInStore(store, ownerRef, playerRef, ownerUuid, profile, binding);
        return true;
    }

    /**
     * Shared respawn logic. Handles both the active companion (via
     * spawnService) and stationed companions (via direct NPCPlugin.spawnNPC).
     * No-op if the companion entity is not present in the current store.
     */
    private void respawnCompanionInStore(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull ScrubyOwnerBindingComponent binding
    ) {
        String entityUuidStr = profile.getCompanionEntityUuid();
        Ref<EntityStore> existingRef = null;
        if (entityUuidStr != null && !entityUuidStr.isEmpty()) {
            try {
                UUID eUuid = UUID.fromString(entityUuidStr);
                existingRef = store.getExternalData().getRefFromUUID(eUuid);
            } catch (Exception ignored) {
            }
        }

        boolean entityPresentHere = existingRef != null && existingRef.isValid();

        if (entityPresentHere) {
            resetService.clearLockedTarget(store, existingRef);
            NPCEntity npcEntity = store.getComponent(existingRef, NPCEntity.getComponentType());
            if (npcEntity != null) npcEntity.remove();
        }

        boolean isActiveSlot = binding.getActiveSlot() == profile.getSlotId();
        if (isActiveSlot && entityPresentHere) {
            companionRegistry.unregister(ownerUuid);
            resetService.resetOwnerSession(ownerUuid);
        }

        profile.setCompanionEntityUuid("");
        profile.setEntityMissing(!entityPresentHere);
        profile.setManuallyDespawned(false);

        if (!entityPresentHere) {
            return;
        }

        if (profile.isStationedAtBase()) {
            respawnStationed(store, ownerUuid, profile, binding);
        } else {
            spawnService.spawnCompanion(store, ownerRef, playerRef, ownerUuid);
        }
    }

    private void respawnStationed(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull ScrubyOwnerBindingComponent binding
    ) {
        try {
            String stationedRole = evolutionService.getStationedRoleNameForProfile(profile);
            Vector3d spawnPos = new Vector3d(
                    profile.getStationX(), profile.getStationY(), profile.getStationZ());

            Pair<Ref<EntityStore>, ?> result = NPCPlugin.get().spawnNPC(
                    store, stationedRole, null, spawnPos, new Vector3f(0f, 0f, 0f));

            if (result == null || result.left() == null) {
                profile.setEntityMissing(true);
                return;
            }

            Ref<EntityStore> newRef = result.left();
            UUIDComponent uuidComp = store.getComponent(newRef, UUIDComponent.getComponentType());
            if (uuidComp != null) {
                profile.setCompanionEntityUuid(uuidComp.getUuid().toString());
            }
            profile.setEntityMissing(false);
            ScrubyArmorService.applyVisualArmor(store, newRef, profile);

            if (binding.getActiveSlot() == profile.getSlotId()) {
                companionRegistry.register(ownerUuid, newRef);
            }
        } catch (Exception e) {
            LOGGER.atSevere().log("[Scruby] Failed to respawn stationed companion slot "
                    + profile.getSlotId() + ": " + e.getMessage());
            profile.setEntityMissing(true);
        }
    }
}
