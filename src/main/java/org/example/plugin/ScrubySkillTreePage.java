package org.example.plugin;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.ButtonBuilder;
import au.ellie.hyui.builders.ItemGridBuilder;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.events.DroppedEventData;
import au.ellie.hyui.events.SlotClickPressWhileDraggingEventData;
import au.ellie.hyui.events.SlotClickReleaseWhileDraggingEventData;
import au.ellie.hyui.events.SlotDoubleClickingEventData;
import au.ellie.hyui.events.SlotMouseEnteredEventData;
import au.ellie.hyui.events.UIContext;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Skilltree Page for the Scruby companion.
 *
 * Renders an interactive page via PageBuilder.fromHtml() with addEventListener.
 * Since HyUI pages cannot be updated in-place, every interaction triggers a
 * close + reopen cycle. Draft state (attribute changes, pending selections)
 * lives in Java maps keyed by player UUID and survives reopens.
 */
public final class ScrubySkillTreePage {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Spawn boss 15 blocks in front of the player. */
    private static final double BOSS_SPAWN_DISTANCE = 15.0;

    /** Fixed MaxHealth applied to every prestige boss for the duration of the fight. */
    private static final float PRESTIGE_BOSS_FIGHT_HP = 500.0f;

    // ===== Skill Type Keys (maps skill constant -> type key for ScrubyLang lookup) =====
    private static final Map<String, String> SKILL_TYPE_KEYS = Map.ofEntries(
            Map.entry(ScrubySkillService.UNIVERSAL_KAMPFINSTINKT, "aura"),
            Map.entry(ScrubySkillService.DPS_BRENNENDE_PFEILE, "aura"),
            Map.entry(ScrubySkillService.DPS_FOKUSFEUER, "always_active"),
            Map.entry(ScrubySkillService.DPS_RAUBTIERSINNE, "passive"),
            Map.entry(ScrubySkillService.HEALER_ERSTE_HILFE, "on_danger"),
            Map.entry(ScrubySkillService.HEALER_REGENERATION, "aura"),
            Map.entry(ScrubySkillService.HEALER_SEGEN_DES_HUETERS, "aura"),
            Map.entry(ScrubySkillService.TANK_PROVOKATION, "aura"),
            Map.entry(ScrubySkillService.TANK_DICKER_PELZ, "passive"),
            Map.entry(ScrubySkillService.TANK_VERGELTUNG, "reactive"),
            Map.entry(ScrubySkillService.DPS_W1A_DOPPELSCHUSS, "automatic"),
            Map.entry(ScrubySkillService.DPS_W1B_GIFTPFEILE, "aura"),
            Map.entry(ScrubySkillService.HEALER_W1A_SCHUTZZAUBER, "on_danger"),
            Map.entry(ScrubySkillService.HEALER_W1B_REINIGUNG, "passive"),
            Map.entry(ScrubySkillService.TANK_W1A_EISENHAUT, "passive"),
            Map.entry(ScrubySkillService.TANK_W1B_DORNEN, "reactive"),
            Map.entry(ScrubySkillService.DPS_W2A_TOEDLICHE_PRAEZISION, "passive"),
            Map.entry(ScrubySkillService.DPS_W2B_UNTERDRUECKUNGSFEUER, "passive"),
            Map.entry(ScrubySkillService.HEALER_W2A_LEBENSBAND, "always_active"),
            Map.entry(ScrubySkillService.HEALER_W2B_NOTFALL_RETTUNG, "on_danger"),
            Map.entry(ScrubySkillService.TANK_W2A_KRIEGSSCHREI, "automatic"),
            Map.entry(ScrubySkillService.TANK_W2B_TREUER_BESCHUETZER, "reactive")
    );

    /** Returns localized skill name via ScrubyLang. */
    private static String skillName(String locale, String skillId) {
        return ScrubyLang.get(locale, ScrubyLang.SKILL + ScrubyLang.skillKey(skillId) + ".name");
    }

    /** Returns localized skill type via ScrubyLang. */
    private static String skillType(String locale, String skillId) {
        String typeKey = SKILL_TYPE_KEYS.getOrDefault(skillId, "passive");
        return ScrubyLang.get(locale, "skill.type." + typeKey);
    }

    /** Returns localized skill scenario via ScrubyLang. */
    private static String skillScenario(String locale, String skillId) {
        return ScrubyLang.get(locale, "skill.scenario." + ScrubyLang.skillKey(skillId));
    }

    // ===== Services =====
    private final ScrubySkillService skillService;
    private final ScrubyEvolutionService evolutionService;
    private final ScrubyXPService xpService;
    private final ScrubyAttributeService attributeService;
    private final ScrubyActiveCompanionRegistry companionRegistry;
    private final ScrubyBindingService bindingService;
    private final ScrubyCompanionSpawnService spawnService;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyHudManager hudManager;
    private final ScrubyBaseStationService baseStationService;
    private final ScrubySoundService soundService;
    private final ScrubyPrestigeFightTracker prestigeFightTracker;
    private final ScrubyInventoryService inventoryService;
    private final ScrubyArmorService armorService;

    // ===== Per-player state (survives page reopens) =====
    private final Map<UUID, AttributeDraft> playerDrafts = new HashMap<>();
    private final Map<UUID, String> pendingPathSelection = new HashMap<>();
    private final Map<UUID, String> pendingSkillW1 = new HashMap<>();
    private final Map<UUID, String> pendingSkillW2 = new HashMap<>();
    private final Map<UUID, Integer> pendingReset = new HashMap<>();
    private final Map<UUID, Integer> pendingDelete = new HashMap<>();
    private final Map<UUID, Integer> pendingRename = new HashMap<>();
    private final Map<UUID, String> pendingRenameText = new HashMap<>();
    private final Map<UUID, HyUIPage> activePages = new HashMap<>();
    private final Set<UUID> reopening = new HashSet<>();
    private final Map<UUID, String> pendingTab = new HashMap<>();
    /** Pending Scruby internal move: {sourceSlot, targetSlot}. Deferred until Dropped confirms. */
    private final Map<UUID, int[]> pendingScrubyMove = new HashMap<>();
    /** Pending Player internal move: {sourceSlot, targetSlot}. Deferred until Dropped confirms. */
    private final Map<UUID, short[]> pendingPlayerMove = new HashMap<>();
    /** Last slot the cursor hovered over in the Scruby grid (for cross-grid slot targeting). */
    private final Map<UUID, Integer> lastHoveredScrubySlot = new HashMap<>();
    /** Last slot the cursor hovered over in the Player grid (for cross-grid slot targeting). */
    private final Map<UUID, Integer> lastHoveredPlayerSlot = new HashMap<>();
    /** Last slot the cursor hovered over in the Armor grid (for cross-grid slot targeting). */
    private final Map<UUID, Integer> lastHoveredArmorSlot = new HashMap<>();
    /** Pending right-button drag (for Shift+Right half-stack detection).
     *  Cleared on Dropped (= normal right-drag) or consumed by a Release with clickMouseButton=3. */
    private final Map<UUID, RightDragState> pendingRightDrag = new HashMap<>();

    /** Source info captured when a right-button drag is in progress. */
    private static final class RightDragState {
        final int sourceSection;   // 100 = scruby, 200 = player
        final int sourceSlot;
        final String itemId;
        final int dragQty;         // quantity as reported by the drag event
        RightDragState(int sourceSection, int sourceSlot, String itemId, int dragQty) {
            this.sourceSection = sourceSection;
            this.sourceSlot = sourceSlot;
            this.itemId = itemId;
            this.dragQty = dragQty;
        }
    }

    // ===== Inner Classes =====

    static final class AttributeDraft {
        int vit, str, zel, remaining;
        final int savedVit, savedStr, savedZel;

        AttributeDraft(@Nonnull CompanionProfile profile) {
            this.vit = this.savedVit = profile.getAttributeVitality();
            this.str = this.savedStr = profile.getAttributeStrength();
            this.zel = this.savedZel = profile.getAttributeZeal();
            this.remaining = profile.getAttributePointsAvailable();
        }

        boolean hasChanges() {
            return vit != savedVit || str != savedStr || zel != savedZel;
        }

        int totalSpent() { return vit + str + zel; }
    }

    // ===== Constructor =====

    public ScrubySkillTreePage(
            @Nonnull ScrubySkillService skillService,
            @Nonnull ScrubyEvolutionService evolutionService,
            @Nonnull ScrubyXPService xpService,
            @Nonnull ScrubyAttributeService attributeService,
            @Nonnull ScrubyActiveCompanionRegistry companionRegistry,
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyCompanionSpawnService spawnService,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyHudManager hudManager,
            @Nonnull ScrubyBaseStationService baseStationService,
            @Nonnull ScrubySoundService soundService,
            @Nonnull ScrubyPrestigeFightTracker prestigeFightTracker,
            @Nonnull ScrubyInventoryService inventoryService,
            @Nonnull ScrubyArmorService armorService
    ) {
        this.skillService = java.util.Objects.requireNonNull(skillService);
        this.evolutionService = java.util.Objects.requireNonNull(evolutionService);
        this.xpService = java.util.Objects.requireNonNull(xpService);
        this.attributeService = java.util.Objects.requireNonNull(attributeService);
        this.companionRegistry = java.util.Objects.requireNonNull(companionRegistry);
        this.bindingService = java.util.Objects.requireNonNull(bindingService);
        this.spawnService = java.util.Objects.requireNonNull(spawnService);
        this.resetService = java.util.Objects.requireNonNull(resetService);
        this.hudManager = java.util.Objects.requireNonNull(hudManager);
        this.baseStationService = java.util.Objects.requireNonNull(baseStationService);
        this.soundService = java.util.Objects.requireNonNull(soundService);
        this.prestigeFightTracker = java.util.Objects.requireNonNull(prestigeFightTracker);
        this.inventoryService = java.util.Objects.requireNonNull(inventoryService);
        this.armorService = java.util.Objects.requireNonNull(armorService);
    }

    // ===== Public API =====

    public void open(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull CompanionProfile profile
    ) {
        UUID ownerUuid = playerRef.getUuid();
        boolean isReopen = reopening.remove(ownerUuid);

        // Fresh open: clear all state
        if (!isReopen) {
            playerDrafts.remove(ownerUuid);
            pendingPathSelection.remove(ownerUuid);
            pendingSkillW1.remove(ownerUuid);
            pendingSkillW2.remove(ownerUuid);
        }

        Ref<EntityStore> companionRef = companionRegistry.getCompanionRef(ownerUuid);
        AttributeDraft draft = playerDrafts.computeIfAbsent(ownerUuid, k -> new AttributeDraft(profile));
        String pendPath = pendingPathSelection.get(ownerUuid);
        String pendW1 = pendingSkillW1.get(ownerUuid);
        String pendW2 = pendingSkillW2.get(ownerUuid);

        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        String locale = profile != null ? profile.getLocale() : "en";

        // Decide which tab to show — consume pendingTab or default to "startseite"
        String selectedTab = pendingTab.getOrDefault(ownerUuid, "startseite");
        pendingTab.remove(ownerUuid);

        // Decide which HTML to build
        String path = profile.getPathChoice();
        boolean needsPathChoice = "NONE".equals(path) && profile.getLevel() >= 5;
        boolean isTransferMode = "transfer".equals(selectedTab);

        String html;
        if (isTransferMode) {
            html = buildStyleBlock(path) + buildTransferHtml(profile, store, ownerRef, locale);
        } else if (needsPathChoice) {
            html = buildPathChoiceHtml(profile, pendPath, binding, ownerUuid, selectedTab, locale);
        } else {
            html = buildHtml(profile, draft, pendW1, pendW2, binding, ownerUuid, selectedTab, locale);
        }

        try {
            PageBuilder builder = PageBuilder.pageForPlayer(playerRef).fromHtml(html);
            tryAddListener(builder, "scruby-close", ignored -> clearSession(ownerUuid));
            // Return button (transfer mode only) — go back to character tab for the same profile
            tryAddListener(builder, "player-return", ignored -> reopen(store, ownerRef, playerRef, profile));
            if (isTransferMode) {
                // ONLY register transfer-specific listeners — skip all skilltree listeners
                registerTransferListeners(builder, store, ownerRef, playerRef, ownerUuid, profile);
                HyUIPage page = builder.open(store);
                activePages.put(ownerUuid, page);
                return; // Skip all other listener registration
            } else if (needsPathChoice) {
                registerPathChoiceListeners(builder, store, ownerRef, playerRef, ownerUuid, profile, binding, companionRef);
            } else {
                registerAttributeListeners(builder, store, ownerRef, playerRef, ownerUuid, profile, draft, companionRef);
                registerSkillChoiceListeners(builder, store, ownerRef, playerRef, ownerUuid, profile, companionRef, binding);
                registerInventoryListeners(builder, store, ownerRef, playerRef, ownerUuid, profile);
            }
            registerScrubysListeners(builder, store, ownerRef, playerRef, ownerUuid, binding, companionRef);

            // Combat mode buttons
            tryAddListener(builder, "combat-passive", ignored -> {
                switchCombatMode(store, ownerRef, playerRef, ownerUuid, "PASSIVE");
            });
            tryAddListener(builder, "combat-aggressive", ignored -> {
                switchCombatMode(store, ownerRef, playerRef, ownerUuid, "AGGRESSIVE");
            });

            // Prestige fight button — only register when button exists in HTML
            if (profile.getLevel() >= ScrubyXPService.MAX_LEVEL && !profile.isPrestigeAttempted()) {
                tryAddListener(builder, "prestige-fight", (event) -> {
                    // 1. Validate path is chosen
                    String pathChoice = profile.getPathChoice();
                    if ("NONE".equals(pathChoice)) {
                        playerRef.sendMessage(Message.raw(
                                ScrubyLang.get(profile.getLocale(), "cmd.prestige.no_path_chosen")));
                        return;
                    }

                    // 2. Already in fight? Cancel old one first
                    if (prestigeFightTracker.isInFight(ownerUuid)) {
                        ScrubyPrestigeFightTracker.FightState existingFight = prestigeFightTracker.getFight(ownerUuid);
                        if (existingFight != null) {
                            Ref<EntityStore> oldBossRef = existingFight.getBossRef();
                            if (oldBossRef != null && oldBossRef.isValid()) {
                                NPCEntity oldBoss = store.getComponent(oldBossRef, NPCEntity.getComponentType());
                                if (oldBoss != null) oldBoss.remove();
                            }
                        }
                        prestigeFightTracker.endFight(ownerUuid);
                        profile.setPrestigeAttempted(false);
                        profile.setPrestigeWon(false);
                        if (binding != null) binding.setActiveProfile(profile);
                        playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "cmd.prestige.restarting")));
                    }

                    // 3. Close menu
                    clearSession(ownerUuid);

                    // 4. Despawn active companion if present
                    Ref<EntityStore> currentCompanionRef = companionRegistry.getCompanionRef(ownerUuid);
                    if (currentCompanionRef != null && currentCompanionRef.isValid()) {
                        ComponentType<EntityStore, NPCEntity> npcEntityComponentType = NPCEntity.getComponentType();
                        if (npcEntityComponentType != null) {
                            NPCEntity npcEntity = store.getComponent(currentCompanionRef, npcEntityComponentType);
                            if (npcEntity != null) {
                                resetService.clearLockedTarget(store, currentCompanionRef);
                                npcEntity.remove();
                            }
                        }
                    }
                    companionRegistry.unregister(ownerUuid);
                    bindingService.markCompanionEntityMissing(store, ownerRef);
                    hudManager.removeHud(store, ownerRef, playerRef, ownerUuid);

                    // 5. Determine boss NPC type — spawn the prestige skin so player fights the evolved form
                    String bossNpcType = evolutionService.getPrestigeBossRole(profile.getPathChoice());

                    // 6. Spawn boss in front of the player
                    Vector3d ownerPosition = playerRef.getTransform().getPosition().clone();
                    Vector3f ownerRotation = playerRef.getHeadRotation().clone();

                    double yawRadians = ownerRotation.getYaw();
                    double forwardX = -Math.sin(yawRadians);
                    double forwardZ = -Math.cos(yawRadians);

                    Vector3d bossPosition = new Vector3d();
                    bossPosition.assign(ownerPosition);
                    bossPosition.setX(ownerPosition.getX() + forwardX * BOSS_SPAWN_DISTANCE);
                    bossPosition.setY(ownerPosition.getY() + 1.5);
                    bossPosition.setZ(ownerPosition.getZ() + forwardZ * BOSS_SPAWN_DISTANCE);

                    // Boss faces the player: rotate 180° from player's yaw, keep pitch neutral
                    Vector3f bossRotation = new Vector3f(
                            0f, (float)(ownerRotation.getYaw() + Math.PI), 0f);

                    LOGGER.atInfo().log("[Scruby] Prestige boss spawn (UI): role=" + bossNpcType
                            + " owner=" + ownerUuid + " pos=" + bossPosition);

                    Pair<Ref<EntityStore>, com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter> spawnResult =
                            NPCPlugin.get().spawnNPC(
                                    store,
                                    bossNpcType,
                                    null,
                                    bossPosition,
                                    bossRotation
                            );

                    if (spawnResult == null) {
                        playerRef.sendMessage(Message.raw(
                                ScrubyLang.get(profile.getLocale(), "cmd.prestige.boss_failed")));
                        LOGGER.atInfo().log("[Scruby] Prestige boss spawn failed (UI). NPCPlugin returned null.");
                        return;
                    }

                    Ref<EntityStore> bossRef = spawnResult.left();
                    if (bossRef == null) {
                        playerRef.sendMessage(Message.raw(
                                ScrubyLang.get(profile.getLocale(), "cmd.prestige.boss_no_ref")));
                        return;
                    }

                    // 7. Set the player as the boss's marked target
                    NPCEntity bossNpcEntity = store.getComponent(bossRef, NPCEntity.getComponentType());
                    if (bossNpcEntity != null) {
                        Role bossRole = bossNpcEntity.getRole();
                        if (bossRole != null) {
                            MarkedEntitySupport mes = bossRole.getMarkedEntitySupport();
                            if (mes != null) {
                                mes.setMarkedEntity(MarkedEntitySupport.DEFAULT_TARGET_SLOT, ownerRef);
                                LOGGER.atInfo().log("[Scruby] Boss marked target set to player ref (UI).");
                            }
                        }
                    }

                    // 7b. Override boss MaxHealth to a fixed value for the prestige fight, then heal to full
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
                            LOGGER.atInfo().log("[Scruby] Prestige boss HP set (UI): " + baseMax + " -> " + PRESTIGE_BOSS_FIGHT_HP);
                        }
                    }

                    // 8. Register fight in tracker
                    prestigeFightTracker.startFight(ownerUuid, bossRef);

                    // 9. Sound + battle music
                    soundService.playPrestigeStart(playerRef, ownerPosition.getX(), ownerPosition.getY(), ownerPosition.getZ());
                    soundService.playPrestigeBossSpawn(playerRef, bossPosition.getX(), bossPosition.getY(), bossPosition.getZ());

                    // Play battle music attached to boss entity — stops when boss dies/despawns
                    NetworkId bossNetworkId = store.getComponent(bossRef, NetworkId.getComponentType());
                    if (bossNetworkId != null) {
                        soundService.playPrestigeBattleMusic(playerRef, bossNetworkId.getId());
                    }

