package org.example.plugin;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Genshin-style unsichtbares Follow-System.
 *
 * Drei-Zonen-Modell:
 * - Gruen  (&lt; 35 Bloecke): FlockLeader uebernimmt
 * - Gelb (35-80 Bloecke): Soft-Teleport hinter Spieler, NUR wenn nicht im 120-Grad-Sichtfeld
 * - Rot   (&gt; 80 Bloecke): Hard-Teleport hinter Spieler, immer
 *
 * Kein Sound, kein Partikeleffekt.
 */
public final class ScrubySmartFollowSystem extends TickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Check interval: 10 ticks = 0.5s at 20 TPS. */
    private static final int FOLLOW_CHECK_INTERVAL = 10;

    /** Soft teleport threshold: 35 blocks squared. */
    private static final double SOFT_TELEPORT_DISTANCE_SQ = 35.0 * 35.0;

    /** Hard teleport threshold: 80 blocks squared. */
    private static final double HARD_TELEPORT_DISTANCE_SQ = 80.0 * 80.0;

    /**
     * Dot-product threshold for 120 degree FOV cone.
     * dot > 0.5 means companion is within 60 degrees of player's forward direction = visible.
     */
    private static final double FOV_DOT_THRESHOLD = 0.5;

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionSpawnService spawnService;

    /** Cooldown after wrong-flock correction: 200 ticks = 10 seconds. */
    private static final int WRONG_FLOCK_COOLDOWN = 200;

    private int tickCounter = 0;
    private final Map<UUID, Integer> wrongFlockCooldowns = new HashMap<>();

    public ScrubySmartFollowSystem(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionSpawnService spawnService
    ) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.spawnService = Objects.requireNonNull(spawnService, "spawnService");
    }

    @Override
    public void tick(float deltaTime, int tickCount, @Nonnull Store<EntityStore> store) {
        tickCounter++;
        if (tickCounter % FOLLOW_CHECK_INTERVAL != 0) return;

        EntityStore entityStore = store.getExternalData();

        for (Map.Entry<UUID, Ref<EntityStore>> entry : registry.entriesSnapshot()) {
            UUID ownerUuid = entry.getKey();
            Ref<EntityStore> companionRef = entry.getValue();

            // --- Cross-world guard: skip if companion belongs to a different world's store ---
            if (companionRef != null && companionRef.getStore() != store) {
                continue;
            }

            // --- Self-healing: invalid companion ref -> respawn ---
            if (companionRef == null || !companionRef.isValid()) {
                handleInvalidCompanion(store, entityStore, ownerUuid);
                continue;
            }

            // --- Wrong-flock check: companion in a flock but owner is not -> despawn + respawn ---
            Integer cooldown = wrongFlockCooldowns.get(ownerUuid);
            if (cooldown != null && cooldown > 0) {
                wrongFlockCooldowns.put(ownerUuid, cooldown - FOLLOW_CHECK_INTERVAL);
            } else if (isInWrongFlock(store, entityStore, ownerUuid, companionRef)) {
                Ref<EntityStore> owrRef = entityStore.getRefFromUUID(ownerUuid);
                PlayerRef plrRef = (owrRef != null && owrRef.isValid())
                        ? store.getComponent(owrRef, PlayerRef.getComponentType()) : null;
                despawnAndRespawnAtOwner(store, companionRef, owrRef, plrRef, ownerUuid);
                wrongFlockCooldowns.put(ownerUuid, WRONG_FLOCK_COOLDOWN);
                continue;
            }

            // --- Get transforms ---
            TransformComponent companionTransform =
                    store.getComponent(companionRef, TransformComponent.getComponentType());
            if (companionTransform == null) continue;

            Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(ownerUuid);
            if (ownerRef == null || !ownerRef.isValid()) continue;

            PlayerRef playerRef = store.getComponent(ownerRef, PlayerRef.getComponentType());
            if (playerRef == null) continue;

            // Skip companions with no binding or stationed/despawned
            ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
            if (binding == null) continue;
            CompanionProfile profile = binding.getActiveProfile();
            if (profile.isStationedAtBase() || profile.isManuallyDespawned()) {
                continue;
            }

            Vector3d playerPos = playerRef.getTransform().getPosition();
            Vector3d companionPos = companionTransform.getPosition();
            double distSq = distanceSquared(playerPos, companionPos);

            // --- Green zone: FlockLeader handles it ---
            if (distSq <= SOFT_TELEPORT_DISTANCE_SQ) {
                continue;
            }

            // --- Red zone: hard teleport regardless of FOV ---
            if (distSq > HARD_TELEPORT_DISTANCE_SQ) {
                teleportBehindPlayer(companionTransform, playerPos, playerRef);
                LOGGER.atInfo().log("[Scruby-Follow] Hard-teleport. Owner=" + ownerUuid
                        + " dist=" + (int) Math.sqrt(distSq));
                continue;
            }

            // --- Yellow zone: soft teleport only if NOT in player FOV ---
            float playerYaw = playerRef.getHeadRotation().getYaw();
            if (!isInPlayerFieldOfView(playerYaw, playerPos, companionPos)) {
                teleportBehindPlayer(companionTransform, playerPos, playerRef);
                LOGGER.atInfo().log("[Scruby-Follow] Soft-teleport (out of FOV). Owner=" + ownerUuid
                        + " dist=" + (int) Math.sqrt(distSq));
            }
            // else: in FOV -> wait, do nothing, FlockLeader keeps trying
        }
    }

    /**
     * Prueft ob der Companion im 120-Grad-Sichtfeld des Spielers ist (XZ-Ebene).
     */
    private static boolean isInPlayerFieldOfView(
            float playerYaw,
            @Nonnull Vector3d playerPos,
            @Nonnull Vector3d companionPos
    ) {
        double yawRad = playerYaw;
        double dirX = -Math.sin(yawRad);
        double dirZ = -Math.cos(yawRad);

        double toCompX = companionPos.getX() - playerPos.getX();
        double toCompZ = companionPos.getZ() - playerPos.getZ();

        double length = Math.sqrt(toCompX * toCompX + toCompZ * toCompZ);
        if (length < 0.001) return true; // practically on top of player

        toCompX /= length;
        toCompZ /= length;

        double dot = dirX * toCompX + dirZ * toCompZ;
        return dot > FOV_DOT_THRESHOLD;
    }

    /**
     * Teleportiert den Companion hinter den Spieler via FollowSlot.
     * Kein Sound, kein Effekt.
     */
    private static void teleportBehindPlayer(
            @Nonnull TransformComponent companionTransform,
            @Nonnull Vector3d playerPos,
            @Nonnull PlayerRef playerRef
    ) {
        Vector3d followSlot = ScrubyFollowSlots.computeDefaultFollowSlot(
                playerPos, playerRef.getHeadRotation());
        companionTransform.setPosition(followSlot);
    }

    /**
     * Self-healing: Companion-Ref ist ungueltig -> aus Registry entfernen und respawnen.
     * Migriert aus ScrubyXPSystem.teleportDistantCompanions().
     */
    private void handleInvalidCompanion(
            @Nonnull Store<EntityStore> store,
            @Nonnull EntityStore entityStore,
            @Nonnull UUID ownerUuid
    ) {
        Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(ownerUuid);
        if (ownerRef == null || !ownerRef.isValid()) return;

        // Owner must be in this world's store — don't respawn cross-world
        if (ownerRef.getStore() != store) return;

        PlayerRef playerRef = store.getComponent(ownerRef, PlayerRef.getComponentType());
        if (playerRef == null) return;

        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding != null && (binding.getActiveProfile().isManuallyDespawned()
                || binding.getActiveProfile().isStationedAtBase())) {
            return;
        }

        registry.unregister(ownerUuid);
        bindingService.markCompanionEntityMissing(store, ownerRef);
        UUID newUuid = spawnService.spawnCompanion(store, ownerRef, playerRef, ownerUuid, true);
        if (newUuid != null) {
            LOGGER.atInfo().log("[Scruby-Follow] Respawned lost companion for Owner=" + ownerUuid);
        } else {
            LOGGER.atInfo().log("[Scruby-Follow] Respawn failed for Owner=" + ownerUuid);
        }
    }

    /**
     * Checks if the companion is in the wrong flock: companion has a flock
     * but owner does not, meaning the companion joined another player's flock.
     */
    private boolean isInWrongFlock(
            @Nonnull Store<EntityStore> store,
            @Nonnull EntityStore entityStore,
            @Nonnull UUID ownerUuid,
            @Nonnull Ref<EntityStore> companionRef
    ) {
        try {
            ComponentType<EntityStore, FlockMembership> fmType =
                    FlockPlugin.get().getFlockMembershipComponentType();

            FlockMembership companionFm = store.getComponent(companionRef, fmType);
            if (companionFm == null || companionFm.getFlockId() == null) {
                return false; // companion not in any flock — normal state right after spawn
            }

            Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(ownerUuid);
            if (ownerRef == null || !ownerRef.isValid()) {
                return false; // owner not present — can't check
            }

            FlockMembership ownerFm = store.getComponent(ownerRef, fmType);
            if (ownerFm == null || ownerFm.getFlockId() == null) {
                // Owner has no flock but companion does — companion is in someone else's flock
                return true;
            }

            // Both have flocks — check if they match
            return !companionFm.getFlockId().equals(ownerFm.getFlockId());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Despawns a companion that joined the wrong flock and immediately respawns
     * it at the owner's exact position so the owner is the nearest player
     * for FlockLeader assignment.
     */
    private void despawnAndRespawnAtOwner(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nullable Ref<EntityStore> ownerRef,
            @Nullable PlayerRef playerRef,
            @Nonnull UUID ownerUuid
    ) {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (npcType != null) {
            NPCEntity npc = store.getComponent(companionRef, npcType);
            if (npc != null) {
                npc.remove();
            }
        }
        registry.unregister(ownerUuid);

        if (ownerRef != null && ownerRef.isValid() && playerRef != null) {
            bindingService.markCompanionEntityMissing(store, ownerRef);
            // spawnCompanion with facingPlayer=false spawns at default follow slot;
            // we use the 4-arg overload which places closer to owner
            UUID newUuid = spawnService.spawnCompanion(store, ownerRef, playerRef, ownerUuid);
            if (newUuid != null) {
                LOGGER.atInfo().log("[Scruby-Follow] Wrong-flock fixed, respawned for Owner=" + ownerUuid);
            }
        } else {
            LOGGER.atInfo().log("[Scruby-Follow] Despawned wrong-flock companion, owner not available. Owner=" + ownerUuid);
        }
    }

    private static double distanceSquared(@Nonnull Vector3d a, @Nonnull Vector3d b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
