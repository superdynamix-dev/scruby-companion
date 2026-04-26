package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Listens for Use-interaction packets (F key) and opens the correct
 * Scruby UI depending on which companion the player is looking at:
 *
 * - Active companion in view  -> SkillTree page
 * - Stationed companion in view -> Transfer-only page (chest-like)
 * - Anything else              -> let packet pass through unchanged
 *
 * This never blocks packets (test() always returns false) — chests and
 * other normal interactions are unaffected.
 */
public final class ScrubyInteractionHandler implements PlayerPacketFilter {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Max distance squared from player to Scruby to count as "nearby". 5 blocks. */
    private static final double INTERACT_DISTANCE_SQ = 5.0 * 5.0;

    /** Dot-product threshold for 180-degree view sector (90° each side). */
    private static final double VIEW_DOT_THRESHOLD = 0.0;

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubySkillTreePage skillTreePage;

    public ScrubyInteractionHandler(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubySkillTreePage skillTreePage
    ) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.skillTreePage = Objects.requireNonNull(skillTreePage, "skillTreePage");
    }

    @Override
    public boolean test(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (!(packet instanceof SyncInteractionChains syncPacket)) {
            return false;
        }

        boolean hasUse = false;
        for (SyncInteractionChain chain : syncPacket.updates) {
            if (chain.interactionType == InteractionType.Use) {
                hasUse = true;
                break;
            }
        }
        if (!hasUse) return false;

        Ref<EntityStore> ownerRef;
        try {
            ownerRef = playerRef.getReference();
        } catch (Throwable t) {
            return false;
        }
        if (ownerRef == null || !ownerRef.isValid()) return false;

        Store<EntityStore> store = ownerRef.getStore();
        World world;
        try {
            world = store.getExternalData().getWorld();
        } catch (Throwable t) {
            return false;
        }
        if (world == null) return false;

        world.execute(() -> {
            try {
                handleUse(store, ownerRef, playerRef);
            } catch (Exception e) {
                LOGGER.atInfo().log("[Scruby-Interact] Error handling F press: " + e.getMessage());
            }
        });

        return false;
    }

    private void handleUse(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef
    ) {
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding == null || !binding.hasBinding()) return;

        Vector3d playerPos = playerRef.getTransform().getPosition();
        float playerYaw = playerRef.getHeadRotation().getYaw();

        UUID ownerUuid = playerRef.getUuid();
        Ref<EntityStore> activeRef = registry.getCompanionRef(ownerUuid);

        CompanionProfile bestProfile = null;
        boolean bestIsActive = false;
        double bestDistSq = Double.MAX_VALUE;

        List<CompanionProfile> profiles = binding.getProfiles();
        for (CompanionProfile profile : profiles) {
            Ref<EntityStore> companionRef = resolveCompanionRef(store, profile, binding, activeRef);
            if (companionRef == null || !companionRef.isValid()) continue;
            if (companionRef.getStore() != store) continue;

            TransformComponent transform =
                    store.getComponent(companionRef, TransformComponent.getComponentType());
            if (transform == null) continue;

            Vector3d companionPos = transform.getPosition();
            double distSq = distanceSquared(playerPos, companionPos);
            if (distSq > INTERACT_DISTANCE_SQ) continue;

            if (!isInViewSector(playerYaw, playerPos, companionPos)) continue;

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestProfile = profile;
                bestIsActive = !profile.isStationedAtBase()
                        && activeRef != null
                        && companionRef.getIndex() == activeRef.getIndex();
            }
        }

        if (bestProfile == null) return;

        if (bestIsActive) {
            skillTreePage.open(store, ownerRef, playerRef, bestProfile);
        } else if (bestProfile.isStationedAtBase()) {
            skillTreePage.openTransfer(store, ownerRef, playerRef, bestProfile);
        }
    }

    /**
     * Resolves a profile's companion entity Ref. For the active profile we prefer
     * the registry value (always up-to-date). For stationed profiles we look up
     * the stored entity UUID.
     */
    private Ref<EntityStore> resolveCompanionRef(
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionProfile profile,
            @Nonnull ScrubyOwnerBindingComponent binding,
            Ref<EntityStore> activeRef
    ) {
        if (profile.isStationedAtBase()) {
            String uuidStr = profile.getCompanionEntityUuid();
            if (uuidStr == null || uuidStr.isEmpty()) return null;
            try {
                UUID entityUuid = UUID.fromString(uuidStr);
                return store.getExternalData().getRefFromUUID(entityUuid);
            } catch (Exception e) {
                return null;
            }
        }
        // Non-stationed: only the active profile has a live entity.
        // Returning activeRef for every non-stationed profile would cause the
        // iteration in handleUse() to pick the first profile in list order
        // instead of the one the player is actually looking at.
        if (profile.getSlotId() != binding.getActiveSlot()) return null;
        return activeRef;
    }

    private static double distanceSquared(@Nonnull Vector3d a, @Nonnull Vector3d b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isInViewSector(
            float playerYaw,
            @Nonnull Vector3d playerPos,
            @Nonnull Vector3d companionPos
    ) {
        double dirX = -Math.sin(playerYaw);
        double dirZ = -Math.cos(playerYaw);

        double toX = companionPos.getX() - playerPos.getX();
        double toZ = companionPos.getZ() - playerPos.getZ();

        double length = Math.sqrt(toX * toX + toZ * toZ);
        if (length < 0.001) return true;

        toX /= length;
        toZ /= length;

        double dot = dirX * toX + dirZ * toZ;
        return dot > VIEW_DOT_THRESHOLD;
    }
}
