package org.example.plugin;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.RespawnSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for player respawn (DeathComponent removed) and spawns the companion
 * at the player's new position. Only acts on players whose companion was
 * despawned during death (tracked via {@link #pendingPlayerRespawn}).
 */
public final class ScrubyPlayerRespawnSystem extends RespawnSystems.OnRespawnSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionSpawnService spawnService;
    private final ScrubyHudManager hudManager;
    private final ScrubySoundService soundService;

    /** Player UUIDs whose companion was despawned on death and awaits respawn. */
    private final Set<UUID> pendingPlayerRespawn = ConcurrentHashMap.newKeySet();

    public ScrubyPlayerRespawnSystem(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionSpawnService spawnService,
            @Nonnull ScrubyHudManager hudManager,
            @Nonnull ScrubySoundService soundService
    ) {
        this.bindingService = Objects.requireNonNull(bindingService);
        this.registry = Objects.requireNonNull(registry);
        this.spawnService = Objects.requireNonNull(spawnService);
        this.hudManager = Objects.requireNonNull(hudManager);
        this.soundService = Objects.requireNonNull(soundService);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    /** Called by ScrubyKillDetectionSystem when a player dies and their companion is despawned. */
    public void markPendingRespawn(@Nonnull UUID playerUuid) {
        pendingPlayerRespawn.add(playerUuid);
    }

    @Override
    public void onComponentRemoved(
            @Nonnull Ref<EntityStore> respawnedRef,
            @Nonnull DeathComponent deathComponent,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        PlayerRef playerRef = store.getComponent(respawnedRef, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        if (!pendingPlayerRespawn.remove(playerUuid)) {
            return;
        }

        // Skip if companion somehow already active
        Ref<EntityStore> existingRef = registry.getCompanionRef(playerUuid);
        if (existingRef != null && existingRef.isValid()) {
            LOGGER.atInfo().log("[Scruby] Player respawned but companion already active. Owner=" + playerUuid);
            return;
        }

        // Skip if manually despawned
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, respawnedRef);
        if (binding != null) {
            CompanionProfile profile = binding.getActiveProfile();
            if (profile.isManuallyDespawned()) {
                LOGGER.atInfo().log("[Scruby] Player respawned but companion manually despawned. Owner=" + playerUuid);
                return;
            }
        }

        UUID companionUuid = spawnService.spawnCompanion(store, respawnedRef, playerRef, playerUuid);
        if (companionUuid != null) {
            LOGGER.atInfo().log("[Scruby] Companion respawned after player death. Owner=" + playerUuid);
        } else {
            LOGGER.atInfo().log("[Scruby] Companion respawn failed after player death. Owner=" + playerUuid);
        }
    }
}
