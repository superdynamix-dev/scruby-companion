package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ticking system that monitors active prestige boss fights.
 *
 * Runs every 20 ticks (1 second at 20 TPS).
 *
 * For each active fight:
 * - Boss ref invalid or has DeathComponent -> PLAYER WINS
 * - Player has DeathComponent -> PLAYER LOSES
 * - Timeout exceeded -> treated as LOSS
 *
 * On outcome: update profile, clean up boss, queue companion respawn, send messages.
 */
public final class ScrubyPrestigeTickingSystem extends TickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Check interval: every 20 ticks = 1 second at 20 TPS. */
    private static final int CHECK_INTERVAL = 20;

    /** Boss aura: damage dealt to player every CHECK_INTERVAL when within range. */
    private static final float BOSS_AURA_DAMAGE = 2.0f;

    /** Boss aura range in blocks (squared for distance check). */
    private static final double BOSS_AURA_RANGE_SQ = 25.0 * 25.0;

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyPrestigeFightTracker prestigeFightTracker;
    private final ScrubyPendingRespawnQueue pendingRespawnQueue;
    private final ScrubySoundService soundService;

    private int tickCounter = 0;

    public ScrubyPrestigeTickingSystem(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyPrestigeFightTracker prestigeFightTracker,
            @Nonnull ScrubyPendingRespawnQueue pendingRespawnQueue,
            @Nonnull ScrubySoundService soundService
    ) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.prestigeFightTracker = Objects.requireNonNull(prestigeFightTracker, "prestigeFightTracker");
        this.pendingRespawnQueue = Objects.requireNonNull(pendingRespawnQueue, "pendingRespawnQueue");
        this.soundService = Objects.requireNonNull(soundService, "soundService");
    }

    @Override
    public void tick(float deltaTime, int tickCount, @Nonnull Store<EntityStore> store) {
        tickCounter++;

        if (tickCounter % CHECK_INTERVAL != 0) {
            return;
        }

        Map<UUID, ScrubyPrestigeFightTracker.FightState> activeFights =
                prestigeFightTracker.getActiveFights();

        if (activeFights.isEmpty()) {
            return;
        }

        EntityStore entityStore = store.getExternalData();

        // Snapshot keys to avoid ConcurrentModificationException during iteration + removal
        List<UUID> ownerUuids = new ArrayList<>(activeFights.keySet());

        for (UUID ownerUuid : ownerUuids) {
            ScrubyPrestigeFightTracker.FightState fight = prestigeFightTracker.getFight(ownerUuid);
            if (fight == null) {
                continue;
            }

            Ref<EntityStore> bossRef = fight.getBossRef();

            // Cross-world guard: skip if boss belongs to a different world's store
            if (bossRef != null && bossRef.getStore() != store) continue;

            // Resolve owner
            Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(ownerUuid);
            if (ownerRef == null || !ownerRef.isValid()) {
                // Owner disconnected — clean up fight, treat as loss
                LOGGER.atInfo().log("[Scruby] Prestige fight — owner disconnected. Owner=" + ownerUuid);
                cleanupBoss(store, bossRef);
                handleLoss(store, ownerRef, ownerUuid, null);
                continue;
            }

            PlayerRef playerRef = store.getComponent(ownerRef, PlayerRef.getComponentType());

            // Race-safe win path: kill-detection listener saw the boss's DeathComponent
            // and marked a pending win. Resolve as win regardless of boss entity state,
            // because Hytale removes dead entities before the next tick and bossRef.isValid()
            // would then trigger the "disappeared (not killed)" branch below.
            if (prestigeFightTracker.consumePendingWin(ownerUuid)) {
                LOGGER.atInfo().log("[Scruby] Prestige fight — boss defeated (pending win). Owner=" + ownerUuid);
                cleanupBoss(store, bossRef);
                handleWin(store, ownerRef, ownerUuid, playerRef);
                continue;
            }

            // Check boss state: distinguish between killed (win) and disappeared (abort)
            if (bossRef == null || !bossRef.isValid()) {
                // Boss entity gone (chunk unload, despawn, etc.) — NOT a win, abort the fight
                LOGGER.atInfo().log("[Scruby] Prestige fight — boss disappeared (not killed). Owner=" + ownerUuid);
                prestigeFightTracker.endFight(ownerUuid);
                if (playerRef != null) {
                    soundService.stopPrestigeBattleMusic(playerRef);
                    String cancelLocale = getLocaleForOwner(store, ownerRef);
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(cancelLocale, "chat.prestige.cancelled")));
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(cancelLocale, "chat.prestige.cancelled.hint")));
                    soundService.playError(playerRef);
                }
                continue;
            }

            // Boss exists — check if actually killed (DeathComponent)
            DeathComponent bossDeathComponent = store.getComponent(bossRef, DeathComponent.getComponentType());
            if (bossDeathComponent != null) {
                LOGGER.atInfo().log("[Scruby] Prestige fight — boss defeated! Owner=" + ownerUuid);
                cleanupBoss(store, bossRef);
                handleWin(store, ownerRef, ownerUuid, playerRef);
                continue;
            }

            // Check if player is dead
            DeathComponent playerDeathComponent = store.getComponent(ownerRef, DeathComponent.getComponentType());
            if (playerDeathComponent != null) {
                LOGGER.atInfo().log("[Scruby] Prestige fight — player died. Owner=" + ownerUuid);
                cleanupBoss(store, bossRef);
                handleLoss(store, ownerRef, ownerUuid, playerRef);
                continue;
            }

            // Check timeout
            if (prestigeFightTracker.isFightTimedOut(ownerUuid)) {
                LOGGER.atInfo().log("[Scruby] Prestige fight — timed out. Owner=" + ownerUuid);
                cleanupBoss(store, bossRef);
                handleLoss(store, ownerRef, ownerUuid, playerRef);
                continue;
            }

            // Boss aura: deal bonus damage to player when boss is within range
            if (playerRef != null) {
                applyBossAuraDamage(store, ownerRef, bossRef, playerRef);
            }
        }
    }

    private void handleWin(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull UUID ownerUuid,
            @javax.annotation.Nullable PlayerRef playerRef
    ) {
        // Update profile — fully reset companion entity state for respawn
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding != null) {
            CompanionProfile profile = binding.getActiveProfile();
            profile.setPrestigeAttempted(true);
            profile.setPrestigeWon(true);
            profile.setCompanionEntityUuid("");
            profile.setEntityMissing(false);
            profile.setManuallyDespawned(false);
            binding.setActiveProfile(profile);
        }

        // End fight
        prestigeFightTracker.endFight(ownerUuid);

        // Queue companion respawn with 3s delay
        pendingRespawnQueue.enqueue(ownerUuid, System.currentTimeMillis() + 3000L);

        // Stop battle music + play victory sounds
        if (playerRef != null) {
            soundService.stopPrestigeBattleMusic(playerRef);
            String locale = getLocaleForOwner(store, ownerRef);
            soundService.playPrestigeWin(playerRef);
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.prestige.won.header")));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.prestige.won.title")));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.prestige.won.desc")));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.prestige.won.skin")));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.prestige.won.header")));
        }

        LOGGER.atInfo().log("[Scruby] Prestige WON. Owner=" + ownerUuid);
    }

    private void handleLoss(
            @Nonnull Store<EntityStore> store,
            @javax.annotation.Nullable Ref<EntityStore> ownerRef,
            @Nonnull UUID ownerUuid,
            @javax.annotation.Nullable PlayerRef playerRef
    ) {
        // Update profile — fully reset companion entity state for respawn
        if (ownerRef != null && ownerRef.isValid()) {
            ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
            if (binding != null) {
                CompanionProfile profile = binding.getActiveProfile();
                profile.setPrestigeAttempted(true);
                profile.setPrestigeWon(false);
                profile.setCompanionEntityUuid("");
                profile.setEntityMissing(false);
                profile.setManuallyDespawned(false);
                binding.setActiveProfile(profile);
            }
        }

        // End fight
        prestigeFightTracker.endFight(ownerUuid);

        // Queue companion respawn with 5s delay so player has time to respawn first
        pendingRespawnQueue.enqueue(ownerUuid, System.currentTimeMillis() + 5000L);

        // Stop battle music + play defeat sounds
        if (playerRef != null) {
            soundService.stopPrestigeBattleMusic(playerRef);
            String locale = getLocaleForOwner(store, ownerRef);
            soundService.playPrestigeLoss(playerRef);
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.prestige.lost.header")));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.prestige.lost.title")));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.prestige.lost.desc")));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.prestige.lost.nochance")));
            playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.prestige.lost.header")));
        }

        LOGGER.atInfo().log("[Scruby] Prestige LOST. Owner=" + ownerUuid);
    }

    /**
     * Resolves the locale from the owner's active profile, falling back to default.
     */
    @Nonnull
    private String getLocaleForOwner(@Nonnull Store<EntityStore> store, @javax.annotation.Nullable Ref<EntityStore> ownerRef) {
        if (ownerRef != null && ownerRef.isValid()) {
            ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
            if (binding != null) {
                return binding.getActiveProfile().getLocale();
            }
        }
        return ScrubyLang.DEFAULT_LOCALE;
    }

    /**
     * Boss aura: deals bonus damage to the player every tick cycle when the boss is within range.
     * Simulates doubled boss damage output since Hytale has no runtime damage stat modifier.
     */
    private void applyBossAuraDamage(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull Ref<EntityStore> bossRef,
            @Nonnull PlayerRef playerRef
    ) {
        TransformComponent bossTransform = store.getComponent(bossRef, TransformComponent.getComponentType());
        if (bossTransform == null) return;

        Vector3d bossPos = bossTransform.getPosition();
        Vector3d playerPos = playerRef.getTransform().getPosition();
        if (bossPos == null || playerPos == null) return;

        double dx = bossPos.getX() - playerPos.getX();
        double dy = bossPos.getY() - playerPos.getY();
        double dz = bossPos.getZ() - playerPos.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq > BOSS_AURA_RANGE_SQ) return;

        EntityStatMap playerStats = store.getComponent(ownerRef, EntityStatMap.getComponentType());
        if (playerStats == null) return;

        int healthIndex = DefaultEntityStatTypes.getHealth();
        playerStats.addStatValue(healthIndex, -BOSS_AURA_DAMAGE);
    }

    /**
     * Removes the boss NPC entity if it still exists.
     */
    private void cleanupBoss(
            @Nonnull Store<EntityStore> store,
            @javax.annotation.Nullable Ref<EntityStore> bossRef
    ) {
        if (bossRef == null || !bossRef.isValid()) {
            return;
        }

        NPCEntity npcEntity = store.getComponent(bossRef, NPCEntity.getComponentType());
        if (npcEntity != null) {
            npcEntity.remove();
        }
    }
}
