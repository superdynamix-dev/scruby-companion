package org.example.plugin;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

public final class ScrubyCompanionSpawnService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String FALLBACK_NPC_TYPE = "Scruby_Archer_Test";

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyEvolutionService evolutionService;
    private final ScrubyAttributeService attributeService;
    private final ScrubySkillService skillService;
    private final ScrubyHudManager hudManager;
    private final ScrubySoundService soundService;

    public ScrubyCompanionSpawnService(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyEvolutionService evolutionService,
            @Nonnull ScrubyAttributeService attributeService,
            @Nonnull ScrubySkillService skillService,
            @Nonnull ScrubyHudManager hudManager,
            @Nonnull ScrubySoundService soundService
    ) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.evolutionService = Objects.requireNonNull(evolutionService, "evolutionService");
        this.attributeService = Objects.requireNonNull(attributeService, "attributeService");
        this.skillService = Objects.requireNonNull(skillService, "skillService");
        this.hudManager = Objects.requireNonNull(hudManager, "hudManager");
        this.soundService = Objects.requireNonNull(soundService, "soundService");
    }

    @Nullable
    public UUID spawnCompanion(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid
    ) {
        return spawnCompanion(store, ownerRef, playerRef, ownerUuid, false);
    }

    @Nullable
    public UUID spawnCompanion(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            boolean facingPlayer
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(ownerRef, "ownerRef");
        Objects.requireNonNull(playerRef, "playerRef");
        Objects.requireNonNull(ownerUuid, "ownerUuid");

        LOGGER.atInfo().log("[Scruby] spawnCompanion called. Owner=" + ownerUuid
                + " caller=" + Thread.currentThread().getStackTrace()[2]);

        // Guard: prevent double-spawn if companion already active
        Ref<EntityStore> existingRef = this.registry.getCompanionRef(ownerUuid);
        if (existingRef != null && existingRef.isValid()) {
            LOGGER.atInfo().log("[Scruby] Spawn blocked — companion already active for Owner=" + ownerUuid);
            return null;
        }

        // Check if companion should spawn at station instead of near player
        ScrubyOwnerBindingComponent preBinding = this.bindingService.getBindingOrNull(store, ownerRef);
        CompanionProfile preProfile = (preBinding != null) ? preBinding.getActiveProfile() : null;
        boolean spawnAtStation = preProfile != null && preProfile.isStationedAtBase()
                && !ScrubyBaseStationService.MODE_NONE.equals(preProfile.getStationMode());

        Vector3d spawnPosition;
        Vector3f spawnRotation;

        if (spawnAtStation) {
            spawnPosition = new Vector3d(preProfile.getStationX(), preProfile.getStationY(), preProfile.getStationZ());
            spawnRotation = new Vector3f(0f, 0f, 0f);
            LOGGER.atInfo().log("[Scruby] Spawning at station: " + preProfile.getStationX()
                    + ", " + preProfile.getStationY() + ", " + preProfile.getStationZ());
        } else if (facingPlayer) {
            Vector3d ownerPosition = playerRef.getTransform().getPosition().clone();
            Vector3f ownerRotation = playerRef.getHeadRotation().clone();
            Object[] facingSlot = ScrubyFollowSlots.computeSpawnFacingSlot(ownerPosition, ownerRotation);
            spawnPosition = (Vector3d) facingSlot[0];
            spawnRotation = (Vector3f) facingSlot[1];
        } else {
            Vector3d ownerPosition = playerRef.getTransform().getPosition().clone();
            Vector3f ownerRotation = playerRef.getHeadRotation().clone();
            spawnPosition = ScrubyFollowSlots.computeDefaultFollowSlot(ownerPosition, ownerRotation);
            spawnRotation = ownerRotation.clone();
        }

        String npcType = resolveNpcType(store, ownerRef);
        LOGGER.atInfo().log("[Scruby] Spawning NPC role=" + npcType + " atStation=" + spawnAtStation + " pos=" + spawnPosition);

        Pair<Ref<EntityStore>, com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter> spawnResult =
                NPCPlugin.get().spawnNPC(
                        store,
                        npcType,
                        null,
                        spawnPosition,
                        spawnRotation
                );

        if (spawnResult == null) {
            LOGGER.atInfo().log("[Scruby] Spawn failed. NPCPlugin returned null for npcType=" + npcType);
            return null;
        }

        Ref<EntityStore> companionRef = spawnResult.left();

        if (companionRef == null) {
            LOGGER.atInfo().log("[Scruby] Spawn failed. Spawn result did not contain an entity ref.");
            return null;
        }

        ComponentType<EntityStore, NPCEntity> npcEntityComponentType = NPCEntity.getComponentType();

        if (npcEntityComponentType == null) {
            LOGGER.atInfo().log("[Scruby] Spawn failed. NPCEntity component type is unavailable.");
            return null;
        }

        NPCEntity npcEntity = store.getComponent(companionRef, npcEntityComponentType);

        if (npcEntity == null) {
            LOGGER.atInfo().log("[Scruby] Spawn failed. Spawned entity is missing NPCEntity component.");
            return null;
        }

        UUID companionUuid = extractEntityUuid(store, companionRef);

        if (companionUuid == null) {
            LOGGER.atInfo().log("[Scruby] Spawn failed. Spawned entity UUIDComponent is missing.");
            return null;
        }

        // DEBUG: Log role name and attitude info
        try {
            String roleName = npcEntity.getRoleName();
            com.hypixel.hytale.server.npc.role.Role role = npcEntity.getRole();
            LOGGER.atInfo().log("[Scruby-DEBUG] Spawned: role=" + roleName + " hasRole=" + (role != null));
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-DEBUG] Could not read attitude info: " + e.getMessage());
        }

        this.resetService.clearLockedTarget(store, companionRef);

        this.bindingService.attachCompanionEntity(
                store,
                ownerRef,
                ownerUuid,
                companionUuid
        );

        this.registry.register(ownerUuid, companionRef);

        // Apply attribute and skill modifiers to the freshly spawned companion
        ScrubyOwnerBindingComponent binding = this.bindingService.getBindingOrNull(store, ownerRef);
        if (binding != null) {
            CompanionProfile profile = binding.getActiveProfile();
            this.attributeService.applyAttributes(store, companionRef, profile, ownerUuid);
            this.skillService.applyPassiveSkills(store, companionRef, profile);
            // Restore visual armor from persistent profile
            ScrubyArmorService.applyVisualArmor(store, companionRef, profile);

            // Heal to full after all MaxHealth modifiers are applied. Without this,
            // current HP stays at the role's base value (e.g. 80) while MaxHP jumped
            // to the boosted value (e.g. 150), causing the respawn to start at 80/150
            // and slowly regen up.
            EntityStatMap statMap = store.getComponent(companionRef, EntityStatMap.getComponentType());
            if (statMap != null) {
                statMap.maximizeStatValue(DefaultEntityStatTypes.getHealth());
            }

            // HUD creation is handled lazily by ScrubySkillTickingSystem
            // to avoid client crash when UI is sent before client is ready
        }

        // Play spawn sound at companion position
        this.soundService.playSpawn(playerRef, spawnPosition.getX(), spawnPosition.getY(), spawnPosition.getZ());

        LOGGER.atInfo().log("[Scruby] Companion spawned. Owner=" + ownerUuid + ", Companion=" + companionUuid);

        return companionUuid;
    }

    @Nonnull
    private String resolveNpcType(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef
    ) {
        ScrubyOwnerBindingComponent binding = this.bindingService.getBindingOrNull(store, ownerRef);
        if (binding == null || !binding.hasBinding()) {
            return FALLBACK_NPC_TYPE;
        }
        CompanionProfile profile = binding.getActiveProfile();
        boolean stationed = profile.isStationedAtBase()
                && !ScrubyBaseStationService.MODE_NONE.equals(profile.getStationMode());
        return stationed
                ? this.evolutionService.getStationedRoleNameForProfile(profile)
                : this.evolutionService.getRoleNameForProfile(profile);
    }

    @Nullable
    private static UUID extractEntityUuid(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef
    ) {
        UUIDComponent uuidComponent = store.getComponent(companionRef, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return null;
        }
        return uuidComponent.getUuid();
    }

}
