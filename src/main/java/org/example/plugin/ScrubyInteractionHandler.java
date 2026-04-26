package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
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

        SyncInteractionChain useChain = null;
        for (SyncInteractionChain chain : syncPacket.updates) {
            if (chain.interactionType == InteractionType.Use) {
                useChain = chain;
                break;
            }
        }
        if (useChain == null) return false;

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

        final SyncInteractionChain capturedChain = useChain;
        world.execute(() -> {
            try {
                handleUse(store, ownerRef, playerRef, capturedChain);
            } catch (Exception e) {
                LOGGER.atInfo().log("[Scruby-Interact] Error handling F press: " + e.getMessage());
            }
        });

        return false;
    }

    private void handleUse(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull SyncInteractionChain useChain
    ) {
        com.hypixel.hytale.protocol.InteractionChainData chainData = useChain.data;
        if (chainData == null) return;

        // The Use chain's entityId is the network id of whatever the client's raycast
        // selected when the player pressed F. <= 0 means no entity hit (block/air).
        int targetNetworkId = chainData.entityId;
        if (targetNetworkId <= 0) return;

        EntityStore entityStore = store.getExternalData();
        Ref<EntityStore> targetRef;
        try {
            targetRef = entityStore.getRefFromNetworkId(targetNetworkId);
        } catch (Throwable t) {
            return;
        }
        if (targetRef == null || !targetRef.isValid()) return;
        if (targetRef.getStore() != store) return;

        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding == null || !binding.hasBinding()) return;

        UUID ownerUuid = playerRef.getUuid();
        Ref<EntityStore> activeRef = registry.getCompanionRef(ownerUuid);

        // Find which (if any) of this player's companions is the hit entity.
        int targetIdx = targetRef.getIndex();
        CompanionProfile matched = null;
        boolean matchedIsActive = false;

        for (CompanionProfile profile : binding.getProfiles()) {
            Ref<EntityStore> companionRef = resolveCompanionRef(store, profile, binding, activeRef);
            if (companionRef == null || !companionRef.isValid()) continue;
            if (companionRef.getStore() != store) continue;
            if (companionRef.getIndex() != targetIdx) continue;

            matched = profile;
            matchedIsActive = !profile.isStationedAtBase()
                    && activeRef != null
                    && companionRef.getIndex() == activeRef.getIndex();
            break;
        }

        if (matched == null) {
            LOGGER.atInfo().log("[Scruby-Interact] F press hit non-companion entity (networkId="
                    + targetNetworkId + ", refIdx=" + targetIdx + "), ignoring.");
            return;
        }

        if (matchedIsActive) {
            skillTreePage.open(store, ownerRef, playerRef, matched);
        } else if (matched.isStationedAtBase()) {
            skillTreePage.openTransfer(store, ownerRef, playerRef, matched);
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
}
