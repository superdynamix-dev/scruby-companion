package org.example.plugin;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Kill detection via DeathComponent observation.
 *
 * DamageData.haveKill() is never populated for Summoned Allies — the Role system's
 * combat pipeline does not call onKill()/onInflictedDamage() on the companion's
 * DamageData. Instead, this system observes DeathComponent being added to dying
 * entities (a reliable ECS signal from Hytale's death pipeline) and attributes
 * kills to companions within combat range.
 *
 * Extends DeathSystems.OnDeathSystem (RefChangeSystem on DeathComponent).
 * Fires exactly once per entity death — no UUID-based dedup needed.
 * Only NPC deaths are counted (players filtered by NPCEntity component check).
 */
public final class ScrubyKillDetectionSystem extends DeathSystems.OnDeathSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Kill attribution range squared (20 blocks — matches CombatBehaviorDistance of ranged roles). */
    private static final double KILL_RANGE_SQ = 20.0 * 20.0;

    /** Cleanup radius squared for removing duplicate ground drops near kill position. */
    static final double CLEANUP_RADIUS_SQ = 4.0 * 4.0;

    /** How long to wait before cleaning up ground items (ms). Gives DropDeathItems time to run. */
    static final long CLEANUP_DELAY_MS = 2000L;

    /** Max age before a pending cleanup is discarded (ms). */
    static final long CLEANUP_MAX_AGE_MS = 5000L;

    /** Pending kill positions that need item cleanup. Processed by ScrubySkillTickingSystem. */
    static final ConcurrentLinkedQueue<PendingCleanup> pendingItemCleanups = new ConcurrentLinkedQueue<>();

    /** Position + timestamp of a kill that needs ground-item cleanup. */
    static final class PendingCleanup {
        final Vector3d position;
        final long timestamp;
        PendingCleanup(@Nonnull Vector3d position, long timestamp) {
            this.position = position;
            this.timestamp = timestamp;
        }
    }

    // Blocked entity prefixes/suffixes — no XP awarded
    private static final Set<String> BLOCKED_PREFIXES = Set.of("Tamed_", "Test_");
    private static final Set<String> BLOCKED_NAMES = Set.of("Quest_Master");
    private static final String BLOCKED_SUFFIX_MERCHANT = "_Merchant";

    private static final int PATH_REQUIRED_LEVEL = 5;

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionHitTracker hitTracker;
    private final ScrubyXPService xpService;
    private final ScrubyEvolutionService evolutionService;
    private final ScrubyPendingRespawnQueue pendingRespawnQueue;
    private final ScrubyHudManager hudManager;
    private final ScrubySoundService soundService;
    private final ScrubyConfigService configService;
    private final ScrubyInventoryService inventoryService;
    private final ScrubyPlayerRespawnSystem playerRespawnSystem;
    private final ScrubyPrestigeFightTracker prestigeFightTracker;

    public ScrubyKillDetectionSystem(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionHitTracker hitTracker,
            @Nonnull ScrubyXPService xpService,
            @Nonnull ScrubyEvolutionService evolutionService,
            @Nonnull ScrubyPendingRespawnQueue pendingRespawnQueue,
            @Nonnull ScrubyHudManager hudManager,
            @Nonnull ScrubySoundService soundService,
            @Nonnull ScrubyConfigService configService,
            @Nonnull ScrubyInventoryService inventoryService,
            @Nonnull ScrubyPlayerRespawnSystem playerRespawnSystem,
            @Nonnull ScrubyPrestigeFightTracker prestigeFightTracker
    ) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.hitTracker = Objects.requireNonNull(hitTracker, "hitTracker");
        this.xpService = Objects.requireNonNull(xpService, "xpService");
        this.evolutionService = Objects.requireNonNull(evolutionService, "evolutionService");
        this.pendingRespawnQueue = Objects.requireNonNull(pendingRespawnQueue, "pendingRespawnQueue");
        this.hudManager = Objects.requireNonNull(hudManager, "hudManager");
        this.soundService = Objects.requireNonNull(soundService, "soundService");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService");
        this.playerRespawnSystem = Objects.requireNonNull(playerRespawnSystem, "playerRespawnSystem");
        this.prestigeFightTracker = Objects.requireNonNull(prestigeFightTracker, "prestigeFightTracker");
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> deadRef,
            @Nonnull DeathComponent deathComponent,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        LOGGER.atInfo().log("[Scruby-Kill] onComponentAdded fired for ref=" + deadRef);

        // Prestige boss death: mark pending win immediately so the next prestige tick
        // resolves the fight as a win, before Hytale removes the entity and the tick
        // would otherwise see bossRef.isValid() == false ("disappeared (not killed)").
        UUID prestigeOwner = prestigeFightTracker.findOwnerByBossRef(deadRef);
        if (prestigeOwner != null) {
            prestigeFightTracker.markPendingWin(prestigeOwner);
            LOGGER.atInfo().log("[Scruby-Kill] Prestige boss died — pending win marked. Owner=" + prestigeOwner);
        }

        // Check if the dead entity is a registered companion — handle death instead of kill attribution
        for (Map.Entry<UUID, Ref<EntityStore>> entry : registry.entriesSnapshot()) {
            if (entry.getValue() != null && entry.getValue().equals(deadRef)) {
                LOGGER.atInfo().log("[Scruby-Kill] Dead entity is a registered companion. Owner=" + entry.getKey());
                handleCompanionDeath(store, entry.getKey(), deadRef);
                return;
            }
        }

        // Player death: skip — companion stays alive and teleport check brings it to player on respawn
        PlayerRef deadPlayerRef = store.getComponent(deadRef, PlayerRef.getComponentType());
        if (deadPlayerRef != null) {
            return;
        }

        // Only count NPC deaths — skip players and non-NPC entities
        NPCEntity deadNpc = store.getComponent(deadRef, NPCEntity.getComponentType());
        if (deadNpc == null) {
            return;
        }

        String roleName = extractRoleName(deadNpc);
        if (isBlockedEntity(roleName)) {
            LOGGER.atInfo().log("[Scruby-Kill] Blocked entity, no XP: " + roleName);
            return;
        }

        TransformComponent deadTransform = store.getComponent(deadRef, TransformComponent.getComponentType());
        if (deadTransform == null) {
            return;
        }
        Vector3d deadPos = deadTransform.getPosition();

        // Read MaxHealth from dying entity
        EntityStatMap deadStatMap = store.getComponent(deadRef, EntityStatMap.getComponentType());
        float mobMaxHP = 0f;
        if (deadStatMap != null) {
            EntityStatValue healthStat = deadStatMap.get(DefaultEntityStatTypes.getHealth());
            if (healthStat != null) {
                mobMaxHP = healthStat.getMax();
            }
        }

        if (mobMaxHP <= 0f) {
            LOGGER.atInfo().log("[Scruby-Kill] Dead NPC has no MaxHealth stat, skipping XP.");
            return;
        }

        // Only award XP/loot to owners whose companions actually dealt damage to this mob
        Set<UUID> attackerOwners = hitTracker.consumeKillCredits(deadRef);

        for (Map.Entry<UUID, Ref<EntityStore>> entry : registry.entriesSnapshot()) {
            UUID ownerUuid = entry.getKey();
            Ref<EntityStore> companionRef = entry.getValue();

            if (companionRef == null || !companionRef.isValid()) {
                continue;
            }

            // Cross-world guard: skip if companion belongs to a different world's store
            if (companionRef.getStore() != store) continue;

            // Don't award XP for own companion's death
            if (companionRef.equals(deadRef)) {
                continue;
            }

            // Only owners whose companions dealt damage get XP
            if (!attackerOwners.contains(ownerUuid)) {
                continue;
            }

            attributeKill(store, ownerUuid, companionRef, mobMaxHP, roleName, deadRef);

            // Queue cleanup of Hytale's duplicate ground drops at kill position.
            // Must happen here because deadRef's TransformComponent may be gone later.
            if (configService.isKillLootEnabled()) {
                pendingItemCleanups.add(new PendingCleanup(deadPos, System.currentTimeMillis()));
            }
        }
    }

    private void attributeKill(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID ownerUuid,
            @Nonnull Ref<EntityStore> companionRef,
            float mobMaxHP,
            @Nonnull String roleName,
            @Nonnull Ref<EntityStore> deadRef
    ) {
        EntityStore entityStore = store.getExternalData();
        Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(ownerUuid);
        if (ownerRef == null || !ownerRef.isValid()) {
            return;
        }

        // Owner must be near companion for XP
        TransformComponent ownerTransform = store.getComponent(ownerRef, TransformComponent.getComponentType());
        TransformComponent companionTransform = store.getComponent(companionRef, TransformComponent.getComponentType());
        if (ownerTransform == null || companionTransform == null) {
            return;
        }
        double ownerDistSq = distanceSquared(ownerTransform.getPosition(), companionTransform.getPosition());
        if (ownerDistSq > configService.getOwnerProximityRangeSq()) {
            return;
        }

        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding == null) {
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();

        int oldLevel = profile.getLevel();
        profile.setKillCount(profile.getKillCount() + 1);

        // First Discovery — double XP for first kill of each mob type (player-wide, shared across all companions)
        boolean firstDiscovery = configService.isFirstDiscoveryBonusEnabled()
                && !roleName.isEmpty() && !binding.hasDiscoveredMob(roleName);
        if (firstDiscovery) {
            binding.addDiscoveredMob(roleName);
        }

        long killXp = xpService.calculateKillXP(mobMaxHP, configService.getXpCapPerKill());
        if (firstDiscovery) {
            killXp *= 2;
        }
        LOGGER.atInfo().log("[Scruby-Kill] Kill attributed. Owner=" + ownerUuid
                + " MobHP=" + mobMaxHP + " XP=" + killXp
                + (firstDiscovery ? " [FIRST DISCOVERY]" : ""));
        List<String> messages = xpService.addXP(profile, killXp, locale);
        binding.setActiveProfile(profile);
        ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);

        int newLevel = profile.getLevel();

        PlayerRef playerRef = store.getComponent(ownerRef, PlayerRef.getComponentType());

        // Kill-Loot: collect drops into companion inventory
        collectKillLoot(store, profile, binding, companionRef, playerRef, deadRef, ownerRef, ownerUuid);

        // Sound feedback
        if (playerRef != null) {
            if (oldLevel != newLevel) {
                soundService.playLevelUp(playerRef, newLevel);
            } else {
                soundService.playXpGain(playerRef);
            }
        }

        // First Discovery announcement — sent before level-up messages for maximum impact
        if (firstDiscovery && playerRef != null) {
            String displayName = roleName.replace('_', ' ');
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.discovery.header")));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.discovery.title")));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.discovery.desc",
                    profile.getCompanionName(), displayName)));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.discovery.bonus", killXp)));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.discovery.header")));
            soundService.playLevelUp(playerRef, profile.getLevel());
        }

        if (playerRef != null && !messages.isEmpty()) {
            for (String msg : messages) {
                playerRef.sendMessage(Message.raw(msg));
            }
        }

        // Warn if level 5 with no path and XP is accumulating but level-up blocked
        if (profile.getLevel() == PATH_REQUIRED_LEVEL && "NONE".equals(profile.getPathChoice())) {
            PlayerRef pathWarnRef = store.getComponent(ownerRef, PlayerRef.getComponentType());
            if (pathWarnRef != null) {
                soundService.playBlocked(pathWarnRef);
                pathWarnRef.sendMessage(Message.raw(
                        ScrubyLang.get(locale, "chat.levelup.path_required")));
                pathWarnRef.sendMessage(Message.raw(
                        ScrubyLang.get(locale, "chat.levelup.path_required.hint")));
            }
        }

        // Evolution: despawn old companion, respawn with new Role/Appearance
        if (oldLevel != newLevel && xpService.triggersEvolution(oldLevel, newLevel)) {
            int newStage = evolutionService.calculateEvolutionStage(newLevel);
            profile.setEvolutionStage(newStage);
            binding.setActiveProfile(profile);

            String evoRoleName = evolutionService.getRoleNameForProfile(profile);

            LOGGER.atInfo().log("[Scruby] Evolution triggered. Owner=" + ownerUuid
                    + " Stage=" + newStage + " Role=" + evoRoleName);

            if (playerRef != null) {
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
            }

            // 1. Unregister FIRST — prevents companion-death from being detected as mob kill
            registry.unregister(ownerUuid);

            // 2. Update profile before remove() triggers any callbacks
            profile.setCompanionEntityUuid("");
            profile.setEntityMissing(false);
            profile.setManuallyDespawned(false);
            binding.setActiveProfile(profile);

            // 3. Queue instant respawn for next tick (facingPlayer=true so it spawns in front of owner)
            pendingRespawnQueue.enqueue(ownerUuid, System.currentTimeMillis(), true);

            // 4. Remove old companion LAST — may trigger DeathComponent callback
            NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
            if (npcEntity != null) {
                npcEntity.remove();
            }
        }
    }

    /**
     * Collects kill drops into the companion's inventory.
     * Items that don't fit are dropped on the ground at the companion's position.
     */
    private void collectKillLoot(
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionProfile profile,
            @Nonnull ScrubyOwnerBindingComponent binding,
            @Nonnull Ref<EntityStore> companionRef,
            @Nullable PlayerRef playerRef,
            @Nonnull Ref<EntityStore> deadRef,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull UUID ownerUuid
    ) {
        if (!profile.isKillLootActive(configService.isKillLootEnabled())) {
            return;
        }

        // 1. Get dead NPC's drop list
        NPCEntity deadNpc = store.getComponent(deadRef, NPCEntity.getComponentType());
        if (deadNpc == null) return;

        Role role = deadNpc.getRole();
        if (role == null) return;

        String dropListId = role.getDropListId();
        if (dropListId == null || dropListId.isEmpty()) return;

        // 2. Generate random drops from the NPC's loot table
        ItemModule itemModule = ItemModule.get();
        if (itemModule == null || !itemModule.isEnabled()) return;

        List<ItemStack> drops;
        try {
            drops = itemModule.getRandomItemDrops(dropListId);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-KillLoot] Failed to get drops for " + dropListId + ": " + e.getMessage());
            return;
        }

        if (drops == null || drops.isEmpty()) return;

        // 3. Try to add each drop to companion inventory
        String locale = profile.getLocale();
        List<String> collected = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        for (ItemStack drop : drops) {
            if (drop == null || ItemStack.isEmpty(drop)) continue;
            String itemId = drop.getItemId();
            int qty = drop.getQuantity();
            if (itemId == null || qty <= 0) continue;

            int remaining = inventoryService.smartStack(profile, itemId, qty, ScrubyInventoryService.DEFAULT_MAX_STACK);
            int added = qty - remaining;

            if (added > 0) {
                collected.add("+" + added + "x " + itemId);
            }
            if (remaining > 0) {
                dropped.add("+" + remaining + "x " + itemId);
                // Drop overflow at companion position
                TransformComponent companionTransform = store.getComponent(companionRef, TransformComponent.getComponentType());
                if (companionTransform != null) {
                    try {
                        ItemComponent.generateItemDrop(
                                store,
                                new ItemStack(itemId, remaining),
                                companionTransform.getPosition(),
                                new Vector3f(0, 0, 0),
                                0f, 0.3f, 0f
                        );
                    } catch (Exception e) {
                        LOGGER.atInfo().log("[Scruby-KillLoot] Failed to drop overflow: " + e.getMessage());
                    }
                }
            }
        }

        // 4. Persist changes
        binding.setActiveProfile(profile);
        ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);

        // 5. Chat feedback (respect mute setting)
        if (playerRef != null && !ScrubyMuteInventoryCommand.isMuted(binding)) {
            if (!collected.isEmpty() && dropped.isEmpty()) {
                playerRef.sendMessage(Message.raw(
                        ScrubyLang.get(locale, "chat.killloot.collected", String.join(", ", collected))));
            } else if (!collected.isEmpty()) {
                playerRef.sendMessage(Message.raw(
                        ScrubyLang.get(locale, "chat.killloot.partial",
                                String.join(", ", collected), String.join(", ", dropped))));
            } else if (!dropped.isEmpty()) {
                playerRef.sendMessage(Message.raw(
                        ScrubyLang.get(locale, "chat.killloot.full", String.join(", ", dropped))));
            }
        }

        LOGGER.atInfo().log("[Scruby-KillLoot] Collected: " + collected + " Dropped: " + dropped
                + " DropListId: " + dropListId);
    }

    private void handleCompanionDeath(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID ownerUuid,
            @Nonnull Ref<EntityStore> companionRef
    ) {
        LOGGER.atInfo().log("[Scruby] Companion died. Owner=" + ownerUuid);

        // 1. Resolve owner
        EntityStore entityStore = store.getExternalData();
        Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(ownerUuid);
        if (ownerRef == null || !ownerRef.isValid()) {
            LOGGER.atInfo().log("[Scruby] Companion death — owner not found, cleaning up. Owner=" + ownerUuid);
            registry.unregister(ownerUuid);
            return;
        }

        PlayerRef playerRef = store.getComponent(ownerRef, PlayerRef.getComponentType());

        // 2. Unregister FIRST — prevents recursive detection
        registry.unregister(ownerUuid);

        // 3. Update profile fields individually (NOT clearCompanionEntityReference which sets entityMissing=true)
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding != null) {
            CompanionProfile profile = binding.getActiveProfile();
            profile.setCompanionEntityUuid("");
            profile.setEntityMissing(false);
            profile.setManuallyDespawned(false);
            profile.setStationedAtBase(false);
            profile.setDeathCount(profile.getDeathCount() + 1);
            binding.setActiveProfile(profile);

            // Drop inventory items if keepInventoryOnDeath is false
            if (!profile.isKeepInventoryOnDeath()) {
                List<ScrubyInventoryService.InventoryEntry> items = inventoryService.getItems(profile);
                if (!items.isEmpty()) {
                    TransformComponent companionTransform = store.getComponent(companionRef, TransformComponent.getComponentType());
                    if (companionTransform != null) {
                        Vector3d dropPos = companionTransform.getPosition();
                        for (ScrubyInventoryService.InventoryEntry entry : items) {
                            try {
                                ItemComponent.generateItemDrop(
                                    store,
                                    new ItemStack(entry.getItemId(), entry.getQuantity()),
                                    dropPos,
                                    new Vector3f(0, 0, 0),
                                    0f, 0.3f, 0f
                                );
                            } catch (Exception e) {
                                LOGGER.atInfo().log("[Scruby] Failed to drop inventory item: " + entry.getItemId() + " - " + e.getMessage());
                            }
                        }
                    }
                    inventoryService.clearInventory(profile);
                    binding.setActiveProfile(profile);
                }
            }
        }

        // 4. Remove HUD
        if (playerRef != null) {
            hudManager.removeHud(store, ownerRef, playerRef, ownerUuid);
        }

        // 5. Death sound + chat message
        if (playerRef != null) {
            TransformComponent deadTransform = store.getComponent(companionRef, TransformComponent.getComponentType());
            if (deadTransform != null) {
                Vector3d pos = deadTransform.getPosition();
                soundService.playDeath(playerRef, pos.getX(), pos.getY(), pos.getZ());
            }
            long respawnSeconds = configService.getRespawnDelayMs() / 1000L;
            String deathLocale = (binding != null) ? binding.getActiveProfile().getLocale() : ScrubyLang.DEFAULT_LOCALE;
            playerRef.sendMessage(Message.raw(
                    ScrubyLang.get(deathLocale, "chat.death", respawnSeconds)));
        }

        // 6. Queue timed respawn
        pendingRespawnQueue.enqueue(ownerUuid, System.currentTimeMillis() + configService.getRespawnDelayMs());

        // 7. Remove entity LAST — may trigger callbacks
        NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
        if (npcEntity != null) {
            npcEntity.remove();
        }
    }

    /**
     * Extracts the role name string from an NPCEntity for blocklist matching.
     * Uses getRole().toString() — verify output format in-game via logs.
     */
    @Nonnull
    private static String extractRoleName(@Nonnull NPCEntity npcEntity) {
        try {
            String roleName = npcEntity.getRoleName();
            return roleName != null ? roleName : "";
        } catch (Exception e) {
            LOGGER.atWarning().log("[Scruby-Kill] Failed to extract role name: " + e.getMessage());
            return "";
        }
    }

    private static boolean isBlockedEntity(@Nonnull String roleName) {
        if (roleName.isEmpty()) return false;
        for (String prefix : BLOCKED_PREFIXES) {
            if (roleName.startsWith(prefix)) return true;
        }
        if (roleName.endsWith(BLOCKED_SUFFIX_MERCHANT)) return true;
        if (BLOCKED_NAMES.contains(roleName)) return true;
        return false;
    }

    private static double distanceSquared(@Nonnull Vector3d a, @Nonnull Vector3d b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
