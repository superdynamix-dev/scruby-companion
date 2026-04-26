// Datei: ScrubyPlayerLifecycleListener.java
package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

public final class ScrubyPlayerLifecycleListener {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyCompanionSpawnService spawnService;
    private final ScrubyHudManager hudManager;
    private final ScrubyEvolutionService evolutionService;
    private final ScrubyConfigService configService;
    private final ScrubyCombatModeOverrideService combatModeOverrideService;
    private final ScrubyAttributeService attributeService;
    private final ScrubySkillService skillService;

    public ScrubyPlayerLifecycleListener(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyCompanionSpawnService spawnService,
            @Nonnull ScrubyHudManager hudManager,
            @Nonnull ScrubyEvolutionService evolutionService,
            @Nonnull ScrubyConfigService configService,
            @Nonnull ScrubyCombatModeOverrideService combatModeOverrideService,
            @Nonnull ScrubyAttributeService attributeService,
            @Nonnull ScrubySkillService skillService
    ) {
        this.bindingService = bindingService;
        this.registry = registry;
        this.resetService = resetService;
        this.spawnService = spawnService;
        this.hudManager = hudManager;
        this.evolutionService = evolutionService;
        this.configService = configService;
        this.combatModeOverrideService = combatModeOverrideService;
        this.attributeService = attributeService;
        this.skillService = skillService;
    }

    public void onPlayerReady(@Nonnull PlayerReadyEvent event) {

        Ref<EntityStore> playerRef = event.getPlayerRef();
        World world = event.getPlayer().getWorld();
        String worldName;
        try {
            worldName = world.getName();
            if (worldName == null) worldName = "";
        } catch (Exception e) {
            worldName = "";
        }
        final String worldNameFinal = worldName;

        world.execute(() -> {
            try {
                onPlayerReadyInternal(playerRef, worldNameFinal);
            } catch (Exception e) {
                LOGGER.atSevere().log("[Scruby] Error in onPlayerReady: " + e.getMessage());
            }
        });
    }

    private void onPlayerReadyInternal(@Nonnull Ref<EntityStore> playerRef, @Nonnull String worldName) {
        Store<EntityStore> store = playerRef.getStore();
        EntityStore entityStore = store.getExternalData();

        PlayerRef playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());

        // Temple combat-mode override: runs BEFORE companion respawn so the
        // subsequent spawnCompanion() call uses the forced PASSIVE role while
        // the player is inside a Forgotten Temple instance.
        if (playerRefComponent != null) {
            try {
                combatModeOverrideService.handleWorldChange(
                        store, playerRef, playerRefComponent,
                        playerRefComponent.getUuid(), worldName);
            } catch (Exception e) {
                LOGGER.atSevere().log("[Scruby] Temple override failed: " + e.getMessage());
            }
        }

        if (playerRefComponent != null) {
            hudManager.onPlayerJoin(playerRefComponent.getUuid());

            ScrubyOwnerBindingComponent posBinding = bindingService.getBindingOrNull(store, playerRef);
            if (posBinding != null && posBinding.hasBinding()) {
                hudManager.loadHudPosition(playerRefComponent.getUuid(), posBinding.getHudPosition());
            }

            if (configService.isWelcomeGreetingEnabled()
                    && posBinding != null && !ScrubyMuteGreetingCommand.isMuted(posBinding)) {
                String locale = ScrubyLang.DEFAULT_LOCALE;
                CompanionProfile earlyProfile = posBinding.hasBinding() ? posBinding.getActiveProfile() : null;
                if (earlyProfile != null) locale = earlyProfile.getLocale();
                playerRefComponent.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.welcome")));
                playerRefComponent.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.welcome.hint")));
            }
        }

        ScrubyOwnerBindingComponent binding =
                bindingService.getBindingOrNull(store, playerRef);

