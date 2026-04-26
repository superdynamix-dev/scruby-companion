package org.example.plugin;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.UUID;

/**
 * /scruby-prestige — initiates the prestige boss fight.
 *
 * Requirements:
 * - Active binding with a companion at MAX_LEVEL (20)
 * - Prestige not yet attempted for the active companion
 * - Not currently in a prestige fight
 *
 * Flow:
 * 1. Despawn the active companion (unregister from registry, remove NPC entity)
 * 2. Spawn a hostile boss NPC based on the companion's path
 * 3. Set the player as the boss's marked target
 * 4. Register the fight in the tracker
 */
public final class ScrubyPrestigeCommand extends AbstractPlayerCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Boss uses the prestige skin — player fights the evolved form they can earn

    /** Boss spawns this many blocks offset from the arena center. */
    private static final double BOSS_ARENA_OFFSET = 10.0;

    /** Fixed MaxHealth applied to every prestige boss for the duration of the fight. */
    private static final float PRESTIGE_BOSS_FIGHT_HP = 500.0f;

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyHudManager hudManager;
    private final ScrubyPrestigeFightTracker prestigeFightTracker;
    private final ScrubyEvolutionService evolutionService;
    private final ScrubyConfigService configService;

    public ScrubyPrestigeCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyHudManager hudManager,
            @Nonnull ScrubyPrestigeFightTracker prestigeFightTracker,
            @Nonnull ScrubyEvolutionService evolutionService,
            @Nonnull ScrubyConfigService configService
    ) {
        super("scruby-prestige", "Challenge your Level 20 companion to a prestige boss fight.");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.hudManager = Objects.requireNonNull(hudManager, "hudManager");
        this.prestigeFightTracker = Objects.requireNonNull(prestigeFightTracker, "prestigeFightTracker");
        this.evolutionService = Objects.requireNonNull(evolutionService, "evolutionService");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    @Override
    protected void execute(
            @Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        // 0. Check if prestige is enabled
        if (!configService.isPrestigeEnabled()) {
            commandContext.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.prestige.disabled")));
            return;
        }

        // 1. Validate binding exists
        ScrubyOwnerBindingComponent binding = this.bindingService.getBindingOrNull(store, ref);

        if (binding == null || !binding.hasBinding()) {
            commandContext.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.prestige.no_binding")));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();
        UUID ownerUuid = playerRef.getUuid();

        // 2. Validate level == MAX_LEVEL
        if (profile.getLevel() < ScrubyXPService.MAX_LEVEL) {
            commandContext.sendMessage(Message.raw(
                    ScrubyLang.get(locale, "cmd.prestige.level_required", ScrubyXPService.MAX_LEVEL)));
            return;
        }

        // 3. Validate prestige not already attempted
        if (profile.isPrestigeAttempted()) {
            if (profile.isPrestigeWon()) {
                commandContext.sendMessage(Message.raw(
                        ScrubyLang.get(locale, "cmd.prestige.already_won_detail")));
            } else {
                commandContext.sendMessage(Message.raw(
                        ScrubyLang.get(locale, "cmd.prestige.already_lost")));
            }
            return;
        }

        // 4. Already in fight? Auto-cancel and restart (handles stuck/bugged fights)
        if (this.prestigeFightTracker.isInFight(ownerUuid)) {
            ScrubyPrestigeFightTracker.FightState existingFight = this.prestigeFightTracker.getFight(ownerUuid);
            if (existingFight != null) {
                Ref<EntityStore> oldBossRef = existingFight.getBossRef();
                if (oldBossRef != null && oldBossRef.isValid()) {
                    NPCEntity oldBoss = store.getComponent(oldBossRef, NPCEntity.getComponentType());
                    if (oldBoss != null) oldBoss.remove();
                }
            }
            this.prestigeFightTracker.endFight(ownerUuid);
            // Reset prestige flags so the new attempt works cleanly
            profile.setPrestigeAttempted(false);
            profile.setPrestigeWon(false);
            binding.setActiveProfile(profile);
            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.prestige.restarting")));
        }

        // 5. Validate path is chosen (needed to determine boss type)
        String pathChoice = profile.getPathChoice();
        if ("NONE".equals(pathChoice)) {
            commandContext.sendMessage(Message.raw(
                    ScrubyLang.get(locale, "cmd.prestige.no_path_chosen")));
            return;
        }

        // 6. Despawn active companion if present
        Ref<EntityStore> companionRef = this.registry.getCompanionRef(ownerUuid);

        if (companionRef != null && companionRef.isValid()) {
            ComponentType<EntityStore, NPCEntity> npcEntityComponentType = NPCEntity.getComponentType();

            if (npcEntityComponentType != null) {
                NPCEntity npcEntity = store.getComponent(companionRef, npcEntityComponentType);

                if (npcEntity != null) {
                    this.resetService.clearLockedTarget(store, companionRef);
                    npcEntity.remove();
                }
            }
        }

        // Unregister companion from registry
        this.registry.unregister(ownerUuid);

        // Mark companion entity as missing in binding
        this.bindingService.markCompanionEntityMissing(store, ref);
        this.hudManager.removeHud(store, ref, playerRef, ownerUuid);

        // 7. Determine boss NPC type — spawn the prestige skin so player fights the evolved form
        String bossNpcType = this.evolutionService.getPrestigeBossRole(pathChoice);

        // 8. Teleport player to world spawn (arena) and spawn boss there
        Vector3d ownerPosition = playerRef.getTransform().getPosition().clone();
        Vector3f ownerRotation = playerRef.getHeadRotation().clone();

        // Resolve world spawn point as arena center
        Vector3d arenaCenter = null;
        ISpawnProvider spawnProvider = world.getWorldConfig().getSpawnProvider();
        if (spawnProvider != null) {
            Transform spawnTransform = spawnProvider.getSpawnPoint(world, ownerUuid);
            if (spawnTransform != null) {
                arenaCenter = spawnTransform.getPosition();
            }
        }

        if (arenaCenter == null) {
            commandContext.sendMessage(Message.raw(
                    ScrubyLang.get(locale, "cmd.prestige.boss_failed")));
            LOGGER.atInfo().log("[Scruby] Prestige arena — could not resolve world spawn point.");
            return;
        }

        // Teleport player to arena center via PendingTeleport (proper player teleport)
        PendingTeleport pending = store.getComponent(ref, PendingTeleport.getComponentType());
        if (pending != null) {
            Teleport tp = Teleport.createForPlayer(world, arenaCenter, ownerRotation);
            pending.queueTeleport(tp);
            LOGGER.atInfo().log("[Scruby] Queued player teleport to prestige arena: " + arenaCenter);
        } else {
            LOGGER.atInfo().log("[Scruby] PendingTeleport component missing — cannot teleport player.");
        }

        // Spawn boss offset from arena center
        Vector3d bossPosition = new Vector3d();
        bossPosition.assign(arenaCenter);
        bossPosition.setX(arenaCenter.getX() + BOSS_ARENA_OFFSET);
        bossPosition.setY(arenaCenter.getY() + 1.5);

        // Boss faces toward arena center (where the player is teleported to).
        // Boss is +X from arena center, so boss must look in -X direction (yaw = π/2
        // given Hytale's forward convention: forward = (-sin(yaw), -cos(yaw))).
        Vector3f bossRotation = new Vector3f(0f, (float)(Math.PI / 2.0), 0f);

        LOGGER.atInfo().log("[Scruby] Prestige boss spawn: role=" + bossNpcType
                + " owner=" + ownerUuid + " arena=" + arenaCenter + " boss=" + bossPosition);

        Pair<Ref<EntityStore>, com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter> spawnResult =
                NPCPlugin.get().spawnNPC(
                        store,
                        bossNpcType,
                        null,
                        bossPosition,
                        bossRotation
                );

        if (spawnResult == null) {
            commandContext.sendMessage(Message.raw(
                    ScrubyLang.get(locale, "cmd.prestige.boss_failed")));
            LOGGER.atInfo().log("[Scruby] Prestige boss spawn failed. NPCPlugin returned null.");
            return;
        }

        Ref<EntityStore> bossRef = spawnResult.left();

        if (bossRef == null) {
            commandContext.sendMessage(Message.raw(
                    ScrubyLang.get(locale, "cmd.prestige.boss_no_ref")));
            return;
        }

        // 9. Set the player as the boss's marked target
        NPCEntity bossNpcEntity = store.getComponent(bossRef, NPCEntity.getComponentType());

        if (bossNpcEntity != null) {
            Role bossRole = bossNpcEntity.getRole();

            if (bossRole != null) {
                MarkedEntitySupport mes = bossRole.getMarkedEntitySupport();

                if (mes != null) {
                    mes.setMarkedEntity(MarkedEntitySupport.DEFAULT_TARGET_SLOT, ref);
                    LOGGER.atInfo().log("[Scruby] Boss marked target set to player ref.");
                }
            }
        }

        // 10. Override boss MaxHealth to a fixed value for the prestige fight, then heal to full
        EntityStatMap bossStatMap = store.getComponent(bossRef, EntityStatMap.getComponentType());
        if (bossStatMap != null) {
            int healthIndex = DefaultEntityStatTypes.getHealth();
            EntityStatValue healthStat = bossStatMap.get(healthIndex);
            if (healthStat != null) {
                float baseMax = healthStat.getMax();
                StaticModifier hpBoost = new StaticModifier(
                        Modifier.ModifierTarget.MAX,
                        StaticModifier.CalculationType.ADDITIVE,
                        PRESTIGE_BOSS_FIGHT_HP - baseMax
                );
                bossStatMap.putModifier(healthIndex, "scruby_prestige_boss_hp", hpBoost);
                bossStatMap.maximizeStatValue(healthIndex);
                LOGGER.atInfo().log("[Scruby] Prestige boss HP set: " + baseMax + " -> " + PRESTIGE_BOSS_FIGHT_HP);
            }
        }

        // 11. Register fight in tracker
        this.prestigeFightTracker.startFight(ownerUuid, bossRef);

        // 12. Sound: prestige start + boss spawn + battle music
        ScrubySoundService sound = ScrubyCompanionPlugin.instance().getSoundService();
        sound.playPrestigeStart(playerRef, arenaCenter.getX(), arenaCenter.getY(), arenaCenter.getZ());
        sound.playPrestigeBossSpawn(playerRef, bossPosition.getX(), bossPosition.getY(), bossPosition.getZ());

        // Play battle music attached to boss entity — stops automatically when boss dies/despawns
        NetworkId bossNetworkId = store.getComponent(bossRef, NetworkId.getComponentType());
        if (bossNetworkId != null) {
            sound.playPrestigeBattleMusic(playerRef, bossNetworkId.getId());
        }

        // 13. Send messages
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.prestige.header")));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.prestige.title")));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.prestige.true_form", profile.getCompanionName())));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.prestige.defeat_desc")));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.prestige.timer")));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.prestige.header")));

        LOGGER.atInfo().log("[Scruby] Prestige fight started. Owner=" + ownerUuid
                + " BossRef=" + bossRef + " Role=" + bossNpcType);
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }
}
