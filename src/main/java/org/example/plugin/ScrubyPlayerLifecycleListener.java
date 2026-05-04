// Datei: ScrubyPlayerLifecycleListener.java
package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ScrubyPlayerLifecycleListener {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Wait-and-retry window: when a rejoin's UUID lookup fails (chunks still
     * unloading from the previous session), keep retrying for this long before
     * giving up and spawning a fresh companion. The original entity often
     * resurfaces within ~1 second once the chunk finishes loading; 5s leaves
     * generous headroom and matches the "feels instant" budget for the user.
     */
    static final long RESOLVE_TIMEOUT_MS = 5000L;

    /** Pending rejoin resolves keyed by owner UUID. */
    static final ConcurrentHashMap<UUID, PendingCompanionResolve> pendingResolves =
            new ConcurrentHashMap<>();

    /** State carried for a wait-and-retry resolve attempt across system ticks. */
    static final class PendingCompanionResolve {
        final UUID ownerUuid;
        final String companionUuidString;
        /** Null falls back to the default world when the system processes the entry. */
        final UUID worldUuid;
        final long enqueueTimestamp;

        PendingCompanionResolve(@Nonnull UUID ownerUuid,
                                @Nonnull String companionUuidString,
                                UUID worldUuid,
                                long enqueueTimestamp) {
            this.ownerUuid = ownerUuid;
            this.companionUuidString = companionUuidString;
            this.worldUuid = worldUuid;
            this.enqueueTimestamp = enqueueTimestamp;
        }
    }

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

    /**
     * Minimal disconnect cleanup — only in-memory state, NO ECS access and NO
     * packets to the leaving client. Without this, the active-companion
     * registry keeps a stale {@code owner -> companionRef} entry that survives
     * the disconnect; on rejoin the chunk-unloaded ref tests as invalid and
     * {@link ScrubySmartFollowSystem#handleInvalidCompanion} eagerly spawns a
     * fresh companion before the wait-and-retry resolve gets a chance — the
     * original entity later loads back in as an orphan.
     *
     * Earlier versions called {@code npcEntity.remove()} here and crashed the
     * client with a despawn packet on a tearing-down connection. Keep this
     * handler strictly to plain Java state.
     */
    public void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        if (playerRef == null) return;
        UUID ownerUuid = playerRef.getUuid();
        registry.unregister(ownerUuid);
        pendingResolves.remove(ownerUuid);
        hudManager.onPlayerLeave(ownerUuid);
        LOGGER.atInfo().log("[Scruby-Disconnect] In-memory cleanup for " + ownerUuid);
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
            try {
                UUID companionUuid = UUID.fromString(companionUuidString);
                companionRef = entityStore.getRefFromUUID(companionUuid);
            } catch (IllegalArgumentException ignored) {}
        }

        if (companionRef != null && companionRef.isValid() && companionRef.getStore() == store) {
            registerExistingCompanion(store, playerRef, companionRef, binding, profile);
            return;
        }

        UUID ownerUuid = resolveOwnerUuidOrFallback(binding, playerRef);
        boolean wasManuallyDespawned = profile.isManuallyDespawned();

        if (wasManuallyDespawned) {
            // User explicitly despawned with /scruby despawn before disconnecting.
            // Honor that — clear the stale UUID and don't auto-respawn.
            bindingService.markCompanionEntityMissing(store, playerRef);
            this.resetService.resetOwnerSession(ownerUuid);
            LOGGER.atInfo().log("[Scruby] Companion was manually despawned, skipping auto-respawn.");
            return;
        }

        // Lookup failed but the profile still has a UUID and it wasn't a manual
        // despawn — the companion is likely in a chunk that's still loading
        // after the rejoin. Queue a wait-and-retry instead of immediately
        // spawning a fresh one (the immediate spawn was the orphan-on-rejoin
        // bug — the old entity later loads in and we end up with two).
        if (companionUuidString != null && !companionUuidString.isEmpty()) {
            UUID worldUuidForResolve = (playerRefComponent != null) ? playerRefComponent.getWorldUuid() : null;
            pendingResolves.put(ownerUuid, new PendingCompanionResolve(
                    ownerUuid,
                    companionUuidString,
                    worldUuidForResolve,
                    System.currentTimeMillis()));
            LOGGER.atInfo().log("[Scruby] Companion lookup failed on rejoin — queued resolve retry. Owner="
                    + ownerUuid + " entity=" + companionUuidString);
            // Don't touch profile or registry yet — the resolve system will
            // either reattach to the entity or fall back to a fresh spawn.
            respawnStationedCompanions(store, entityStore, binding);
            return;
        }

        // No saved UUID at all (truly fresh case) — spawn now.
        spawnFreshCompanion(store, playerRef, playerRefComponent, binding, ownerUuid);
        respawnStationedCompanions(store, entityStore, binding);
    }

    /**
     * Registers a found companion entity as the player's active companion.
     * Re-applies attributes/skills/visual armor so plugin-balance updates
     * take effect on existing companions.
     *
     * Called from both the immediate-success path of onPlayerReadyInternal
     * and from {@link ScrubyCompanionResolveSystem} after a deferred resolve.
     */
    void registerExistingCompanion(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull ScrubyOwnerBindingComponent binding,
            @Nonnull CompanionProfile profile
    ) {
        if (profile.isEntityMissing()) {
            profile.setEntityMissing(false);
            binding.setActiveProfile(profile);
        }

        String ownerUuidString = binding.getOwnerPlayerUuid();
        if (ownerUuidString == null || ownerUuidString.isEmpty()) {
            return;
        }

        UUID ownerUuid;
        try {
            ownerUuid = UUID.fromString(ownerUuidString);
        } catch (IllegalArgumentException e) {
            return;
        }

        this.resetService.clearLockedTarget(store, companionRef);
        this.registry.register(ownerUuid, companionRef);

        LOGGER.atInfo().log("[Scruby] registerExistingCompanion: reattached owner=" + ownerUuid
                + " refIdx=" + companionRef.getIndex() + " profileSlot=" + profile.getSlotId());

        // Re-apply attribute and skill modifiers so balancing changes from a plugin
        // update take effect on companions already living in the world. Modifiers
        // are stored on the entity's StatMap and otherwise stay frozen at the values
        // computed at the last spawn — putModifier replaces by id, so this is a
        // safe in-place refresh.
        this.attributeService.applyAttributes(store, companionRef, profile, ownerUuid);
        this.skillService.applyPassiveSkills(store, companionRef, profile);

        ScrubyArmorService.applyVisualArmor(store, companionRef, profile);
    }

    /**
     * Spawns a fresh companion when no saved UUID exists or the resolve
     * timed out. Mirrors the original auto-respawn path.
     *
     * Called from onPlayerReadyInternal (no-saved-UUID branch) and from
     * {@link ScrubyCompanionResolveSystem} (timeout fallback).
     */
    void spawnFreshCompanion(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            PlayerRef playerRefComponent,
            @Nonnull ScrubyOwnerBindingComponent binding,
            @Nonnull UUID ownerUuid
    ) {
        bindingService.markCompanionEntityMissing(store, ownerRef);
        this.resetService.resetOwnerSession(ownerUuid);

        if (playerRefComponent == null) {
            playerRefComponent = store.getComponent(ownerRef, PlayerRef.getComponentType());
        }

        if (playerRefComponent == null) {
            LOGGER.atInfo().log("[Scruby] Auto-respawn skipped: no PlayerRef.");
            return;
        }

        UUID spawnedUuid = this.spawnService.spawnCompanion(store, ownerRef, playerRefComponent, ownerUuid);

        if (spawnedUuid != null) {
            LOGGER.atInfo().log("[Scruby] Auto-respawn after rejoin.");
        } else {
            LOGGER.atInfo().log("[Scruby] Auto-respawn failed. Binding cleaned.");
        }
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

        // Resolve current world's name once. Stationed companions are bound to
        // a specific world via stationWorldId — we must NEVER re-spawn them in
        // a different world's store. Without this filter, entering a Forgotten
        // Temple instance respawns home-stationed Scrubys at their home coords
        // inside the temple-instance store, which (a) places the entity in
        // unloaded chunks ("moved into a chunk that isn't currently loaded"
        // warnings), (b) overwrites profile.companionEntityUuid so the
        // original home-stationed entity becomes an orphan in its real world,
        // and (c) stays as a corpse in the destroyed temple instance forever.
        String currentWorldName = "";
        try {
            if (entityStore.getWorld() != null && entityStore.getWorld().getName() != null) {
                currentWorldName = entityStore.getWorld().getName();
            }
        } catch (Exception ignored) {}

        for (CompanionProfile p : profiles) {
            if (p.getSlotId() == activeSlot) continue; // active companion handled above
            if (!p.isStationedAtBase()) continue; // not stationed
            if (ScrubyBaseStationService.MODE_NONE.equals(p.getStationMode())) continue;

            // World filter: stationed lives in its own world. Empty stationWorldId
            // is legacy data (pre-stationWorldId tracking) — treat as match-any
            // for backwards compat.
            String stationWorldName = p.getStationWorldId();
            if (!stationWorldName.isEmpty() && !stationWorldName.equals(currentWorldName)) {
                LOGGER.atInfo().log("[Scruby] Stationed slot " + p.getSlotId()
                        + " stationWorld='" + stationWorldName + "' != currentWorld='"
                        + currentWorldName + "', skipping respawn here");
                continue;
            }

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

    /** Package-private accessor for {@link ScrubyCompanionResolveSystem}. */
    @Nonnull
    ScrubyBindingService getBindingService() { return bindingService; }
}
