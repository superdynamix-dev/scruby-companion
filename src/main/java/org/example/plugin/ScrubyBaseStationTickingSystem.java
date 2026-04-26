package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Ticking system for stationed companions.
 * <p>
 * Iterates over ALL stationed companion slots (not just the active one),
 * finding each entity by its UUID stored in the CompanionProfile.
 * <p>
 * Two responsibilities on separate intervals:
 * <ul>
 *   <li>Radius enforcement (every 5 ticks / 0.25s): teleport back to station if beyond 30 blocks</li>
 *   <li>Mob scanning (every 20 ticks / 1s): find nearest hostile NPC within guard radius
 *       and assign it as the companion's attack target via MarkedEntitySupport</li>
 * </ul>
 */
public final class ScrubyBaseStationTickingSystem extends TickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Radius enforcement runs every 5 ticks (0.25s at 20 TPS). */
    private static final int RADIUS_CHECK_INTERVAL = 5;

    /** Mob scanning runs every 20 ticks (1s at 20 TPS). */
    private static final int MOB_SCAN_INTERVAL = 20;

    /** Teleport back to station if further than 50 blocks. */
    private static final double STATION_LEASH_SQ = 50.0 * 50.0;

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyConfigService configService;

    private int tickCounter = 0;

    public ScrubyBaseStationTickingSystem(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyConfigService configService
    ) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    @Override
    public void tick(float deltaTime, int tickCount, @Nonnull Store<EntityStore> store) {
        tickCounter++;

        boolean doRadiusCheck = (tickCounter % RADIUS_CHECK_INTERVAL == 0);
        boolean doMobScan = (tickCounter % MOB_SCAN_INTERVAL == 0);

        if (!doRadiusCheck && !doMobScan) return;

        // Collect online owner UUIDs from registry (all owners with any active companion)
        Set<UUID> processedOwners = new HashSet<>();

        for (Map.Entry<UUID, Ref<EntityStore>> entry : registry.entriesSnapshot()) {
            UUID ownerUuid = entry.getKey();
            if (!processedOwners.add(ownerUuid)) continue;

            Ref<EntityStore> ownerRef = store.getExternalData().getRefFromUUID(ownerUuid);
            if (ownerRef == null || !ownerRef.isValid()) continue;

            // Cross-world guard
            if (ownerRef.getStore() != store) continue;

            ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
            if (binding == null) continue;

            // Iterate ALL profile slots, not just the active one
            List<CompanionProfile> profiles = binding.getProfiles();
            for (CompanionProfile profile : profiles) {
                if (!profile.isStationedAtBase()) continue;
                if (ScrubyBaseStationService.MODE_NONE.equals(profile.getStationMode())) continue;

                // Find the stationed entity by its UUID
                String entityUuidStr = profile.getCompanionEntityUuid();
                if (entityUuidStr == null || entityUuidStr.isEmpty()) continue;

                Ref<EntityStore> companionRef;
                try {
                    UUID entityUuid = UUID.fromString(entityUuidStr);
                    companionRef = store.getExternalData().getRefFromUUID(entityUuid);
                } catch (Exception e) {
                    continue;
                }
                if (companionRef == null || !companionRef.isValid()) continue;
                if (companionRef.getStore() != store) continue;

                TransformComponent companionTransform =
                        store.getComponent(companionRef, TransformComponent.getComponentType());
                if (companionTransform == null) continue;

                Vector3d companionPos = companionTransform.getPosition();
                Vector3d stationPos = new Vector3d(
                        profile.getStationX(), profile.getStationY(), profile.getStationZ());

                // --- Radius enforcement: teleport back if > 30 blocks ---
                if (doRadiusCheck) {
                    double distSq = distanceSquaredXZ(companionPos, stationPos);
                    if (distSq > STATION_LEASH_SQ) {
                        companionTransform.setPosition(stationPos);
                    }
                }

                // --- Mob scanning ---
                if (doMobScan) {
                    scanAndAssignTarget(store, companionRef, companionPos, stationPos);
                }
            }
        }
    }

    /**
     * Scans all NPC entities in the store, finds the closest hostile mob
     * within the guard radius of the station, and assigns it as the
     * companion's attack target via MarkedEntitySupport.
     */
    private void scanAndAssignTarget(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull Vector3d companionPos,
            @Nonnull Vector3d stationPos
    ) {
        MarkedEntitySupport mes = getMarkedEntitySupportOrNull(store, companionRef);
        if (mes == null) return;

        // Check if current target is still valid and in range — skip scan if so
        Ref<EntityStore> currentTarget = mes.getMarkedEntityRef(MarkedEntitySupport.DEFAULT_TARGET_SLOT);
        if (currentTarget != null && currentTarget.isValid()) {
            TransformComponent targetTransform =
                    store.getComponent(currentTarget, TransformComponent.getComponentType());
            if (targetTransform != null) {
                double guardRadiusSq = configService.getGuardRadiusSq();
                double targetDistSq = distanceSquaredXZ(targetTransform.getPosition(), stationPos);
                if (targetDistSq <= guardRadiusSq) {
                    return; // Current target is fine, no need to re-scan
                }
            }
        }

        // Collect candidate mobs: NPC entities within guard radius of station
        List<CandidateMob> candidates = new ArrayList<>();

        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid()) continue;

                // Skip self
                if (ref.equals(companionRef)) continue;

                // Skip registered companions
                if (isRegisteredCompanion(ref)) continue;

                // Skip other Scrubys so stationed companions never target each other
                NPCEntity candidateNpc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (candidateNpc != null) {
                    String candidateRole = candidateNpc.getRoleName();
                    if (candidateRole != null && candidateRole.contains("Scruby_")) {
                        continue;
                    }
                }

                TransformComponent transform =
                        chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) continue;

                double guardRadiusSq = configService.getGuardRadiusSq();
                double distToStationSq = distanceSquaredXZ(transform.getPosition(), stationPos);
                if (distToStationSq > guardRadiusSq) continue;

                double distToCompanionSq = distanceSquaredXZ(transform.getPosition(), companionPos);
                candidates.add(new CandidateMob(ref, distToCompanionSq));
            }
        });

        // Pick the closest candidate to the companion
        Ref<EntityStore> bestTarget = null;
        double bestDistSq = Double.MAX_VALUE;

        for (CandidateMob candidate : candidates) {
            if (candidate.distSqToCompanion < bestDistSq) {
                bestDistSq = candidate.distSqToCompanion;
                bestTarget = candidate.ref;
            }
        }

        if (bestTarget != null) {
            mes.setMarkedEntity(MarkedEntitySupport.DEFAULT_TARGET_SLOT, bestTarget);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    @Nullable
    private static MarkedEntitySupport getMarkedEntitySupportOrNull(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef
    ) {
        NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
        if (npcEntity == null) return null;

        Role role = npcEntity.getRole();
        if (role == null) return null;

        return role.getMarkedEntitySupport();
    }

    private boolean isRegisteredCompanion(@Nonnull Ref<EntityStore> ref) {
        for (Map.Entry<UUID, Ref<EntityStore>> entry : registry.entriesSnapshot()) {
            if (entry.getValue() != null && entry.getValue().equals(ref)) {
                return true;
            }
        }
        return false;
    }

    private static double distanceSquaredXZ(@Nonnull Vector3d a, @Nonnull Vector3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static final class CandidateMob {
        final Ref<EntityStore> ref;
        final double distSqToCompanion;

        CandidateMob(@Nonnull Ref<EntityStore> ref, double distSqToCompanion) {
            this.ref = ref;
            this.distSqToCompanion = distSqToCompanion;
        }
    }
}