        // One-way migration: top up attribute points for profiles saved under the
        // old "1 per level" rate. Safe to call on every join (idempotent no-op after).
        if (binding != null && binding.hasBinding()) {
            int migrated = 0;
            List<CompanionProfile> profilesToMigrate = binding.getProfiles();
            for (int i = 0; i < profilesToMigrate.size(); i++) {
                CompanionProfile p = profilesToMigrate.get(i);
                if (ScrubyAttributeService.migrateAttributePoints(p)) {
                    profilesToMigrate.set(i, p);
                    migrated++;
                }
            }
            if (migrated > 0) {
                binding.setProfiles(profilesToMigrate);
                UUID pu = playerRefComponent != null ? playerRefComponent.getUuid() : null;
                if (pu != null) {
                    ScrubyCompanionPlugin.savePlayerAsync(store, playerRef, pu);
                }
                LOGGER.atInfo().log("[Scruby] Attribute-point migration: topped up "
                        + migrated + " profile(s) for player " + pu);
            }
        }

        if (binding == null || !binding.hasBinding()) {
            try {
                UUID ownerUuid = playerRefComponent != null
                        ? playerRefComponent.getUuid()
                        : store.getComponent(playerRef, PlayerRef.getComponentType()).getUuid();
                binding = bindingService.bindOwner(store, playerRef, ownerUuid);
                LOGGER.atInfo().log("[Scruby] Auto-bind for first-time player " + ownerUuid);

                UUID spawnedUuid = spawnService.spawnCompanion(store, playerRef, playerRefComponent, ownerUuid);
                if (spawnedUuid != null) {
                    LOGGER.atInfo().log("[Scruby] Auto-spawn for first-time player " + ownerUuid);
                }
            } catch (Exception e) {
                LOGGER.atInfo().log("[Scruby] Auto-bind/spawn failed: " + e.getMessage());
            }
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        String companionUuidString = profile.getCompanionEntityUuid();

        Ref<EntityStore> companionRef = null;

        if (companionUuidString != null && !companionUuidString.isEmpty()) {
            UUID companionUuid = UUID.fromString(companionUuidString);
            companionRef = entityStore.getRefFromUUID(companionUuid);
        }

        if (companionRef != null && companionRef.isValid()) {
            // Cross-world guard: only register if companion is in this world's store
            if (companionRef.getStore() != store) {
                return;
            }

            if (profile.isEntityMissing()) {
                profile.setEntityMissing(false);
                binding.setActiveProfile(profile);
            }

            String ownerUuidString = binding.getOwnerPlayerUuid();
            if (ownerUuidString == null || ownerUuidString.isEmpty()) {
                return;
            }

            UUID ownerUuid = UUID.fromString(ownerUuidString);
            this.resetService.clearLockedTarget(store, companionRef);
            this.registry.register(ownerUuid, companionRef);

            // Re-apply attribute and skill modifiers so balancing changes from a plugin
            // update take effect on companions already living in the world. Modifiers
            // are stored on the entity's StatMap and otherwise stay frozen at the values
            // computed at the last spawn — putModifier replaces by id, so this is a
            // safe in-place refresh.
            this.attributeService.applyAttributes(store, companionRef, profile, ownerUuid);
            this.skillService.applyPassiveSkills(store, companionRef, profile);

            ScrubyArmorService.applyVisualArmor(store, companionRef, profile);
            return;
        }

        UUID ownerUuid = resolveOwnerUuidOrFallback(binding, playerRef);

        boolean wasManuallyDespawned = profile.isManuallyDespawned();

        bindingService.markCompanionEntityMissing(store, playerRef);
        this.resetService.resetOwnerSession(ownerUuid);

        if (wasManuallyDespawned) {
            LOGGER.atInfo().log("[Scruby] Companion was manually despawned, skipping auto-respawn.");
            return;
        }

        if (playerRefComponent == null) {
            playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());
        }

        if (playerRefComponent == null) {
            LOGGER.atInfo().log("[Scruby] Companion entity missing after rejoin, binding cleaned. Auto-respawn skipped: no PlayerRef.");
            return;
        }

        UUID spawnedUuid = this.spawnService.spawnCompanion(store, playerRef, playerRefComponent, ownerUuid);

        if (spawnedUuid != null) {
            LOGGER.atInfo().log("[Scruby] Auto-respawn after rejoin.");
        } else {
            LOGGER.atInfo().log("[Scruby] Companion entity missing after rejoin, auto-respawn failed. Binding cleaned.");
        }

