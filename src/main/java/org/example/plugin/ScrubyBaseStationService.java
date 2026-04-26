package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerConfigData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages stationing a companion at the player's base.
 * Stationed companions are always in Guard mode (patrol + kill monsters).
 */
public final class ScrubyBaseStationService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final String MODE_NONE = "NONE";
    public static final String MODE_GUARD = "GUARD";

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyEvolutionService evolutionService;
    private final ScrubyConfigService configService;

    public ScrubyBaseStationService(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyEvolutionService evolutionService,
            @Nonnull ScrubyConfigService configService
    ) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.evolutionService = Objects.requireNonNull(evolutionService, "evolutionService");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    public double getGuardRadius() { return configService.getGuardRadiusBlocks(); }

    /**
     * Attempts to read the player's bed/respawn position from the Hytale API.
     * Returns the respawn position as Vector3d, or null if no respawn point is set.
     */
    @Nullable
    public Vector3d getBedPositionOrNull(@Nonnull Player player, @Nonnull String worldName) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(worldName, "worldName");

        try {
            PlayerConfigData configData = player.getPlayerConfigData();
            if (configData == null) {
                LOGGER.atInfo().log("[Scruby-Station] PlayerConfigData is null.");
                return null;
            }

            PlayerWorldData worldData = configData.getPerWorldData(worldName);
            if (worldData == null) {
                LOGGER.atInfo().log("[Scruby-Station] No world data for world: " + worldName);
                return null;
            }

            PlayerRespawnPointData[] respawnPoints = worldData.getRespawnPoints();
            if (respawnPoints == null || respawnPoints.length == 0) {
                LOGGER.atInfo().log("[Scruby-Station] No respawn points set for player.");
                return null;
            }

            // Use the first respawn point (primary bed)
            return respawnPoints[0].getRespawnPosition();
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Station] Error reading bed position: " + e.getMessage());
            return null;
        }
    }

    /**
     * Stations the companion at the player's bed position.
     * Falls back to the player's current position if no bed is found.
     */
    public boolean stationAtBed(
            @Nonnull Player player,
            @Nonnull PlayerRef playerRef,
            @Nonnull CompanionProfile profile,
            @Nonnull String worldName
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(playerRef, "playerRef");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(worldName, "worldName");

        Vector3d bedPos = getBedPositionOrNull(player, worldName);
        if (bedPos != null) {
            return stationAtPosition(profile, bedPos.x, bedPos.y, bedPos.z, worldName);
        }

        // Fallback: use player's current position
        LOGGER.atInfo().log("[Scruby-Station] No bed found, using player position as fallback.");
        Vector3d playerPos = playerRef.getTransform().getPosition();
        return stationAtPosition(profile, playerPos.x, playerPos.y, playerPos.z, worldName);
    }

    /**
     * Stations the companion at an explicit position.
     */
    public boolean stationAtPosition(
            @Nonnull CompanionProfile profile,
            double x, double y, double z,
            @Nonnull String worldId
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(worldId, "worldId");

        profile.setStationedAtBase(true);
        profile.setStationX(x);
        profile.setStationY(y);
        profile.setStationZ(z);
        profile.setStationWorldId(worldId);
        profile.setStationMode(MODE_GUARD);

        LOGGER.atInfo().log("[Scruby-Station] Stationed at " + x + ", " + y + ", " + z
                + " world=" + worldId + " mode=GUARD");
        return true;
    }

    /**
     * Recalls the companion from the station back to the player.
     */
    public void recallFromStation(@Nonnull CompanionProfile profile) {
        Objects.requireNonNull(profile, "profile");

        profile.setStationedAtBase(false);
        profile.setStationMode(MODE_NONE);
        profile.setStationX(0.0);
        profile.setStationY(0.0);
        profile.setStationZ(0.0);
        profile.setStationWorldId("");

        LOGGER.atInfo().log("[Scruby-Station] Companion recalled from station.");
    }

    /**
     * Checks if the companion is currently stationed.
     */
    public boolean isStationActive(@Nonnull CompanionProfile profile) {
        return profile.isStationedAtBase();
    }

    /**
     * Returns the effective radius for the current station mode.
     */
    public double getStationRadius(@Nonnull CompanionProfile profile) {
        return getGuardRadius();
    }

    /**
     * Despawns the current companion and respawns it using the Stationed template
     * at the station position. This swaps the NPC role so FlockLeader follow is disabled.
     */
    public boolean respawnAsStationed(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile
    ) {
        // Despawn current companion
        Ref<EntityStore> oldRef = registry.getCompanionRef(ownerUuid);
        if (oldRef != null && oldRef.isValid()) {
            com.hypixel.hytale.server.npc.entities.NPCEntity npc = store.getComponent(oldRef, com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType());
            if (npc != null) npc.remove();
            registry.unregister(ownerUuid);
        }

        String stationedRole = evolutionService.getStationedRoleNameForProfile(profile);
        Vector3d spawnPos = new Vector3d(profile.getStationX(), profile.getStationY(), profile.getStationZ());

        Pair<Ref<EntityStore>, ?> result = NPCPlugin.get().spawnNPC(
                store, stationedRole, null, spawnPos, new Vector3f(0f, 0f, 0f));

        if (result == null || result.left() == null) {
            LOGGER.atInfo().log("[Scruby-Station] Failed to spawn stationed companion: " + stationedRole);
            return false;
        }

        Ref<EntityStore> newRef = result.left();
        registry.register(ownerUuid, newRef);

        // Update entity UUID in profile so it survives server restarts
        UUIDComponent uuidComp = store.getComponent(newRef, UUIDComponent.getComponentType());
        if (uuidComp != null) {
            profile.setCompanionEntityUuid(uuidComp.getUuid().toString());
            profile.setEntityMissing(false);
        }

        // Restore visual armor from persistent profile
        ScrubyArmorService.applyVisualArmor(store, newRef, profile);

        LOGGER.atInfo().log("[Scruby-Station] Respawned as stationed: " + stationedRole);
        return true;
    }

    /**
     * Despawns the stationed companion and respawns it using the normal template
     * near the player. This restores FlockLeader follow behavior.
     */
    public boolean respawnAsFollowing(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID ownerUuid,
            @Nonnull PlayerRef playerRef,
            @Nonnull CompanionProfile profile
    ) {
        // Despawn stationed companion
        Ref<EntityStore> oldRef = registry.getCompanionRef(ownerUuid);
        if (oldRef != null && oldRef.isValid()) {
            NPCEntity npc = store.getComponent(oldRef, NPCEntity.getComponentType());
            if (npc != null) npc.remove();
            registry.unregister(ownerUuid);
        }

        String normalRole = evolutionService.getRoleNameForProfile(profile);
        Vector3d ownerPos = playerRef.getTransform().getPosition();
        Vector3f ownerRot = playerRef.getHeadRotation().clone();
        Vector3d spawnPos = ScrubyFollowSlots.computeDefaultFollowSlot(ownerPos, ownerRot);

        Pair<Ref<EntityStore>, ?> result = NPCPlugin.get().spawnNPC(
                store, normalRole, null, spawnPos, ownerRot);

        if (result == null || result.left() == null) {
            LOGGER.atInfo().log("[Scruby-Station] Failed to respawn following companion: " + normalRole);
            return false;
        }

        Ref<EntityStore> newRef = result.left();
        registry.register(ownerUuid, newRef);

        // Update entity UUID in profile so it survives server restarts
        UUIDComponent uuidComp = store.getComponent(newRef, UUIDComponent.getComponentType());
        if (uuidComp != null) {
            profile.setCompanionEntityUuid(uuidComp.getUuid().toString());
            profile.setEntityMissing(false);
        }

        // Restore visual armor from persistent profile
        ScrubyArmorService.applyVisualArmor(store, newRef, profile);

        LOGGER.atInfo().log("[Scruby-Station] Respawned as following: " + normalRole);
        return true;
    }
}
