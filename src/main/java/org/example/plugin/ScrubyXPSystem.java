package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ticking system for passive proximity XP and deferred evolution respawns.
 * Kill XP is handled by ScrubyKillDetectionSystem (DeathComponent-based).
 * Evolution respawns are queued by KillDetectionSystem and drained here,
 * because Store.addEntity is forbidden inside RefChangeSystem callbacks.
 */
public final class ScrubyXPSystem extends TickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Proximity XP interval: 1200 ticks = 60s at 20 TPS. */
    private static final int PROXIMITY_TICK_INTERVAL = 1200;

    private static final double PROXIMITY_CLOSE_SQ = 10.0 * 10.0;
    private static final double PROXIMITY_MID_SQ   = 20.0 * 20.0;

    /** Backoff before retrying a deferred respawn entry (multi-world tick race). */
    private static final long RESPAWN_DEFER_BACKOFF_MS = 200L;
    /** Drop deferred respawns this long after their original ready time. */
    private static final long RESPAWN_MAX_DEFER_MS = 60_000L;

    private static final int PATH_REQUIRED_LEVEL = 5;
    private static final long PATH_WARNING_COOLDOWN_MS = 60_000L;

    private final Map<UUID, Long> lastPathWarningTime = new HashMap<>();

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyXPService xpService;
    private final ScrubyEvolutionService evolutionService;
    private final ScrubyPendingRespawnQueue pendingRespawnQueue;
    private final ScrubyCompanionSpawnService spawnService;
    private final ScrubySoundService soundService;
    private final ScrubyConfigService configService;

    private int tickCounter = 0;

    public ScrubyXPSystem(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyXPService xpService,
            @Nonnull ScrubyEvolutionService evolutionService,
            @Nonnull ScrubyPendingRespawnQueue pendingRespawnQueue,
            @Nonnull ScrubyCompanionSpawnService spawnService,
            @Nonnull ScrubySoundService soundService,
            @Nonnull ScrubyConfigService configService
    ) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.xpService = Objects.requireNonNull(xpService, "xpService");
        this.evolutionService = Objects.requireNonNull(evolutionService, "evolutionService");
        this.pendingRespawnQueue = Objects.requireNonNull(pendingRespawnQueue, "pendingRespawnQueue");
        this.spawnService = Objects.requireNonNull(spawnService, "spawnService");
        this.soundService = Objects.requireNonNull(soundService, "soundService");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    @Override
    public void tick(float deltaTime, int tickCount, @Nonnull Store<EntityStore> store) {
        tickCounter++;

        // Drain pending evolution respawns (queued by KillDetectionSystem)
        drainPendingRespawns(store);

        if (tickCounter % PROXIMITY_TICK_INTERVAL != 0) {
            return;
        }

        EntityStore entityStore = store.getExternalData();

        for (Map.Entry<UUID, Ref<EntityStore>> entry : registry.entriesSnapshot()) {
            UUID ownerUuid = entry.getKey();
            Ref<EntityStore> companionRef = entry.getValue();

            if (companionRef == null || !companionRef.isValid()) {
                continue;
            }

            // Cross-world guard: skip if companion belongs to a different world's store
            if (companionRef.getStore() != store) continue;

            NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
            if (npcEntity == null) {
                continue;
            }

            Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(ownerUuid);
            if (ownerRef == null || !ownerRef.isValid()) {
                continue;
            }

            ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
            if (binding == null) {
                continue;
            }

            applyProximityXp(store, ownerRef, companionRef, binding);
        }
    }

    private void drainPendingRespawns(@Nonnull Store<EntityStore> store) {
        ScrubyPendingRespawnQueue.RespawnEntry entry;
        while ((entry = pendingRespawnQueue.pollReady()) != null) {
            UUID pendingOwner = entry.ownerUuid();
            LOGGER.atInfo().log("[Scruby] Draining pending respawn for owner=" + pendingOwner);

            // Skip if companion already active
            Ref<EntityStore> existingRef = registry.getCompanionRef(pendingOwner);
            if (existingRef != null && existingRef.isValid()) {
                LOGGER.atInfo().log("[Scruby] Skipping respawn, companion already active for " + pendingOwner);
                continue;
            }

            EntityStore entityStore = store.getExternalData();
            Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(pendingOwner);
            if (ownerRef == null || !ownerRef.isValid()) {
                // The respawn queue is shared across all worlds; this system ticks per world.
                // If a non-owner world's tick polled the entry first, the owner won't be in
                // this store. Re-enqueue with a short backoff so the owner's world picks it
                // up. Drop after MAX_DEFER_MS; ScrubyPlayerLifecycleListener.respawn-
                // StationedCompanions is the safety net on player rejoin.
                long now = System.currentTimeMillis();
                long ageSinceReady = now - entry.respawnAtMs();
                if (ageSinceReady < RESPAWN_MAX_DEFER_MS) {
                    pendingRespawnQueue.enqueue(pendingOwner,
                            now + RESPAWN_DEFER_BACKOFF_MS,
                            entry.facingPlayer());
                    LOGGER.atInfo().log("[Scruby] Respawn deferred — owner not in this store. Owner="
                            + pendingOwner + " ageMs=" + ageSinceReady);
                } else {
                    LOGGER.atInfo().log("[Scruby] Respawn dropped — owner unreachable for "
                            + ageSinceReady + "ms. Owner=" + pendingOwner
                            + ". Stationed companions auto-respawn on player rejoin.");
                }
                continue;
            }

            // Skip if manually despawned during timer
            ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
            if (binding != null) {
                CompanionProfile profile = binding.getActiveProfile();
                if (profile.isManuallyDespawned()) {
                    LOGGER.atInfo().log("[Scruby] Respawn skipped — manually despawned. Owner=" + pendingOwner);
                    continue;
                }
            }

            PlayerRef playerRef = store.getComponent(ownerRef, PlayerRef.getComponentType());
            if (playerRef == null) {
                LOGGER.atInfo().log("[Scruby] Respawn skipped — PlayerRef missing. Owner=" + pendingOwner);
                continue;
            }

            UUID companionUuid = spawnService.spawnCompanion(store, ownerRef, playerRef, pendingOwner, entry.facingPlayer());
            if (companionUuid != null) {
                Vector3d playerPos = playerRef.getTransform().getPosition();
                soundService.playRespawn(playerRef, playerPos.getX(), playerPos.getY(), playerPos.getZ());
                String locale = ScrubyLang.DEFAULT_LOCALE;
                if (binding != null) {
                    locale = binding.getActiveProfile().getLocale();
                }
                playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.respawn")));
            } else {
                LOGGER.atInfo().log("[Scruby] Respawn failed. Owner=" + pendingOwner);
            }
        }
    }

    private void applyProximityXp(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull ScrubyOwnerBindingComponent binding
    ) {
        PlayerRef playerRef = store.getComponent(ownerRef, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        TransformComponent companionTransform =
                store.getComponent(companionRef, TransformComponent.getComponentType());
        if (companionTransform == null) {
            return;
        }

        if (!configService.isProximityXpEnabled()) {
            return;
        }

        Vector3d ownerPos = playerRef.getTransform().getPosition();
        Vector3d companionPos = companionTransform.getPosition();
        double distSq = distanceSquared(ownerPos, companionPos);

        long proximityXp;
        if (distSq <= PROXIMITY_CLOSE_SQ) {
            proximityXp = configService.getProximityXpClose();
        } else if (distSq <= PROXIMITY_MID_SQ) {
            proximityXp = configService.getProximityXpMid();
        } else {
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();

        // XP blocked if level >= 5 and no path chosen
        if (profile.getLevel() >= PATH_REQUIRED_LEVEL && "NONE".equals(profile.getPathChoice())) {
            return;
        }

        int oldLevel = profile.getLevel();
        xpService.addXP(profile, proximityXp, profile.getLocale());
        int newLevel = profile.getLevel();
        binding.setActiveProfile(profile);

        // Evolution: despawn old companion, respawn with new Role/Appearance
        if (oldLevel != newLevel && xpService.triggersEvolution(oldLevel, newLevel)) {
            UUID ownerUuid = playerRef.getUuid();
            int newStage = evolutionService.calculateEvolutionStage(newLevel);
            profile.setEvolutionStage(newStage);
            binding.setActiveProfile(profile);

            String locale = profile.getLocale();
            String roleName = evolutionService.getRoleNameForProfile(profile);
            LOGGER.atInfo().log("[Scruby] Proximity XP evolution triggered. Owner=" + ownerUuid
                    + " Stage=" + newStage + " Role=" + roleName);

            TransformComponent evoTransform = store.getComponent(companionRef, TransformComponent.getComponentType());
            if (evoTransform != null) {
                Vector3d evoPos = evoTransform.getPosition();
                soundService.playEvolution(playerRef, newStage, evoPos.getX(), evoPos.getY(), evoPos.getZ());
            }
            String evoFormName = ScrubyLang.getEvolutionName(locale, newStage, profile.getPathChoice());
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.evolution.header")));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.evolution.title", newStage)));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.evolution.evolved", profile.getCompanionName())));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.evolution.form", evoFormName)));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.evolution.header")));

            registry.unregister(ownerUuid);
            profile.setCompanionEntityUuid("");
            profile.setEntityMissing(false);
            profile.setManuallyDespawned(false);
            binding.setActiveProfile(profile);
            pendingRespawnQueue.enqueue(ownerUuid, System.currentTimeMillis(), true);

            NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
            if (npcEntity != null) {
                npcEntity.remove();
            }
        }
    }

    private static double distanceSquared(@Nonnull Vector3d a, @Nonnull Vector3d b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