                    // 10. Send messages — use per-profile locale, not hardcoded German
                    String prestigeLocale = profile.getLocale();
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(prestigeLocale, "cmd.prestige.header")));
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(prestigeLocale, "cmd.prestige.title")));
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(prestigeLocale, "cmd.prestige.true_form", profile.getCompanionName())));
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(prestigeLocale, "cmd.prestige.defeat_desc")));
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(prestigeLocale, "cmd.prestige.timer")));
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(prestigeLocale, "cmd.prestige.header")));

                    LOGGER.atInfo().log("[Scruby] Prestige fight started (UI). Owner=" + ownerUuid
                            + " BossRef=" + bossRef + " Role=" + bossNpcType);
                });
            }

            HyUIPage page = builder.open(store);
            activePages.put(ownerUuid, page);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Page] Open failed: " + e.getMessage());
        }
    }

    /**
     * Opens the Transfer-only view (inventory tab) for the given profile.
     * Used for stationed Scruby F-interaction — acts like a chest.
     */
    public void openTransfer(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull CompanionProfile profile
    ) {
        pendingTab.put(playerRef.getUuid(), "transfer");
        open(store, ownerRef, playerRef, profile);
    }

    public void openNoScruby(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef
    ) {
        UUID ownerUuid = playerRef.getUuid();
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        String locale = "en";
        if (binding != null) {
            CompanionProfile activeProf = binding.getActiveProfile();
            if (activeProf != null) locale = activeProf.getLocale();
        }
        try {
            String html = buildNoScrubyHtml(binding, ownerUuid, locale);
            PageBuilder builder = PageBuilder.pageForPlayer(playerRef).fromHtml(html);
            registerScrubysListeners(builder, store, ownerRef, playerRef, ownerUuid, binding, null);
            HyUIPage page = builder.open(store);
            activePages.put(ownerUuid, page);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Page] OpenNoScruby failed: " + e.getMessage());
        }
    }

    public void clearSession(@Nonnull UUID ownerUuid) {
        playerDrafts.remove(ownerUuid);
        pendingPathSelection.remove(ownerUuid);
        pendingSkillW1.remove(ownerUuid);
        pendingSkillW2.remove(ownerUuid);
        pendingReset.remove(ownerUuid);
        pendingDelete.remove(ownerUuid);
        pendingRename.remove(ownerUuid);
        pendingRenameText.remove(ownerUuid);
        pendingTab.remove(ownerUuid);
        reopening.remove(ownerUuid);
        HyUIPage page = activePages.remove(ownerUuid);
        if (page != null) {
            try { page.close(); } catch (Exception ignored) {}
        }
    }

    // ===== Reopen =====

    private void switchCombatMode(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull String mode
    ) {
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding == null) return;
        CompanionProfile profile = binding.getActiveProfile();
        if (profile == null) return;

        if (profile.hasTempleOverride()) {
            playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "cmd.combat.temple_locked")));
            return;
        }

        boolean wantAggressive = "AGGRESSIVE".equals(mode);
        if (wantAggressive == profile.isAggressive()) return; // already in this mode

        profile.setCombatMode(mode);
        binding.setActiveProfile(profile);

        // Despawn existing companion
        Ref<EntityStore> existing = companionRegistry.getCompanionRef(ownerUuid);
        if (existing != null && existing.isValid()) {
            resetService.clearLockedTarget(store, existing);
            NPCEntity npcEntity = store.getComponent(existing, NPCEntity.getComponentType());
            if (npcEntity != null) npcEntity.remove();
            companionRegistry.unregister(ownerUuid);
        }
        resetService.resetOwnerSession(ownerUuid);
        profile.setCompanionEntityUuid("");
        profile.setManuallyDespawned(false);
        profile.setEntityMissing(false);
        binding.setActiveProfile(profile);

        // Respawn with correct role
        spawnService.spawnCompanion(store, ownerRef, playerRef, ownerUuid);
        ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);

        // Chat message
        String locale = profile.getLocale();
        String msgKey = wantAggressive ? "cmd.combat.aggressive" : "cmd.combat.defensive";
        playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, msgKey)));

        // Reopen UI to reflect new icon state
        reopen(store, ownerRef, playerRef, profile);
    }

    private void switchStationedCombatMode(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            int slotId,
            @Nonnull String mode
    ) {
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding == null) return;

        // Find the stationed profile for this slot
        List<CompanionProfile> profiles = binding.getProfiles();
        CompanionProfile target = null;
        for (CompanionProfile p : profiles) {
            if (p.getSlotId() == slotId) { target = p; break; }
        }
        if (target == null || !target.isStationedAtBase()) return;

        if (target.hasTempleOverride()) {
            playerRef.sendMessage(Message.raw(ScrubyLang.get(target.getLocale(), "cmd.combat.temple_locked")));
            return;
        }

        boolean wantAggressive = "AGGRESSIVE".equals(mode);
        if (wantAggressive == target.isAggressive()) return;

        target.setCombatMode(mode);

        // Remove the stationed entity from the world (by entity UUID, not registry)
        String entityUuid = target.getCompanionEntityUuid();
        if (entityUuid != null && !entityUuid.isEmpty()) {
            try {
                UUID eUuid = UUID.fromString(entityUuid);
                Ref<EntityStore> stRef = store.getExternalData().getRefFromUUID(eUuid);
                if (stRef != null && stRef.isValid()) {
                    NPCEntity npc = store.getComponent(stRef, NPCEntity.getComponentType());
                    if (npc != null) npc.remove();
                }
            } catch (Exception ignored) {}
        }

        // Spawn new stationed entity with correct role directly (bypass registry)
        String stationedRole = evolutionService.getStationedRoleNameForProfile(target);
        Vector3d spawnPos = new Vector3d(target.getStationX(), target.getStationY(), target.getStationZ());
        Pair<Ref<EntityStore>, ?> result = NPCPlugin.get().spawnNPC(
                store, stationedRole, null, spawnPos, new Vector3f(0f, 0f, 0f));

        if (result != null && result.left() != null) {
            Ref<EntityStore> newRef = result.left();
            UUIDComponent uuidComp = store.getComponent(newRef, UUIDComponent.getComponentType());
            if (uuidComp != null) {
                target.setCompanionEntityUuid(uuidComp.getUuid().toString());
            }
            target.setEntityMissing(false);
            ScrubyArmorService.applyVisualArmor(store, newRef, target);
            // If this is the active slot, update the registry so SmartFollowSystem doesn't auto-respawn
            if (binding.getActiveSlot() == slotId) {
                companionRegistry.register(ownerUuid, newRef);
            }
        } else {
            target.setCompanionEntityUuid("");
            target.setEntityMissing(true);
        }

        // Save the profile back
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).getSlotId() == slotId) {
                profiles.set(i, target);
                break;
            }
        }
        binding.setProfiles(profiles);
        ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);

        // Chat message — stationed scrubys use the Base Guarding wording
        String locale = target.getLocale();
        String msgKey = wantAggressive ? "cmd.combat.stationed_aggressive" : "cmd.combat.stationed_defensive";
        playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, msgKey)));

        reopenScrubys(store, ownerRef, playerRef);
    }

    private void reopen(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull CompanionProfile profile
    ) {
        UUID ownerUuid = playerRef.getUuid();
        reopening.add(ownerUuid);
        // Remove old page reference but don't close — open() replaces it
        activePages.remove(ownerUuid);
        open(store, ownerRef, playerRef, profile);
    }

    /**
     * Reopen the page with the Scrubys tab active.
     * Refreshes binding from storage so changes (switch/delete/create) are reflected.
     */
    private void reopenScrubys(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef
    ) {
        UUID ownerUuid = playerRef.getUuid();
        pendingTab.put(ownerUuid, "scrubys");
        // Clear attribute draft since the active companion may have changed
        playerDrafts.remove(ownerUuid);

        // Re-read binding to pick up changes
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding == null || !binding.hasBinding()) {
            // No binding left — close page
            clearSession(ownerUuid);
            return;
        }
        CompanionProfile activeProfile = binding.getActiveProfile();
        reopen(store, ownerRef, playerRef, activeProfile);
    }

    // ===== Event Registration =====

    private void registerAttributeListeners(
            @Nonnull PageBuilder builder,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull AttributeDraft draft,
            @Nullable Ref<EntityStore> companionRef
    ) {
        // Vit +
        builder.addEventListener("vit-plus", CustomUIEventBindingType.Activating, (ignored, ctx) -> {
            if (draft.remaining <= 0) return;
            boolean needsReopen = !draft.hasChanges() || draft.vit == draft.savedVit;
            draft.vit++;
            draft.remaining--;
            if (needsReopen) {
                reopen(store, ownerRef, playerRef, profile);
            } else {
                updateAttrLabels(ctx, draft, profile, profile.getLocale());
            }
        });
        // Str +
        builder.addEventListener("str-plus", CustomUIEventBindingType.Activating, (ignored, ctx) -> {
            if (draft.remaining <= 0) return;
            boolean needsReopen = !draft.hasChanges() || draft.str == draft.savedStr;
            draft.str++;
            draft.remaining--;
            if (needsReopen) {
                reopen(store, ownerRef, playerRef, profile);
            } else {
                updateAttrLabels(ctx, draft, profile, profile.getLocale());
            }
        });
        // Zel +
        builder.addEventListener("zel-plus", CustomUIEventBindingType.Activating, (ignored, ctx) -> {
            if (draft.remaining <= 0) return;
            boolean needsReopen = !draft.hasChanges() || draft.zel == draft.savedZel;
            draft.zel++;
            draft.remaining--;
            if (needsReopen) {
                reopen(store, ownerRef, playerRef, profile);
            } else {
                updateAttrLabels(ctx, draft, profile, profile.getLocale());
            }
        });
        // Vit -
        builder.addEventListener("vit-minus", CustomUIEventBindingType.Activating, (ignored, ctx) -> {
            if (draft.vit <= draft.savedVit) return;
            draft.vit--;
            draft.remaining++;
            if (!draft.hasChanges() || draft.vit == draft.savedVit) {
                reopen(store, ownerRef, playerRef, profile);
            } else {
                updateAttrLabels(ctx, draft, profile, profile.getLocale());
            }
        });
        // Str -
        builder.addEventListener("str-minus", CustomUIEventBindingType.Activating, (ignored, ctx) -> {
            if (draft.str <= draft.savedStr) return;
            draft.str--;
            draft.remaining++;
            if (!draft.hasChanges() || draft.str == draft.savedStr) {
                reopen(store, ownerRef, playerRef, profile);
            } else {
                updateAttrLabels(ctx, draft, profile, profile.getLocale());
            }
        });
        // Zel -
        builder.addEventListener("zel-minus", CustomUIEventBindingType.Activating, (ignored, ctx) -> {
            if (draft.zel <= draft.savedZel) return;
            draft.zel--;
            draft.remaining++;
            if (!draft.hasChanges() || draft.zel == draft.savedZel) {
                reopen(store, ownerRef, playerRef, profile);
            } else {
                updateAttrLabels(ctx, draft, profile, profile.getLocale());
            }
        });
        // Confirm attributes — only registered when button exists (after first change triggers reopen)
        if (draft.hasChanges()) {
        builder.addEventListener("confirm-attr", CustomUIEventBindingType.Activating, (ignored, ctx) -> {
            profile.setAttributeVitality(draft.vit);
            profile.setAttributeStrength(draft.str);
            profile.setAttributeZeal(draft.zel);
            profile.setAttributePointsAvailable(draft.remaining);
            // Persist to binding so changes survive page close/reopen
            ScrubyOwnerBindingComponent attrBinding = bindingService.getBindingOrNull(store, ownerRef);
            if (attrBinding != null) {
                attrBinding.setActiveProfile(profile);
                ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);
            }
            playerDrafts.remove(ownerUuid);
            if (companionRef != null && companionRef.isValid()) {
                attributeService.applyAttributes(store, companionRef, profile, ownerUuid);
            }
            reopen(store, ownerRef, playerRef, profile);
        });
        }
    }

    private void registerSkillChoiceListeners(
            @Nonnull PageBuilder builder,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nullable Ref<EntityStore> companionRef,
            @Nullable ScrubyOwnerBindingComponent binding
    ) {
        String[] opts1 = skillService.getChoiceOptions(profile, 1);
        String[] opts2 = skillService.getChoiceOptions(profile, 2);

        // Slot 1
        if (opts1 != null && skillService.canChoose(profile, 1)) {
            builder.addEventListener("w1a", CustomUIEventBindingType.Activating, (event) -> {
                pendingSkillW1.put(ownerUuid, opts1[0]);
                reopen(store, ownerRef, playerRef, profile);
            });
            builder.addEventListener("w1b", CustomUIEventBindingType.Activating, (event) -> {
                pendingSkillW1.put(ownerUuid, opts1[1]);
                reopen(store, ownerRef, playerRef, profile);
            });
            if (pendingSkillW1.containsKey(ownerUuid)) {
                builder.addEventListener("confirm-w1", CustomUIEventBindingType.Activating, (event) -> {
                    String selected = pendingSkillW1.remove(ownerUuid);
                    if (selected != null) {
                        int idx = selected.equals(opts1[0]) ? 1 : 2;
                        skillService.applyChoice(profile, 1, idx);
                        if (binding != null) {
                            binding.setActiveProfile(profile);
                            ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);
                        }
                        if (companionRef != null && companionRef.isValid()) {
                            skillService.applyPassiveSkills(store, companionRef, profile);
                        }
                    }
                    reopen(store, ownerRef, playerRef, profile);
                });
            }
        }
        // Slot 2
        if (opts2 != null && skillService.canChoose(profile, 2)) {
            builder.addEventListener("w2a", CustomUIEventBindingType.Activating, (event) -> {
                pendingSkillW2.put(ownerUuid, opts2[0]);
                reopen(store, ownerRef, playerRef, profile);
            });
            builder.addEventListener("w2b", CustomUIEventBindingType.Activating, (event) -> {
                pendingSkillW2.put(ownerUuid, opts2[1]);
                reopen(store, ownerRef, playerRef, profile);
            });
            if (pendingSkillW2.containsKey(ownerUuid)) {
                builder.addEventListener("confirm-w2", CustomUIEventBindingType.Activating, (event) -> {
                    String selected = pendingSkillW2.remove(ownerUuid);
                    if (selected != null) {
                        int idx = selected.equals(opts2[0]) ? 1 : 2;
                        skillService.applyChoice(profile, 2, idx);
                        if (binding != null) {
                            binding.setActiveProfile(profile);
                            ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);
                        }
                        if (companionRef != null && companionRef.isValid()) {
                            skillService.applyPassiveSkills(store, companionRef, profile);
                        }
                    }
                    reopen(store, ownerRef, playerRef, profile);
                });
            }
        }
    }

    private void registerPathChoiceListeners(
            @Nonnull PageBuilder builder,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nullable ScrubyOwnerBindingComponent binding,
            @Nullable Ref<EntityStore> companionRef
    ) {
        builder.addEventListener("path-dps", CustomUIEventBindingType.Activating, (event) -> {
            pendingPathSelection.put(ownerUuid, "DPS");
            reopen(store, ownerRef, playerRef, profile);
        });
        builder.addEventListener("path-healer", CustomUIEventBindingType.Activating, (event) -> {
            pendingPathSelection.put(ownerUuid, "HEALER");
            reopen(store, ownerRef, playerRef, profile);
        });
        builder.addEventListener("path-tank", CustomUIEventBindingType.Activating, (event) -> {
            pendingPathSelection.put(ownerUuid, "TANK");
            reopen(store, ownerRef, playerRef, profile);
        });
        if (pendingPathSelection.containsKey(ownerUuid)) {
            builder.addEventListener("confirm-path", CustomUIEventBindingType.Activating, (event) -> {
                String selected = pendingPathSelection.remove(ownerUuid);
                if (selected != null) {
                    profile.setPathChoice(selected);
                    // Persist path choice + update evolution stage
                    if (binding != null) {
                        int newStage = evolutionService.calculateEvolutionStage(profile.getLevel());
                        profile.setEvolutionStage(newStage);
                        // Grant all fixed skills for current level + path
                        for (int lvl = 1; lvl <= profile.getLevel(); lvl++) {
                            skillService.processLevelUp(profile, lvl);
                        }
                        binding.setActiveProfile(profile);

                        // Respawn companion with new role/appearance
                        Ref<EntityStore> oldRef = companionRegistry.getCompanionRef(ownerUuid);
                        if (oldRef != null && oldRef.isValid()) {
                            resetService.clearLockedTarget(store, oldRef);
                            NPCEntity npc = store.getComponent(oldRef, NPCEntity.getComponentType());
                            if (npc != null) npc.remove();
                            companionRegistry.unregister(ownerUuid);
                            hudManager.removeHud(store, ownerRef, playerRef, ownerUuid);
                        }
                        spawnService.spawnCompanion(store, ownerRef, playerRef, ownerUuid, true);
                    }
                }
                clearSession(ownerUuid);
            });
        }
    }

    // ===== HTML: State A (no scruby) =====

    private String buildNoScrubyHtml(@Nullable ScrubyOwnerBindingComponent binding, @Nonnull UUID ownerUuid, @Nonnull String locale) {
        String content = "<div style=\"layout-mode: Top; padding: 40 20;\">"
                + "<p style=\"font-size: 16; color: " + ScrubyColors.TEXT_PRIMARY + "; font-weight: bold; text-align: center;\">" + esc(ScrubyLang.get(locale, "ui.skilltree.no_scruby")) + "</p>"
                + "<p style=\"font-size: 12; color: " + ScrubyColors.TEXT_SECONDARY + "; text-align: center; padding-top: 6;\">" + esc(ScrubyLang.get(locale, "ui.skilltree.no_scruby.hint")) + "</p>"
                + "</div>";
        String scrubysContent = buildScrubysTab(binding, ownerUuid, locale);
        return buildStyleBlock("NONE") + pageWrap(content, scrubysContent, "scrubys", locale);
    }

    // ===== HTML: State C (path choice) =====

    private String buildPathChoiceHtml(
            @Nonnull CompanionProfile profile,
            @Nullable String pendingPath,
            @Nullable ScrubyOwnerBindingComponent binding,
            @Nonnull UUID ownerUuid,
            @Nonnull String selectedTab,
            @Nonnull String locale
    ) {
        boolean dpsSelected = "DPS".equals(pendingPath);
        boolean healerSelected = "HEALER".equals(pendingPath);
        boolean tankSelected = "TANK".equals(pendingPath);
        boolean anySelected = pendingPath != null;

        // Path choice content uses layout-mode: Top with centered children.
        // Three cards sit side by side via layout-mode: Left with flex-weight: 1 each.
        String startseiteContent =
                "<div style=\"layout-mode: Top; padding: 16 12;\">"
                // Title
                + "<p style=\"font-size: 18; color: " + ScrubyColors.TEXT_BRIGHT + "; font-weight: bold; text-align: center;\">" + esc(ScrubyLang.get(locale, "ui.pathchoice.title")) + "</p>"
                + "<p style=\"font-size: 11; color: " + ScrubyColors.TEXT_SECONDARY + "; text-align: center; padding-top: 4;\">" + esc(ScrubyLang.get(locale, "ui.pathchoice.instruction")) + "</p>"
                // Spacer
                + "<p style=\"anchor-height: 8; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>"
                // Three cards side by side
                + "<div style=\"layout-mode: Left; flex-weight: 1;\">"
                  + pathCard("path-dps", ScrubyLang.get(locale, "path.dps.name").toUpperCase(),
                          ScrubyLang.get(locale, "path.dps.badge"),
                          ScrubyLang.get(locale, "path.dps.desc"),
                          skillName(locale, ScrubySkillService.DPS_BRENNENDE_PFEILE),
                          skillName(locale, ScrubySkillService.DPS_FOKUSFEUER),
                          skillName(locale, ScrubySkillService.DPS_RAUBTIERSINNE),
                          dpsSelected, ScrubyColors.DPS_PRIMARY, locale)
                  + pathCard("path-healer", ScrubyLang.get(locale, "path.healer.name").toUpperCase(),
                          ScrubyLang.get(locale, "path.healer.badge"),
                          ScrubyLang.get(locale, "path.healer.desc"),
                          skillName(locale, ScrubySkillService.HEALER_ERSTE_HILFE),
                          skillName(locale, ScrubySkillService.HEALER_W1A_SCHUTZZAUBER),
                          skillName(locale, ScrubySkillService.HEALER_SEGEN_DES_HUETERS),
                          healerSelected, ScrubyColors.HEALER_PRIMARY, locale)
                  + pathCard("path-tank", ScrubyLang.get(locale, "path.tank.name").toUpperCase(),
                          ScrubyLang.get(locale, "path.tank.badge"),
                          ScrubyLang.get(locale, "path.tank.desc"),
                          skillName(locale, ScrubySkillService.TANK_PROVOKATION),
                          skillName(locale, ScrubySkillService.TANK_W1A_EISENHAUT),
                          skillName(locale, ScrubySkillService.TANK_W2A_KRIEGSSCHREI),
                          tankSelected, ScrubyColors.TANK_PRIMARY, locale)
                + "</div>"
                // Spacer
                + "<p style=\"anchor-height: 10; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>"
                // Confirm button or hint text
                + (anySelected
                        ? "<button id=\"confirm-path\" class=\"custom-textbutton\""
                          + " data-hyui-default-label-style=\"@GoldBtnLabel\""
                          + " data-hyui-hovered-label-style=\"@GoldBtnLabel\""
                          + " data-hyui-default-bg=\"@GoldBtnBg\""
                          + " data-hyui-hovered-bg=\"@GoldBtnHoverBg\""
                          + " data-hyui-pressed-bg=\"@GoldBtnHoverBg\""
                          + " style=\"anchor-width: 250; anchor-height: 34; horizontal-align: center;\">" + esc(ScrubyLang.get(locale, "ui.pathchoice.confirm")) + "</button>"
                        : "<p style=\"font-size: 11; color: " + ScrubyColors.TEXT_MUTED + "; text-align: center;\">" + esc(ScrubyLang.get(locale, "ui.pathchoice.hint")) + "</p>")
                + "</div>";

        String scrubysContent = buildScrubysTab(binding, ownerUuid, locale);

        return buildStyleBlock("NONE") + pageWrap(startseiteContent, scrubysContent, selectedTab, locale, 420, false);
    }

    /**
     * Renders a single path-choice card as a clickable custom-textbutton.
     * Uses layout-mode: Top for internal stacking, flex-weight: 1 so all three
     * cards share the available width equally.
     */
    private String pathCard(String id, String title, String subtitle, String desc,
                            String skill1, String skill2, String skill3,
                            boolean selected, String accentColor, String locale) {
        // Path-specific color themes
        String bg, borderBg, titleColor;
        String subtitleBg, subtitleColor;
        String descColor, skillColor, skillLabelColor, dotColor;
        String accentDimmed;

        if (ScrubyColors.DPS_PRIMARY.equals(accentColor)) {
            bg = selected ? ScrubyColors.DPS_BG : ScrubyColors.BG_CARD;
            borderBg = selected ? "rgba(224,90,58,0.3) 2" : "rgba(42,20,16,0.5) 1";
            titleColor = selected ? ScrubyColors.DPS_TEXT : ScrubyColors.TEXT_PRIMARY;
            subtitleBg = selected ? ScrubyColors.DPS_DARK : "rgba(224,90,58,0.08)";
            subtitleColor = selected ? ScrubyColors.DPS_TEXT : "#aa7a6a";
            descColor = selected ? ScrubyColors.DPS_DESC : ScrubyColors.TEXT_SECONDARY;
            skillColor = selected ? "#d8a898" : ScrubyColors.TEXT_SECONDARY;
            skillLabelColor = selected ? "#aa6a5a" : ScrubyColors.TEXT_MUTED;
            dotColor = selected ? ScrubyColors.DPS_PRIMARY : ScrubyColors.TEXT_DISABLED;
            accentDimmed = "#3a2418";
        } else if (ScrubyColors.HEALER_PRIMARY.equals(accentColor)) {
            bg = selected ? ScrubyColors.HEALER_BG : ScrubyColors.BG_CARD;
            borderBg = selected ? "rgba(74,154,90,0.3) 2" : "rgba(16,42,24,0.5) 1";
            titleColor = selected ? ScrubyColors.HEALER_TEXT : ScrubyColors.TEXT_PRIMARY;
            subtitleBg = selected ? ScrubyColors.HEALER_DARK : "rgba(74,154,90,0.08)";
            subtitleColor = selected ? ScrubyColors.HEALER_TEXT : "#6a9a6a";
            descColor = selected ? ScrubyColors.HEALER_DESC : ScrubyColors.TEXT_SECONDARY;
            skillColor = selected ? "#a8d8a8" : ScrubyColors.TEXT_SECONDARY;
            skillLabelColor = selected ? "#5a9a6a" : ScrubyColors.TEXT_MUTED;
            dotColor = selected ? ScrubyColors.HEALER_PRIMARY : ScrubyColors.TEXT_DISABLED;
            accentDimmed = "#1e3e20";
        } else {
            // TANK
            bg = selected ? ScrubyColors.TANK_BG : ScrubyColors.BG_CARD;
            borderBg = selected ? "rgba(74,122,200,0.3) 2" : "rgba(16,26,42,0.5) 1";
            titleColor = selected ? ScrubyColors.TANK_TEXT : ScrubyColors.TEXT_PRIMARY;
            subtitleBg = selected ? ScrubyColors.TANK_DARK : "rgba(74,122,200,0.08)";
            subtitleColor = selected ? ScrubyColors.TANK_TEXT : "#6a7aaa";
            descColor = selected ? ScrubyColors.TANK_DESC : ScrubyColors.TEXT_SECONDARY;
            skillColor = selected ? "#a0c0e0" : ScrubyColors.TEXT_SECONDARY;
            skillLabelColor = selected ? "#5a88aa" : ScrubyColors.TEXT_MUTED;
            dotColor = selected ? ScrubyColors.TANK_PRIMARY : ScrubyColors.TEXT_DISABLED;
            accentDimmed = "#1e2e40";
        }

        StringBuilder sb = new StringBuilder();
        // Outer flex container per card — flex-weight: 1 so cards share width equally
        sb.append("<div style=\"flex-weight: 1; layout-mode: Top; padding: 0 4;\">");

        // Wrapper-div border frame around the card
        sb.append("<div style=\"flex-weight: 1; layout-mode: Top; background-color: ").append(borderBg).append(";\">");

        // Top accent bar — 4px high, full width
        String spacerColor = ScrubyColors.BG_SURFACE; // always hex-safe for color: property
        sb.append("<p style=\"anchor-height: 4; background-color: ")
          .append(selected ? accentColor : accentDimmed)
          .append("; font-size: 1; color: ").append(selected ? accentColor : accentDimmed).append(";\"> </p>");

        // Card body — raw-button accepts child content (custom-textbutton crashes with children)
        String innerBg = selected ? bg : ScrubyColors.BG_CARD;
        sb.append("<button id=\"").append(id).append("\" class=\"raw-button\"")
          .append(" style=\"flex-weight: 1; background-color: ").append(innerBg).append(";\">");

        // Internal content using layout-mode: Top
        sb.append("<div style=\"layout-mode: Top; padding: 12 8;\">");

        // Title
        sb.append("<p style=\"font-size: 15; color: ").append(titleColor)
          .append("; font-weight: bold; text-align: center;\">").append(esc(title)).append("</p>");

        // Spacer
        sb.append("<p style=\"anchor-height: 4; font-size: 1; color: ").append(spacerColor).append(";\"> </p>");

        // Subtitle badge
        sb.append("<div style=\"anchor-width: 80; anchor-height: 22; background-color: ").append(subtitleBg)
          .append("; horizontal-align: center; layout-mode: Top;\">");
        sb.append("<p style=\"font-size: 10; color: ").append(subtitleColor)
          .append("; font-weight: bold; text-align: center; vertical-align: center;\">").append(esc(subtitle)).append("</p>");
        sb.append("</div>");

        // Spacer
        sb.append("<p style=\"anchor-height: 6; font-size: 1; color: ").append(spacerColor).append(";\"> </p>");

        // Separator line
        sb.append("<p style=\"anchor-height: 1; background-color: ")
          .append(selected ? ScrubyColors.BORDER_GREEN : ScrubyColors.TEXT_DISABLED)
          .append("; font-size: 1; color: ").append(selected ? ScrubyColors.BORDER_GREEN : ScrubyColors.TEXT_DISABLED).append(";\"> </p>");

        // Spacer
        sb.append("<p style=\"anchor-height: 8; font-size: 1; color: ").append(spacerColor).append(";\"> </p>");

        // Description
        sb.append("<p style=\"font-size: 11; color: ").append(descColor)
          .append("; text-align: center;\">").append(esc(desc)).append("</p>");

        // Spacer
        sb.append("<p style=\"anchor-height: 12; font-size: 1; color: ").append(spacerColor).append(";\"> </p>");

        // Skill list label
        sb.append("<p style=\"font-size: 9; color: ").append(skillLabelColor)
          .append("; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.pathchoice.skills"))).append("</p>");

        // Spacer
        sb.append("<p style=\"anchor-height: 6; font-size: 1; color: ").append(spacerColor).append(";\"> </p>");

        // Skills listed vertically with dot separators
        sb.append("<p style=\"font-size: 12; color: ").append(skillColor)
          .append("; text-align: center;\">").append(esc(skill1)).append("</p>");
        sb.append("<p style=\"font-size: 8; color: ").append(dotColor)
          .append("; text-align: center;\">\u00b7</p>");
        sb.append("<p style=\"font-size: 12; color: ").append(skillColor)
          .append("; text-align: center;\">").append(esc(skill2)).append("</p>");
        sb.append("<p style=\"font-size: 8; color: ").append(dotColor)
          .append("; text-align: center;\">\u00b7</p>");
        sb.append("<p style=\"font-size: 12; color: ").append(skillColor)
          .append("; text-align: center;\">").append(esc(skill3)).append("</p>");

        // Spacer
        sb.append("<p style=\"anchor-height: 12; font-size: 1; color: ").append(spacerColor).append(";\"> </p>");

        // Selection indicator
        if (selected) {
            sb.append("<div style=\"anchor-width: 120; anchor-height: 22; background-color: rgba(255,255,255,0.06) 1; horizontal-align: center; layout-mode: Top;\">");
            sb.append("<p style=\"font-size: 10; color: ").append(titleColor)
              .append("; font-weight: bold; text-align: center; vertical-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.pathchoice.selected"))).append("</p>");
            sb.append("</div>");
        } else {
            sb.append("<p style=\"font-size: 10; color: ").append(ScrubyColors.TEXT_MUTED)
              .append("; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.pathchoice.select"))).append("</p>");
        }

        sb.append("</div>"); // end internal Top layout
        sb.append("</button>"); // end card button
        sb.append("</div>"); // end wrapper-div border frame
        sb.append("</div>"); // end flex container
        return sb.toString();
    }

    // ===== HTML: States B/D/E/F (normal three-column) =====

    private String buildHtml(
            @Nonnull CompanionProfile profile,
            @Nonnull AttributeDraft draft,
            @Nullable String pendW1,
            @Nullable String pendW2,
            @Nullable ScrubyOwnerBindingComponent binding,
            @Nonnull UUID ownerUuid,
            @Nonnull String selectedTab,
            @Nonnull String locale
    ) {
        // Startseite tab: header + three-column layout
        String startseiteContent =
                buildHeader(profile, draft, locale)
                // Three columns side by side
                + "<div style=\"flex-weight: 1; layout-mode: Left;\">"
                  + "<div style=\"anchor-width: 8; background-color: " + ScrubyColors.BG_SURFACE + ";\">"
                    + "<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>"
                  + "</div>"
                  + "<div id=\"col-skills\" class=\"col-skills\" style=\"anchor-width: 265; layout-mode: Top; background-color: " + ScrubyColors.BG_SURFACE + ";\">"
                    + buildSkillsColumn(profile, pendW1, pendW2, locale)
                    // Combat mode icons (bottom of skills column)
                    + "<div style=\"flex-weight: 1;\"><p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p></div>"
                    + "<div style=\"layout-mode: Left; padding-left: 15; padding-bottom: 8;\">"
                      + "<button id=\"combat-aggressive\" class=\"custom-textbutton\""
                      + " data-hyui-default-label-style=\"@CombatBtnLabel\""
                      + " data-hyui-hovered-label-style=\"@CombatBtnLabel\""
                      + " data-hyui-pressed-label-style=\"@CombatBtnLabel\""
                      + " data-hyui-default-bg=\"" + (!profile.isAggressive() ? "@CombatAggressiveBg" : "@CombatAggressiveChosenBg") + "\""
                      + " data-hyui-hovered-bg=\"@CombatAggressiveChosenBg\""
                      + " data-hyui-pressed-bg=\"@CombatAggressiveChosenBg\""
                      + " style=\"anchor-width: 64; anchor-height: 64;\"> </button>"
                      + "<div style=\"anchor-width: 4;\"><p style=\"font-size: 1;\"> </p></div>"
                      + "<button id=\"combat-passive\" class=\"custom-textbutton\""
                      + " data-hyui-default-label-style=\"@CombatBtnLabel\""
                      + " data-hyui-hovered-label-style=\"@CombatBtnLabel\""
                      + " data-hyui-pressed-label-style=\"@CombatBtnLabel\""
                      + " data-hyui-default-bg=\"" + (profile.isAggressive() ? "@CombatPassiveBg" : "@CombatPassiveChosenBg") + "\""
                      + " data-hyui-hovered-bg=\"@CombatPassiveChosenBg\""
                      + " data-hyui-pressed-bg=\"@CombatPassiveChosenBg\""
                      + " style=\"anchor-width: 64; anchor-height: 64;\"> </button>"
                    + "</div>"
                  + "</div>"
                  + "<div style=\"anchor-width: 8; background-color: " + ScrubyColors.BG_SURFACE + ";\">"
                    + "<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>"
                  + "</div>"
                  + "<div id=\"col-center\" style=\"flex-weight: 1; layout-mode: Top;\">"
                    + buildCenterColumn(profile, locale)
                  + "</div>"
                  + "<div style=\"anchor-width: 8; background-color: " + ScrubyColors.BG_SURFACE + ";\">"
                    + "<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>"
                  + "</div>"
                  + "<div id=\"col-sidebar\" class=\"col-sidebar\" style=\"anchor-width: 255; layout-mode: Top; background-color: " + ScrubyColors.BG_SURFACE + ";\">"
                    + buildSidebarColumn(profile, draft, locale)
                  + "</div>"
                  + "<div style=\"anchor-width: 8; background-color: " + ScrubyColors.BG_SURFACE + ";\">"
                    + "<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>"
                  + "</div>"
                + "</div>";

        String scrubysContent = buildScrubysTab(binding, ownerUuid, locale);

        return buildStyleBlock(profile.getPathChoice()) + pageWrap(startseiteContent, scrubysContent, selectedTab, locale);
    }

    // ===== Stub Methods (replaced in later tasks) =====

    // ===== Center Column: Character Preview + Inventory =====

    private String buildCenterColumn(@Nonnull CompanionProfile profile, @Nonnull String locale) {
        String appearance = evolutionService.getAppearanceForProfile(profile);
        String portraitAsset = evolutionService.getPortraitAssetName(profile);
        int kills = profile.getKillCount();
        String killText = (kills == 1) ? "1 Kill" : kills + " Kills";
        int stage = profile.getEvolutionStage();

        StringBuilder sb = new StringBuilder();

        // ── Portrait rectangle: 240x320, centered ──
        // Use padding-top 20 for margin-top effect; horizontal-align centers the block
        sb.append("<div style=\"layout-mode: Top; padding: 20 0 0 0; horizontal-align: center;\">");

        // Portrait container: fixed size, subtle background, bordered.
        // No layout-mode so children can overlap via anchor-* positioning; portrait renders
        // first, armor renders second so the armor slots paint ON TOP of the portrait image
        // when the 315x315 portrait overflows leftward into the armor area.
        sb.append("<div style=\"anchor-width: 318; anchor-height: 320; background-color: rgba(20,40,60,0.6) 1; horizontal-align: center;\">");

        int slotSize = 60;
        int slotGap = 8;
        int totalArmorH = 4 * slotSize + 3 * slotGap; // 264
        int armorTopOffset = (320 - totalArmorH) / 2; // center vertically in 320px container

        // Full-body portrait — placed FIRST in DOM so armor overlay (later) paints on top
        sb.append("<div style=\"anchor-left: 68; anchor-right: 0; anchor-top: 0; anchor-bottom: 0; layout-mode: MiddleCenter;\">");
        boolean isPrestige = profile.isPrestigeWon();
        String path = profile.getPathChoice();
        int portraitShift = 0;
        int portraitSize;
        if (isPrestige && "DPS".equals(path)) {
            portraitSize = 315; // Prestige Gunner — tuned separately
        } else if (isPrestige && "HEALER".equals(path)) {
            portraitSize = 315; // Prestige Mage — tuned separately
        } else if (!isPrestige && stage == 1) {
            portraitSize = 277; // Stage 1 (Lv1-4): 252 * 1.1
        } else {
            portraitSize = 315; // Stage 2/3 + Prestige Tank
        }
        sb.append("<img src=\"").append(portraitAsset).append("\" style=\"anchor-top: ").append(portraitShift).append("; anchor-width: ").append(portraitSize).append("; anchor-height: ").append(portraitSize).append(";\" />");
        sb.append("</div>");

        // ── Armor Slots (4 slots, vertical, section 300) ──
        // Placed AFTER the portrait in DOM so it paints on top (HyUI has no z-index).
        sb.append("<div style=\"anchor-left: 14; anchor-top: 0; anchor-width: 68; anchor-height: 320;\">");

        // Background icons (visible when slot is empty)
        String[] armorSlotIcons = {
            "icons/armor_slot_head.png",
            "icons/armor_slot_chest.png",
            "icons/armor_slot_hands.png",
            "icons/armor_slot_legs.png"
        };
        // Per-icon sizing to preserve aspect ratio (gloves is 62x55, others ~57x57)
        int[] iconWidths  = {70, 70, 70, 70};
        int[] iconHeights = {70, 70, 70, 70};
        for (int i = 0; i < ScrubyArmorService.SLOT_COUNT; i++) {
            int xOff = (slotSize - iconWidths[i]) / 2;
            int yOff = (slotSize - iconHeights[i]) / 2;
            int yPos = armorTopOffset + i * (slotSize + slotGap) + yOff;
            sb.append("<img src=\"").append(armorSlotIcons[i]).append("\" style=\"anchor-top: ").append(yPos)
              .append("; anchor-left: ").append(xOff)
              .append("; anchor-width: ").append(iconWidths[i])
              .append("; anchor-height: ").append(iconHeights[i]).append(";\" />");
        }

        // Item grid on top
        sb.append("<div id=\"scruby-armor-grid\" class=\"item-grid\"");
        sb.append(" style=\"anchor-top: ").append(armorTopOffset).append(";\"");
        sb.append(" data-hyui-slots-per-row=\"1\"");
        sb.append(" data-hyui-style=\"SlotSize: ").append(slotSize).append("; SlotSpacing: ").append(slotGap).append("\"");
        sb.append(" data-hyui-inventory-section-id=\"300\"");
        sb.append(" data-hyui-are-items-draggable=\"true\"");
        sb.append(" data-hyui-display-item-quantity=\"false\"");
        sb.append(" data-hyui-info-display=\"None\">");
        for (int i = 0; i < ScrubyArmorService.SLOT_COUNT; i++) {
            String equipped = armorService.getEquippedItem(profile, i);
            if (!equipped.isEmpty()) {
                sb.append("<div class=\"item-grid-slot\"")
                  .append(" data-hyui-item-id=\"").append(esc(equipped)).append("\"")
                  .append(" data-hyui-quantity=\"1\">")
                  .append("</div>");
            } else {
                sb.append("<div class=\"item-grid-slot\"></div>");
            }
        }
        sb.append("</div>"); // end armor item-grid
        sb.append("</div>"); // end armor wrapper

        sb.append("</div>"); // end portrait container

        // ── Inventory grid: 5 cols x 4 rows = 20 slots (real ItemGrid) ──

        // Build a lookup of slotIndex -> InventoryEntry for this profile
        java.util.List<ScrubyInventoryService.InventoryEntry> invItems = inventoryService.getItems(profile);
        ScrubyInventoryService.InventoryEntry[] slotLookup = new ScrubyInventoryService.InventoryEntry[ScrubyInventoryService.MAX_SLOTS];
        for (ScrubyInventoryService.InventoryEntry entry : invItems) {
            if (entry.getSlotIndex() >= 0 && entry.getSlotIndex() < ScrubyInventoryService.MAX_SLOTS) {
                slotLookup[entry.getSlotIndex()] = entry;
            }
        }

        sb.append("<div id=\"scruby-inv-grid\" class=\"item-grid\"")
          .append(" data-hyui-slots-per-row=\"5\"")
          .append(" data-hyui-inventory-section-id=\"100\"")
          .append(" data-hyui-are-items-draggable=\"true\"")
          .append(" data-hyui-display-item-quantity=\"true\"")
          .append(" data-hyui-info-display=\"None\"")
          .append(" style=\"horizontal-align: center; padding: 8 18 0 0;\">");
        for (int i = 0; i < ScrubyInventoryService.MAX_SLOTS; i++) {
            ScrubyInventoryService.InventoryEntry entry = slotLookup[i];
            if (entry != null) {
                sb.append("<div class=\"item-grid-slot\"")
                  .append(" data-hyui-item-id=\"").append(esc(entry.getItemId())).append("\"")
                  .append(" data-hyui-quantity=\"").append(entry.getQuantity()).append("\">")
                  .append("</div>");
            } else {
                sb.append("<div class=\"item-grid-slot\"></div>");
            }
        }
        sb.append("</div>");

        // Grid label — show occupied / total (id for in-place update)
        int occupied = invItems.size();
        sb.append("<p id=\"inv-count\" style=\"font-size: 9; color: ").append(ScrubyColors.TEXT_MUTED)
          .append("; text-align: center; padding-top: 4;\">")
          .append(esc(ScrubyLang.get(locale, "ui.scrubys.inventory", occupied))).append("</p>");

        // Transfer button
        sb.append("<button id=\"btn-transfer\" class=\"custom-textbutton\""
          + " data-hyui-default-label-style=\"@GoldBtnLabel\""
          + " data-hyui-hovered-label-style=\"@GoldBtnLabel\""
          + " data-hyui-default-bg=\"@GoldBtnBg\""
          + " data-hyui-hovered-bg=\"@GoldBtnHoverBg\""
          + " data-hyui-pressed-bg=\"@GoldBtnHoverBg\""
          + " style=\"anchor-height: 28; horizontal-align: center; padding: 6 0 0 0;\">")
          .append(esc(ScrubyLang.get(locale, "ui.scrubys.transfer"))).append("</button>");

        sb.append("</div>"); // end outer Top layout

        return sb.toString();
    }

    // ===== Sidebar Column: Attributes + Evolution + Stats =====

    private String buildSidebarColumn(@Nonnull CompanionProfile profile, @Nonnull AttributeDraft draft, @Nonnull String locale) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildAttributes(profile, draft, locale));
        sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        sb.append(buildEvolution(profile, locale));
        sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        sb.append(buildStats(profile, draft, locale));
		sb.append("<p style=\"anchor-height: 8; font-size: 1; color:").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        sb.append(buildPrestige(profile, locale));
        return sb.toString();
    }

    private String buildPrestige(@Nonnull CompanionProfile profile, @Nonnull String locale) {
        boolean maxLevel = profile.getLevel() >= ScrubyXPService.MAX_LEVEL;

        StringBuilder sb = new StringBuilder();
        sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

        if (!maxLevel) {
            // Teaser — locked prestige section
            sb.append("<div style=\"layout-mode: Top; background-color: rgba(255,255,255,0.04) 1;\">");
            sb.append("<div style=\"layout-mode: Top; padding: 10; background-color: rgba(255,255,255,0.02);\">");
            sb.append("<p style=\"font-size: 8; color: #4a5868; font-weight: bold; text-align: center; letter-spacing: 2;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.title"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
            sb.append("<p style=\"font-size: 11; color: #4a5868; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.fight"))).append("</p>");
            sb.append("<p style=\"font-size: 9; color: #3a4858; text-align: center;\">")
              .append(esc(ScrubyLang.get(locale, "ui.prestige.locked", ScrubyXPService.MAX_LEVEL))).append("</p>");
            sb.append("</div>");
            sb.append("</div>");
            return sb.toString();
        }

        if (profile.isPrestigeWon()) {
            // Prestige already won — show golden badge
            sb.append("<div style=\"layout-mode: Top; background-color: rgba(200,168,50,0.30) 1;\">");
            sb.append("<div style=\"layout-mode: Top; padding: 10; background-color: rgba(200,168,50,0.08);\">");
            sb.append("<p style=\"font-size: 8; color: #a08a40; font-weight: bold; text-align: center; letter-spacing: 2;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.title"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
            sb.append("<p style=\"font-size: 12; color: #daa520; font-weight: bold; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.won"))).append("</p>");
            sb.append("<p style=\"font-size: 9; color: #a08a50; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.won.sub"))).append("</p>");
            sb.append("</div>");
            sb.append("</div>");
        } else if (profile.isPrestigeAttempted()) {
            // Prestige attempted but lost — show grey message
            sb.append("<div style=\"layout-mode: Top; background-color: rgba(255,255,255,0.05) 1;\">");
            sb.append("<div style=\"layout-mode: Top; padding: 10; background-color: rgba(255,255,255,0.03);\">");
            sb.append("<p style=\"font-size: 8; color: #6a7a8a; font-weight: bold; text-align: center; letter-spacing: 2;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.title"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
            sb.append("<p style=\"font-size: 11; color: #6a7a8a; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.lost"))).append("</p>");
            sb.append("<p style=\"font-size: 9; color: #4a5868; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.lost.sub"))).append("</p>");
            sb.append("</div>");
            sb.append("</div>");
        } else {
            // Prestige available — epic golden button
            sb.append("<div style=\"layout-mode: Top; background-color: rgba(200,168,50,0.45) 2;\">");
            sb.append("<div style=\"layout-mode: Top; padding: 12; background-color: rgba(200,168,50,0.10);\">");
            sb.append("<p style=\"font-size: 8; color: #a09040; font-weight: bold; text-align: center; letter-spacing: 3; padding-top: 4;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.banner"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
            sb.append("<p style=\"font-size: 16; color: #d4b840; font-weight: bold; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.button_title"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
            sb.append("<p style=\"font-size: 10; color: #b8a060; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.desc"))).append("</p>");
            sb.append("<p style=\"font-size: 10; color: #b8a060; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.reward"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
            sb.append("<p style=\"font-size: 9; color: #e05050; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.warning"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
            sb.append("<button id=\"prestige-fight\" class=\"custom-textbutton\""
                    + " data-hyui-default-label-style=\"@PrestigeBtnLabel\""
                    + " data-hyui-hovered-label-style=\"@PrestigeBtnLabel\""
                    + " data-hyui-default-bg=\"@PrestigeBtnBg\""
                    + " data-hyui-hovered-bg=\"@PrestigeBtnHoverBg\""
                    + " data-hyui-pressed-bg=\"@PrestigeBtnHoverBg\""
                    + " style=\"anchor-height: 32;\">").append(esc(ScrubyLang.get(locale, "ui.prestige.button"))).append("</button>");
            sb.append("</div>");
            sb.append("</div>");
        }
        return sb.toString();
    }

    // ===== Scrubys Tab =====

    private String buildScrubysTab(@Nullable ScrubyOwnerBindingComponent binding, @Nonnull UUID ownerUuid, @Nonnull String locale) {
        StringBuilder sb = new StringBuilder();
        boolean hasDialog = pendingReset.containsKey(ownerUuid) || pendingDelete.containsKey(ownerUuid) || pendingRename.containsKey(ownerUuid);
        int scrollReduce = hasDialog ? 70 : 0;

        sb.append("<div style=\"layout-mode: Top; padding: 8;\">");

        // Reset confirmation dialog — shown at the top when pendingReset is set
        if (pendingReset.containsKey(ownerUuid) && binding != null) {
            int resetSlot = pendingReset.get(ownerUuid);
            CompanionProfile resetProfile = null;
            for (CompanionProfile p : binding.getProfiles()) {
                if (p.getSlotId() == resetSlot) { resetProfile = p; break; }
            }
            String resetName = resetProfile != null ? resetProfile.getCompanionName() : "Scruby";

            sb.append("<div id=\"reset-dialog\" style=\"layout-mode: Top; background-color: rgba(90,26,26,0.4) 1; padding: 14;\">");
            sb.append("<p style=\"font-size: 13; color: ").append(ScrubyColors.TEXT_BRIGHT)
              .append("; font-weight: bold; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.reset.confirm"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<p style=\"font-size: 11; color: ").append(ScrubyColors.TEXT_PRIMARY)
              .append("; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.reset.details", resetName))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<p style=\"font-size: 11; color: #e05050; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.reset.warning"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<div style=\"layout-mode: Left;\">");
            sb.append("<p style=\"flex-weight: 1; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<button id=\"cancel-reset\" class=\"custom-textbutton\""
                    + " data-hyui-default-label-style=\"@GreenBtnLabel\""
                    + " data-hyui-hovered-label-style=\"@GreenBtnLabel\""
                    + " data-hyui-default-bg=\"@GreenBtnBg\""
                    + " style=\"anchor-width: 120; anchor-height: 28;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.cancel"))).append("</button>");
            sb.append("<p style=\"anchor-width: 8; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<button id=\"confirm-reset\" class=\"custom-textbutton\""
                    + " data-hyui-default-label-style=\"@DangerBtnLabel\""
                    + " data-hyui-hovered-label-style=\"@DangerBtnLabel\""
                    + " data-hyui-default-bg=\"@DangerBtnBg\""
                    + " style=\"anchor-width: 160; anchor-height: 28;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.reset.button"))).append("</button>");
            sb.append("<p style=\"flex-weight: 1; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("</div>");
            sb.append("</div>");
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        }

        // Delete confirmation dialog — shown when pendingDelete is set
        if (pendingDelete.containsKey(ownerUuid) && binding != null) {
            int deleteSlot = pendingDelete.get(ownerUuid);
            CompanionProfile deleteProfile = null;
            for (CompanionProfile p : binding.getProfiles()) {
                if (p.getSlotId() == deleteSlot) { deleteProfile = p; break; }
            }
            String deleteName = deleteProfile != null ? deleteProfile.getCompanionName() : "Scruby";

            sb.append("<div id=\"delete-dialog\" style=\"layout-mode: Top; background-color: rgba(90,26,26,0.4) 1; padding: 14;\">");
            sb.append("<p style=\"font-size: 13; color: ").append(ScrubyColors.TEXT_BRIGHT)
              .append("; font-weight: bold; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.delete.confirm"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<p style=\"font-size: 11; color: #e05050; text-align: center;\">")
              .append(esc(ScrubyLang.get(locale, "ui.scrubys.delete.warning", deleteName))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<div style=\"layout-mode: Left;\">");
            sb.append("<p style=\"flex-weight: 1; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<button id=\"cancel-delete\" class=\"custom-textbutton\""
                    + " data-hyui-default-label-style=\"@GreenBtnLabel\""
                    + " data-hyui-hovered-label-style=\"@GreenBtnLabel\""
                    + " data-hyui-default-bg=\"@GreenBtnBg\""
                    + " style=\"anchor-width: 120; anchor-height: 28;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.cancel"))).append("</button>");
            sb.append("<p style=\"anchor-width: 8; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<button id=\"confirm-delete\" class=\"custom-textbutton\""
                    + " data-hyui-default-label-style=\"@DangerBtnLabel\""
                    + " data-hyui-hovered-label-style=\"@DangerBtnLabel\""
                    + " data-hyui-default-bg=\"@DangerBtnBg\""
                    + " style=\"anchor-width: 160; anchor-height: 28;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.delete.button"))).append("</button>");
            sb.append("<p style=\"flex-weight: 1; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("</div>");
            sb.append("</div>");
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        }

        // Rename dialog — shown when pendingRename is set
        if (pendingRename.containsKey(ownerUuid) && binding != null) {
            int renameSlot = pendingRename.get(ownerUuid);
            CompanionProfile renameProfile = null;
            for (CompanionProfile p : binding.getProfiles()) {
                if (p.getSlotId() == renameSlot) { renameProfile = p; break; }
            }
            String currentName = renameProfile != null ? renameProfile.getCompanionName() : "Scruby";

            sb.append("<div id=\"rename-dialog\" style=\"layout-mode: Top; background-color: rgba(80,140,200,0.15) 1; padding: 14;\">");
            sb.append("<p style=\"font-size: 13; color: ").append(ScrubyColors.TEXT_BRIGHT)
              .append("; font-weight: bold; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.rename.title"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<p style=\"font-size: 11; color: ").append(ScrubyColors.TEXT_PRIMARY)
              .append("; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.rename.instruction"))).append("</p>");
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<input type=\"text\" id=\"rename-input\" value=\"").append(esc(currentName))
              .append("\" maxlength=\"12\" placeholder=\"").append(esc(ScrubyLang.get(locale, "ui.scrubys.rename.placeholder"))).append("\" class=\"default-style\""
                    + " style=\"anchor-height: 28; anchor-width: 300; horizontal-align: center;\" />");
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<div style=\"layout-mode: Left;\">");
            sb.append("<p style=\"flex-weight: 1; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<button id=\"cancel-rename\" class=\"custom-textbutton\""
                    + " data-hyui-default-label-style=\"@ScrubySecLabel\""
                    + " data-hyui-hovered-label-style=\"@ScrubySecLabel\""
                    + " data-hyui-pressed-label-style=\"@ScrubySecLabel\""
                    + " data-hyui-default-bg=\"@ScrubySecBg\""
                    + " data-hyui-hovered-bg=\"@ScrubySecHoverBg\""
                    + " data-hyui-pressed-bg=\"@ScrubySecHoverBg\""
                    + " style=\"anchor-width: 120; anchor-height: 28;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.cancel"))).append("</button>");
            sb.append("<p style=\"anchor-width: 8; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<button id=\"confirm-rename\" class=\"custom-textbutton\""
                    + " data-hyui-default-label-style=\"@ScrubySwitchLabel\""
                    + " data-hyui-hovered-label-style=\"@ScrubySwitchLabel\""
                    + " data-hyui-pressed-label-style=\"@ScrubySwitchLabel\""
                    + " data-hyui-default-bg=\"@ScrubySwitchBg\""
                    + " data-hyui-hovered-bg=\"@ScrubySwitchHoverBg\""
                    + " data-hyui-pressed-bg=\"@ScrubySwitchHoverBg\""
                    + " style=\"anchor-width: 140; anchor-height: 28;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.rename.confirm_btn"))).append("</button>");
            sb.append("<p style=\"flex-weight: 1; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("</div>");
            sb.append("</div>");
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        }

        if (binding == null) {
            sb.append("<p style=\"font-size: 13; color: ").append(ScrubyColors.TEXT_MUTED)
              .append("; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.no_data"))).append("</p>");
            sb.append("</div>");
            return sb.toString();
        }

        List<CompanionProfile> profiles = new java.util.ArrayList<>(binding.getProfiles());
        profiles.sort(java.util.Comparator.comparingInt(CompanionProfile::getSlotId));
        int activeSlot = binding.getActiveSlot();
        int maxSlots = binding.getMaxSlots();
        long now = System.currentTimeMillis();
        long cooldownMs = 24L * 60 * 60 * 1000;

        // Build a set of existing slot IDs for quick lookup
        java.util.Set<Integer> existingSlots = new java.util.HashSet<>();
        for (CompanionProfile p : profiles) existingSlots.add(p.getSlotId());

        // Section header
        sb.append("<div style=\"layout-mode: Left; anchor-height: 22;\">");
        sb.append("<p style=\"font-size: 9; color: #3a8a5a; font-weight: bold;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.title"))).append("</p>");
        sb.append("</div>");
        sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

        // YOUR SCRUBYS scroll area
        sb.append("<div style=\"layout-mode: TopScrolling; anchor-height: ").append(350 - scrollReduce).append(";\" data-hyui-scrollbar-style=\"Common.ui DefaultScrollbarStyle\">");

        // Render filled profiles (compact, no gaps)
        java.util.Set<Integer> usedSlots = new java.util.HashSet<>();
        for (CompanionProfile cp : profiles) usedSlots.add(cp.getSlotId());

        for (CompanionProfile p : profiles) {
            int slot = p.getSlotId();
            boolean isActive = (slot == activeSlot);
                boolean isStationed = p.isStationedAtBase();
                String name = p.getCompanionName();
                int level = p.getLevel();
                String path = p.getPathChoice();
                String pathLabel = "NONE".equals(path) ? ScrubyLang.get(locale, "ui.scrubys.no_path") : ScrubyColors.pathBadgeLabel(path, locale);
                String slotHeadAsset = evolutionService.getHeadAssetName(p);

                // Card background: subtle tint for active
                String rowBg = isActive
                        ? "rgba(80,200,120,0.08) 1"
                        : "rgba(255,255,255,0.02) 1";

                // Single row: info left, buttons right
                sb.append("<div style=\"layout-mode: Left; background-color: ").append(rowBg).append("; padding: 6 8; anchor-height: 48;\">");

                // Head portrait — border via background-color suffix (HyUI border mechanism)
                String borderColor = isActive ? ScrubyColors.GREEN_PRIMARY : (isStationed ? ScrubyColors.GOLD : "#2a4a6e");
                sb.append("<div style=\"anchor-width: 36; anchor-height: 36; background-color: ")
                  .append(borderColor).append(" 2; vertical-align: center; layout-mode: MiddleCenter;\">");
                sb.append("<img src=\"").append(slotHeadAsset).append("\" style=\"anchor-width: 32; anchor-height: 32;\" />");
                sb.append("</div>");
                sb.append("<p style=\"anchor-width: 6; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

                // Left: info block
                sb.append("<div style=\"flex-weight: 1; layout-mode: Top; vertical-align: center;\">");
                sb.append("<div style=\"layout-mode: Left; anchor-height: 18;\">");
                sb.append("<p style=\"font-size: 12; color: ").append(ScrubyColors.TEXT_BRIGHT)
                  .append("; font-weight: bold; vertical-align: center;\">").append(esc(name)).append("</p>");
                if (isActive) {
                    sb.append("<div style=\"anchor-width: 50; anchor-height: 16; background-color: rgba(80,200,120,0.2) 1; vertical-align: center; padding-left: 4;\">");
                    sb.append("<p style=\"font-size: 9; color: ").append(ScrubyColors.GREEN_PRIMARY)
                      .append("; font-weight: bold; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.active"))).append("</p>");
                    sb.append("</div>");
                } else if (isStationed) {
                    sb.append("<div style=\"anchor-width: 70; anchor-height: 16; background-color: rgba(200,170,80,0.2) 1; vertical-align: center; padding-left: 4;\">");
                    sb.append("<p style=\"font-size: 9; color: ").append(ScrubyColors.GOLD)
                      .append("; font-weight: bold; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.stationed"))).append("</p>");
                    sb.append("</div>");
                }
                sb.append("</div>");
                // Level + path as colored badges
                sb.append("<div style=\"layout-mode: Left; anchor-height: 16; padding-top: 2;\">");
                sb.append("<div style=\"anchor-width: 40; anchor-height: 14; background-color: rgba(255,255,255,0.06) 1; vertical-align: center;\">");
                sb.append("<p style=\"font-size: 8; color: ").append(ScrubyColors.TEXT_MUTED)
                  .append("; font-weight: bold; text-align: center;\">Lv").append(level).append("</p>");
                sb.append("</div>");
                if (!"NONE".equals(path)) {
                    String pathColor = ScrubyColors.pathAccent(path);
                    sb.append("<div style=\"anchor-width: 54; anchor-height: 14; background-color: rgba(255,255,255,0.04) 1; vertical-align: center; padding-left: 3;\">");
                    sb.append("<p style=\"font-size: 8; color: ").append(pathColor)
                      .append("; font-weight: bold; text-align: center;\">").append(esc(pathLabel)).append("</p>");
                    sb.append("</div>");
                } else {
                    sb.append("<p style=\"font-size: 8; color: ").append(ScrubyColors.TEXT_DISABLED)
                      .append("; padding-left: 3;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.no_path"))).append("</p>");
                }
                sb.append("</div>");
                sb.append("</div>"); // end info block

                // Right: buttons
                sb.append("<div style=\"layout-mode: Left; vertical-align: center;\">");

                // Context button: Wechseln / Stationieren / Zurueckrufen
                if (!isActive && !isStationed) {
                    sb.append("<button id=\"switch-").append(slot).append("\" class=\"custom-textbutton\""
                            + " data-hyui-default-label-style=\"@ScrubySwitchLabel\""
                            + " data-hyui-hovered-label-style=\"@ScrubySwitchLabel\""
                            + " data-hyui-pressed-label-style=\"@ScrubySwitchLabel\""
                            + " data-hyui-default-bg=\"@ScrubySwitchBg\""
                            + " data-hyui-hovered-bg=\"@ScrubySwitchHoverBg\""
                            + " data-hyui-pressed-bg=\"@ScrubySwitchHoverBg\""
                            + " style=\"anchor-width: 110; anchor-height: 26;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.switch"))).append("</button>");
                    sb.append("<p style=\"anchor-width: 4; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
                }
                if (isActive) {
                    String stationBtnKey = isStationed ? "ui.scrubys.stationed" : "ui.scrubys.station";
                    sb.append("<button id=\"station-").append(slot).append("\" class=\"custom-textbutton\""
                            + " data-hyui-default-label-style=\"@ScrubyGoldLabel\""
                            + " data-hyui-hovered-label-style=\"@ScrubyGoldLabel\""
                            + " data-hyui-pressed-label-style=\"@ScrubyGoldLabel\""
                            + " data-hyui-default-bg=\"@ScrubyGoldBg\""
                            + " data-hyui-hovered-bg=\"@ScrubyGoldHoverBg\""
                            + " data-hyui-pressed-bg=\"@ScrubyGoldHoverBg\""
                            + " style=\"anchor-width: 130; anchor-height: 26;\">").append(esc(ScrubyLang.get(locale, stationBtnKey))).append("</button>");
                    sb.append("<p style=\"anchor-width: 4; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
                }
                // Recall button removed from list — only in STATIONIERT section below

                // Umbenennen
                sb.append("<button id=\"rename-").append(slot).append("\" class=\"custom-textbutton\""
                        + " data-hyui-default-label-style=\"@ScrubySecLabel\""
                        + " data-hyui-hovered-label-style=\"@ScrubySecLabel\""
                        + " data-hyui-pressed-label-style=\"@ScrubySecLabel\""
                        + " data-hyui-default-bg=\"@ScrubySecBg\""
                        + " data-hyui-hovered-bg=\"@ScrubySecHoverBg\""
                        + " data-hyui-pressed-bg=\"@ScrubySecHoverBg\""
                        + " style=\"anchor-width: 140; anchor-height: 26;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.rename"))).append("</button>");
                sb.append("<p style=\"anchor-width: 4; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

                // Reset (or cooldown timer)
                long lastRespec = p.getLastRespecTimestamp();
                boolean onCooldown = lastRespec > 0 && (lastRespec + cooldownMs) > now;
                if (onCooldown) {
                    long remainingSecs = ((lastRespec + cooldownMs) - now) / 1000L;
                    long remainingMs = remainingSecs * 1000L;
                    sb.append("<div style=\"anchor-width: 120; anchor-height: 26; background-color: rgba(200,100,100,0.15) 1; layout-mode: MiddleCenter;\">");
                    sb.append("<timer value=\"").append(remainingMs).append("\" format=\"hms\""
                            + " style=\"font-size: 10; color: #e05050"
                            + "; text-align: center; vertical-align: center;\"></timer>");
                    sb.append("</div>");
                } else {
                    sb.append("<button id=\"reset-").append(slot).append("\" class=\"custom-textbutton\""
                            + " data-hyui-default-label-style=\"@ScrubySecLabel\""
                            + " data-hyui-hovered-label-style=\"@ScrubySecLabel\""
                            + " data-hyui-pressed-label-style=\"@ScrubySecLabel\""
                            + " data-hyui-default-bg=\"@ScrubySecBg\""
                            + " data-hyui-hovered-bg=\"@ScrubySecHoverBg\""
                            + " data-hyui-pressed-bg=\"@ScrubySecHoverBg\""
                            + " style=\"anchor-width: 80; anchor-height: 26;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.reset"))).append("</button>");
                }
                sb.append("<p style=\"anchor-width: 4; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

                // Delete (X)
                sb.append("<button id=\"delete-").append(slot).append("\" class=\"custom-textbutton\""
                        + " data-hyui-default-label-style=\"@ScrubyDelLabel\""
                        + " data-hyui-hovered-label-style=\"@ScrubyDelLabel\""
                        + " data-hyui-pressed-label-style=\"@ScrubyDelLabel\""
                        + " data-hyui-default-bg=\"@ScrubyDelBg\""
                        + " data-hyui-hovered-bg=\"@ScrubyDelHoverBg\""
                        + " data-hyui-pressed-bg=\"@ScrubyDelHoverBg\""
                        + " style=\"anchor-width: 36; anchor-height: 26;\">X</button>");

                sb.append("</div>"); // end buttons
                sb.append("</div>"); // end row

            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        }

        // Single empty create-slot, or "all slots full"
        if (profiles.size() < maxSlots) {
            int firstFreeSlot = 0;
            for (int i = 0; i < maxSlots; i++) {
                if (!usedSlots.contains(i)) { firstFreeSlot = i; break; }
            }
            sb.append("<div style=\"layout-mode: Left; anchor-height: 40; background-color: rgba(255,255,255,0.02) 1; padding: 4 8;\">");
            sb.append("<div style=\"flex-weight: 1;\"><p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p></div>");
            sb.append("<button id=\"create-").append(firstFreeSlot).append("\" class=\"custom-textbutton\""
                    + " data-hyui-default-label-style=\"@ScrubyCreateLabel\""
                    + " data-hyui-hovered-label-style=\"@ScrubyCreateLabel\""
                    + " data-hyui-pressed-label-style=\"@ScrubyCreateLabel\""
                    + " data-hyui-default-bg=\"@ScrubyCreateBg\""
                    + " data-hyui-hovered-bg=\"@ScrubyCreateHoverBg\""
                    + " data-hyui-pressed-bg=\"@ScrubyCreateHoverBg\""
                    + " style=\"anchor-width: 210; anchor-height: 28; vertical-align: center;\">")
                    .append(esc(ScrubyLang.get(locale, "ui.scrubys.create"))).append("</button>");
            sb.append("</div>");
        } else {
            sb.append("<div style=\"layout-mode: Left; anchor-height: 40; background-color: rgba(255,255,255,0.02) 1; padding: 4 8;\">");
            sb.append("<p style=\"flex-weight: 1; font-size: 11; color: ").append(ScrubyColors.TEXT_DISABLED)
              .append("; vertical-align: center; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.all_slots_full"))).append("</p>");
            sb.append("</div>");
        }
        sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        sb.append("</div>"); // end YOUR SCRUBYS scroll area

        // Station info section — list ALL stationed companions
        java.util.List<CompanionProfile> stationedProfiles = new java.util.ArrayList<>();
        for (CompanionProfile p : profiles) {
            if (p.isStationedAtBase()) stationedProfiles.add(p);
        }
        sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        sb.append("<div style=\"layout-mode: TopScrolling; anchor-height: ").append(350 - scrollReduce).append("; background-color: rgba(200,170,80,0.08) 1; padding: 8;\" data-hyui-scrollbar-style=\"Common.ui DefaultScrollbarStyle\">");
        sb.append("<div style=\"layout-mode: Left; anchor-height: 22;\">");
        sb.append("<p style=\"font-size: 9; color: ").append(ScrubyColors.GOLD)
          .append("; font-weight: bold;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.stationed_header"))).append("</p>");
        sb.append("</div>");

        if (!stationedProfiles.isEmpty()) {

            for (CompanionProfile sp : stationedProfiles) {
                String stPath = sp.getPathChoice();
                String stPathLabel = "NONE".equals(stPath) ? ScrubyLang.get(locale, "ui.scrubys.no_path") : ScrubyColors.pathBadgeLabel(stPath, locale);
                String stHeadAsset = evolutionService.getHeadAssetName(sp);
                int stLevel = sp.getLevel();

                sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
                sb.append("<div style=\"layout-mode: Left; background-color: rgba(255,255,255,0.02) 1; padding: 6 8; anchor-height: 48;\">");

                // Head portrait with gold border
                sb.append("<div style=\"anchor-width: 36; anchor-height: 36; background-color: ")
                  .append(ScrubyColors.GOLD).append(" 2; vertical-align: center; layout-mode: MiddleCenter;\">");
                sb.append("<img src=\"").append(stHeadAsset).append("\" style=\"anchor-width: 32; anchor-height: 32;\" />");
                sb.append("</div>");
                sb.append("<p style=\"anchor-width: 6; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

                // Info block: name + level/path
                sb.append("<div style=\"flex-weight: 1; layout-mode: Top; vertical-align: center;\">");
                sb.append("<div style=\"layout-mode: Left; anchor-height: 18;\">");
                sb.append("<p style=\"font-size: 12; color: ").append(ScrubyColors.TEXT_BRIGHT)
                  .append("; font-weight: bold; vertical-align: center;\">").append(esc(sp.getCompanionName())).append("</p>");
                sb.append("</div>");
                // Level + path badges
                sb.append("<div style=\"layout-mode: Left; anchor-height: 16; padding-top: 2;\">");
                sb.append("<div style=\"anchor-width: 40; anchor-height: 14; background-color: rgba(255,255,255,0.06) 1; vertical-align: center;\">");
                sb.append("<p style=\"font-size: 8; color: ").append(ScrubyColors.TEXT_MUTED)
                  .append("; font-weight: bold; text-align: center;\">Lv").append(stLevel).append("</p>");
                sb.append("</div>");
                if (!"NONE".equals(stPath)) {
                    String stPathColor = ScrubyColors.pathAccent(stPath);
                    sb.append("<div style=\"anchor-width: 54; anchor-height: 14; background-color: rgba(255,255,255,0.04) 1; vertical-align: center; padding-left: 3;\">");
                    sb.append("<p style=\"font-size: 8; color: ").append(stPathColor)
                      .append("; font-weight: bold; text-align: center;\">").append(esc(stPathLabel)).append("</p>");
                    sb.append("</div>");
                } else {
                    sb.append("<p style=\"font-size: 8; color: ").append(ScrubyColors.TEXT_DISABLED)
                      .append("; padding-left: 3;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.no_path"))).append("</p>");
                }
                sb.append("</div>");
                sb.append("</div>"); // end info block

                // Station mode icons (rally-the-troops = aggressive, player-base = defensive)
                boolean stAggressive = sp.isAggressive();
                sb.append("<button id=\"st-aggro-").append(sp.getSlotId()).append("\" class=\"custom-textbutton\"")
                  .append(" data-hyui-default-label-style=\"@CombatBtnLabel\"")
                  .append(" data-hyui-hovered-label-style=\"@CombatBtnLabel\"")
                  .append(" data-hyui-pressed-label-style=\"@CombatBtnLabel\"")
                  .append(" data-hyui-default-bg=\"").append(stAggressive ? "@StRallyChosenBg" : "@StRallyBg").append("\"")
                  .append(" data-hyui-hovered-bg=\"@StRallyChosenBg\"")
                  .append(" data-hyui-pressed-bg=\"@StRallyChosenBg\"")
                  .append(" style=\"anchor-width: 28; anchor-height: 28; vertical-align: center;\"> </button>");
                sb.append("<div style=\"anchor-width: 3;\"><p style=\"font-size: 1;\"> </p></div>");
                sb.append("<button id=\"st-def-").append(sp.getSlotId()).append("\" class=\"custom-textbutton\"")
                  .append(" data-hyui-default-label-style=\"@CombatBtnLabel\"")
                  .append(" data-hyui-hovered-label-style=\"@CombatBtnLabel\"")
                  .append(" data-hyui-pressed-label-style=\"@CombatBtnLabel\"")
                  .append(" data-hyui-default-bg=\"").append(!stAggressive ? "@StBaseChosenBg" : "@StBaseBg").append("\"")
                  .append(" data-hyui-hovered-bg=\"@StBaseChosenBg\"")
                  .append(" data-hyui-pressed-bg=\"@StBaseChosenBg\"")
                  .append(" style=\"anchor-width: 28; anchor-height: 28; vertical-align: center;\"> </button>");
                sb.append("<div style=\"anchor-width: 6;\"><p style=\"font-size: 1;\"> </p></div>");

                // Recall button (unchanged)
                sb.append("<button id=\"recall-").append(sp.getSlotId()).append("\" class=\"custom-textbutton\""
                        + " data-hyui-default-label-style=\"@ScrubyGoldLabel\""
                        + " data-hyui-hovered-label-style=\"@ScrubyGoldLabel\""
                        + " data-hyui-pressed-label-style=\"@ScrubyGoldLabel\""
                        + " data-hyui-default-bg=\"@ScrubyGoldBg\""
                        + " data-hyui-hovered-bg=\"@ScrubyGoldHoverBg\""
                        + " data-hyui-pressed-bg=\"@ScrubyGoldHoverBg\""
                        + " style=\"anchor-width: 130; anchor-height: 28; vertical-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.recall"))).append("</button>");
                sb.append("</div>");
            }
            sb.append("</div>");
        } else {
            sb.append("<p style=\"font-size: 11; color: ").append(ScrubyColors.TEXT_DISABLED)
              .append("; text-align: center; padding-top: 20;\">").append(esc(ScrubyLang.get(locale, "ui.scrubys.no_stationed"))).append("</p>");
        }
        sb.append("</div>"); // end STATIONED scroll area

        // Spacer to push language selector to the bottom
        sb.append("<div style=\"flex-weight: 1;\"><p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p></div>");

        // --- Language selector (bottom-left) ---
        String currentLocale = "en";
        if (binding != null) {
            CompanionProfile activeProf = binding.getActiveProfile();
            if (activeProf != null) currentLocale = activeProf.getLocale();
        }
        String langLabel = ScrubyLang.get(currentLocale, "ui.lang.label");
        sb.append("<div style=\"layout-mode: Left; anchor-height: 28; padding-bottom: 4; padding-left: 4;\">");
        sb.append("<p style=\"font-size: 11; color: ").append(ScrubyColors.TEXT_MUTED)
          .append("; font-weight: bold; vertical-align: center;\">").append(esc(langLabel)).append(":  </p>");
        // DE button
        sb.append("<button id=\"lang-de\" class=\"custom-textbutton\"");
        if ("de".equals(currentLocale)) {
            sb.append(" data-hyui-default-label-style=\"@ScrubySwitchLabel\"")
              .append(" data-hyui-hovered-label-style=\"@ScrubySwitchLabel\"")
              .append(" data-hyui-pressed-label-style=\"@ScrubySwitchLabel\"")
              .append(" data-hyui-default-bg=\"@ScrubySwitchBg\"")
              .append(" data-hyui-hovered-bg=\"@ScrubySwitchHoverBg\"")
              .append(" data-hyui-pressed-bg=\"@ScrubySwitchHoverBg\"");
        } else {
            sb.append(" data-hyui-default-label-style=\"@ScrubySecLabel\"")
              .append(" data-hyui-hovered-label-style=\"@ScrubySecLabel\"")
              .append(" data-hyui-pressed-label-style=\"@ScrubySecLabel\"")
              .append(" data-hyui-default-bg=\"@ScrubySecBg\"")
              .append(" data-hyui-hovered-bg=\"@ScrubySecHoverBg\"")
              .append(" data-hyui-pressed-bg=\"@ScrubySecHoverBg\"");
        }
        sb.append(" style=\"anchor-width: 120; anchor-height: 24;\">Deutsch</button>");
        sb.append("<p style=\"anchor-width: 4; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        // EN button
        sb.append("<button id=\"lang-en\" class=\"custom-textbutton\"");
        if ("en".equals(currentLocale)) {
            sb.append(" data-hyui-default-label-style=\"@ScrubySwitchLabel\"")
              .append(" data-hyui-hovered-label-style=\"@ScrubySwitchLabel\"")
              .append(" data-hyui-pressed-label-style=\"@ScrubySwitchLabel\"")
              .append(" data-hyui-default-bg=\"@ScrubySwitchBg\"")
              .append(" data-hyui-hovered-bg=\"@ScrubySwitchHoverBg\"")
              .append(" data-hyui-pressed-bg=\"@ScrubySwitchHoverBg\"");
        } else {
            sb.append(" data-hyui-default-label-style=\"@ScrubySecLabel\"")
              .append(" data-hyui-hovered-label-style=\"@ScrubySecLabel\"")
              .append(" data-hyui-pressed-label-style=\"@ScrubySecLabel\"")
              .append(" data-hyui-default-bg=\"@ScrubySecBg\"")
              .append(" data-hyui-hovered-bg=\"@ScrubySecHoverBg\"")
              .append(" data-hyui-pressed-bg=\"@ScrubySecHoverBg\"");
        }
        sb.append(" style=\"anchor-width: 120; anchor-height: 24;\">English</button>");
        sb.append("</div>");

        sb.append("</div>"); // end outer padding div
        return sb.toString();
    }

    // ===== Scrubys Event Listeners =====

    private void registerScrubysListeners(
            @Nonnull PageBuilder builder,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nullable ScrubyOwnerBindingComponent binding,
            @Nullable Ref<EntityStore> companionRef
    ) {
        if (binding == null) return;

        List<CompanionProfile> profiles = binding.getProfiles();
        int maxSlots = binding.getMaxSlots();

        for (int slot = 0; slot < maxSlots; slot++) {
            final int s = slot;
            CompanionProfile slotProfile = null;
            for (CompanionProfile p : profiles) {
                if (p.getSlotId() == s) { slotProfile = p; break; }
            }

            if (slotProfile == null) {
                // === Erstellen (create-[slot]) ===
                tryAddListener(builder, "create-" + s, (event) -> {
                    try {
                        bindingService.addCompanion(store, ownerRef);
                        soundService.playBind(playerRef);
                        ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);
                    } catch (Exception e) {
                        LOGGER.atInfo().log("[Scruby-Page] Create failed: " + e.getMessage());
                    }
                    reopenScrubys(store, ownerRef, playerRef);
                });
                continue;
            }

            final CompanionProfile sp = slotProfile;
            final boolean isActiveSlot = (s == binding.getActiveSlot());
            final boolean isStationed = sp.isStationedAtBase();

            // === Wechseln (switch-[slot]) ===
            if (!isActiveSlot && !isStationed) {
                tryAddListener(builder, "switch-" + s, (event) -> {
                    // Despawn current active companion — keep stationed NPCs in world
                    CompanionProfile currentProfile = binding.getActiveProfile();
                    Ref<EntityStore> currentRef = companionRegistry.getCompanionRef(ownerUuid);
                    if (currentProfile != null && currentRef != null && currentRef.isValid()) {
                        if (!currentProfile.isStationedAtBase()) {
                            // Not stationed: fully remove NPC
                            NPCEntity npcEntity = store.getComponent(currentRef, NPCEntity.getComponentType());
                            if (npcEntity != null) {
                                resetService.clearLockedTarget(store, currentRef);
                                npcEntity.remove();
                            }
                            currentProfile.setCompanionEntityUuid("");
                            currentProfile.setEntityMissing(false);
                            currentProfile.setManuallyDespawned(true);
                            binding.setActiveProfile(currentProfile);
                        }
                        // Always unregister from registry so new companion can spawn
                        companionRegistry.unregister(ownerUuid);
                    }
                    hudManager.removeHud(store, ownerRef, playerRef, ownerUuid);

                    // Switch slot
                    CompanionProfile newProfile = bindingService.switchSlot(store, ownerRef, s);
                    if (newProfile == null) {
                        reopenScrubys(store, ownerRef, playerRef);
                        return;
                    }

                    // Clear despawn flag so HUD can be created
                    newProfile.setManuallyDespawned(false);
                    newProfile.setEntityMissing(false);
                    binding.setActiveProfile(newProfile);

                    soundService.playSlotSwitch(playerRef);

                    // Auto-spawn if not stationed (HUD created by TickingSystem)
                    if (!newProfile.isStationedAtBase()) {
                        spawnService.spawnCompanion(store, ownerRef, playerRef, ownerUuid, true);
                    }

                    reopenScrubys(store, ownerRef, playerRef);
                });
            }

            // === Stationieren (station-[slot]) ===
            if (isActiveSlot) {
                tryAddListener(builder, "station-" + s, (event) -> {
                    CompanionProfile activeProfile = binding.getActiveProfile();
                    if (activeProfile.isStationedAtBase()) {
                        reopenScrubys(store, ownerRef, playerRef);
                        return;
                    }

                    // Check companion is spawned
                    Ref<EntityStore> cRef = companionRegistry.getCompanionRef(ownerUuid);
                    if (cRef == null || !cRef.isValid()) {
                        soundService.playError(playerRef);
                        reopenScrubys(store, ownerRef, playerRef);
                        return;
                    }

                    // Get Player entity for bed position lookup
                    Player player = store.getComponent(ownerRef, Player.getComponentType());
                    if (player == null) {
                        soundService.playError(playerRef);
                        reopenScrubys(store, ownerRef, playerRef);
                        return;
                    }

                    // Get world name from Player entity
                    String worldName;
                    try {
                        worldName = player.getWorld().getName();
                    } catch (Exception e) {
                        worldName = "default";
                    }

                    boolean success = baseStationService.stationAtBed(player, playerRef, activeProfile, worldName);
                    if (!success) {
                        soundService.playError(playerRef);
                        reopenScrubys(store, ownerRef, playerRef);
                        return;
                    }

                    // Despawn and respawn as stationed (UUID is updated inside respawnAsStationed)
                    baseStationService.respawnAsStationed(store, ownerUuid, activeProfile);
                    binding.setActiveProfile(activeProfile);
                    hudManager.removeHud(store, ownerRef, playerRef, ownerUuid);

                    soundService.playStation(playerRef,
                            activeProfile.getStationX(), activeProfile.getStationY(), activeProfile.getStationZ());

                    reopenScrubys(store, ownerRef, playerRef);
                });
            }

            // === Zurueckrufen (recall-[slot]) ===
            if (isStationed) {
                tryAddListener(builder, "recall-" + s, (event) -> {
                    CompanionProfile recallProfile;
                    if (isActiveSlot) {
                        recallProfile = binding.getActiveProfile();
                    } else {
                        // Need to switch to this slot first, then recall
                        // Despawn current active companion if spawned
                        Ref<EntityStore> currentRef = companionRegistry.getCompanionRef(ownerUuid);
                        if (currentRef != null && currentRef.isValid()) {
                            NPCEntity npcEntity = store.getComponent(currentRef, NPCEntity.getComponentType());
                            if (npcEntity != null) {
                                resetService.clearLockedTarget(store, currentRef);
                                npcEntity.remove();
                            }
                            CompanionProfile currentProfile = binding.getActiveProfile();
                            currentProfile.setCompanionEntityUuid("");
                            currentProfile.setEntityMissing(false);
                            currentProfile.setManuallyDespawned(true);
                            binding.setActiveProfile(currentProfile);
                            companionRegistry.unregister(ownerUuid);
                            hudManager.removeHud(store, ownerRef, playerRef, ownerUuid);
                        }
                        CompanionProfile switched = bindingService.switchSlot(store, ownerRef, s);
                        if (switched == null) {
                            reopenScrubys(store, ownerRef, playerRef);
                            return;
                        }
                        recallProfile = switched;
                    }

                    if (!recallProfile.isStationedAtBase()) {
                        reopenScrubys(store, ownerRef, playerRef);
                        return;
                    }

                    // Remove the stationed entity from the world before respawning
                    // (registry may not track it if we switched slots)
                    String stationedEntityUuid = recallProfile.getCompanionEntityUuid();
                    if (stationedEntityUuid != null && !stationedEntityUuid.isEmpty()) {
                        try {
                            UUID entityUuid = UUID.fromString(stationedEntityUuid);
                            Ref<EntityStore> stationedRef = store.getExternalData().getRefFromUUID(entityUuid);
                            if (stationedRef != null && stationedRef.isValid()) {
                                NPCEntity npc = store.getComponent(stationedRef, NPCEntity.getComponentType());
                                if (npc != null) npc.remove();
                            }
                        } catch (Exception ignored) {}
                    }

                    baseStationService.recallFromStation(recallProfile);
                    baseStationService.respawnAsFollowing(store, ownerUuid, playerRef, recallProfile);
                    binding.setActiveProfile(recallProfile);

                    // HUD will be created by TickingSystem on next tick

                    com.hypixel.hytale.math.vector.Vector3d playerPos = playerRef.getTransform().getPosition();
                    soundService.playRecall(playerRef, playerPos.getX(), playerPos.getY(), playerPos.getZ());

                    reopenScrubys(store, ownerRef, playerRef);
                });
            }

            // === Station combat mode (st-aggro-[slot] / st-def-[slot]) ===
            if (isStationed) {
                tryAddListener(builder, "st-aggro-" + s, (event) -> {
                    switchStationedCombatMode(store, ownerRef, playerRef, ownerUuid, s, "AGGRESSIVE");
                });
                tryAddListener(builder, "st-def-" + s, (event) -> {
                    switchStationedCombatMode(store, ownerRef, playerRef, ownerUuid, s, "PASSIVE");
                });
            }

            // === Umbenennen (rename-[slot]) — opens rename dialog ===
            tryAddListener(builder, "rename-" + s, (event) -> {
                pendingRename.put(ownerUuid, s);
                pendingRenameText.remove(ownerUuid);
                reopenScrubys(store, ownerRef, playerRef);
            });

            // === Delete (delete-[slot]) — opens confirm dialog ===
            tryAddListener(builder, "delete-" + s, (event) -> {
                pendingDelete.put(ownerUuid, s);
                reopenScrubys(store, ownerRef, playerRef);
            });

            // === Reset (reset-[slot]) ===
            long now = System.currentTimeMillis();
            long cooldownMs = 24L * 60 * 60 * 1000;
            long lastRespec = sp.getLastRespecTimestamp();
            boolean onCooldown = lastRespec > 0 && (lastRespec + cooldownMs) > now;
            if (!onCooldown) {
                tryAddListener(builder, "reset-" + s, (event) -> {
                    pendingReset.put(ownerUuid, s);
                    reopenScrubys(store, ownerRef, playerRef);
                });
            }
        }

        // === Confirm/Cancel Reset (Enter/Escape supported) ===
        if (pendingReset.containsKey(ownerUuid)) {
            int resetSlot = pendingReset.get(ownerUuid);
            CompanionProfile resetProfile = null;
            for (CompanionProfile p : profiles) {
                if (p.getSlotId() == resetSlot) { resetProfile = p; break; }
            }
            final CompanionProfile rp = resetProfile;
            if (rp != null) {
                Runnable confirmResetAction = () -> {
                    // Class (pathChoice) and fixed path abilities are permanent — NOT reset here.
                    // Only attribute points and chosen ability picks are rolled back.
                    rp.setChosenAbilities("");
                    rp.setAttributeVitality(0);
                    rp.setAttributeStrength(0);
                    rp.setAttributeZeal(0);
                    rp.setAttributePointsAvailable(rp.getLevel() * 2);
                    rp.setLastRespecTimestamp(System.currentTimeMillis());
                    Ref<EntityStore> resetCompRef = companionRegistry.getCompanionRef(ownerUuid);
                    if (resetCompRef != null && resetCompRef.isValid() && resetSlot == binding.getActiveSlot()) {
                        attributeService.removeAttributes(store, resetCompRef);
                        skillService.removeAllSkillModifiers(store, resetCompRef);
                        // Re-apply fixed path skills — they survive the reset.
                        skillService.applyPassiveSkills(store, resetCompRef, rp);
                    }
                    List<CompanionProfile> updated = binding.getProfiles();
                    for (int i = 0; i < updated.size(); i++) {
                        if (updated.get(i).getSlotId() == resetSlot) { updated.set(i, rp); break; }
                    }
                    binding.setProfiles(updated);
                    pendingReset.remove(ownerUuid);
                    playerDrafts.remove(ownerUuid);
                    soundService.playRespec(playerRef);
                    reopenScrubys(store, ownerRef, playerRef);
                };
                Runnable cancelResetAction = () -> {
                    pendingReset.remove(ownerUuid);
                    reopenScrubys(store, ownerRef, playerRef);
                };
                tryAddListener(builder, "confirm-reset", (event) -> confirmResetAction.run());
                tryAddListener(builder, "cancel-reset", (event) -> cancelResetAction.run());
                tryAddDialogKeyListener(builder, "reset-dialog", confirmResetAction, cancelResetAction);
            }
        }

        // === Confirm/Cancel Delete (Enter/Escape supported) ===
        if (pendingDelete.containsKey(ownerUuid)) {
            int deleteSlot = pendingDelete.get(ownerUuid);
            Runnable confirmDeleteAction = () -> {
                pendingDelete.remove(ownerUuid);
                if (deleteSlot == binding.getActiveSlot()) {
                    Ref<EntityStore> delRef = companionRegistry.getCompanionRef(ownerUuid);
                    if (delRef != null && delRef.isValid()) {
                        NPCEntity npcEntity = store.getComponent(delRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            resetService.clearLockedTarget(store, delRef);
                            npcEntity.remove();
                        }
                        companionRegistry.unregister(ownerUuid);
                        hudManager.removeHud(store, ownerRef, playerRef, ownerUuid);
                    }
                }
                boolean deleted = bindingService.deleteSlot(store, ownerRef, deleteSlot);
                if (deleted) {
                    soundService.playDelete(playerRef);
                    ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);
                }
                reopenScrubys(store, ownerRef, playerRef);
            };
            Runnable cancelDeleteAction = () -> {
                pendingDelete.remove(ownerUuid);
                reopenScrubys(store, ownerRef, playerRef);
            };
            tryAddListener(builder, "confirm-delete", (event) -> confirmDeleteAction.run());
            tryAddListener(builder, "cancel-delete", (event) -> cancelDeleteAction.run());
            tryAddDialogKeyListener(builder, "delete-dialog", confirmDeleteAction, cancelDeleteAction);
        }

        // === Confirm/Cancel Rename (Enter/Escape supported) ===
        if (pendingRename.containsKey(ownerUuid)) {
            int renameSlot = pendingRename.get(ownerUuid);

            // Track text input changes
            try {
                builder.addEventListener("rename-input", CustomUIEventBindingType.ValueChanged, (data, ctx) -> {
                    String newText = String.valueOf(data);
                    pendingRenameText.put(ownerUuid, newText);
                });
            } catch (Exception ignored) {}

            Runnable confirmRenameAction = () -> {
                String newName = pendingRenameText.getOrDefault(ownerUuid, "");
                if (newName.length() >= 3 && newName.length() <= 12) {
                    ScrubyOwnerBindingComponent freshBinding = bindingService.getBindingOrNull(store, ownerRef);
                    if (freshBinding != null) {
                        java.util.List<CompanionProfile> allProfiles = freshBinding.getProfiles();
                        for (CompanionProfile p : allProfiles) {
                            if (p.getSlotId() == renameSlot) { p.setCompanionName(newName); break; }
                        }
                        freshBinding.setProfiles(allProfiles);
                        if (renameSlot == freshBinding.getActiveSlot()) {
                            CompanionProfile activeProfile = freshBinding.getActiveProfile();
                            activeProfile.setCompanionName(newName);
                            freshBinding.setActiveProfile(activeProfile);
                        }
                    }
                }
                pendingRename.remove(ownerUuid);
                pendingRenameText.remove(ownerUuid);
                reopenScrubys(store, ownerRef, playerRef);
            };
            Runnable cancelRenameAction = () -> {
                pendingRename.remove(ownerUuid);
                pendingRenameText.remove(ownerUuid);
                reopenScrubys(store, ownerRef, playerRef);
            };
            tryAddListener(builder, "confirm-rename", (event) -> confirmRenameAction.run());
            tryAddListener(builder, "cancel-rename", (event) -> cancelRenameAction.run());
            tryAddDialogKeyListener(builder, "rename-dialog", confirmRenameAction, cancelRenameAction);
            // Also confirm on Enter in the text input
            try {
                builder.addEventListener("rename-input", CustomUIEventBindingType.Validating, (event) -> confirmRenameAction.run());
            } catch (Exception ignored) {}
        }

        // Language selector — applies to all profiles in the binding so that
        // /scruby-switch and other slot changes preserve the player's language.
        tryAddListener(builder, "lang-de", ignored -> {
            ScrubyOwnerBindingComponent b = bindingService.getBindingOrNull(store, ownerRef);
            if (b != null) {
                applyLocaleToAllProfiles(b, "de");
            }
            reopenScrubys(store, ownerRef, playerRef);
        });

        tryAddListener(builder, "lang-en", ignored -> {
            ScrubyOwnerBindingComponent b = bindingService.getBindingOrNull(store, ownerRef);
            if (b != null) {
                applyLocaleToAllProfiles(b, "en");
            }
            reopenScrubys(store, ownerRef, playerRef);
        });
    }

    /**
     * Sets the locale on every profile in the binding. Locale is conceptually
     * player-wide (like hudPosition / muteFlags) but lives on CompanionProfile
     * for historical reasons; syncing all profiles keeps the language stable
     * across /scruby-switch and slot changes.
     */
    private static void applyLocaleToAllProfiles(
            @Nonnull ScrubyOwnerBindingComponent binding,
            @Nonnull String locale
    ) {
        java.util.List<CompanionProfile> profiles = binding.getProfiles();
        for (CompanionProfile p : profiles) {
            p.setLocale(locale);
        }
        binding.setProfiles(profiles);
    }

    // ===== Inventory Listeners =====

    private void registerInventoryListeners(
            @Nonnull PageBuilder builder,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile
    ) {
        tryAddListener(builder, "btn-transfer", (event) -> {
            pendingTab.put(ownerUuid, "transfer");
            reopen(store, ownerRef, playerRef, profile);
        });

        // ── Skilltree inventory: hover tracking for drag-and-drop ──
        builder.addEventListener("scruby-inv-grid", CustomUIEventBindingType.SlotMouseEntered,
                SlotMouseEnteredEventData.class, (event, ctx) -> {
            Integer slot = event.getSlotIndex();
            if (slot != null && slot >= 0) {
                lastHoveredScrubySlot.put(ownerUuid, slot);
            }
        });

        // ── Skilltree inventory: internal reorder via click-click (deferred) ──
        builder.addEventListener("scruby-inv-grid", CustomUIEventBindingType.SlotClickPressWhileDragging,
                SlotClickPressWhileDraggingEventData.class, (event, ctx) -> {
            Integer targetSlot = event.getSlotIndex();
            Integer sourceSection = event.getDragSourceInventorySectionId();
            Integer sourceSlot = event.getDragSourceSlotId();
            if (targetSlot == null || targetSlot < 0 || sourceSection == null || sourceSlot == null) return;
            if (sourceSection == 100 && !sourceSlot.equals(targetSlot)) {
                pendingScrubyMove.put(ownerUuid, new int[]{sourceSlot, targetSlot});
            }
        });

        // ── Skilltree inventory: confirm internal move via Dropped ──
        builder.addEventListener("scruby-inv-grid", CustomUIEventBindingType.Dropped,
                DroppedEventData.class, (drop, ctx) -> {
            Integer sourceSection = drop.getSourceInventorySectionId();
            if (sourceSection == null || sourceSection != 100) return;

            // Determine source and target slots (click-click or drag-and-drop)
            int srcSlot, tgtSlot;
            int[] pending = pendingScrubyMove.remove(ownerUuid);
            if (pending != null) {
                srcSlot = pending[0];
                tgtSlot = pending[1];
            } else {
                Integer hovered = lastHoveredScrubySlot.remove(ownerUuid);
                Integer source = drop.getSourceSlotId();
                if (hovered == null || hovered < 0 || source == null || source.equals(hovered)) return;
                srcSlot = source;
                tgtSlot = hovered;
            }

            // Execute the move using InventoryService directly (no tab switch)
            try {
                ScrubyInventoryService.InventoryEntry srcEntry = inventoryService.getItemAt(profile, srcSlot);
                ScrubyInventoryService.InventoryEntry tgtEntry = inventoryService.getItemAt(profile, tgtSlot);
                if (srcEntry == null) return;

                if (tgtEntry == null) {
                    inventoryService.removeItem(profile, srcSlot);
                    inventoryService.addItemToSlot(profile, srcEntry.getItemId(),
                            srcEntry.getQuantity(), tgtSlot, ScrubyInventoryService.DEFAULT_MAX_STACK);
                } else if (tgtEntry.getItemId().equals(srcEntry.getItemId())) {
                    int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;
                    int canAdd = maxStack - tgtEntry.getQuantity();
                    if (canAdd <= 0) return;
                    int toMove = Math.min(srcEntry.getQuantity(), canAdd);
                    inventoryService.removeItem(profile, srcSlot);
                    inventoryService.addItemToSlot(profile, srcEntry.getItemId(), toMove, tgtSlot, maxStack);
                    int leftover = srcEntry.getQuantity() - toMove;
                    if (leftover > 0) {
                        inventoryService.addItemToSlot(profile, srcEntry.getItemId(), leftover, srcSlot, maxStack);
                    }
                } else {
                    inventoryService.swapSlots(profile, srcSlot, tgtSlot);
                }

                // Persist profile
                ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
                if (binding != null) binding.updateProfileBySlot(profile);

                // Try fast in-place grid update; fall back to full reopen if it fails
                String locale = profile.getLocale() != null ? profile.getLocale() : "en";
                if (!tryInPlaceGridUpdate(ctx, profile, locale)) {
                    reopen(store, ownerRef, playerRef, profile);
                }
            } catch (Exception e) {
                LOGGER.atInfo().log("[Scruby-Inv] Skilltree internal move failed: " + e.getMessage());
            }
        });

        // ── Scruby inventory: double-click armor item to auto-equip ──
        builder.addEventListener("scruby-inv-grid", CustomUIEventBindingType.SlotDoubleClicking,
                SlotDoubleClickingEventData.class, (event, ctx) -> {
            Integer slotIdx = event.getSlotIndex();
            if (slotIdx == null || slotIdx < 0) return;

            ScrubyInventoryService.InventoryEntry entry = inventoryService.getItemAt(profile, slotIdx);
            if (entry == null) return;
            String itemId = entry.getItemId();

            // Find matching armor slot
            int targetArmorSlot = -1;
            for (int i = 0; i < ScrubyArmorService.SLOT_COUNT; i++) {
                if (armorService.isValidArmorForSlot(itemId, i)) {
                    targetArmorSlot = i;
                    break;
                }
            }
            if (targetArmorSlot < 0) return; // not an armor item

            String currentEquip = armorService.getEquippedItem(profile, targetArmorSlot);

            // Remove 1 from inventory
            inventoryService.removeItem(profile, slotIdx);
            if (entry.getQuantity() > 1) {
                inventoryService.addItemToSlot(profile, itemId, entry.getQuantity() - 1, slotIdx, ScrubyInventoryService.DEFAULT_MAX_STACK);
            }

            // If slot occupied, put old armor back into inventory at the same slot
            if (!currentEquip.isEmpty()) {
                inventoryService.addItemToSlot(profile, currentEquip, 1, slotIdx, 1);
            }

            // Equip new armor
            armorService.setEquippedItem(profile, targetArmorSlot, itemId);
            reapplyArmorStats(store, ownerUuid, profile);

            ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
            if (binding != null) binding.updateProfileBySlot(profile);
            reopen(store, ownerRef, playerRef, profile);
        });

        // ── Armor grid: hover tracking ──
        builder.addEventListener("scruby-armor-grid", CustomUIEventBindingType.SlotMouseEntered,
                SlotMouseEnteredEventData.class, (event, ctx) -> {
            Integer slot = event.getSlotIndex();
            if (slot != null && slot >= 0) {
                lastHoveredArmorSlot.put(ownerUuid, slot);
            }
        });

        // ── Armor grid: equip from Scruby inventory (Dropped) ──
        builder.addEventListener("scruby-armor-grid", CustomUIEventBindingType.Dropped,
                DroppedEventData.class, (drop, ctx) -> {
            Integer sourceSection = drop.getSourceInventorySectionId();
            if (sourceSection == null) return;

            if (sourceSection == 100) {
                // Scruby inventory -> Armor slot
                Integer sourceSlot = drop.getSourceSlotId();
                Integer targetSlot = lastHoveredArmorSlot.remove(ownerUuid);
                if (sourceSlot == null || targetSlot == null || targetSlot < 0 || targetSlot >= ScrubyArmorService.SLOT_COUNT) return;

                ScrubyInventoryService.InventoryEntry srcEntry = inventoryService.getItemAt(profile, sourceSlot);
                if (srcEntry == null) return;
                String itemId = srcEntry.getItemId();

                // Validate armor type matches slot
                if (!armorService.isValidArmorForSlot(itemId, targetSlot)) {
                    String locale = profile.getLocale() != null ? profile.getLocale() : "en";
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "ui.armor.wrong_slot")));
                    return;
                }

                // Check if slot already has armor (swap)
                String currentEquip = armorService.getEquippedItem(profile, targetSlot);
                if (!currentEquip.isEmpty()) {
                    int remaining = inventoryService.smartStack(profile, currentEquip, 1, 1);
                    if (remaining > 0) {
                        String locale = profile.getLocale() != null ? profile.getLocale() : "en";
                        playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "ui.transfer.backpack_full")));
                        return;
                    }
                }

                // Remove 1 from inventory stack
                inventoryService.removeItem(profile, sourceSlot);
                if (srcEntry.getQuantity() > 1) {
                    inventoryService.addItemToSlot(profile, itemId, srcEntry.getQuantity() - 1, sourceSlot, ScrubyInventoryService.DEFAULT_MAX_STACK);
                }

                // Equip
                armorService.setEquippedItem(profile, targetSlot, itemId);
                reapplyArmorStats(store, ownerUuid, profile);

                ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
                if (binding != null) binding.updateProfileBySlot(profile);
                reopen(store, ownerRef, playerRef, profile);
            }
        });

        // ── Armor grid: double-click to unequip ──
        builder.addEventListener("scruby-armor-grid", CustomUIEventBindingType.SlotDoubleClicking,
                SlotDoubleClickingEventData.class, (event, ctx) -> {
            Integer slotIdx = event.getSlotIndex();
            if (slotIdx == null || slotIdx < 0 || slotIdx >= ScrubyArmorService.SLOT_COUNT) return;

            String equipped = armorService.getEquippedItem(profile, slotIdx);
            if (equipped.isEmpty()) return;

            int remaining = inventoryService.smartStack(profile, equipped, 1, 1);
            if (remaining > 0) {
                String locale = profile.getLocale() != null ? profile.getLocale() : "en";
                playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "ui.transfer.backpack_full")));
                return;
            }

            armorService.setEquippedItem(profile, slotIdx, "");
            reapplyArmorStats(store, ownerUuid, profile);

            ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
            if (binding != null) binding.updateProfileBySlot(profile);
            reopen(store, ownerRef, playerRef, profile);
        });

        // ── Scruby inventory: handle drops FROM armor grid (unequip via drag) ──
        builder.addEventListener("scruby-inv-grid", CustomUIEventBindingType.Dropped,
                DroppedEventData.class, (drop, ctx) -> {
            Integer sourceSection = drop.getSourceInventorySectionId();
            if (sourceSection == null || sourceSection != 300) return;

            Integer sourceSlot = drop.getSourceSlotId();
            if (sourceSlot == null || sourceSlot < 0 || sourceSlot >= ScrubyArmorService.SLOT_COUNT) return;

            String equipped = armorService.getEquippedItem(profile, sourceSlot);
            if (equipped.isEmpty()) return;

            Integer targetSlot = lastHoveredScrubySlot.remove(ownerUuid);
            if (targetSlot != null && targetSlot >= 0 && targetSlot < ScrubyInventoryService.MAX_SLOTS) {
                ScrubyInventoryService.InventoryEntry existing = inventoryService.getItemAt(profile, targetSlot);
                if (existing != null) {
                    int remaining = inventoryService.smartStack(profile, equipped, 1, 1);
                    if (remaining > 0) {
                        playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.backpack_full")));
                        return;
                    }
                } else {
                    inventoryService.addItemToSlot(profile, equipped, 1, targetSlot, 1);
                }
            } else {
                int remaining = inventoryService.smartStack(profile, equipped, 1, 1);
                if (remaining > 0) {
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.backpack_full")));
                    return;
                }
            }

            armorService.setEquippedItem(profile, sourceSlot, "");
            reapplyArmorStats(store, ownerUuid, profile);

            ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
            if (binding != null) binding.updateProfileBySlot(profile);
            reopen(store, ownerRef, playerRef, profile);
        });
    }

    // ===== Transfer Stubs (fully implemented in Task 4) =====

    private String buildTransferHtml(@Nonnull CompanionProfile profile,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull Ref<EntityStore> ownerRef,
                                     @Nonnull String locale) {
        StringBuilder sb = new StringBuilder();

        // page-overlay layout-mode: Full — lets us place siblings independently.
        // Child 1 (MiddleCenter wrapper) holds the centered inventory row.
        // Child 2 (further down) is the hint image anchored to the screen corner.
        sb.append("<div class=\"page-overlay\" style=\"layout-mode: Full;\">");
        sb.append("<div style=\"layout-mode: MiddleCenter;\">");
        sb.append("<div style=\"layout-mode: Left;\">");

        // ── Container 1: Spieler-Inventar (9x5 = 45 slots) ──
        sb.append("<div class=\"decorated-container\" data-hyui-title=\"").append(esc(ScrubyLang.get(locale, "ui.transfer.player_title"))).append("\" style=\"anchor-width: 615; anchor-height: 400;\">");
        // Return button in title bar (top-left, mirror of scruby-close)
        sb.append("<div class=\"container-title\">")
          .append("<button id=\"player-return\" class=\"custom-textbutton\"")
          .append(" data-hyui-default-label-style=\"@ReturnBtnLabel\"")
          .append(" data-hyui-hovered-label-style=\"@ReturnBtnLabel\"")
          .append(" data-hyui-pressed-label-style=\"@ReturnBtnLabel\"")
          .append(" data-hyui-default-bg=\"@ReturnBtnBg\"")
          .append(" data-hyui-hovered-bg=\"@ReturnBtnHoverBg\"")
          .append(" data-hyui-pressed-bg=\"@ReturnBtnHoverBg\"")
          .append(" style=\"anchor-width: 26; anchor-height: 26; horizontal-align: left; anchor-left: 8;\"> </button>")
          .append("</div>");
        sb.append("<div style=\"layout-mode: Left; vertical-align: top; anchor-left: 5;\">");
        sb.append("<div id=\"player-inv-grid\" class=\"item-grid\"");
        sb.append(" data-hyui-slots-per-row=\"9\"");
        sb.append(" data-hyui-inventory-section-id=\"200\"");
        sb.append(" data-hyui-are-items-draggable=\"true\"");
        sb.append(" data-hyui-display-item-quantity=\"true\"");
        sb.append(" data-hyui-info-display=\"None\">");
        // Read Storage (main inventory) + Hotbar separately to get all slots
        try {
            Player player = store.getComponent(ownerRef, Player.getComponentType());
            if (player != null) {
                com.hypixel.hytale.server.core.inventory.Inventory inv = player.getInventory();
                if (inv != null) {
                    // Storage section (typically 27 slots = 3x9)
                    var storage = inv.getStorage();
                    int storageCount = storage != null ? storage.getCapacity() : 0;
                    LOGGER.atInfo().log("[Scruby-Transfer] Storage capacity: " + storageCount);
                    for (short s = 0; s < storageCount; s++) {
                        ItemStack stack = storage.getItemStack(s);
                        if (stack != null && !ItemStack.isEmpty(stack)) {
                            sb.append("<div class=\"item-grid-slot\"")
                              .append(" data-hyui-item-id=\"").append(esc(stack.getItemId())).append("\"")
                              .append(" data-hyui-quantity=\"").append(stack.getQuantity()).append("\">")
                              .append("</div>");
                        } else {
                            sb.append("<div class=\"item-grid-slot\"></div>");
                        }
                    }
                    // Hotbar section (typically 9 slots)
                    var hotbar = inv.getHotbar();
                    int hotbarCount = hotbar != null ? hotbar.getCapacity() : 0;
                    LOGGER.atInfo().log("[Scruby-Transfer] Hotbar capacity: " + hotbarCount);
                    for (short s = 0; s < hotbarCount; s++) {
                        ItemStack stack = hotbar.getItemStack(s);
                        if (stack != null && !ItemStack.isEmpty(stack)) {
                            sb.append("<div class=\"item-grid-slot\"")
                              .append(" data-hyui-item-id=\"").append(esc(stack.getItemId())).append("\"")
                              .append(" data-hyui-quantity=\"").append(stack.getQuantity()).append("\">")
                              .append("</div>");
                        } else {
                            sb.append("<div class=\"item-grid-slot\"></div>");
                        }
                    }
                    LOGGER.atInfo().log("[Scruby-Transfer] Total player slots: " + (storageCount + hotbarCount));
                } else {
                    for (int i = 0; i < 45; i++) sb.append("<div class=\"item-grid-slot\"></div>");
                }
            } else {
                for (int i = 0; i < 45; i++) sb.append("<div class=\"item-grid-slot\"></div>");
            }
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] Failed to read player inventory: " + e.getMessage());
            for (int i = 0; i < 45; i++) sb.append("<div class=\"item-grid-slot\"></div>");
        }
        sb.append("</div>"); // end item-grid
        sb.append("</div>"); // end container-contents
        sb.append("</div>"); // end decorated-container SPIELER

        // 15px spacer between the two containers
        sb.append("<div style=\"anchor-width: 15;\"></div>");

        // ── Container 2: Scruby-Inventar ──
        sb.append("<div class=\"decorated-container\" data-hyui-title=\"").append(esc(profile.getCompanionName().toUpperCase())).append("\" style=\"anchor-width: 355; anchor-height: 330;\">");
        // Close button in title bar
        sb.append("<div class=\"container-title\">")
          .append("<button id=\"scruby-close\" class=\"custom-textbutton\"")
          .append(" data-hyui-default-label-style=\"@CloseBtnLabel\"")
          .append(" data-hyui-hovered-label-style=\"@CloseBtnLabel\"")
          .append(" data-hyui-pressed-label-style=\"@CloseBtnLabel\"")
          .append(" data-hyui-default-bg=\"@CloseBtnBg\"")
          .append(" data-hyui-hovered-bg=\"@CloseBtnHoverBg\"")
          .append(" data-hyui-pressed-bg=\"@CloseBtnHoverBg\"")
          .append(" style=\"anchor-width: 26; anchor-height: 26; horizontal-align: right; anchor-right: 8;\"> </button>")
          .append("</div>");
        sb.append("<div class=\"container-contents\" style=\"layout-mode: Top; padding: 6;\">");
        sb.append("<div id=\"scruby-transfer-grid\" class=\"item-grid\"");
        sb.append(" data-hyui-slots-per-row=\"5\"");
        sb.append(" data-hyui-inventory-section-id=\"100\"");
        sb.append(" data-hyui-are-items-draggable=\"true\"");
        sb.append(" data-hyui-display-item-quantity=\"true\"");
        sb.append(" data-hyui-info-display=\"None\">");
        java.util.List<ScrubyInventoryService.InventoryEntry> invItems = inventoryService.getItems(profile);
        ScrubyInventoryService.InventoryEntry[] slotLookup = new ScrubyInventoryService.InventoryEntry[ScrubyInventoryService.MAX_SLOTS];
        for (ScrubyInventoryService.InventoryEntry entry : invItems) {
            if (entry.getSlotIndex() >= 0 && entry.getSlotIndex() < ScrubyInventoryService.MAX_SLOTS) {
                slotLookup[entry.getSlotIndex()] = entry;
            }
        }
        for (int i = 0; i < ScrubyInventoryService.MAX_SLOTS; i++) {
            ScrubyInventoryService.InventoryEntry entry = slotLookup[i];
            if (entry != null) {
                sb.append("<div class=\"item-grid-slot\"")
                  .append(" data-hyui-item-id=\"").append(esc(entry.getItemId())).append("\"")
                  .append(" data-hyui-quantity=\"").append(entry.getQuantity()).append("\">")
                  .append("</div>");
            } else {
                sb.append("<div class=\"item-grid-slot\"></div>");
            }
        }
        sb.append("</div>"); // end item-grid
        sb.append("</div>"); // end container-contents
        sb.append("</div>"); // end decorated-container SCRUBY

        sb.append("</div>"); // end layout-mode: Left inventory row
        sb.append("</div>"); // end MiddleCenter wrapper

        // ── Hint image pinned to the screen's bottom-right corner. Sibling of
        // the MiddleCenter wrapper inside the Full-layout page-overlay. ──
        sb.append("<div style=\"anchor-right: 30; anchor-bottom: 30;")
          .append(" anchor-width: 218; anchor-height: 172;\">");
        sb.append("<img src=\"icons/inventory-hints.png\"")
          .append(" style=\"anchor-width: 218; anchor-height: 172;\" />");
        sb.append("</div>"); // end hint anchor

        sb.append("</div>"); // end page-overlay

        return sb.toString();
    }

    private void registerTransferListeners(
            @Nonnull PageBuilder builder,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile
    ) {
        // ── A) Player inventory is now populated in HTML (buildTransferHtml) ──
        // editById with ItemGridBuilder.updateSlot crashes the client, so we
        // generate item-grid-slot elements directly in the HTML instead.

        // ── Hover tracking: remember last slot the cursor entered (for cross-grid targeting) ──
        builder.addEventListener("scruby-transfer-grid", CustomUIEventBindingType.SlotMouseEntered,
                SlotMouseEnteredEventData.class, (event, ctx) -> {
            Integer slot = event.getSlotIndex();
            if (slot != null && slot >= 0) {
                lastHoveredScrubySlot.put(ownerUuid, slot);
            }
        });
        builder.addEventListener("player-inv-grid", CustomUIEventBindingType.SlotMouseEntered,
                SlotMouseEnteredEventData.class, (event, ctx) -> {
            Integer slot = event.getSlotIndex();
            if (slot != null && slot >= 0) {
                lastHoveredPlayerSlot.put(ownerUuid, slot);
            }
        });

        // ── Right-drag placement: plain right-click (1 unit) OR Shift+Right (half stack)
        //    is finalized by a release click (left OR right) on the target slot.
        //    Branch on the pending source's grid to route to cross-grid vs internal. ──
        builder.addEventListener("scruby-transfer-grid", CustomUIEventBindingType.SlotClickReleaseWhileDragging,
                SlotClickReleaseWhileDraggingEventData.class, (event, ctx) -> {
            Integer targetSlot = event.getSlotIndex();
            if (targetSlot == null || targetSlot < 0) return;
            RightDragState pending = pendingRightDrag.remove(ownerUuid);
            if (pending == null) return;
            if (pending.sourceSection == 200) {
                // cross-grid: player -> scruby
                handleHalfStackPlayerToScruby(store, ownerRef, playerRef, ownerUuid, profile,
                        pending, targetSlot);
            } else if (pending.sourceSection == 100) {
                // internal: scruby -> scruby
                handleRightDragScrubyInternal(store, ownerRef, playerRef, ownerUuid, profile,
                        pending, targetSlot);
            }
        });
        builder.addEventListener("player-inv-grid", CustomUIEventBindingType.SlotClickReleaseWhileDragging,
                SlotClickReleaseWhileDraggingEventData.class, (event, ctx) -> {
            Integer targetSlot = event.getSlotIndex();
            if (targetSlot == null || targetSlot < 0) return;
            RightDragState pending = pendingRightDrag.remove(ownerUuid);
            if (pending == null) return;
            if (pending.sourceSection == 100) {
                // cross-grid: scruby -> player
                handleHalfStackScrubyToPlayer(store, ownerRef, playerRef, ownerUuid, profile,
                        pending, targetSlot.shortValue());
            } else if (pending.sourceSection == 200) {
                // internal: player -> player
                handleRightDragPlayerInternal(store, ownerRef, playerRef, ownerUuid, profile,
                        pending, targetSlot.shortValue());
            }
        });

        // ── B-pre) Scruby internal: DEFER via SlotClickPressWhileDragging ──
        // This event fires BEFORE Dropped. Store pending move; Dropped confirms or discards.
        builder.addEventListener("scruby-transfer-grid", CustomUIEventBindingType.SlotClickPressWhileDragging,
                SlotClickPressWhileDraggingEventData.class, (event, ctx) -> {
            Integer dragBtn = event.getDragPressedMouseButton();
            Integer sourceSection = event.getDragSourceInventorySectionId();
            Integer sourceSlot = event.getDragSourceSlotId();
            Integer dragQty = event.getDragItemStackQuantity();
            String dragItemId = event.getDragItemStackId();
            // Capture right-button drag source info for possible Shift+Right half-stack handling
            if (dragBtn != null && dragBtn == 3 && sourceSection != null && sourceSlot != null
                    && dragItemId != null && dragQty != null) {
                pendingRightDrag.put(ownerUuid, new RightDragState(
                        sourceSection, sourceSlot, dragItemId, dragQty));
            }
            Integer targetSlot = event.getSlotIndex();
            if (targetSlot == null || targetSlot < 0 || sourceSection == null || sourceSlot == null) return;
            if (sourceSection == 100 && !sourceSlot.equals(targetSlot)) {
                pendingScrubyMove.put(ownerUuid, new int[]{sourceSlot, targetSlot});
            }
        });

        // ── B) Dropped on Scruby grid: cross-grid Player->Scruby OR confirm Scruby internal ──
        builder.addEventListener("scruby-transfer-grid", CustomUIEventBindingType.Dropped,
                DroppedEventData.class, (drop, ctx) -> {
            // Normal drag completed — not a Shift+Right click-click scenario
            pendingRightDrag.remove(ownerUuid);
            Integer sourceSection = drop.getSourceInventorySectionId();
            if (sourceSection == null) return;

            if (sourceSection == 200) {
                // ── Player -> Scruby (cross-grid, slot-targeted via hover tracking) ──
                pendingScrubyMove.remove(ownerUuid);
                pendingPlayerMove.remove(ownerUuid);
                String itemId = drop.getItemStackId();
                Integer qty = drop.getItemStackQuantity();
                if (itemId != null && qty != null && qty > 0) {
                    Integer targetSlot = lastHoveredScrubySlot.remove(ownerUuid);
                    if (targetSlot != null && targetSlot >= 0 && targetSlot < ScrubyInventoryService.MAX_SLOTS) {
                        // Slot-targeted placement
                        handlePlayerToScrubyDrop(store, ownerRef, playerRef, ownerUuid, profile,
                                itemId, qty, drop.getSourceSlotId() != null ? drop.getSourceSlotId().shortValue() : (short) -1,
                                targetSlot);
                    } else {
                        // Fallback: smartStack (first free slot)
                        int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;
                        int remaining = inventoryService.smartStack(profile, itemId, qty, maxStack);
                        int transferred = qty - remaining;
                        if (transferred > 0) {
                            Integer sourceSlot = drop.getSourceSlotId();
                            if (sourceSlot != null) {
                                removePlayerItemPartial(store, ownerRef, sourceSlot.shortValue(), transferred);
                            }
                            persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
                        } else {
                            playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.backpack_full")));
                        }
                    }
                }
            } else if (sourceSection == 100) {
                // ── Scruby internal (click-click OR drag-and-drop) ──
                int[] pending = pendingScrubyMove.remove(ownerUuid);
                int dropQty = drop.getItemStackQuantity() != null ? drop.getItemStackQuantity() : 0;
                if (pending != null) {
                    // Click-click: use deferred target slot
                    handleScrubyInternalMove(store, ownerRef, playerRef, ownerUuid, profile,
                            pending[0], pending[1], dropQty);
                } else {
                    // Drag-and-drop: use last hovered slot as target
                    Integer targetSlot = lastHoveredScrubySlot.remove(ownerUuid);
                    Integer sourceSlot = drop.getSourceSlotId();
                    if (targetSlot != null && targetSlot >= 0 && sourceSlot != null && !sourceSlot.equals(targetSlot)) {
                        handleScrubyInternalMove(store, ownerRef, playerRef, ownerUuid, profile,
                                sourceSlot, targetSlot, dropQty);
                    }
                }
            }
        });

        // ── C-pre) Player internal: DEFER via SlotClickPressWhileDragging ──
        builder.addEventListener("player-inv-grid", CustomUIEventBindingType.SlotClickPressWhileDragging,
                SlotClickPressWhileDraggingEventData.class, (event, ctx) -> {
            Integer dragBtn = event.getDragPressedMouseButton();
            Integer sourceSection = event.getDragSourceInventorySectionId();
            Integer sourceSlot = event.getDragSourceSlotId();
            Integer dragQty = event.getDragItemStackQuantity();
            String dragItemId = event.getDragItemStackId();
            // Capture right-button drag source info (cross-grid variant)
            if (dragBtn != null && dragBtn == 3 && sourceSection != null && sourceSlot != null
                    && dragItemId != null && dragQty != null) {
                pendingRightDrag.put(ownerUuid, new RightDragState(
                        sourceSection, sourceSlot, dragItemId, dragQty));
            }
            Integer targetSlot = event.getSlotIndex();
            if (targetSlot == null || targetSlot < 0 || sourceSection == null || sourceSlot == null) return;
            if (sourceSection == 200 && !sourceSlot.equals(targetSlot)) {
                pendingPlayerMove.put(ownerUuid, new short[]{sourceSlot.shortValue(), targetSlot.shortValue()});
            }
        });

        // ── C) Dropped on Player grid: cross-grid Scruby->Player OR confirm Player internal ──
        builder.addEventListener("player-inv-grid", CustomUIEventBindingType.Dropped,
                DroppedEventData.class, (drop, ctx) -> {
            // Normal drag completed — not a Shift+Right click-click scenario
            pendingRightDrag.remove(ownerUuid);
            Integer sourceSection = drop.getSourceInventorySectionId();
            if (sourceSection == null) return;

            if (sourceSection == 100) {
                // ── Scruby -> Player (cross-grid, slot-targeted via hover tracking) ──
                pendingScrubyMove.remove(ownerUuid);
                pendingPlayerMove.remove(ownerUuid);
                Integer sourceSlot = drop.getSourceSlotId();
                Integer qty = drop.getItemStackQuantity();
                String itemId = drop.getItemStackId();
                if (sourceSlot != null && qty != null && qty > 0 && itemId != null) {
                    Integer targetSlot = lastHoveredPlayerSlot.remove(ownerUuid);
                    if (targetSlot != null && targetSlot >= 0) {
                        // Slot-targeted placement, honouring client-reported qty
                        handleScrubyToPlayerDrop(store, ownerRef, playerRef, ownerUuid, profile,
                                sourceSlot, targetSlot.shortValue(), itemId, qty);
                    } else {
                        // Fallback: givePlayerItem (any slot), only the dropped qty
                        int removedQty = inventoryService.removeFromSlotPartial(profile, sourceSlot, qty);
                        if (removedQty > 0) {
                            givePlayerItem(store, ownerRef, itemId, removedQty);
                            persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
                        }
                    }
                }
            } else if (sourceSection == 200) {
                // ── Player internal (click-click OR drag-and-drop) ──
                short[] pending = pendingPlayerMove.remove(ownerUuid);
                int dropQty = drop.getItemStackQuantity() != null ? drop.getItemStackQuantity() : 0;
                if (pending != null) {
                    // Click-click: use deferred target slot
                    handlePlayerInternalMove(store, ownerRef, playerRef, ownerUuid, profile,
                            pending[0], pending[1], dropQty);
                } else {
                    // Drag-and-drop: use last hovered slot as target
                    Integer targetSlot = lastHoveredPlayerSlot.remove(ownerUuid);
                    Integer sourceSlot = drop.getSourceSlotId();
                    if (targetSlot != null && targetSlot >= 0 && sourceSlot != null && !sourceSlot.equals(targetSlot)) {
                        handlePlayerInternalMove(store, ownerRef, playerRef, ownerUuid, profile,
                                sourceSlot.shortValue(), targetSlot.shortValue(), dropQty);
                    }
                }
            }
        });

        // ── D) Double-Click on player item -> quick transfer to Scruby ──
        builder.addEventListener("player-inv-grid", CustomUIEventBindingType.SlotDoubleClicking,
                SlotDoubleClickingEventData.class, (event, ctx) -> {
            Integer slotIdx = event.getSlotIndex();
            if (slotIdx == null) return;
            transferPlayerSlotToScruby(store, ownerRef, playerRef, ownerUuid, profile, slotIdx.shortValue());
        });

        // ── E) Double-Click on scruby item -> quick transfer to Player ──
        builder.addEventListener("scruby-transfer-grid", CustomUIEventBindingType.SlotDoubleClicking,
                SlotDoubleClickingEventData.class, (event, ctx) -> {
            Integer slotIdx = event.getSlotIndex();
            if (slotIdx == null) return;
            ScrubyInventoryService.InventoryEntry removed = inventoryService.removeItem(profile, slotIdx);
            if (removed != null) {
                givePlayerItem(store, ownerRef, removed.getItemId(), removed.getQuantity());
                persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
            }
        });

        // Close via ESC — no button needed, HyUI page-overlay closes on ESC
    }

    // ===== Transfer Helper Methods =====

    /**
     * Removes an item from the player's combined inventory at the given slot index.
     */
    private void removePlayerItem(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            short slotIndex
    ) {
        try {
            Player player = store.getComponent(ownerRef, Player.getComponentType());
            if (player == null) return;
            com.hypixel.hytale.server.core.inventory.Inventory inv = player.getInventory();
            if (inv == null) return;
            var container = inv.getCombinedBackpackStorageHotbar();
            if (container != null) {
                ItemStack stack = container.getItemStack(slotIndex);
                if (stack != null && !ItemStack.isEmpty(stack)) {
                    container.removeItemStackFromSlot(slotIndex, stack.getQuantity());
                }
            }
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] removePlayerItem failed: " + e.getMessage());
        }
    }

    /**
     * Removes up to {@code qty} items from the player slot, leaving the rest in place.
     * Returns the amount actually removed (clamped to what the slot holds).
     */
    private int removePlayerItemPartial(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            short slotIndex,
            int qty
    ) {
        if (qty <= 0) return 0;
        try {
            Player player = store.getComponent(ownerRef, Player.getComponentType());
            if (player == null) return 0;
            com.hypixel.hytale.server.core.inventory.Inventory inv = player.getInventory();
            if (inv == null) return 0;
            var container = inv.getCombinedBackpackStorageHotbar();
            if (container == null) return 0;
            ItemStack stack = container.getItemStack(slotIndex);
            if (stack == null || ItemStack.isEmpty(stack)) return 0;
            int take = Math.min(qty, stack.getQuantity());
            container.removeItemStackFromSlot(slotIndex, take);
            return take;
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] removePlayerItemPartial failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gives an item to the player via Player.giveItem().
     */
    private void givePlayerItem(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull String itemId,
            int quantity
    ) {
        try {
            Player player = store.getComponent(ownerRef, Player.getComponentType());
            if (player == null) return;
            player.giveItem(new ItemStack(itemId, quantity), ownerRef, store);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] givePlayerItem failed: " + e.getMessage());
        }
    }

    /**
     * Transfers an item from a player inventory slot to the Scruby inventory.
     */
    private void transferPlayerSlotToScruby(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            short playerSlotIndex
    ) {
        try {
            Player player = store.getComponent(ownerRef, Player.getComponentType());
            if (player == null) return;
            com.hypixel.hytale.server.core.inventory.Inventory inv = player.getInventory();
            if (inv == null) return;
            var container = inv.getCombinedBackpackStorageHotbar();
            if (container == null) return;
            ItemStack stack = container.getItemStack(playerSlotIndex);
            if (stack == null || ItemStack.isEmpty(stack)) return;

            String itemId = stack.getItemId();
            int qty = stack.getQuantity();
            int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;
            int remaining = inventoryService.smartStack(profile, itemId, qty, maxStack);
            if (remaining < qty) {
                // At least some items were transferred
                container.removeItemStackFromSlot(playerSlotIndex, qty);
                // Give back remainder to player if any
                if (remaining > 0) {
                    player.giveItem(new ItemStack(itemId, remaining), ownerRef, store);
                }
                persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
            } else {
                playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.backpack_full")));
            }
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] transferPlayerSlotToScruby failed: " + e.getMessage());
        }
    }

    /**
     * Persists the profile and reopens the transfer overlay.
     * Uses updateProfileBySlot so stationed-profile transfers don't overwrite the active slot.
     */
    private void persistAndReopen(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile
    ) {
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding != null) binding.updateProfileBySlot(profile);
        pendingTab.put(ownerUuid, "transfer");
        reopen(store, ownerRef, playerRef, profile);
    }

    /**
     * Re-applies armor stat modifiers to the companion entity after equip/unequip.
     */
    private void reapplyArmorStats(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile
    ) {
        try {
            Ref<EntityStore> companionRef = companionRegistry.getCompanionRef(ownerUuid);
            if (companionRef != null && companionRef.isValid()) {
                attributeService.applyAttributes(store, companionRef, profile, ownerUuid);
                ScrubyArmorService.applyVisualArmor(store, companionRef, profile);
            }
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Armor] reapplyArmorStats failed: " + e.getMessage());
        }
    }

    /**
     * Handles a player item being dropped onto a specific Scruby slot.
     */
    private void handlePlayerToScrubyDrop(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull String itemId,
            int qty,
            short playerSourceSlot,
            int scrubyTargetSlot
    ) {
        try {
            int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;
            ScrubyInventoryService.InventoryEntry targetEntry = inventoryService.getItemAt(profile, scrubyTargetSlot);

            // Look up the actual source stack size so we can distinguish partial vs full drops
            int sourceStackQty = 0;
            try {
                Player playerLookup = store.getComponent(ownerRef, Player.getComponentType());
                if (playerLookup != null && playerSourceSlot >= 0) {
                    var invLookup = playerLookup.getInventory();
                    if (invLookup != null) {
                        var c = invLookup.getCombinedBackpackStorageHotbar();
                        if (c != null) {
                            ItemStack s = c.getItemStack(playerSourceSlot);
                            if (s != null && !ItemStack.isEmpty(s)) sourceStackQty = s.getQuantity();
                        }
                    }
                }
            } catch (Exception ignore) {}

            if (targetEntry == null || targetEntry.getItemId().equals(itemId)) {
                // Empty slot or same item → addItemToSlot (stack or place)
                ScrubyInventoryService.SlotAddResult result =
                        inventoryService.addItemToSlot(profile, itemId, qty, scrubyTargetSlot, maxStack);
                if (result.isSuccess()) {
                    int transferred = qty - result.getRemainingQuantity();
                    if (transferred > 0 && playerSourceSlot >= 0) {
                        removePlayerItemPartial(store, ownerRef, playerSourceSlot, transferred);
                    }
                    persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
                } else {
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.slot_full")));
                }
            } else {
                // Different item → cross-grid swap. Only valid when the full source
                // stack is being moved; a partial drop would leave a hanging swap.
                if (qty < sourceStackQty) {
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.slot_full")));
                    return;
                }
                ScrubyInventoryService.DisplacedItem displaced =
                        inventoryService.crossGridSwap(profile, scrubyTargetSlot, itemId, qty);
                if (displaced != null) {
                    removePlayerItem(store, ownerRef, playerSourceSlot);
                    // Place displaced Scruby item in the player's source slot
                    Player player = store.getComponent(ownerRef, Player.getComponentType());
                    if (player != null) {
                        var inv = player.getInventory();
                        if (inv != null) {
                            var container = inv.getCombinedBackpackStorageHotbar();
                            if (container != null) {
                                container.addItemStackToSlot(playerSourceSlot,
                                        new ItemStack(displaced.getItemId(), displaced.getQuantity()));
                            }
                        }
                    }
                    persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
                }
            }
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] handlePlayerToScrubyDrop failed: " + e.getMessage());
        }
    }

    /**
     * Handles moving/swapping items within the Scruby grid. Honours the
     * client-reported drop quantity: a partial drop (e.g. right-drag = 1)
     * only moves that many items and leaves the rest in the source slot.
     * A full-stack drop behaves exactly like the old move/swap logic.
     */
    private void handleScrubyInternalMove(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            int sourceSlot,
            int targetSlot,
            int dropQty
    ) {
        try {
            if (sourceSlot == targetSlot) return;

            ScrubyInventoryService.InventoryEntry sourceEntry = inventoryService.getItemAt(profile, sourceSlot);
            ScrubyInventoryService.InventoryEntry targetEntry = inventoryService.getItemAt(profile, targetSlot);

            if (sourceEntry == null) return; // nothing to move
            int sourceQty = sourceEntry.getQuantity();
            int moveQty = (dropQty <= 0 || dropQty >= sourceQty) ? sourceQty : dropQty;
            boolean fullStack = (moveQty >= sourceQty);

            if (targetEntry == null) {
                // Target empty → move moveQty into target
                int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;
                if (fullStack) {
                    inventoryService.removeItem(profile, sourceSlot);
                } else {
                    inventoryService.removeFromSlotPartial(profile, sourceSlot, moveQty);
                }
                inventoryService.addItemToSlot(profile, sourceEntry.getItemId(),
                        moveQty, targetSlot, maxStack);
                persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
            } else if (targetEntry.getItemId().equals(sourceEntry.getItemId())) {
                // Same item → stack, remainder stays in source
                int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;
                int canAdd = maxStack - targetEntry.getQuantity();
                if (canAdd > 0) {
                    int toMove = Math.min(moveQty, canAdd);
                    int removed = inventoryService.removeFromSlotPartial(profile, sourceSlot, toMove);
                    if (removed > 0) {
                        inventoryService.addItemToSlot(profile, sourceEntry.getItemId(),
                                removed, targetSlot, maxStack);
                        persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
                    }
                }
            } else if (fullStack) {
                // Different item, full stack → swap
                inventoryService.swapSlots(profile, sourceSlot, targetSlot);
                persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
            } else {
                // Partial drop onto a different item → reject
                playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.slot_full")));
            }
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] handleScrubyInternalMove failed: " + e.getMessage());
        }
    }

    /**
     * Handles a Scruby item being dropped onto a specific Player slot.
     */
    private void handleScrubyToPlayerDrop(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            int scrubySourceSlot,
            short playerTargetSlot,
            @Nonnull String dropItemId,
            int dropQty
    ) {
        try {
            ScrubyInventoryService.InventoryEntry scrubyEntry = inventoryService.getItemAt(profile, scrubySourceSlot);
            if (scrubyEntry == null) return;
            // Honour the client-reported drop quantity (e.g. right-drag = 1 from a bigger stack)
            int requestedQty = Math.max(1, Math.min(dropQty, scrubyEntry.getQuantity()));

            Player player = store.getComponent(ownerRef, Player.getComponentType());
            if (player == null) return;
            var inv = player.getInventory();
            if (inv == null) return;
            var container = inv.getCombinedBackpackStorageHotbar();
            if (container == null) return;

            ItemStack targetStack = container.getItemStack(playerTargetSlot);
            boolean targetEmpty = (targetStack == null || ItemStack.isEmpty(targetStack));

            if (targetEmpty) {
                // Empty player slot → move requestedQty
                int removed = inventoryService.removeFromSlotPartial(profile, scrubySourceSlot, requestedQty);
                if (removed > 0) {
                    container.addItemStackToSlot(playerTargetSlot, new ItemStack(scrubyEntry.getItemId(), removed));
                    persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
                }
            } else if (targetStack.getItemId().equals(scrubyEntry.getItemId())) {
                // Same item → stack with remainder handling
                int currentPlayerQty = targetStack.getQuantity();
                int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;
                int canAdd = maxStack - currentPlayerQty;
                if (canAdd <= 0) return; // player slot already full
                int toTransfer = Math.min(requestedQty, canAdd);
                int removed = inventoryService.removeFromSlotPartial(profile, scrubySourceSlot, toTransfer);
                if (removed > 0) {
                    container.addItemStackToSlot(playerTargetSlot, new ItemStack(scrubyEntry.getItemId(), removed));
                    persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
                }
            } else {
                // Different item → cross-grid swap; only valid for full-stack drops
                if (requestedQty < scrubyEntry.getQuantity()) {
                    playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.slot_full")));
                    return;
                }
                String playerItemId = targetStack.getItemId();
                int playerQty = targetStack.getQuantity();
                container.removeItemStackFromSlot(playerTargetSlot, playerQty);
                inventoryService.removeItem(profile, scrubySourceSlot);
                container.addItemStackToSlot(playerTargetSlot, new ItemStack(scrubyEntry.getItemId(), scrubyEntry.getQuantity()));
                inventoryService.addItemToSlot(profile, playerItemId, playerQty,
                        scrubySourceSlot, ScrubyInventoryService.DEFAULT_MAX_STACK);
                persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
            }
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] handleScrubyToPlayerDrop failed: " + e.getMessage());
        }
    }

    /**
     * Shift+Rightclick player-stack then right-click a scruby slot: transfers
     * exactly half of the source stack to the target.
     */
    private void handleHalfStackPlayerToScruby(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull RightDragState pending,
            int scrubyTargetSlot
    ) {
        try {
            if (scrubyTargetSlot < 0 || scrubyTargetSlot >= ScrubyInventoryService.MAX_SLOTS) return;
            Player player = store.getComponent(ownerRef, Player.getComponentType());
            if (player == null) return;
            var inv = player.getInventory();
            if (inv == null) return;
            var container = inv.getCombinedBackpackStorageHotbar();
            if (container == null) return;
            ItemStack sourceStack = container.getItemStack((short) pending.sourceSlot);
            if (sourceStack == null || ItemStack.isEmpty(sourceStack)) return;
            if (!sourceStack.getItemId().equals(pending.itemId)) return;
            int sourceQty = sourceStack.getQuantity();
            if (sourceQty <= 0) return;

            // Shift+Right fingerprint: dragQty equals the full source stack AND
            // the source has at least 2 items (can actually be halved). Otherwise
            // this is a plain right-click pickup → use dragQty (clamped).
            int desired;
            if (sourceQty >= 2 && pending.dragQty >= sourceQty) {
                desired = sourceQty / 2;
            } else {
                desired = Math.max(1, Math.min(pending.dragQty, sourceQty));
            }
            if (desired <= 0) return;

            // Target must be empty or same item; don't clobber a different item
            ScrubyInventoryService.InventoryEntry targetEntry =
                    inventoryService.getItemAt(profile, scrubyTargetSlot);
            if (targetEntry != null && !targetEntry.getItemId().equals(pending.itemId)) {
                playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.slot_full")));
                return;
            }

            int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;
            ScrubyInventoryService.SlotAddResult result =
                    inventoryService.addItemToSlot(profile, pending.itemId, desired, scrubyTargetSlot, maxStack);
            if (!result.isSuccess()) return;
            int transferred = desired - result.getRemainingQuantity();
            if (transferred > 0) {
                removePlayerItemPartial(store, ownerRef, (short) pending.sourceSlot, transferred);
            }
            persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] handleHalfStackPlayerToScruby failed: " + e.getMessage());
        }
    }

    /**
     * Shift+Rightclick scruby-stack then right-click a player slot: transfers
     * exactly half of the source stack to the target.
     */
    private void handleHalfStackScrubyToPlayer(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull RightDragState pending,
            short playerTargetSlot
    ) {
        try {
            if (playerTargetSlot < 0) return;
            ScrubyInventoryService.InventoryEntry sourceEntry =
                    inventoryService.getItemAt(profile, pending.sourceSlot);
            if (sourceEntry == null) return;
            if (!sourceEntry.getItemId().equals(pending.itemId)) return;
            int sourceQty = sourceEntry.getQuantity();
            if (sourceQty <= 0) return;

            // Shift+Right fingerprint: only when source is big enough to halve
            int desired;
            if (sourceQty >= 2 && pending.dragQty >= sourceQty) {
                desired = sourceQty / 2;
            } else {
                desired = Math.max(1, Math.min(pending.dragQty, sourceQty));
            }
            if (desired <= 0) return;

            Player player = store.getComponent(ownerRef, Player.getComponentType());
            if (player == null) return;
            var inv = player.getInventory();
            if (inv == null) return;
            var container = inv.getCombinedBackpackStorageHotbar();
            if (container == null) return;

            ItemStack targetStack = container.getItemStack(playerTargetSlot);
            boolean targetEmpty = (targetStack == null || ItemStack.isEmpty(targetStack));
            if (!targetEmpty && !targetStack.getItemId().equals(pending.itemId)) {
                playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.slot_full")));
                return;
            }

            int canAdd = desired;
            if (!targetEmpty) {
                int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;
                canAdd = Math.min(desired, maxStack - targetStack.getQuantity());
            }
            if (canAdd <= 0) return;

            int removed = inventoryService.removeFromSlotPartial(profile, pending.sourceSlot, canAdd);
            if (removed > 0) {
                container.addItemStackToSlot(playerTargetSlot, new ItemStack(pending.itemId, removed));
                persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
            }
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] handleHalfStackScrubyToPlayer failed: " + e.getMessage());
        }
    }

    /**
     * Plain right-click (1 unit) or Shift+Right (half stack) placement
     * *within* the Player grid. Source and target both live in the player
     * inventory; no cross-grid logic involved.
     */
    private void handleRightDragPlayerInternal(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull RightDragState pending,
            short playerTargetSlot
    ) {
        try {
            if (playerTargetSlot < 0) return;
            if (pending.sourceSlot == playerTargetSlot) {
                // Left-click on the source slot = put it back. State is unchanged
                // server-side, but we must re-render so the client clears its cursor.
                persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
                return;
            }
            Player player = store.getComponent(ownerRef, Player.getComponentType());
            if (player == null) return;
            var inv = player.getInventory();
            if (inv == null) return;
            var container = inv.getCombinedBackpackStorageHotbar();
            if (container == null) return;

            ItemStack sourceStack = container.getItemStack((short) pending.sourceSlot);
            if (sourceStack == null || ItemStack.isEmpty(sourceStack)) return;
            if (!sourceStack.getItemId().equals(pending.itemId)) return;
            int sourceQty = sourceStack.getQuantity();
            if (sourceQty <= 0) return;

            // Shift+Right fingerprint: only when source is big enough to halve
            int desired;
            if (sourceQty >= 2 && pending.dragQty >= sourceQty) {
                desired = sourceQty / 2;
            } else {
                desired = Math.max(1, Math.min(pending.dragQty, sourceQty));
            }
            if (desired <= 0) return;

            ItemStack targetStack = container.getItemStack(playerTargetSlot);
            boolean targetEmpty = (targetStack == null || ItemStack.isEmpty(targetStack));
            if (!targetEmpty && !targetStack.getItemId().equals(pending.itemId)) {
                playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.slot_full")));
                return;
            }

            int canAdd = desired;
            if (!targetEmpty) {
                int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;
                canAdd = Math.min(desired, maxStack - targetStack.getQuantity());
            }
            if (canAdd <= 0) return;

            container.removeItemStackFromSlot((short) pending.sourceSlot, canAdd);
            container.addItemStackToSlot(playerTargetSlot, new ItemStack(pending.itemId, canAdd));
            persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] handleRightDragPlayerInternal failed: " + e.getMessage());
        }
    }

    /**
     * Plain right-click (1 unit) or Shift+Right (half stack) placement
     * *within* the Scruby grid.
     */
    private void handleRightDragScrubyInternal(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull RightDragState pending,
            int scrubyTargetSlot
    ) {
        try {
            if (scrubyTargetSlot < 0 || scrubyTargetSlot >= ScrubyInventoryService.MAX_SLOTS) return;
            if (pending.sourceSlot == scrubyTargetSlot) {
                // Left-click on the source slot = put it back. Force a re-render
                // so the client clears its cursor.
                persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
                return;
            }

            ScrubyInventoryService.InventoryEntry sourceEntry =
                    inventoryService.getItemAt(profile, pending.sourceSlot);
            if (sourceEntry == null) return;
            if (!sourceEntry.getItemId().equals(pending.itemId)) return;
            int sourceQty = sourceEntry.getQuantity();
            if (sourceQty <= 0) return;

            // Shift+Right fingerprint: only when source is big enough to halve
            int desired;
            if (sourceQty >= 2 && pending.dragQty >= sourceQty) {
                desired = sourceQty / 2;
            } else {
                desired = Math.max(1, Math.min(pending.dragQty, sourceQty));
            }
            if (desired <= 0) return;

            ScrubyInventoryService.InventoryEntry targetEntry =
                    inventoryService.getItemAt(profile, scrubyTargetSlot);
            if (targetEntry != null && !targetEntry.getItemId().equals(pending.itemId)) {
                playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.slot_full")));
                return;
            }

            int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;
            int canAdd = desired;
            if (targetEntry != null) {
                canAdd = Math.min(desired, maxStack - targetEntry.getQuantity());
            }
            if (canAdd <= 0) return;

            int removed = inventoryService.removeFromSlotPartial(profile, pending.sourceSlot, canAdd);
            if (removed <= 0) return;
            ScrubyInventoryService.SlotAddResult result =
                    inventoryService.addItemToSlot(profile, pending.itemId, removed, scrubyTargetSlot, maxStack);
            if (!result.isSuccess() || result.getRemainingQuantity() > 0) {
                // Put any unplaceable leftover back into the source slot
                int leftover = result.isSuccess() ? result.getRemainingQuantity() : removed;
                if (leftover > 0) {
                    inventoryService.addItemToSlot(profile, pending.itemId, leftover, pending.sourceSlot, maxStack);
                }
            }
            persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] handleRightDragScrubyInternal failed: " + e.getMessage());
        }
    }

    /**
     * Handles moving/swapping items within the Player grid in the transfer
     * overlay. Honours the client-reported drop quantity so a right-drag
     * partial drop only moves that many items. Full-stack drops still swap.
     */
    private void handlePlayerInternalMove(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            short sourceSlot,
            short targetSlot,
            int dropQty
    ) {
        try {
            if (sourceSlot == targetSlot) return;

            Player player = store.getComponent(ownerRef, Player.getComponentType());
            if (player == null) return;
            var inv = player.getInventory();
            if (inv == null) return;
            var container = inv.getCombinedBackpackStorageHotbar();
            if (container == null) return;

            ItemStack sourceStack = container.getItemStack(sourceSlot);
            if (sourceStack == null || ItemStack.isEmpty(sourceStack)) return;
            int sourceQty = sourceStack.getQuantity();
            int moveQty = (dropQty <= 0 || dropQty >= sourceQty) ? sourceQty : dropQty;
            boolean fullStack = (moveQty >= sourceQty);

            ItemStack targetStack = container.getItemStack(targetSlot);
            boolean targetEmpty = (targetStack == null || ItemStack.isEmpty(targetStack));
            int maxStack = ScrubyInventoryService.DEFAULT_MAX_STACK;

            if (targetEmpty) {
                container.removeItemStackFromSlot(sourceSlot, moveQty);
                container.addItemStackToSlot(targetSlot, new ItemStack(sourceStack.getItemId(), moveQty));
                persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
            } else if (targetStack.getItemId().equals(sourceStack.getItemId())) {
                int canAdd = maxStack - targetStack.getQuantity();
                if (canAdd <= 0) return;
                int toMove = Math.min(moveQty, canAdd);
                container.removeItemStackFromSlot(sourceSlot, toMove);
                container.addItemStackToSlot(targetSlot, new ItemStack(sourceStack.getItemId(), toMove));
                persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
            } else if (fullStack) {
                // Different item, full stack → swap
                container.removeItemStackFromSlot(sourceSlot, sourceQty);
                container.removeItemStackFromSlot(targetSlot, targetStack.getQuantity());
                container.addItemStackToSlot(sourceSlot, new ItemStack(targetStack.getItemId(), targetStack.getQuantity()));
                container.addItemStackToSlot(targetSlot, new ItemStack(sourceStack.getItemId(), sourceQty));
                persistAndReopen(store, ownerRef, playerRef, ownerUuid, profile);
            } else {
                // Partial drop onto a different item → reject
                playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "ui.transfer.slot_full")));
            }
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Transfer] handlePlayerInternalMove failed: " + e.getMessage());
        }
    }

    // ===== Header =====

    private String buildHeader(@Nonnull CompanionProfile profile, @Nonnull AttributeDraft draft, @Nonnull String locale) {
        String path = profile.getPathChoice();
        boolean maxed = profile.getLevel() >= ScrubyXPService.MAX_LEVEL;
        long xpNext = xpService.getXpForLevel(profile.getLevel() + 1);
        long curXp = profile.getCurrentXp();

        // ── Left section (anchor-width: 265): name + level badge + path badge ──
        // Border syntax: background-color: rgba(R,G,B,A) BORDER_SIZE
        // The rgba alpha provides the visible background; the integer suffix = border thickness
        String lvText = maxed ? "Lv" + ScrubyXPService.MAX_LEVEL + " MAX" : "Lv" + profile.getLevel();
        String lvBadgeBg = maxed ? "rgba(80,200,120,0.2) 1" : "rgba(255,255,255,0.05) 1";
        String lvTextColor = maxed ? "#90ee90" : ScrubyColors.GREEN_PRIMARY;

        String leftSection = "<div style=\"anchor-width: 265; layout-mode: Left;\">"
            // 8px spacer from window edge
            + "<div style=\"anchor-width: 8;\">"
              + "<p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>"
            + "</div>"
            // Name + badges stacked
            + "<div style=\"flex-weight: 1; layout-mode: Top; padding-top: 6; padding-bottom: 6;\">"
              + "<p style=\"font-size: 15; color: " + ScrubyColors.TEXT_BRIGHT + "; font-weight: bold;\">" + esc(profile.getCompanionName()) + "</p>"
              // Badges row (level + path)
              + "<div style=\"layout-mode: Left; anchor-height: 20; padding-top: 4;\">"
                + "<div style=\"anchor-width: 70; anchor-height: 18; background-color: " + lvBadgeBg + "; vertical-align: center;\">"
                  + "<p style=\"font-size: 9; color: " + lvTextColor + "; font-weight: bold; text-align: center; vertical-align: center;\">" + esc(lvText) + "</p>"
                + "</div>"
                + (!"NONE".equals(path)
                    ? "<div style=\"anchor-width: 64; anchor-height: 18; background-color: " + ScrubyColors.BORDER_BG_SUBTLE + "; vertical-align: center; padding-left: 4;\">"
                        + "<p style=\"font-size: 9; color: " + ScrubyColors.pathAccent(path) + "; font-weight: bold; text-align: center; vertical-align: center;\">" + esc(ScrubyColors.pathBadgeLabel(path, locale)) + "</p>"
                      + "</div>"
                    : "")
              + "</div>"
            + "</div>"
            + "</div>";

        // ── Center section (flex-weight: 1): SKILLTREE title + XP bar ──
        String xpLabel = maxed ? "MAX" : (curXp + " / " + xpNext + " XP");
        int xpMax = 100;
        int xpVal = maxed ? 100 : (int) ((curXp * 100) / Math.max(1, xpNext));

        // XP bar — fixed-width track + absolutely-positioned fill overlay (same pattern as HUD).
        // Centered via flex spacers on both sides so it sits mid-section regardless of page width.
        int xpBarWidth = 400;
        int xpFillWidth = (int) ((long) xpVal * xpBarWidth / 100);
        String xpTrack = "rgba(255,255,255,0.08)";
        String xpFillColor = "#c6a86d";

        String xpBar = "<div style=\"anchor-width: " + xpBarWidth + "; anchor-height: 8; vertical-align: center; background-color: " + xpTrack + ";\">"
                + (xpFillWidth > 0
                    ? "<div style=\"anchor-left: 0; anchor-top: 0; anchor-width: " + xpFillWidth + "; anchor-height: 8; background-color: " + xpFillColor + ";\"></div>"
                    : "")
                + "</div>";

        // Header text: dynamic evolution stage name (e.g. FIGHTER / HUNTER / ARCHMAGE),
        // uppercased to match the previous SKILLTREE title styling.
        int currentStage = evolutionService.calculateEvolutionStage(profile.getLevel());
        String evolutionTitle = ScrubyLang.getEvolutionName(locale, currentStage, path).toUpperCase();

        String centerSection = "<div style=\"flex-weight: 1; layout-mode: Top; padding: 6;\">"
            + "<p style=\"font-size: 13; color: " + ScrubyColors.TEXT_SECONDARY + "; font-weight: bold; text-align: center; letter-spacing: 2;\">" + esc(evolutionTitle) + "</p>"
            + "<div style=\"layout-mode: Left; anchor-height: 18; padding-top: 4;\">"
              + "<div style=\"flex-weight: 1;\"><p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p></div>"
              + xpBar
              + "<div style=\"flex-weight: 1;\"><p style=\"font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p></div>"
            + "</div>"
            + "<p style=\"font-size: 9; color: " + ScrubyColors.TEXT_MUTED + "; text-align: center;\">" + esc(xpLabel) + "</p>"
            + "</div>";

        // ── Right section (anchor-width: 255): spacer for alignment (non-empty — HyUI parser requires content) ──
        String rightSection = "<div style=\"anchor-width: 255;\">"
            + "<p style=\"font-size: 9; color: " + ScrubyColors.TEXT_DISABLED + "; text-align: end; padding: 4 6;\">ESC</p>"
            + "</div>";

        // ── Assemble header + separator ──
        StringBuilder sb = new StringBuilder();
        sb.append("<div id=\"header\" style=\"layout-mode: Left; anchor-height: 54; background-color: " + ScrubyColors.BG_SURFACE + ";\">");
        sb.append(leftSection);
        sb.append(centerSection);
        sb.append(rightSection);
        sb.append("</div>");
        return sb.toString();
    }

    // ===== Left Column: Skills =====

    private String buildSkillsColumn(@Nonnull CompanionProfile profile, @Nullable String pendW1, @Nullable String pendW2, @Nonnull String locale) {
        String path = profile.getPathChoice();
        int lv = profile.getLevel();
        String all = allAbilities(profile);
        ScrubyColors.PathTheme theme = ScrubyColors.pathTheme(path);
        StringBuilder sb = new StringBuilder();

        // ── Section header (path-colored) ──
        sb.append("<p style=\"padding: 4 8; font-size: 9; color: ").append(theme.accent())
          .append("; font-weight: bold;\">")
          .append(esc(getSkillsTitle(path, locale))).append("</p>");
        // Separator (path-colored)
        sb.append("<p style=\"anchor-height: 1; background-color: ").append(theme.separator())
          .append("; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

        // ── Lv3: Kampfinstinkt (universal — always shown) ──
        sb.append("<p style=\"anchor-height: 7; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
        sb.append(skillTile(ScrubySkillService.UNIVERSAL_KAMPFINSTINKT, 3, lv, all, locale, theme));

        // ── Lv5: Path choice or path name ──
        sb.append("<p style=\"anchor-height: 7; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
        if ("NONE".equals(path)) {
            // Path not yet chosen — show locked placeholder
            sb.append("<div style=\"layout-mode: Top; padding: 14 8; background-color: " + ScrubyColors.BORDER_BG_SUBTLE + ";\">");
            sb.append("<p style=\"font-size: 9; color: " + ScrubyColors.TEXT_MUTED + "; font-weight: bold; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.skills.lv5_locked"))).append("</p>");
            sb.append("<p style=\"font-size: 9; color: " + ScrubyColors.TEXT_DISABLED + "; padding-top: 2; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.skills.lv5_locked.desc"))).append("</p>");
            sb.append("</div>");
            return sb.toString();
        }

        // Path chosen — milestone badge via theme colors (same structure)
        String pathDisplayName = ScrubyColors.pathDisplayName(path, locale);
        sb.append("<div style=\"layout-mode: Top; background-color: ").append(theme.milestoneBorder()).append(";\">");
        sb.append("<div style=\"layout-mode: Top; padding: 3 10; background-color: ").append(theme.milestoneBg()).append(";\">");
        sb.append("<p style=\"font-size: 10; color: ").append(ScrubyColors.TEXT_SECONDARY).append("; font-weight: bold; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.skills.lv5_path"))).append("</p>");
        sb.append("<p style=\"font-size: 13; color: ").append(theme.accent()).append("; font-weight: bold; padding-top: 2; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.pathchoice.path_name", pathDisplayName))).append("</p>");
        sb.append("</div>");
        sb.append("</div>");

        // ── Lv7: Path skill 1 ──
        sb.append("<p style=\"anchor-height: 7; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
        sb.append(skillTile(fixedSkill(path, 1), 7, lv, all, locale, theme));

        // ── Lv9: Path skill 2 ──
        sb.append("<p style=\"anchor-height: 7; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
        sb.append(skillTile(fixedSkill(path, 2), 9, lv, all, locale, theme));

        // ── Lv12: Choice 1 ──
        sb.append("<p style=\"anchor-height: 7; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
        sb.append(choiceBlock(profile, 1, pendW1, locale));

        // ── Lv15: Path skill 3 ──
        sb.append("<p style=\"anchor-height: 7; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
        sb.append(skillTile(fixedSkill(path, 3), 15, lv, all, locale, theme));

        // ── Lv18: Choice 2 ──
        sb.append("<p style=\"anchor-height: 7; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");
        sb.append(choiceBlock(profile, 2, pendW2, locale));

        return sb.toString();
    }

    private String skillTile(String skillId, int reqLevel, int curLevel, String allAbilities, String locale, ScrubyColors.PathTheme theme) {
        boolean unlocked = allAbilities.contains(skillId);
        String name = skillName(locale, skillId);
        String type = skillType(locale, skillId);
        String desc = skillService.getSkillDescription(skillId, locale);

        // Universal skills (e.g. Combat Instinct) use the neutral #1c2a39 palette
        // only while the player has no path yet. After Lv5 path selection, fall
        // through so the standard milestoneTile picks up the path-themed colors.
        boolean noPath = theme.stripe() == null;
        if (ScrubySkillService.UNIVERSAL_KAMPFINSTINKT.equals(skillId) && unlocked && noPath) {
            return centeredTile(name, type, desc, reqLevel,
                    "#1c2a39 2", "#1c2a39",
                    ScrubyColors.TEXT_BRIGHT, "#8a94a8", ScrubyColors.TEXT_PRIMARY,
                    true, false);
        }

        // All three states use the same centered layout via centeredTile, only
        // colors and border thickness differ.
        if (unlocked) {
            return milestoneTile(name, type, desc, reqLevel, theme, true, false);
        }
        if (curLevel >= reqLevel) {
            // Ready — subdued path color, 1px border
            return centeredTile(name, type, desc, reqLevel,
                    theme.tileBorderReady() + " 1", theme.tileBgReady(),
                    ScrubyColors.TEXT_BRIGHT, theme.secondary(), theme.descText(),
                    true, false);
        }
        // Locked — neutral gray, 1px border
        return centeredTile(name, type, desc, reqLevel,
                "rgba(255,255,255,0.10) 1", "rgba(255,255,255,0.02)",
                ScrubyColors.TEXT_SECONDARY, ScrubyColors.TEXT_MUTED, ScrubyColors.TEXT_MUTED,
                true, false);
    }

    /**
     * Generic centered tile renderer used by all skill states (unlocked, ready, locked)
     * and by the chosen Lv12/Lv18 tiles. Caller passes explicit colors so each state
     * can dim or theme independently while sharing the same layout.
     */
    private String centeredTile(String name, String type, String desc, int reqLevel,
                                String borderBg, String fillBg,
                                String nameColor, String metaColor, String descColor,
                                boolean centered, boolean metaOnTop) {
        String metaText = "Lv" + reqLevel + (type == null || type.isEmpty() ? "" : " -- " + type);
        StringBuilder tsb = new StringBuilder();
        tsb.append("<div style=\"layout-mode: Top; background-color: ").append(borderBg).append(";\">");
        tsb.append("<div style=\"layout-mode: Top; padding-left: 10; padding-right: 10; background-color: ").append(fillBg).append(";\">");
        // Top vertical spacer (HyUI sometimes ignores vertical padding when the container
        // auto-shrinks to content; explicit anchor-height <p> reliably forces space).
        // Use solid BG_SURFACE for color — semi-transparent rgba on label color crashes HyUI.
        tsb.append("<p style=\"anchor-height: 7; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        if (metaOnTop) {
            // Meta stacked above name, both centered (Lv5 PATH style)
            tsb.append("<p style=\"font-size: 9; color: ").append(metaColor).append("; font-weight: bold; text-align: center;\">").append(esc(metaText)).append("</p>");
            tsb.append("<p style=\"font-size: 12; color: ").append(nameColor).append("; font-weight: bold; padding-top: 2; text-align: center;\">").append(esc(name)).append("</p>");
        } else {
            // Name + meta side by side
            tsb.append("<div style=\"layout-mode: Left;\">");
            if (centered) {
                // Invisible left spacer mirrors meta width so name is truly centered in the card
                int metaWidth = 72;
                tsb.append("<p style=\"anchor-width: ").append(metaWidth).append("; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
                tsb.append("<p style=\"flex-weight: 1; font-size: 12; color: ").append(nameColor).append("; font-weight: bold; text-align: center; vertical-align: center;\">").append(esc(name)).append("</p>");
                tsb.append("<p style=\"anchor-width: ").append(metaWidth).append("; font-size: 9; color: ").append(metaColor).append("; font-weight: bold; text-align: right; vertical-align: center;\">").append(esc(metaText)).append("</p>");
            } else {
                tsb.append("<p style=\"flex-weight: 1; font-size: 12; color: ").append(nameColor).append("; font-weight: bold; vertical-align: center;\">").append(esc(name)).append("</p>");
                tsb.append("<p style=\"font-size: 9; color: ").append(metaColor).append("; font-weight: bold; vertical-align: center;\">").append(esc(metaText)).append("</p>");
            }
            tsb.append("</div>");
        }
        // Description
        String descAlign = centered ? " text-align: center;" : "";
        tsb.append("<p style=\"font-size: 10; color: ").append(descColor).append("; padding-top: 4;").append(descAlign).append("\">").append(esc(desc)).append("</p>");
        // Bottom vertical spacer to mirror the top one
        tsb.append("<p style=\"anchor-height: 7; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        tsb.append("</div>");
        tsb.append("</div>");
        return tsb.toString();
    }

    /**
     * Milestone-style tile for achieved/chosen skills: path-themed colors via theme.
     * Thin wrapper around centeredTile with theme-derived colors.
     */
    private String milestoneTile(String name, String type, String desc, int reqLevel, ScrubyColors.PathTheme theme, boolean centered, boolean metaOnTop) {
        return centeredTile(name, type, desc, reqLevel,
                theme.milestoneBorder(), theme.milestoneBg(),
                ScrubyColors.TEXT_BRIGHT, theme.accent(), theme.descText(),
                centered, metaOnTop);
    }

    private String choiceBlock(@Nonnull CompanionProfile profile, int slot, @Nullable String pendingChoice, @Nonnull String locale) {
        int reqLevel = slot == 1 ? 12 : 18;
        int curLevel = profile.getLevel();
        String[] opts = skillService.getChoiceOptions(profile, slot);
        if (opts == null) return "";

        boolean chosen0 = profile.hasChosenAbility(opts[0]);
        boolean chosen1 = profile.hasChosenAbility(opts[1]);
        boolean done = chosen0 || chosen1;
        boolean locked = curLevel < reqLevel;
        String prefix = slot == 1 ? "w1" : "w2";
        ScrubyColors.PathTheme cTheme = ScrubyColors.pathTheme(profile.getPathChoice());

        StringBuilder sb = new StringBuilder();

        if (locked) {
            // Locked state — unchanged (no path color)
            sb.append("<div style=\"layout-mode: Top; background-color: rgba(255,255,255,0.10) 1;\">");
            sb.append("<div style=\"layout-mode: Top; padding: 14 8; background-color: rgba(255,255,255,0.02);\">");
            sb.append("<p style=\"font-size: 10; color: " + ScrubyColors.TEXT_DISABLED + "; font-weight: bold; text-align: center;\">").append(esc(ScrubyLang.get(locale, slot == 1 ? "ui.skills.choice1" : "ui.skills.choice2"))).append("</p>");
            sb.append("<p style=\"font-size: 9; color: " + ScrubyColors.TEXT_DISABLED + "; padding-top: 2; text-align: center;\">").append(esc(ScrubyLang.get(locale, "ui.skills.choice_locked", reqLevel))).append("</p>");
            sb.append("</div>");
            sb.append("</div>");
        } else if (done) {
            // Done state — milestone-style tile matching achieved skills
            String chosenId = chosen0 ? opts[0] : opts[1];
            sb.append(milestoneTile(
                    skillName(locale, chosenId),
                    skillType(locale, chosenId),
                    skillService.getSkillDescription(chosenId, locale),
                    reqLevel,
                    cTheme,
                    true,
                    true));
        } else {
            // Open choice state — three path-framed boxes (header, detail, confirm) + card row
            boolean selecting0 = opts[0].equals(pendingChoice);
            boolean selecting1 = opts[1].equals(pendingChoice);
            boolean anySelected = pendingChoice != null;

            // Shared box style values (2px path border + 14% path fill, same as choice cards)
            String boxBorder = ScrubyColors.toRgba(cTheme.accent(), 0.80f) + " 2";
            String boxFill = ScrubyColors.toRgba(cTheme.accent(), 0.14f);

            sb.append("<div style=\"layout-mode: Top; background-color: ").append(cTheme.tileBorderUnlocked()).append(" 1;\">");
            sb.append("<div style=\"layout-mode: Top; padding: 8; background-color: ").append(cTheme.tileBgUnlocked()).append(";\">");

            // Header box — "CHOICE 1 -- Lv12" in a framed tint box
            sb.append("<div style=\"layout-mode: Top; background-color: ").append(boxBorder).append(";\">");
            sb.append("<div style=\"layout-mode: Top; padding: 12 8; background-color: ").append(boxFill).append(";\">");
            sb.append("<p style=\"font-size: 9; color: ").append(ScrubyColors.TEXT_BRIGHT)
              .append("; font-weight: bold; letter-spacing: 1; text-align: center;\">")
              .append(esc(ScrubyLang.get(locale, slot == 1 ? "ui.skills.choice1" : "ui.skills.choice2")))
              .append("</p>");
            sb.append("</div></div>");
            sb.append("<p style=\"anchor-height: 6; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");

            // Two compact card-buttons side by side — 4px gap via padding-right on card A
            sb.append("<div style=\"layout-mode: Left;\">");
            sb.append(choiceOption(prefix + "a", opts[0], selecting0, true, locale, cTheme));
            sb.append(choiceOption(prefix + "b", opts[1], selecting1, false, locale, cTheme));
            sb.append("</div>");
            sb.append("<p style=\"anchor-height: 6; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");

            // Description box — pending selection detail OR placeholder, framed the same way
            sb.append("<div style=\"layout-mode: Top; background-color: ").append(boxBorder).append(";\">");
            sb.append("<div style=\"layout-mode: Top; padding: 14 8; background-color: ").append(boxFill).append(";\">");
            if (anySelected) {
                String chosenId = selecting0 ? opts[0] : opts[1];
                String desc = skillService.getSkillDescription(chosenId, locale);
                String scenario = skillScenario(locale, chosenId);
                sb.append("<p style=\"font-size: 10; color: ").append(ScrubyColors.TEXT_BRIGHT).append("; text-align: center;\">")
                  .append(esc(desc)).append("</p>");
                if (!scenario.isEmpty()) {
                    sb.append("<p style=\"font-size: 10; color: ").append(ScrubyColors.TEXT_BRIGHT)
                      .append("; font-style: italic; padding-top: 2; text-align: center;\">").append(esc(scenario)).append("</p>");
                }
            } else {
                sb.append("<p style=\"font-size: 10; color: ").append(ScrubyColors.TEXT_BRIGHT)
                  .append("; font-style: italic; text-align: center;\">")
                  .append(esc(ScrubyLang.get(locale, "ui.skills.choice_placeholder"))).append("</p>");
            }
            sb.append("</div></div>");
            sb.append("<p style=\"anchor-height: 6; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>");

            // Confirm box — path-framed wrapper around the gold CONFIRM button
            sb.append("<div style=\"layout-mode: Top; background-color: ").append(boxBorder).append(";\">");
            sb.append("<div style=\"layout-mode: Top; background-color: ").append(boxFill).append(";\">");
            sb.append("<button id=\"confirm-").append(prefix).append("\" class=\"custom-textbutton\"");
            if (anySelected) {
                sb.append(" data-hyui-default-label-style=\"@GoldBtnLabel\"")
                  .append(" data-hyui-hovered-label-style=\"@GoldBtnLabel\"")
                  .append(" data-hyui-default-bg=\"@GoldBtnBg\"")
                  .append(" data-hyui-hovered-bg=\"@GoldBtnHoverBg\"");
            } else {
                sb.append(" disabled")
                  .append(" data-hyui-default-label-style=\"@GoldBtnDisabledLabel\"")
                  .append(" data-hyui-hovered-label-style=\"@GoldBtnDisabledLabel\"")
                  .append(" data-hyui-disabled-label-style=\"@GoldBtnDisabledLabel\"")
                  .append(" data-hyui-default-bg=\"@GoldBtnDisabledBg\"")
                  .append(" data-hyui-hovered-bg=\"@GoldBtnDisabledBg\"")
                  .append(" data-hyui-disabled-bg=\"@GoldBtnDisabledBg\"");
            }
            sb.append(" style=\"anchor-height: 28;\">")
              .append(esc(ScrubyLang.get(locale, "ui.pathchoice.confirm")))
              .append("</button>");
            sb.append("</div></div>");

            sb.append("</div>"); // inner padding div
            sb.append("</div>"); // outer border div
        }

        return sb.toString();
    }

    private String choiceOption(String id, String skillId, boolean selected, boolean isFirst, String locale, ScrubyColors.PathTheme theme) {
        String name = skillName(locale, skillId);

        // Both states use semi-transparent button bg so the outer 2px border stays visible.
        String labelStyle = selected ? "@ChoiceSelLabel" : "@ChoiceIdleLabel";
        String bgStyle = selected ? "@ChoiceSelBg" : "@ChoiceIdleBg";
        String hoverBg = selected ? "@ChoiceSelBg" : "@ChoiceHoverBg";
        String cardBorder = selected
                ? "rgba(200,170,110,0.80) 2"
                : ScrubyColors.toRgba(theme.accent(), 0.80f) + " 2";
        String gapPadding = isFirst ? " padding-right: 4;" : "";

        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"flex-weight: 1; layout-mode: Top;").append(gapPadding).append("\">");
        sb.append("<div style=\"layout-mode: Top; padding-top: 2; padding-bottom: 2; background-color: ").append(cardBorder).append(";\">");
        sb.append("<button id=\"").append(id).append("\" class=\"custom-textbutton\"")
          .append(" data-hyui-default-label-style=\"").append(labelStyle).append("\"")
          .append(" data-hyui-hovered-label-style=\"").append(labelStyle).append("\"")
          .append(" data-hyui-default-bg=\"").append(bgStyle).append("\"")
          .append(" data-hyui-hovered-bg=\"").append(hoverBg).append("\"")
          .append(" style=\"anchor-height: 48;\">")
          .append(esc(name))
          .append("</button>");
        sb.append("</div>"); // outer border
        sb.append("</div>"); // flex wrapper
        return sb.toString();
    }


    // ===== Right Column: Attributes =====

    private String buildAttributes(@Nonnull CompanionProfile profile, @Nonnull AttributeDraft draft, @Nonnull String locale) {
        int spent = draft.totalSpent();
        boolean hasPoints = draft.remaining > 0;
        ScrubyColors.PathTheme aTheme = ScrubyColors.pathTheme(profile.getPathChoice());

        StringBuilder sb = new StringBuilder();

        // Section header row (path-colored)
        sb.append("<div style=\"layout-mode: Left; anchor-height: 22; padding: 4 8 0 8;\">");
        sb.append("<p style=\"font-size: 9; color: ").append(aTheme.accent()).append("; font-weight: bold;\">").append(esc(ScrubyLang.get(locale, "ui.attr.title"))).append("</p>");
        sb.append("<p style=\"flex-weight: 1; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        sb.append("<p id=\"attr-remaining\" style=\"font-size: 9; color: ").append(hasPoints ? aTheme.accent() + "; font-weight: bold" : ScrubyColors.TEXT_MUTED)
          .append(";\">").append(hasPoints ? esc(ScrubyLang.get(locale, "ui.attr.points", draft.remaining)) : spent + "/" + (draft.remaining + spent)).append("</p>");
        sb.append("</div>");

        // Separator (path-colored)
        sb.append("<p style=\"anchor-height: 1; background-color: ").append(aTheme.separator())
          .append("; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

        sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

        // Three attribute cards
        sb.append(attrCard(ScrubyLang.get(locale, "ui.attr.vitality"), ScrubyLang.get(locale, "ui.attr.hp", draft.vit * 8),
                draft.vit, draft.savedVit, draft.remaining,
                ScrubyColors.VIT_COLOR, ScrubyColors.VIT_BG, "@AttrVitBtnBg", "vit"));
        sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        sb.append(attrCard(ScrubyLang.get(locale, "ui.attr.strength"), ScrubyLang.get(locale, "ui.attr.dmg", draft.str * 6),
                draft.str, draft.savedStr, draft.remaining,
                ScrubyColors.STR_COLOR, ScrubyColors.STR_BG, "@AttrStrBtnBg", "str"));
        sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
        sb.append(attrCard(ScrubyLang.get(locale, "ui.attr.zeal"), ScrubyLang.get(locale, "ui.attr.cd", draft.zel * 3),
                draft.zel, draft.savedZel, draft.remaining,
                ScrubyColors.EIF_COLOR, ScrubyColors.EIF_BG, "@AttrEifBtnBg", "zel"));

        // Confirm button — only when draft has unsaved changes (appears after first reopen)
        if (draft.hasChanges()) {
            sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");
            sb.append("<button id=\"confirm-attr\" class=\"custom-textbutton\""
                    + " data-hyui-default-label-style=\"@GoldBtnLabel\""
                    + " data-hyui-hovered-label-style=\"@GoldBtnLabel\""
                    + " data-hyui-default-bg=\"@GoldBtnBg\""
                    + " data-hyui-hovered-bg=\"@GoldBtnHoverBg\""
                    + " data-hyui-pressed-bg=\"@GoldBtnHoverBg\""
                    + " style=\"anchor-height: 28;\">").append(esc(ScrubyLang.get(locale, "ui.attr.confirm"))).append("</button>");
        }

        return sb.toString();
    }

    /**
     * Single attribute card: name + sublabel on the left, value in the center,
     * plus/minus buttons on the right. Uses layout-mode: Left with flex-weight
     * so the left side expands naturally.
     */
    private String attrCard(
            String name, String sublabel,
            int value, int savedValue, int remaining,
            String color, String bgColor, String btnBgRef, String idPrefix
    ) {
        boolean changed = value != savedValue;
        String valueColor = changed ? color : ScrubyColors.TEXT_BRIGHT;
        boolean canPlus = remaining > 0;
        boolean canMinus = value > savedValue;

        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"layout-mode: Left; background-color: ").append(bgColor)
          .append("; padding: 6 8; anchor-height: 46;\">");

        // Left label cell: flex-weight 1 without layout-mode so the inner stack can
        // be absolutely anchored. anchor-left: 10 on top of the card's 8 padding = 18
        // from card left; anchor-top: 5 visually centers the ~25px stack in the 34px
        // card content area.
        sb.append("<div style=\"flex-weight: 1;\">");
        sb.append("<div style=\"anchor-left: 6; anchor-top: 10; layout-mode: Top;\">");
        sb.append("<p style=\"font-size: 12; color: ").append(color)
          .append("; font-weight: bold;\">").append(esc(name)).append("</p>");
        sb.append("<p id=\"").append(idPrefix).append("-sub\" style=\"font-size: 9; color: ").append(ScrubyColors.TEXT_MUTED)
          .append("; padding-top: 1;\">").append(esc(sublabel)).append("</p>");
        sb.append("</div>");
        sb.append("</div>");

        // Value
        sb.append("<p id=\"").append(idPrefix).append("-val\" style=\"font-size: 14; color: ").append(valueColor)
          .append("; font-weight: bold; vertical-align: center; padding: 0 10;\">")
          .append(value).append("</p>");

        // Derive hover ref from default ref: @AttrVitBtnBg -> @AttrVitBtnHoverBg
        String btnHoverBgRef = btnBgRef.replace("BtnBg", "BtnHoverBg");

        // Minus button — only active when unsaved points exist (value > savedValue)
        sb.append("<button id=\"").append(idPrefix).append("-minus\" class=\"custom-textbutton\"")
          .append(" data-hyui-default-label-style=\"@AttrBtnLabel\"")
          .append(" data-hyui-hovered-label-style=\"@AttrBtnLabel\"")
          .append(" data-hyui-default-bg=\"@AttrBtnIdleBg\"")
          .append(" data-hyui-hovered-bg=\"").append(btnHoverBgRef).append("\"")
          .append(" data-hyui-pressed-bg=\"").append(btnHoverBgRef).append("\"")
          .append(" data-hyui-disabled-bg=\"@AttrBtnIdleBg\"")
          .append(" data-hyui-disabled-label-style=\"@AttrBtnDisabledLabel\"")
          .append(" style=\"anchor-width: 26; anchor-height: 28; vertical-align: center;\"");
        if (!canMinus) sb.append(" disabled");
        sb.append(">-</button>");

        // Spacer between minus and plus
        sb.append("<div style=\"anchor-width: 4;\"><p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p></div>");

        // Plus button
        sb.append("<button id=\"").append(idPrefix).append("-plus\" class=\"custom-textbutton\"")
          .append(" data-hyui-default-label-style=\"@AttrBtnLabel\"")
          .append(" data-hyui-hovered-label-style=\"@AttrBtnLabel\"")
          .append(" data-hyui-default-bg=\"@AttrBtnIdleBg\"")
          .append(" data-hyui-hovered-bg=\"").append(btnHoverBgRef).append("\"")
          .append(" data-hyui-pressed-bg=\"").append(btnHoverBgRef).append("\"")
          .append(" data-hyui-disabled-bg=\"@AttrBtnIdleBg\"")
          .append(" data-hyui-disabled-label-style=\"@AttrBtnDisabledLabel\"")
          .append(" style=\"anchor-width: 26; anchor-height: 28; vertical-align: center; anchor-right: 10;\"");
        if (!canPlus) sb.append(" disabled");
        sb.append(">+</button>");

        sb.append("</div>");
        return sb.toString();
    }

    // ===== Right Column: Evolution =====

    private String buildEvolution(@Nonnull CompanionProfile profile, @Nonnull String locale) {
        int stage = profile.getEvolutionStage();
        ScrubyColors.PathTheme eTheme = ScrubyColors.pathTheme(profile.getPathChoice());
        StringBuilder sb = new StringBuilder();

        // Section header (path-colored)
        sb.append("<div style=\"layout-mode: Left; anchor-height: 22; padding: 4 8 0 8;\">");
        sb.append("<p style=\"font-size: 9; color: ").append(eTheme.accent()).append("; font-weight: bold;\">").append(esc(ScrubyLang.get(locale, "ui.evolution.title"))).append("</p>");
        sb.append("</div>");

        // Separator (path-colored)
        sb.append("<p style=\"anchor-height: 1; background-color: ").append(eTheme.separator())
          .append("; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

        sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

        // Three evolution stage boxes side by side with spacers
        String path = profile.getPathChoice();
        sb.append("<div style=\"layout-mode: Left; padding: 0 8;\">");
        sb.append(evoBox("I",   ScrubyLang.getEvolutionName(locale, 1, path),  stage >= 1, stage == 1, eTheme));
        sb.append("<div style=\"anchor-width: 3;\"><p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p></div>");
        sb.append(evoBox("II",  ScrubyLang.getEvolutionName(locale, 2, path),  stage >= 2, stage == 2, eTheme));
        sb.append("<div style=\"anchor-width: 3;\"><p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p></div>");
        sb.append(evoBox("III", ScrubyLang.getEvolutionName(locale, 3, path),  stage >= 3, stage == 3, eTheme));
        sb.append("</div>");

        return sb.toString();
    }

    private String evoBox(String num, String name, boolean reached, boolean current, ScrubyColors.PathTheme theme) {
        String numColor, nameColor, outerBg, innerBg, barColor;
        if (current) {
            numColor = theme.accent();
            nameColor = ScrubyColors.TEXT_BRIGHT;
            outerBg = theme.evoBorderCurrent();
            innerBg = theme.evoBgCurrent();
            barColor = theme.evoBarCurrent();
        } else if (reached) {
            numColor = ScrubyColors.TEXT_MUTED;
            nameColor = theme.secondary();
            outerBg = theme.evoBorderReached() + " 1";
            innerBg = theme.evoBgReached();
            barColor = theme.evoBarReached();
        } else {
            numColor = "#5a6a7a";
            nameColor = "#4a5a6a";
            outerBg = "rgba(80,80,80,0.30) 1";
            innerBg = "rgba(255,255,255,0.03)";
            barColor = "#1a2030";
        }

        return "<div style=\"flex-weight: 1; anchor-height: 52; background-color: " + outerBg + "; layout-mode: Top;\">"
                + "<div style=\"flex-weight: 1; background-color: " + innerBg + "; layout-mode: Top;\">"
                + "<p style=\"font-size: 14; color: " + numColor + "; font-weight: bold; text-align: center; padding-top: 6;\">" + num + "</p>"
                + "<p style=\"font-size: 9; color: " + nameColor + "; text-align: center;\">" + esc(name) + "</p>"
                + "</div>"
                + "<p style=\"anchor-height: 3; background-color: " + barColor + "; font-size: 1; color: " + ScrubyColors.BG_SURFACE + ";\"> </p>"
                + "</div>";
    }

    // ===== Right Column: Stats =====

    private String buildStats(@Nonnull CompanionProfile profile, @Nonnull AttributeDraft draft, @Nonnull String locale) {
        String all = allAbilities(profile);

        // Max-HP: base 40 + vit bonus + skill bonuses
        int vitBonus = draft.vit * 8;
        if (all.contains(ScrubySkillService.TANK_DICKER_PELZ))   vitBonus += 16;
        if (all.contains(ScrubySkillService.TANK_W1A_EISENHAUT)) vitBonus += 10;
        int maxHp = 40 + vitBonus;

        // Skill count
        int sk = 0;
        if (!profile.getFixedAbilities().isEmpty())  sk += profile.getFixedAbilities().split(",").length;
        if (!profile.getChosenAbilities().isEmpty()) sk += profile.getChosenAbilities().split(",").length;
        int maxSk = "NONE".equals(profile.getPathChoice()) ? 1 : 6;

        boolean vitChanged = draft.vit != draft.savedVit;
        boolean strChanged = draft.str != draft.savedStr;
        boolean zelChanged = draft.zel != draft.savedZel;

        ScrubyColors.PathTheme sTheme = ScrubyColors.pathTheme(profile.getPathChoice());
        StringBuilder sb = new StringBuilder();

        // Section header (path-colored)
        sb.append("<div style=\"layout-mode: Left; anchor-height: 22; padding: 4 8 0 8;\">");
        sb.append("<p style=\"font-size: 9; color: ").append(sTheme.accent()).append("; font-weight: bold;\">").append(esc(ScrubyLang.get(locale, "ui.stats.title"))).append("</p>");
        sb.append("</div>");

        // Separator (path-colored)
        sb.append("<p style=\"anchor-height: 1; background-color: ").append(sTheme.separator())
          .append("; font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

        sb.append("<p style=\"font-size: 1; color: ").append(ScrubyColors.BG_SURFACE).append(";\"> </p>");

        // Stats container with border
        sb.append("<div style=\"layout-mode: Top; background-color: ").append(ScrubyColors.BG_CARD)
          .append("; background-color: rgba(255,255,255,0.03) 1; padding: 4 8;\">");

        sb.append(statRow(ScrubyLang.get(locale, "ui.stats.maxhp"), String.valueOf(maxHp),
                vitChanged ? "#ff8888" : ScrubyColors.VIT_COLOR, "stat-hp"));
        sb.append(statSep());
        sb.append(statRow(ScrubyLang.get(locale, "ui.stats.damage"), "+" + (draft.str * 6) + "%",
                strChanged ? "#ffdd66" : ScrubyColors.STR_COLOR, "stat-dmg"));
        sb.append(statSep());
        sb.append(statRow(ScrubyLang.get(locale, "ui.stats.cooldown"), "-" + (draft.zel * 3) + "%",
                zelChanged ? "#66bbff" : ScrubyColors.EIF_COLOR, "stat-cd"));
        sb.append(statSep());
        sb.append(statRow(ScrubyLang.get(locale, "ui.stats.skills"), sk + "/" + maxSk, ScrubyColors.GREEN_PRIMARY));

        sb.append("</div>");
        return sb.toString();
    }

    /** Single stat row: label on left (flex-weight 1), value on right. Optional id on value label. */
    private String statRow(String label, String value, String valueColor) {
        return statRow(label, value, valueColor, null);
    }

    private String statRow(String label, String value, String valueColor, @Nullable String valueId) {
        return "<div style=\"layout-mode: Left; anchor-height: 26;\">"
                + "<p style=\"flex-weight: 1; font-size: 11; color: " + ScrubyColors.TEXT_SECONDARY
                + "; vertical-align: center; padding-left: 6;\">" + esc(label) + "</p>"
                + "<p" + (valueId != null ? " id=\"" + valueId + "\"" : "") + " style=\"font-size: 12; font-weight: bold; color: " + valueColor
                + "; vertical-align: center; padding-right: 6;\">" + esc(value) + "</p>"
                + "</div>";
    }

    /** Thin separator line between stat rows — uses a non-empty paragraph as spacer. */
    private String statSep() {
        return "<p style=\"anchor-height: 1; background-color: " + ScrubyColors.BORDER_SUBTLE
                + "; font-size: 1; color: " + ScrubyColors.BORDER_SUBTLE + ";\"> </p>";
    }

    // ===== Style Block =====

    /**
     * Path-themed idle/hover colors for the choice cards (War Cry / Loyal Protector
     * slots). All three paths share the same brightness/saturation as TANK's
     * #2e436a, just rotated in hue so each path gets its own look.
     */
    private static String choiceIdleBg(String path) {
        // Semi-transparent path tint at 14% alpha — same pattern as @ChoiceSelBg (gold 14%)
        // so the outer 2px path border stays visible through the button fill.
        return switch (path) {
            case "HEALER" -> "rgba(74,154,90,0.14)";
            case "DPS" -> "rgba(224,90,58,0.14)";
            case "TANK" -> "rgba(74,122,200,0.14)";
            default -> "rgba(255,255,255,0.04)";
        };
    }

    private static String choiceHoverBg(String path) {
        // Slightly stronger tint for hover; still semi-transparent so border stays visible.
        return switch (path) {
            case "HEALER" -> "rgba(74,154,90,0.25)";
            case "DPS" -> "rgba(224,90,58,0.25)";
            case "TANK" -> "rgba(74,122,200,0.25)";
            default -> "rgba(255,255,255,0.08)";
        };
    }

    /** Path-primary color at 80% alpha with " 2" border suffix — used as idle border for choice cards. */
    private static String choicePathBorder(String path) {
        return switch (path) {
            case "HEALER" -> "rgba(74,154,90,0.80) 2";
            case "DPS" -> "rgba(224,90,58,0.80) 2";
            case "TANK" -> "rgba(74,122,200,0.80) 2";
            default -> "rgba(255,255,255,0.30) 2";
        };
    }

    private static String buildStyleBlock(String path) {
        return "<style>"
                // ── Column layout classes ──
                + ".col-skills { anchor-width: 265; layout-mode: Top; background-color: " + ScrubyColors.BG_SURFACE + "; }"
                + ".col-center { flex-weight: 1; layout-mode: Top; }"
                + ".col-sidebar { anchor-width: 255; layout-mode: Top; background-color: " + ScrubyColors.BG_SURFACE + "; }"
                // ── Gold button states ──
                + "@GoldBtnLabel { color: " + ScrubyColors.BG_PAGE + "; font-size: 14; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@GoldBtnBg { background-color: " + ScrubyColors.GOLD + "; }"
                + "@GoldBtnHoverBg { background-color: #ddbf7e; }"
                + "@GoldBtnDisabledLabel { color: " + ScrubyColors.TEXT_BRIGHT + "; font-size: 14; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@GoldBtnDisabledBg { background-color: rgba(200,170,110,0.22); }"
                // ── Confirm button hidden state (blends into surface) ──
                + "@ConfirmHiddenLabel { color: " + ScrubyColors.BG_SURFACE + "; font-size: 14; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@ConfirmHiddenBg { background-color: " + ScrubyColors.BG_SURFACE + "; }"
                // ── Green button states ──
                + "@GreenBtnLabel { color: " + ScrubyColors.BG_PAGE + "; font-size: 12; font-weight: bold; }"
                + "@GreenBtnBg { background-color: " + ScrubyColors.GREEN_PRIMARY + "; }"
                // ── Danger button states ──
                + "@DangerBtnLabel { color: " + ScrubyColors.TEXT_BRIGHT + "; font-size: 12; font-weight: bold; }"
                + "@DangerBtnBg { background-color: #5a2020; }"
                // ── Attribute button states ──
                + "@AttrBtnLabel { color: " + ScrubyColors.TEXT_BRIGHT + "; font-size: 12; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@AttrBtnIdleBg { background-color: #1e2a3a; }"
                + "@AttrBtnDisabledLabel { color: #5a6a7a; font-size: 12; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@AttrVitBtnHoverBg { background-color: #2e2020; }"
                + "@AttrStrBtnHoverBg { background-color: #2e2a14; }"
                + "@AttrEifBtnHoverBg { background-color: #1a2840; }"
                // ── Scrubys tab button states ──
                + "@ScrubySwitchLabel { color: #e8edf2; font-size: 12; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@ScrubySwitchBg { background-color: #2a7a4a; }"
                + "@ScrubySwitchHoverBg { background-color: #34a85c; }"
                + "@ScrubyGoldLabel { color: #f0e8c8; font-size: 12; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@ScrubyGoldBg { background-color: #8a7428; }"
                + "@ScrubyGoldHoverBg { background-color: #a8901e; }"
                + "@ScrubySecLabel { color: #8a9aaa; font-size: 12; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@ScrubySecBg { background-color: #2a3a4e; }"
                + "@ScrubySecHoverBg { background-color: #354860; }"
                + "@ScrubyDelLabel { color: #e85050; font-size: 14; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@ScrubyDelBg { background-color: #5a2020; }"
                + "@ScrubyDelHoverBg { background-color: #8a2828; }"
                + "@ScrubyCreateLabel { color: #60d888; font-size: 12; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@ScrubyCreateBg { background-color: #1a5a3a; }"
                + "@ScrubyCreateHoverBg { background-color: #248a50; }"
                // ── Skill choice card button states ──
                // Both idle and selected use semi-transparent bg (14% alpha) so the outer
                // 2px path border stays visible through the button fill.
                + "@ChoiceIdleLabel { color: " + ScrubyColors.TEXT_BRIGHT + "; font-size: 11; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@ChoiceIdleBg { background-color: " + choiceIdleBg(path) + "; }"
                + "@ChoiceHoverBg { background-color: " + choiceHoverBg(path) + "; }"
                + "@ChoiceSelLabel { color: " + ScrubyColors.TEXT_BRIGHT + "; font-size: 11; font-weight: bold; text-align: center; vertical-align: center; }"
                + "@ChoiceSelBg { background-color: rgba(200,170,110,0.14); }"
                // ── Path card button states ──
                + "@PathCardBg { background-color: " + ScrubyColors.BG_CARD + "; }"
                + "@PathCardHoverBg { background-color: " + ScrubyColors.BG_PANEL + "; }"
                // ── Prestige button states ──
                + "@PrestigeBtnLabel { color: #0c0a04; font-size: 14; font-weight: bold; text-align: center; vertical-align: center; letter-spacing: 1; }"
                + "@PrestigeBtnBg { background-color: #c8a832; }"
                + "@PrestigeBtnHoverBg { background-color: #daa520; }"
                // ── Close button (X top-right, icon-based) ──
                + "@CloseBtnLabel { color: rgba(0,0,0,0); font-size: 1; }"
                + "@CloseBtnBg { background-image: url('icons/close.png'); }"
                + "@CloseBtnHoverBg { background-image: url('icons/close_choosen.png'); }"
                + "@ReturnBtnLabel { color: rgba(0,0,0,0); font-size: 1; }"
                + "@ReturnBtnBg { background-image: url('icons/return.png'); }"
                + "@ReturnBtnHoverBg { background-image: url('icons/return_choosen.png'); }"
                // ── Combat mode icon buttons ──
                + "@CombatBtnLabel { color: rgba(0,0,0,0); font-size: 1; }"
                // ── Station mode icon buttons ──
                + "@StRallyBg { background-image: url('icons/rally-the-troops.png'); }"
                + "@StRallyChosenBg { background-image: url('icons/rally-the-troops_choosen.png'); }"
                + "@StBaseBg { background-image: url('icons/player-base.png'); }"
                + "@StBaseChosenBg { background-image: url('icons/player-base_choosen.png'); }"
                + "@CombatPassiveBg { background-image: url('icons/shield-bounces.png'); }"
                + "@CombatPassiveChosenBg { background-image: url('icons/shield-bounces-choosen.png'); }"
                + "@CombatAggressiveBg { background-image: url('icons/crossed-swords.png'); }"
                + "@CombatAggressiveChosenBg { background-image: url('icons/crossed-swords-choosen.png'); }"
                + "</style>";
    }

    // ===== Page Wrapper =====

    /**
     * Wraps content in the page-overlay > decorated-container > container-contents structure
     * with two tabs: Startseite and Scrubys.
     *
     * @param startseiteContent HTML content for the Startseite (main skill tree) tab
     * @param scrubysContent    HTML content for the Scrubys management tab
     * @param selectedTab       which tab to show as active ("startseite" or "scrubys")
     */
    private static String pageWrap(String startseiteContent, String scrubysContent, String selectedTab, String locale) {
        return pageWrap(startseiteContent, scrubysContent, selectedTab, locale, 860);
    }

    private static String pageWrap(String startseiteContent, String scrubysContent, String selectedTab, String locale, int height) {
        return pageWrap(startseiteContent, scrubysContent, selectedTab, locale, height, true);
    }

    private static String pageWrap(String startseiteContent, String scrubysContent, String selectedTab, String locale, int height, boolean scrubysTabEnabled) {
        String tabHome = esc(ScrubyLang.get(locale, "ui.skilltree.tab.home"));
        String tabScrubys = esc(ScrubyLang.get(locale, "ui.skilltree.tab.scrubys"));

        String tabBar;
        if (scrubysTabEnabled) {
            tabBar = "<nav id=\"skilltree-tabs\" class=\"tabs\""
                    + " data-tabs=\"startseite:" + tabHome + ":startseite-content,scrubys:" + tabScrubys + ":scrubys-content\""
                    + " data-selected=\"" + selectedTab + "\"></nav>";
        } else {
            // No tab bar (e.g. path choice view — only the startseite content is rendered)
            tabBar = "";
        }

        // Close button placed inside container-title so it sits on the decorated title bar
        String titleBar = "<div class=\"container-title\">"
                + "<button id=\"scruby-close\" class=\"custom-textbutton\""
                + " data-hyui-default-label-style=\"@CloseBtnLabel\""
                + " data-hyui-hovered-label-style=\"@CloseBtnLabel\""
                + " data-hyui-pressed-label-style=\"@CloseBtnLabel\""
                + " data-hyui-default-bg=\"@CloseBtnBg\""
                + " data-hyui-hovered-bg=\"@CloseBtnHoverBg\""
                + " data-hyui-pressed-bg=\"@CloseBtnHoverBg\""
                + " style=\"anchor-width: 26; anchor-height: 26; horizontal-align: right; anchor-right: 8;\"> </button>"
                + "</div>";

        return "<div class=\"page-overlay\">"
                + "<div class=\"decorated-container\" data-hyui-title=\"" + esc(ScrubyLang.get(locale, "ui.skilltree.title")) + "\" style=\"anchor-width: 940; anchor-height: " + height + ";\">"
                + titleBar
                + "<div class=\"container-contents\" style=\"layout-mode: Top;\">"
                + tabBar
                // Startseite tab content
                + "<div id=\"startseite-content\" class=\"tab-content\" data-hyui-tab-id=\"startseite\" style=\"flex-weight: 1; layout-mode: Top;\">"
                + startseiteContent
                + "</div>"
                // Scrubys tab content
                + (scrubysTabEnabled
                        ? "<div id=\"scrubys-content\" class=\"tab-content\" data-hyui-tab-id=\"scrubys\" style=\"flex-weight: 1; layout-mode: Top;\">"
                          + scrubysContent
                          + "</div>"
                        : "")
                // Footer
                + "<p style=\"font-size: 10; color: " + ScrubyColors.TEXT_MUTED + "; text-align: center;\">" + esc(ScrubyLang.get(locale, "ui.skilltree.close")) + "</p>"
                + "</div></div></div>";
    }

    // ===== Utility =====

    /** Build a List<ItemGridSlot> from the profile's inventory for use with ItemGridBuilder.withSlots(). */
    private List<ItemGridSlot> buildSlotList(@Nonnull CompanionProfile profile) {
        java.util.List<ScrubyInventoryService.InventoryEntry> invItems = inventoryService.getItems(profile);
        ScrubyInventoryService.InventoryEntry[] slotLookup = new ScrubyInventoryService.InventoryEntry[ScrubyInventoryService.MAX_SLOTS];
        for (ScrubyInventoryService.InventoryEntry entry : invItems) {
            if (entry.getSlotIndex() >= 0 && entry.getSlotIndex() < ScrubyInventoryService.MAX_SLOTS) {
                slotLookup[entry.getSlotIndex()] = entry;
            }
        }
        List<ItemGridSlot> slots = new ArrayList<>(ScrubyInventoryService.MAX_SLOTS);
        for (int i = 0; i < ScrubyInventoryService.MAX_SLOTS; i++) {
            ScrubyInventoryService.InventoryEntry entry = slotLookup[i];
            if (entry != null) {
                slots.add(new ItemGridSlot(new ItemStack(entry.getItemId(), entry.getQuantity())));
            } else {
                slots.add(new ItemGridSlot());
            }
        }
        return slots;
    }

    /** Update the skilltree inventory grid in-place without page reopen. */
    private boolean tryInPlaceGridUpdate(
            @Nonnull au.ellie.hyui.events.UIContext ctx,
            @Nonnull CompanionProfile profile,
            @Nonnull String locale
    ) {
        try {
            List<ItemGridSlot> newSlots = buildSlotList(profile);
            ctx.getById("scruby-inv-grid", ItemGridBuilder.class).ifPresent(grid ->
                    grid.withSlots(newSlots));
            // Update count label from inventory service (ItemGridSlot may not expose getItemStack)
            int occupied = inventoryService.getItems(profile).size();
            ctx.getById("inv-count", LabelBuilder.class).ifPresent(l ->
                    l.withText(ScrubyLang.get(locale, "ui.scrubys.inventory", occupied)));
            ctx.updatePage(true);
            return true;
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Inv] In-place grid update failed, falling back to reopen: " + e.getMessage());
            return false;
        }
    }

    private static String getSkillsTitle(@Nonnull String path, @Nonnull String locale) {
        return switch (path) {
            case "DPS" -> ScrubyLang.get(locale, "ui.skills.title.dps");
            case "HEALER" -> ScrubyLang.get(locale, "ui.skills.title.healer");
            case "TANK" -> ScrubyLang.get(locale, "ui.skills.title.tank");
            default -> ScrubyLang.get(locale, "ui.skills.title");
        };
    }

    private static String fixedSkill(@Nonnull String path, int tier) {
        return switch (path) {
            case "DPS" -> switch (tier) { case 1 -> ScrubySkillService.DPS_BRENNENDE_PFEILE; case 2 -> ScrubySkillService.DPS_FOKUSFEUER; case 3 -> ScrubySkillService.DPS_RAUBTIERSINNE; default -> ""; };
            case "HEALER" -> switch (tier) { case 1 -> ScrubySkillService.HEALER_ERSTE_HILFE; case 2 -> ScrubySkillService.HEALER_REGENERATION; case 3 -> ScrubySkillService.HEALER_SEGEN_DES_HUETERS; default -> ""; };
            case "TANK" -> switch (tier) { case 1 -> ScrubySkillService.TANK_PROVOKATION; case 2 -> ScrubySkillService.TANK_DICKER_PELZ; case 3 -> ScrubySkillService.TANK_VERGELTUNG; default -> ""; };
            default -> "";
        };
    }

    @Nonnull
    private static String allAbilities(@Nonnull CompanionProfile p) {
        String f = p.getFixedAbilities(); String c = p.getChosenAbilities();
        if (f.isEmpty()) return c; if (c.isEmpty()) return f; return f + "," + c;
    }

    /** Update all attribute labels in-place via ctx — avoids full page reopen for fast clicking. */
    private void updateAttrLabels(au.ellie.hyui.events.UIContext context, AttributeDraft draft, CompanionProfile profile, String locale) {
        try {

            // Attribute values
            context.getById("vit-val", LabelBuilder.class).ifPresent(l -> l.withText(String.valueOf(draft.vit)));
            context.getById("str-val", LabelBuilder.class).ifPresent(l -> l.withText(String.valueOf(draft.str)));
            context.getById("zel-val", LabelBuilder.class).ifPresent(l -> l.withText(String.valueOf(draft.zel)));

            // Sublabels
            context.getById("vit-sub", LabelBuilder.class).ifPresent(l -> l.withText(ScrubyLang.get(locale, "ui.attr.hp", draft.vit * 8)));
            context.getById("str-sub", LabelBuilder.class).ifPresent(l -> l.withText(ScrubyLang.get(locale, "ui.attr.dmg", draft.str * 6)));
            context.getById("zel-sub", LabelBuilder.class).ifPresent(l -> l.withText(ScrubyLang.get(locale, "ui.attr.cd", draft.zel * 3)));

            // Remaining points
            boolean hasPoints = draft.remaining > 0;
            context.getById("attr-remaining", LabelBuilder.class).ifPresent(l ->
                    l.withText(hasPoints ? ScrubyLang.get(locale, "ui.attr.points", draft.remaining) : draft.totalSpent() + "/" + (draft.remaining + draft.totalSpent())));

            // Stats
            String all = allAbilities(profile);
            int vb = draft.vit * 8;
            if (all.contains(ScrubySkillService.TANK_DICKER_PELZ))   vb += 16;
            if (all.contains(ScrubySkillService.TANK_W1A_EISENHAUT)) vb += 10;
            final int maxHp = 40 + vb;
            context.getById("stat-hp", LabelBuilder.class).ifPresent(l -> l.withText(String.valueOf(maxHp)));
            context.getById("stat-dmg", LabelBuilder.class).ifPresent(l -> l.withText("+" + (draft.str * 6) + "%"));
            context.getById("stat-cd", LabelBuilder.class).ifPresent(l -> l.withText("-" + (draft.zel * 3) + "%"));

            // Toggle confirm button appearance — text + style swap (visibility/display unreliable in HyUI)
            boolean showConfirm = draft.hasChanges();
            String confirmText = ScrubyLang.get(locale, "ui.attr.confirm");
            context.getById("confirm-attr", ButtonBuilder.class).ifPresent(btn ->
                    btn.withText(showConfirm ? confirmText : " "));

            context.updatePage(true);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Page] updateAttrLabels failed: " + e.getMessage());
        }
    }

    private static void tryAddListener(PageBuilder builder, String id,
            java.util.function.Consumer<Object> handler) {
        try {
            builder.addEventListener(id, CustomUIEventBindingType.Activating,
                    (event) -> handler.accept(event));
        } catch (Exception ignored) {}
    }

    /** Register a KeyDown listener for Enter (confirm) and Escape (cancel) on a dialog container. */
    private static void tryAddDialogKeyListener(PageBuilder builder, String elementId, Runnable confirmAction, Runnable cancelAction) {
        try {
            builder.addEventListener(elementId, CustomUIEventBindingType.KeyDown, (event) -> {
                String key = String.valueOf(event);
                if ("Return".equals(key) || "Enter".equals(key)) {
                    confirmAction.run();
                } else if ("Escape".equals(key) || "Esc".equals(key)) {
                    cancelAction.run();
                }
            });
        } catch (Exception ignored) {}
    }

    @Nonnull
    private static String esc(@Nullable String t) {
        if (t == null) return "";
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