        respawnStationedCompanions(store, entityStore, binding);
    }

    /**
     * Respawns all stationed companions that no longer exist in the world.
     * Each stationed companion is spawned at its saved position without
     * registering in the companion registry (only the active companion uses the registry).
     */
    private void respawnStationedCompanions(
            @Nonnull Store<EntityStore> store,
            @Nonnull EntityStore entityStore,
            @Nonnull ScrubyOwnerBindingComponent binding
    ) {
        List<CompanionProfile> profiles = binding.getProfiles();
        int activeSlot = binding.getActiveSlot();

        for (CompanionProfile p : profiles) {
            if (p.getSlotId() == activeSlot) continue; // active companion handled above
            if (!p.isStationedAtBase()) continue; // not stationed
            if (ScrubyBaseStationService.MODE_NONE.equals(p.getStationMode())) continue;

            // Check if entity still exists in world
            String entityUuidStr = p.getCompanionEntityUuid();
            if (entityUuidStr != null && !entityUuidStr.isEmpty()) {
                try {
                    UUID entityUuid = UUID.fromString(entityUuidStr);
                    Ref<EntityStore> existingRef = entityStore.getRefFromUUID(entityUuid);
                    if (existingRef != null && existingRef.isValid()) {
                        LOGGER.atInfo().log("[Scruby] Stationed companion slot " + p.getSlotId() + " still exists, skipping respawn.");
                        continue; // entity still alive, no need to respawn
                    }
                } catch (Exception ignored) {}
            }

            // Entity gone — respawn at station position
            try {
                String stationedRole = evolutionService.getStationedRoleNameForProfile(p);
                com.hypixel.hytale.math.vector.Vector3d spawnPos = new com.hypixel.hytale.math.vector.Vector3d(
                        p.getStationX(), p.getStationY(), p.getStationZ());

                it.unimi.dsi.fastutil.Pair<Ref<EntityStore>, ?> result =
                        com.hypixel.hytale.server.npc.NPCPlugin.get().spawnNPC(
                                store, stationedRole, null, spawnPos,
                                new com.hypixel.hytale.math.vector.Vector3f(0f, 0f, 0f));

                if (result != null && result.left() != null) {
                    // Save new entity UUID in profile
                    com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp =
                            store.getComponent(result.left(),
                                    com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
                    if (uuidComp != null) {
                        p.setCompanionEntityUuid(uuidComp.getUuid().toString());
                        // Update profile in binding — replace in profiles list
                        List<CompanionProfile> allProfiles = binding.getProfiles();
                        for (int i = 0; i < allProfiles.size(); i++) {
                            if (allProfiles.get(i).getSlotId() == p.getSlotId()) {
                                allProfiles.set(i, p);
                                break;
                            }
                        }
                        binding.setProfiles(allProfiles);
                    }
                    // Restore visual armor from persistent profile
                    ScrubyArmorService.applyVisualArmor(store, result.left(), p);
                    LOGGER.atInfo().log("[Scruby] Respawned stationed companion slot " + p.getSlotId()
                            + " at (" + p.getStationX() + ", " + p.getStationY() + ", " + p.getStationZ() + ")");
                } else {
                    LOGGER.atInfo().log("[Scruby] Failed to respawn stationed companion slot " + p.getSlotId());
                }
            } catch (Exception e) {
                LOGGER.atInfo().log("[Scruby] Error respawning stationed companion slot " + p.getSlotId() + ": " + e.getMessage());
            }
        }
    }

    @Nonnull
    private UUID resolveOwnerUuidOrFallback(
            @Nonnull ScrubyOwnerBindingComponent binding,
            @Nonnull Ref<EntityStore> playerRef
    ) {
        String ownerUuidString = binding.getOwnerPlayerUuid();

        if (ownerUuidString != null && !ownerUuidString.isEmpty()) {
            try {
                return UUID.fromString(ownerUuidString);
            } catch (IllegalArgumentException ignored) {
            }
        }

        return playerRef.getStore()
                .getComponent(playerRef, PlayerRef.getComponentType())
                .getUuid();
    }
}
