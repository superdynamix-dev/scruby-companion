package org.example.plugin;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.spatial.SpatialStructure;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PickupItemComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ticking system for ALL tick-based skill effects (22 skills).
 * Runs every 20 ticks (1 second).
 *
 * Skill categories processed per tick:
 * 1. Damage auras (Kampfinstinkt, Brennende Pfeile, Giftpfeile, Provokation, Lebensband)
 * 2. Focused/burst (Fokusfeuer, Doppelschuss, Kriegsschrei)
 * 3. Healing (Erste Hilfe, Regeneration, Schutzzauber, Segen des Hueters)
 * 4. HP-delta reactions (Dornen, Vergeltung, Treuer Beschuetzer)
 * 5. Self-heal + emergency (Eisenhaut, Notfall-Rettung)
 * 6. Combat report + HUD update
 */
public final class ScrubySkillTickingSystem extends TickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // === Timing constants ===
    private static final int TICK_INTERVAL = 20;           // 1 second at 20 TPS
    private static final int HUD_UPDATE_INTERVAL = 40;     // 2 seconds
    private static final double PROXIMITY_RANGE_SQ = 15.0 * 15.0;
    private static final int COMBAT_REPORT_INTERVAL = 200; // 10 seconds (200 ticks)
    private static final long INVENTORY_FULL_NOTIFY_MS = 10 * 60 * 1000; // 10 minutes

    // === Cooldowns ===
    private static final long ERSTE_HILFE_COOLDOWN_MS = 15_000L;
    private static final long NOTFALL_RETTUNG_COOLDOWN_MS = 30_000L;
    private static final long SCHUTZZAUBER_COOLDOWN_MS = 30_000L;
    private static final long DOPPELSCHUSS_COOLDOWN_MS = 8_000L;
    private static final long KRIEGSSCHREI_COOLDOWN_MS = 8_000L;

    // === Segen des Hueters modifier key ===
    private static final String MOD_SEGEN_DES_HUETERS = "scruby_segen_des_hueters";

    // === Services ===
    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyHudManager hudManager;
    private final ScrubySoundService soundService;
    private final ScrubyInventoryService inventoryService;

    // === Cooldown state maps ===
    private final Map<UUID, Long> lastErsteHilfeTime = new HashMap<>();
    private final Map<UUID, Long> lastNotfallRettungTime = new HashMap<>();
    private final Map<UUID, Long> lastSchutzzauberTime = new HashMap<>();
    private final Map<UUID, Long> lastDoppelschussTime = new HashMap<>();
    private final Map<UUID, Long> lastKriegsschreiTime = new HashMap<>();

    // === HP-delta tracking ===
    private final Map<UUID, Float> lastCompanionHp = new HashMap<>();
    private final Map<UUID, Float> lastPlayerHp = new HashMap<>();

    // === Segen tracking ===
    private final Map<UUID, Boolean> segenApplied = new HashMap<>();

    // === Inventory full tracking ===
    private final Map<UUID, Long> inventoryFullSince = new HashMap<>();

    // === Combat report accumulators ===
    private final Map<UUID, Float> reportDamageDealt = new HashMap<>();
    private final Map<UUID, Float> reportHealingDone = new HashMap<>();

    // === DPS Debug mode ===
    private static final int DPS_DEBUG_INTERVAL = 40; // 2 seconds
    private static final Set<UUID> dpsDebugPlayers = ConcurrentHashMap.newKeySet();

    public static void toggleDpsDebug(@Nonnull UUID ownerUuid) {
        if (!dpsDebugPlayers.remove(ownerUuid)) {
            dpsDebugPlayers.add(ownerUuid);
        }
    }

    public static boolean isDpsDebugActive(@Nonnull UUID ownerUuid) {
        return dpsDebugPlayers.contains(ownerUuid);
    }

    // === Attribute Debug mode (Strength + Zeal) ===
    private static final Set<UUID> attrDebugPlayers = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, java.util.Queue<String>> attrDebugQueue = new ConcurrentHashMap<>();

    public static void toggleAttrDebug(@Nonnull UUID ownerUuid) {
        if (!attrDebugPlayers.remove(ownerUuid)) {
            attrDebugPlayers.add(ownerUuid);
        } else {
            attrDebugQueue.remove(ownerUuid);
        }
    }

    public static boolean isAttrDebugActive(@Nonnull UUID ownerUuid) {
        return attrDebugPlayers.contains(ownerUuid);
    }

    public static void queueAttrDebug(@Nonnull UUID ownerUuid, @Nonnull String message) {
        if (!attrDebugPlayers.contains(ownerUuid)) return;
        attrDebugQueue
                .computeIfAbsent(ownerUuid, k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                .add(message);
    }

    private int tickCounter = 0;

    // === VFX: Cached entity effects ===
    private EntityEffect burnEffect;
    private EntityEffect poisonEffect;
    private final Map<UUID, Long> lastBurnVfxTime = new HashMap<>();
    private final Map<UUID, Long> lastPoisonVfxTime = new HashMap<>();
    private boolean effectsCacheAttempted = false;

    // === VFX: Periodic cooldown maps ===
    private final Map<UUID, Long> lastRaubtiersinneVfxTime = new HashMap<>();
    private final Map<UUID, Long> lastProvokationVfxTime = new HashMap<>();
    private final Map<UUID, Long> lastEisenhautVfxTime = new HashMap<>();
    private final Map<UUID, Long> lastLebensbandVfxTime = new HashMap<>();
    private final Map<UUID, Long> lastTreuerBeschuetzerVfxTime = new HashMap<>();

    // === VFX: Interval constants ===
    private static final long VFX_INTERVAL_5S = 5_000L;
    private static final long VFX_INTERVAL_3S = 3_000L;

    // Zeal attribute reduces skill cooldowns by 1.5% per point (clamp keeps min 10% of base).
    private static long zealAdjusted(long baseMs, @Nonnull CompanionProfile profile) {
        float factor = 1.0f - profile.getAttributeZeal() * 0.015f;
        if (factor < 0.1f) factor = 0.1f;
        return (long) (baseMs * factor);
    }

    private final ScrubyCompanionHitTracker hitTracker;

    public ScrubySkillTickingSystem(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyHudManager hudManager,
            @Nonnull ScrubySoundService soundService,
            @Nonnull ScrubyInventoryService inventoryService,
            @Nonnull ScrubyCompanionHitTracker hitTracker
    ) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.hudManager = Objects.requireNonNull(hudManager, "hudManager");
        this.soundService = Objects.requireNonNull(soundService, "soundService");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService");
        this.hitTracker = Objects.requireNonNull(hitTracker, "hitTracker");
    }

    @Override
    public void tick(float deltaTime, int tickCount, @Nonnull Store<EntityStore> store) {
        tickCounter++;
        ensureEffectsCached();
        hudManager.tick();
        if (tickCounter % TICK_INTERVAL != 0) return;
        hitTracker.cleanup();

        EntityStore entityStore = store.getExternalData();
        Set<UUID> processedOwners = new HashSet<>();
        long now = System.currentTimeMillis();

        // Forgotten Temple: silence all offensive skill ticks for every companion
        // that is currently ticking inside the temple world, regardless of combat
        // mode. The temple override flag only covers aggressive companions, so
        // checking the store's world directly is authoritative for all modes.
        String worldName = entityStore.getWorld().getName();
        boolean inTempleWorld = ScrubyCombatModeOverrideService.isTempleWorld(worldName);

        for (Map.Entry<UUID, Ref<EntityStore>> entry : registry.entriesSnapshot()) {
            UUID ownerUuid = entry.getKey();
            Ref<EntityStore> companionRef = entry.getValue();
            if (companionRef == null || !companionRef.isValid()) continue;

            // Cross-world guard: skip if companion belongs to a different world's store
            if (companionRef.getStore() != store) continue;

            Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(ownerUuid);
            if (ownerRef == null || !ownerRef.isValid()) continue;

            ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
            if (binding == null) continue;

            CompanionProfile profile = binding.getActiveProfile();
            String allAbilities = getAllAbilities(profile);

            PlayerRef playerRef = store.getComponent(ownerRef, PlayerRef.getComponentType());
            if (playerRef == null) continue;

            // Skip skill ticking for stationed companions (they are not near the player)
            if (profile.isStationedAtBase()
                    && !ScrubyBaseStationService.MODE_NONE.equals(profile.getStationMode())) {
                // Remove Segen modifier if companion is stationed
                if (Boolean.TRUE.equals(segenApplied.get(ownerUuid))) {
                    removeSegenModifier(store, ownerRef);
                    segenApplied.put(ownerUuid, false);
                }
                processedOwners.add(ownerUuid);
                continue;
            }

            // HUD update — BEFORE proximity check, always runs for follow-mode companions
            if (tickCounter % HUD_UPDATE_INTERVAL == 0) {
                if (!hudManager.hasHud(ownerUuid)) {
                    hudManager.tryCreateHud(store, ownerRef, playerRef, ownerUuid, profile, companionRef);
                } else {
                    hudManager.updateHud(ownerUuid, profile, store, companionRef);
                }
            }
            processedOwners.add(ownerUuid);

            // Check proximity
            TransformComponent companionTransform = store.getComponent(companionRef, TransformComponent.getComponentType());
            if (companionTransform == null) continue;
            Vector3d ownerPos = playerRef.getTransform().getPosition();
            Vector3d companionPos = companionTransform.getPosition();
            double distSq = distanceSquared(ownerPos, companionPos);

            if (distSq > PROXIMITY_RANGE_SQ) {
                // Out of range — remove Segen modifier if applied
                if (Boolean.TRUE.equals(segenApplied.get(ownerUuid))) {
                    removeSegenModifier(store, ownerRef);
                    segenApplied.put(ownerUuid, false);
                }
                continue;
            }

            // Get owner health stats
            EntityStatMap ownerStats = store.getComponent(ownerRef, EntityStatMap.getComponentType());
            if (ownerStats == null) continue;
            int healthIndex = DefaultEntityStatTypes.getHealth();
            EntityStatValue healthStat = ownerStats.get(healthIndex);
            if (healthStat == null) continue;

            float currentHp = healthStat.get();
            float maxHp = healthStat.getMax();
            if (maxHp <= 0) continue;
            float hpPercent = currentHp / maxHp;

            // Get companion health stats
            EntityStatMap companionStats = store.getComponent(companionRef, EntityStatMap.getComponentType());
            int companionHealthIndex = DefaultEntityStatTypes.getHealth();
            float companionCurrentHp = 0;
            float companionMaxHp = 0;
            if (companionStats != null) {
                EntityStatValue companionHealthStat = companionStats.get(companionHealthIndex);
                if (companionHealthStat != null) {
                    companionCurrentHp = companionHealthStat.get();
                    companionMaxHp = companionHealthStat.getMax();
                }
            }

            // Per-tick accumulators
            float totalDamageThisTick = 0;
            float totalHealingThisTick = 0;

            // Forgotten Temple: silence all offensive skill ticks (AoE auras,
            // bursts, reflections). Heals, buffs, self-regen, HUD continue.
            // Use world-scope (not profile.hasTempleOverride) because the override
            // flag is only set for aggressive companions — passive/healer profiles
            // would otherwise slip through and still deal AoE damage.
            boolean templeOverride = inTempleWorld;

            // ================================================================
            // 1. DAMAGE AURAS (Task 6)
            // ================================================================

            // Determine DPS radius
            boolean hasRaubtiersinne = allAbilities.contains(ScrubySkillService.DPS_RAUBTIERSINNE);
            boolean hasUnterdrueckungsfeuer = allAbilities.contains(ScrubySkillService.DPS_W2B_UNTERDRUECKUNGSFEUER);
            double baseDpsRadius = hasRaubtiersinne ? 15.0 : 8.0;
            double dpsRadius = hasUnterdrueckungsfeuer ? baseDpsRadius * 2.0 : baseDpsRadius;
            double dpsRadiusSq = dpsRadius * dpsRadius;

            // KAMPFINSTINKT: 0.5 DMG/s in 3 blocks (0.5 if Unterdrueckungsfeuer, using dpsRadius)
            if (!templeOverride && allAbilities.contains(ScrubySkillService.UNIVERSAL_KAMPFINSTINKT)) {
                float kampfDmg = hasUnterdrueckungsfeuer ? 0.5f : 0.5f;
                double kampfRadiusSq = hasUnterdrueckungsfeuer ? dpsRadiusSq : 3.0 * 3.0;
                AoeDamageResult kampfResult = dealAoeDamage(store, companionRef, companionPos, kampfRadiusSq, kampfDmg);
                totalDamageThisTick += kampfResult.totalDamage;
            }

            // BRENNENDE_PFEILE: 1 DMG/s (1.5 with Raubtiersinne, 0.5 if Unterdrueckungsfeuer) in dpsRadius
            if (!templeOverride && allAbilities.contains(ScrubySkillService.DPS_BRENNENDE_PFEILE)) {
                float brennendDmg;
                if (hasUnterdrueckungsfeuer) {
                    brennendDmg = 0.5f;
                } else if (hasRaubtiersinne) {
                    brennendDmg = 1.5f;
                } else {
                    brennendDmg = 1.0f;
                }
                AoeDamageResult brennendResult = dealDebuffedAoeDamage(store, companionRef, companionPos, dpsRadiusSq, brennendDmg);
                totalDamageThisTick += brennendResult.totalDamage;
                if (!brennendResult.hitTargets.isEmpty() && burnEffect != null) {
                    applyEffectToTargets(store, brennendResult.hitTargets, burnEffect, 3.5f);
                }
            }

            // GIFTPFEILE: 1 DMG/s (0.5 if Unterdrueckungsfeuer) in dpsRadius
            if (!templeOverride && allAbilities.contains(ScrubySkillService.DPS_W1B_GIFTPFEILE)) {
                float giftDmg = hasUnterdrueckungsfeuer ? 0.5f : 1.0f;
                AoeDamageResult giftResult = dealDebuffedAoeDamage(store, companionRef, companionPos, dpsRadiusSq, giftDmg);
                totalDamageThisTick += giftResult.totalDamage;
                if (!giftResult.hitTargets.isEmpty() && poisonEffect != null) {
                    applyEffectToTargets(store, giftResult.hitTargets, poisonEffect, 3.5f);
                }
            }

            // Attribute Debug: drain Strength-hit events queued by ScrubyDamageBuffSystem
            if (attrDebugPlayers.contains(ownerUuid)) {
                java.util.Queue<String> q = attrDebugQueue.get(ownerUuid);
                if (q != null) {
                    String line;
                    while ((line = q.poll()) != null) {
                        playerRef.sendMessage(Message.raw(line));
                    }
                }
            }

            // DPS Debug: 2-second chat output showing aura stats
            if (dpsDebugPlayers.contains(ownerUuid) && tickCounter % DPS_DEBUG_INTERVAL == 0) {
                boolean hasBurn = allAbilities.contains(ScrubySkillService.DPS_BRENNENDE_PFEILE);
                boolean hasPoison = allAbilities.contains(ScrubySkillService.DPS_W1B_GIFTPFEILE);
                boolean hasKampf = allAbilities.contains(ScrubySkillService.UNIVERSAL_KAMPFINSTINKT);
                StringBuilder dbg = new StringBuilder("[DPS-Debug] ");
                dbg.append("Radius=").append(String.format("%.0f", Math.sqrt(dpsRadiusSq)));
                dbg.append(" TotalDmg=").append(String.format("%.1f", totalDamageThisTick));
                dbg.append(" | Burn=").append(hasBurn ? "ON" : "OFF");
                dbg.append(" Poison=").append(hasPoison ? "ON" : "OFF");
                dbg.append(" Kampf=").append(hasKampf ? "ON" : "OFF");
                dbg.append(" Raubtier=").append(hasRaubtiersinne ? "ON" : "OFF");
                dbg.append(" Unterdr=").append(hasUnterdrueckungsfeuer ? "ON" : "OFF");
                dbg.append(" Debuffs=").append(hitTracker.activeCount());
                dbg.append(" | Abilities=").append(allAbilities.isEmpty() ? "NONE" : allAbilities);
                playerRef.sendMessage(Message.raw(dbg.toString()));
            }

            // RAUBTIERSINNE VFX: Alerted on random enemy every 5s
            if (hasRaubtiersinne) {
                Long lastRvfx = lastRaubtiersinneVfxTime.get(ownerUuid);
                if (lastRvfx == null || now - lastRvfx >= VFX_INTERVAL_5S) {
                    NearbyEnemy randomEnemy = findRandomEnemy(store, companionRef, companionPos, dpsRadiusSq);
                    if (randomEnemy != null) {
                        spawnParticleSafe(store, "Alerted", randomEnemy.position);
                        lastRaubtiersinneVfxTime.put(ownerUuid, now);
                    }
                }
            }

            // PROVOKATION: 1 DMG/s in 8 blocks (separate from DPS radius)
            if (!templeOverride && allAbilities.contains(ScrubySkillService.TANK_PROVOKATION)) {
                double provoRadiusSq = 8.0 * 8.0;
                AoeDamageResult provoResult = dealAoeDamage(store, companionRef, companionPos, provoRadiusSq, 1.0f);
                totalDamageThisTick += provoResult.totalDamage;
                // VFX: Angry on random hit target every 5s
                Long lastPvfx = lastProvokationVfxTime.get(ownerUuid);
                if (lastPvfx == null || now - lastPvfx >= VFX_INTERVAL_5S) {
                    if (!provoResult.hitTargets.isEmpty()) {
                        Ref<EntityStore> randomTarget = provoResult.hitTargets.get(
                                ThreadLocalRandom.current().nextInt(provoResult.hitTargets.size()));
                        Vector3d targetPos = getEntityPosition(store, randomTarget);
                        if (targetPos != null) {
                            spawnParticleSafe(store, "Angry", targetPos);
                            lastProvokationVfxTime.put(ownerUuid, now);
                        }
                    }
                }
            }

            // LEBENSBAND: +0.5 DMG/s bonus in 3-block radius, 50% of total damage heals player
            if (!templeOverride && allAbilities.contains(ScrubySkillService.HEALER_W2A_LEBENSBAND)) {
                double lebensbandRadiusSq = 3.0 * 3.0;
                AoeDamageResult lebensbandDmgResult = dealAoeDamage(store, companionRef, companionPos, lebensbandRadiusSq, 0.5f);
                totalDamageThisTick += lebensbandDmgResult.totalDamage;
                // 50% of ALL damage this tick heals player (calculated after all damage auras)
                // This is accumulated and applied after all damage is tallied below
            }

            // ================================================================
            // 2. FOCUSED / BURST (Task 7)
            // ================================================================

            // FOKUSFEUER: 1 DMG/s (0.5 if Unterdrueckungsfeuer) on shared target
            if (!templeOverride && allAbilities.contains(ScrubySkillService.DPS_FOKUSFEUER)) {
                float fokusDmg = hasUnterdrueckungsfeuer ? 0.5f : 1.0f;
                boolean hasToedlichePraezision = allAbilities.contains(ScrubySkillService.DPS_W2A_TOEDLICHE_PRAEZISION);
                Ref<EntityStore> fokusTarget = findFokusfeuerTarget(store, companionRef, companionPos, ownerPos, dpsRadiusSq);
                if (fokusTarget != null) {
                    EntityStatMap targetStats = store.getComponent(fokusTarget, EntityStatMap.getComponentType());
                    if (targetStats != null) {
                        EntityStatValue targetHealth = targetStats.get(healthIndex);
                        if (targetHealth != null) {
                            float actualDmg = fokusDmg;
                            boolean executeTriggered = false;
                            // Toedliche Praezision: double damage on targets below 30% HP
                            if (hasToedlichePraezision && targetHealth.getMax() > 0
                                    && targetHealth.get() / targetHealth.getMax() < 0.3f) {
                                actualDmg *= 2.0f;
                                executeTriggered = true;
                            }
                            targetStats.addStatValue(healthIndex, -actualDmg);
                            totalDamageThisTick += actualDmg;
                            // VFX: Impact_Critical at focus target
                            Vector3d fokusTargetPos = getEntityPosition(store, fokusTarget);
                            if (fokusTargetPos != null) {
                                spawnParticleSafe(store, "Impact_Critical", fokusTargetPos);
                            }
                            // VFX: extra Impact_Critical on execute (Toedliche Praezision)
                            if (executeTriggered && fokusTargetPos != null) {
                                spawnParticleSafe(store, "Impact_Critical", fokusTargetPos);
                            }
                        }
                    }
                }
            }

            // DOPPELSCHUSS: 10 burst on nearest enemy in 10 blocks, 8s CD
            if (!templeOverride && allAbilities.contains(ScrubySkillService.DPS_W1A_DOPPELSCHUSS)) {
                Long lastDs = lastDoppelschussTime.get(ownerUuid);
                if (lastDs == null || now - lastDs >= zealAdjusted(DOPPELSCHUSS_COOLDOWN_MS, profile)) {
                    double doppelRadiusSq = 10.0 * 10.0;
                    NearbyEnemy nearest = findNearestEnemy(store, companionRef, companionPos, doppelRadiusSq);
                    if (nearest != null) {
                        EntityStatMap targetStats = store.getComponent(nearest.ref, EntityStatMap.getComponentType());
                        if (targetStats != null) {
                            boolean hasToedlichePraezision = allAbilities.contains(ScrubySkillService.DPS_W2A_TOEDLICHE_PRAEZISION);
                            float burstDmg = 5.0f;
                            EntityStatValue targetHealth = targetStats.get(healthIndex);
                            if (hasToedlichePraezision && targetHealth != null && targetHealth.getMax() > 0
                                    && targetHealth.get() / targetHealth.getMax() < 0.3f) {
                                burstDmg *= 2.0f;
                            }
                            targetStats.addStatValue(healthIndex, -burstDmg);
                            totalDamageThisTick += burstDmg;
                            lastDoppelschussTime.put(ownerUuid, now);
                            soundService.playBurstAttack(playerRef);
                            if (!ScrubyMuteSkillsCommand.isMuted(binding)) {
                                String locale = profile.getLocale();
                                playerRef.sendMessage(Message.raw(ScrubyLang.get(locale, "chat.skill.doubleshot", Math.round(burstDmg))));
                            }
                            if (attrDebugPlayers.contains(ownerUuid)) {
                                long eff = zealAdjusted(DOPPELSCHUSS_COOLDOWN_MS, profile);
                                playerRef.sendMessage(Message.raw(String.format(
                                        "[Scruby-ATTR] Doppelschuss fire — base=%.1fs eff=%.1fs (zeal=%d)",
                                        DOPPELSCHUSS_COOLDOWN_MS / 1000.0, eff / 1000.0, profile.getAttributeZeal())));
                            }
                            spawnParticleSafe(store, "Impact_Blade_01", nearest.position);
                        }
                    }
                }
            }

            // KRIEGSSCHREI: 8 AoE burst in 10 blocks, 8s CD
            if (!templeOverride && allAbilities.contains(ScrubySkillService.TANK_W2A_KRIEGSSCHREI)) {
                Long lastKs = lastKriegsschreiTime.get(ownerUuid);
                if (lastKs == null || now - lastKs >= zealAdjusted(KRIEGSSCHREI_COOLDOWN_MS, profile)) {
                    double kriegRadiusSq = 10.0 * 10.0;
                    // Check if there are enemies before triggering
                    NearbyEnemy nearest = findNearestEnemy(store, companionRef, companionPos, kriegRadiusSq);
                    if (nearest != null) {
                        boolean hasToedlichePraezision = allAbilities.contains(ScrubySkillService.DPS_W2A_TOEDLICHE_PRAEZISION);
                        float dealt = dealAoeDamageWithModifiers(store, companionRef, companionPos, kriegRadiusSq, 4.0f, hasToedlichePraezision);
                        totalDamageThisTick += dealt;
                        lastKriegsschreiTime.put(ownerUuid, now);
                        soundService.playWarCry(playerRef);
                        if (!ScrubyMuteSkillsCommand.isMuted(binding)) {
                            playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "chat.skill.warcry", Math.round(dealt))));
                        }
                        if (attrDebugPlayers.contains(ownerUuid)) {
                            long eff = zealAdjusted(KRIEGSSCHREI_COOLDOWN_MS, profile);
                            playerRef.sendMessage(Message.raw(String.format(
                                    "[Scruby-ATTR] Kriegsschrei fire — base=%.1fs eff=%.1fs (zeal=%d)",
                                    KRIEGSSCHREI_COOLDOWN_MS / 1000.0, eff / 1000.0, profile.getAttributeZeal())));
                        }
                        spawnParticleSafe(store, "Explosion_Medium", companionPos);
                    }
                }
            }

            // ================================================================
            // 3. HEALING (Task 8)
            // ================================================================

            // ERSTE_HILFE: 20% MaxHP heal when player <40% HP, 15s CD
            if (allAbilities.contains(ScrubySkillService.HEALER_ERSTE_HILFE) && hpPercent < 0.4f) {
                Long lastTime = lastErsteHilfeTime.get(ownerUuid);
                if (lastTime == null || now - lastTime >= zealAdjusted(ERSTE_HILFE_COOLDOWN_MS, profile)) {
                    lastErsteHilfeTime.put(ownerUuid, now);
                    float healAmount = maxHp * 0.2f;
                    ownerStats.addStatValue(healthIndex, healAmount);
                    totalHealingThisTick += healAmount;
                    soundService.playEmergencyHeal(playerRef);
                    if (!ScrubyMuteSkillsCommand.isMuted(binding)) {
                        playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "chat.skill.firstaid", Math.round(healAmount))));
                    }
                    if (attrDebugPlayers.contains(ownerUuid)) {
                        long eff = zealAdjusted(ERSTE_HILFE_COOLDOWN_MS, profile);
                        playerRef.sendMessage(Message.raw(String.format(
                                "[Scruby-ATTR] ErsteHilfe fire — base=%.1fs eff=%.1fs (zeal=%d)",
                                ERSTE_HILFE_COOLDOWN_MS / 1000.0, eff / 1000.0, profile.getAttributeZeal())));
                    }
                }
            }

            // REGENERATION: 1 HP/s (1.5 with REINIGUNG) on player
            if (allAbilities.contains(ScrubySkillService.HEALER_REGENERATION) && currentHp < maxHp) {
                boolean hasReinigung = allAbilities.contains(ScrubySkillService.HEALER_W1B_REINIGUNG);
                float regenAmount = hasReinigung ? 1.5f : 1.0f;
                ownerStats.addStatValue(healthIndex, regenAmount);
                totalHealingThisTick += regenAmount;
            }

            // SCHUTZZAUBER: 40% MaxHP heal when player <25% HP, 30s CD
            if (allAbilities.contains(ScrubySkillService.HEALER_W1A_SCHUTZZAUBER) && hpPercent < 0.25f) {
                Long lastTime = lastSchutzzauberTime.get(ownerUuid);
                if (lastTime == null || now - lastTime >= zealAdjusted(SCHUTZZAUBER_COOLDOWN_MS, profile)) {
                    lastSchutzzauberTime.put(ownerUuid, now);
                    float healAmount = maxHp * 0.4f;
                    ownerStats.addStatValue(healthIndex, healAmount);
                    totalHealingThisTick += healAmount;
                    soundService.playEmergencyHeal(playerRef);
                    if (!ScrubyMuteSkillsCommand.isMuted(binding)) {
                        playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "chat.skill.protectionspell", Math.round(healAmount))));
                    }
                    if (attrDebugPlayers.contains(ownerUuid)) {
                        long eff = zealAdjusted(SCHUTZZAUBER_COOLDOWN_MS, profile);
                        playerRef.sendMessage(Message.raw(String.format(
                                "[Scruby-ATTR] Schutzzauber fire — base=%.1fs eff=%.1fs (zeal=%d)",
                                SCHUTZZAUBER_COOLDOWN_MS / 1000.0, eff / 1000.0, profile.getAttributeZeal())));
                    }
                }
            }

            // SEGEN_DES_HUETERS: +20 MaxHP on PLAYER when in 15 blocks
            if (allAbilities.contains(ScrubySkillService.HEALER_SEGEN_DES_HUETERS)) {
                if (!Boolean.TRUE.equals(segenApplied.get(ownerUuid))) {
                    applySegenModifier(store, ownerRef);
                    segenApplied.put(ownerUuid, true);
                }
            } else {
                // If skill was removed (e.g., respec), remove modifier
                if (Boolean.TRUE.equals(segenApplied.get(ownerUuid))) {
                    removeSegenModifier(store, ownerRef);
                    segenApplied.put(ownerUuid, false);
                }
            }

            // LEBENSBAND heal: 50% of total damage this tick heals player
            if (allAbilities.contains(ScrubySkillService.HEALER_W2A_LEBENSBAND) && totalDamageThisTick > 0) {
                float lebensbandHeal = totalDamageThisTick * 0.5f;
                ownerStats.addStatValue(healthIndex, lebensbandHeal);
                totalHealingThisTick += lebensbandHeal;
                // VFX: Effect_Heal at player every 5s
                Long lastLvfx = lastLebensbandVfxTime.get(ownerUuid);
                // Lebensband VFX removed (Aura_Heal loops permanently)
            }

            // ================================================================
            // 4. HP-DELTA REACTIONS (Task 9)
            // ================================================================

            // Track companion HP delta
            if (companionStats != null && companionMaxHp > 0) {
                Float prevCompanionHp = lastCompanionHp.get(ownerUuid);
                if (prevCompanionHp != null && companionCurrentHp < prevCompanionHp) {
                    float hpLoss = prevCompanionHp - companionCurrentHp;

                    // DORNEN: 35% of loss as AoE damage on all enemies in 5 blocks
                    boolean hasDornen = allAbilities.contains(ScrubySkillService.TANK_W1B_DORNEN);
                    if (!templeOverride && hasDornen) {
                        float dornenDmg = hpLoss * 0.35f;
                        double dornenRadiusSq = 5.0 * 5.0;
                        AoeDamageResult dornenResult = dealAoeDamage(store, companionRef, companionPos, dornenRadiusSq, dornenDmg);
                        totalDamageThisTick += dornenResult.totalDamage;
                        if (dornenResult.totalDamage > 0) {
                            spawnParticleSafe(store, "Impact_Fire", companionPos);
                        }
                    }

                    // VERGELTUNG: 50% (70% if Dornen active) on nearest enemy in 5 blocks
                    if (!templeOverride && allAbilities.contains(ScrubySkillService.TANK_VERGELTUNG)) {
                        float vergeltungPct = hasDornen ? 0.70f : 0.50f;
                        float vergeltungDmg = hpLoss * vergeltungPct;
                        double vergeltungRadiusSq = 5.0 * 5.0;
                        NearbyEnemy nearest = findNearestEnemy(store, companionRef, companionPos, vergeltungRadiusSq);
                        if (nearest != null) {
                            EntityStatMap targetStats = store.getComponent(nearest.ref, EntityStatMap.getComponentType());
                            if (targetStats != null) {
                                targetStats.addStatValue(healthIndex, -vergeltungDmg);
                                totalDamageThisTick += vergeltungDmg;
                                spawnParticleSafe(store, "Impact_Critical", nearest.position);
                            }
                        }
                    }
                }
                lastCompanionHp.put(ownerUuid, companionCurrentHp);
            }

            // Track player HP delta
            {
                Float prevPlayerHp = lastPlayerHp.get(ownerUuid);
                if (prevPlayerHp != null && currentHp < prevPlayerHp) {
                    float playerHpLoss = prevPlayerHp - currentHp;

                    // TREUER_BESCHUETZER: heal player 30% of loss, damage companion same amount
                    if (allAbilities.contains(ScrubySkillService.TANK_W2B_TREUER_BESCHUETZER) && companionStats != null) {
                        float transferAmount = playerHpLoss * 0.30f;
                        ownerStats.addStatValue(healthIndex, transferAmount);
                        companionStats.addStatValue(companionHealthIndex, -transferAmount);
                        totalHealingThisTick += transferAmount;
                    }
                }
                lastPlayerHp.put(ownerUuid, currentHp);
            }

            // TREUER_BESCHUETZER permanent glow VFX
            if (allAbilities.contains(ScrubySkillService.TANK_W2B_TREUER_BESCHUETZER)) {
                Long lastTbvfx = lastTreuerBeschuetzerVfxTime.get(ownerUuid);
                // Treuer Beschuetzer VFX removed (Aura_Heal loops permanently)
            }

            // ================================================================
            // 5. SELF-HEAL + EMERGENCY (Task 10)
            // ================================================================

            // EISENHAUT: companion heals 3 HP/s when above 25% HP
            if (allAbilities.contains(ScrubySkillService.TANK_W1A_EISENHAUT)
                    && companionStats != null && companionMaxHp > 0
                    && companionCurrentHp / companionMaxHp > 0.25f
                    && companionCurrentHp < companionMaxHp) {
                companionStats.addStatValue(companionHealthIndex, 3.0f);
                // Eisenhaut VFX removed (Aura_Heal loops permanently)
            }

            // NOTFALL_RETTUNG: at player <15% HP, heal 50% MaxHP + 10 AoE damage around PLAYER in 5 blocks, 30s CD
            if (allAbilities.contains(ScrubySkillService.HEALER_W2B_NOTFALL_RETTUNG) && hpPercent < 0.15f) {
                Long lastTime = lastNotfallRettungTime.get(ownerUuid);
                if (lastTime == null || now - lastTime >= zealAdjusted(NOTFALL_RETTUNG_COOLDOWN_MS, profile)) {
                    lastNotfallRettungTime.put(ownerUuid, now);
                    // Heal 50% MaxHP
                    float healAmount = maxHp * 0.5f;
                    ownerStats.addStatValue(healthIndex, healAmount);
                    totalHealingThisTick += healAmount;
                    // AoE 5 damage around PLAYER (not companion) in 5 blocks — gated in Temple
                    if (!templeOverride) {
                        double rescueRadiusSq = 5.0 * 5.0;
                        AoeDamageResult rescueResult = dealAoeDamage(store, companionRef, ownerPos, rescueRadiusSq, 2.5f);
                        totalDamageThisTick += rescueResult.totalDamage;
                    }
                    soundService.playEmergencyRescue(playerRef);
                    if (!ScrubyMuteSkillsCommand.isMuted(binding)) {
                        playerRef.sendMessage(Message.raw(ScrubyLang.get(profile.getLocale(), "chat.skill.emergencyrescue", Math.round(healAmount))));
                    }
                    if (attrDebugPlayers.contains(ownerUuid)) {
                        long eff = zealAdjusted(NOTFALL_RETTUNG_COOLDOWN_MS, profile);
                        playerRef.sendMessage(Message.raw(String.format(
                                "[Scruby-ATTR] NotfallRettung fire — base=%.1fs eff=%.1fs (zeal=%d)",
                                NOTFALL_RETTUNG_COOLDOWN_MS / 1000.0, eff / 1000.0, profile.getAttributeZeal())));
                    }
                    // Notfall-Rettung VFX removed (particles loop permanently)
                }
            }

            // ================================================================
            // 6. COMBAT REPORT + HUD (Task 10/12)
            // ================================================================

            // Accumulate for combat report
            reportDamageDealt.merge(ownerUuid, totalDamageThisTick, Float::sum);
            reportHealingDone.merge(ownerUuid, totalHealingThisTick, Float::sum);

            // Send combat report every 10s
            if (tickCounter % COMBAT_REPORT_INTERVAL == 0) {
                sendCombatReport(playerRef, ownerUuid, profile.getLocale(), binding);
            }

            // ================================================================
            // 7. INVENTORY FULL TIMER
            // ================================================================
            if (inventoryService.isFull(profile)) {
                Long fullSince = inventoryFullSince.get(ownerUuid);
                if (fullSince == null) {
                    inventoryFullSince.put(ownerUuid, now);
                } else if (now - fullSince >= INVENTORY_FULL_NOTIFY_MS) {
                    if (!ScrubyMuteInventoryCommand.isMuted(binding)) {
                        playerRef.sendMessage(Message.raw(
                                ScrubyLang.get(profile.getLocale(), "chat.skill.inventory_full")));
                    }
                    inventoryFullSince.put(ownerUuid, now); // Reset for next cycle
                }
            } else {
                inventoryFullSince.remove(ownerUuid);
            }
        }

        // Task 4: Remove HUD for owners whose companion is no longer in registry
        if (tickCounter % HUD_UPDATE_INTERVAL == 0) {
            hudManager.cleanupOrphanedHuds(processedOwners, store, entityStore);
        }

        // Periodic cleanup of stale state maps (Task 12)
        if (tickCounter % (TICK_INTERVAL * 10) == 0) {
            lastCompanionHp.keySet().retainAll(processedOwners);
            lastPlayerHp.keySet().retainAll(processedOwners);
            reportDamageDealt.keySet().retainAll(processedOwners);
            reportHealingDone.keySet().retainAll(processedOwners);
            segenApplied.keySet().retainAll(processedOwners);
            lastRaubtiersinneVfxTime.keySet().retainAll(processedOwners);
            lastProvokationVfxTime.keySet().retainAll(processedOwners);
            lastEisenhautVfxTime.keySet().retainAll(processedOwners);
            lastLebensbandVfxTime.keySet().retainAll(processedOwners);
            lastTreuerBeschuetzerVfxTime.keySet().retainAll(processedOwners);
            inventoryFullSince.keySet().retainAll(processedOwners);
        }
    }

    // ========================================================================
    // Helper: VFX — effect caching + particle/effect utilities
    // ========================================================================

    private void ensureEffectsCached() {
        if (effectsCacheAttempted) return;
        effectsCacheAttempted = true;
        try {
            int burnIdx = EntityEffect.getAssetMap().getIndex("Burn");
            if (burnIdx >= 0) burnEffect = EntityEffect.getAssetMap().getAsset(burnIdx);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby] Failed to cache Burn effect: " + e.getMessage());
        }
        try {
            int poisonIdx = EntityEffect.getAssetMap().getIndex("Poison");
            if (poisonIdx >= 0) poisonEffect = EntityEffect.getAssetMap().getAsset(poisonIdx);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby] Failed to cache Poison effect: " + e.getMessage());
        }
    }

    private void spawnParticleSafe(Store<EntityStore> store, String systemId, Vector3d position) {
        try {
            ParticleUtil.spawnParticleEffect(systemId, position, store);
        } catch (Exception e) {
            // Silently ignore VFX failures
        }
    }

    private void applyEffectToTargets(Store<EntityStore> store, List<Ref<EntityStore>> targets, EntityEffect effect, float duration) {
        if (effect == null || targets.isEmpty()) return;
        for (Ref<EntityStore> target : targets) {
            try {
                EffectControllerComponent effectCtrl = store.getComponent(target, EffectControllerComponent.getComponentType());
                if (effectCtrl != null) {
                    effectCtrl.addEffect(target, effect, duration, OverlapBehavior.OVERWRITE, store);
                }
            } catch (Exception e) {
                // Silently ignore per-target failures
            }
        }
    }

    @Nullable
    private Vector3d getEntityPosition(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            return transform != null ? transform.getPosition() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // Helper: AoE damage (no modifiers)
    // ========================================================================

    /**
     * Deals flat damage to all NPC entities within radiusSq of centerPos,
     * excluding registered companions.
     *
     * @return AoeDamageResult with total damage dealt and list of hit targets
     */
    private AoeDamageResult dealAoeDamage(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull Vector3d centerPos,
            double radiusSq,
            float damage
    ) {
        int healthIndex = DefaultEntityStatTypes.getHealth();
        List<Ref<EntityStore>> targets = new ArrayList<>();

        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid()) continue;
                if (ref.equals(companionRef)) continue;
                if (isRegisteredCompanion(ref)) continue;
                if (isScrubyNpc(store, ref)) continue;

                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) continue;

                double distSq = distanceSquared(transform.getPosition(), centerPos);
                if (distSq <= radiusSq) {
                    targets.add(ref);
                }
            }
        });

        float totalDealt = 0;
        List<Ref<EntityStore>> hitTargets = new ArrayList<>();
        for (Ref<EntityStore> target : targets) {
            EntityStatMap targetStats = store.getComponent(target, EntityStatMap.getComponentType());
            if (targetStats != null) {
                targetStats.addStatValue(healthIndex, -damage);
                totalDealt += damage;
                hitTargets.add(target);
            }
        }
        return new AoeDamageResult(totalDealt, hitTargets);
    }

    /**
     * Like dealAoeDamage, but only damages entities that have an active
     * hit debuff from ScrubyCompanionHitTracker (3.5s after companion hit).
     */
    private AoeDamageResult dealDebuffedAoeDamage(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull Vector3d centerPos,
            double radiusSq,
            float damage
    ) {
        int healthIndex = DefaultEntityStatTypes.getHealth();
        List<Ref<EntityStore>> targets = new ArrayList<>();

        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid()) continue;
                if (ref.equals(companionRef)) continue;
                if (isRegisteredCompanion(ref)) continue;
                if (!hitTracker.hasActiveDebuff(ref)) continue;

                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) continue;

                double distSq = distanceSquared(transform.getPosition(), centerPos);
                if (distSq <= radiusSq) {
                    targets.add(ref);
                }
            }
        });

        float totalDealt = 0;
        List<Ref<EntityStore>> hitTargets = new ArrayList<>();
        for (Ref<EntityStore> target : targets) {
            EntityStatMap targetStats = store.getComponent(target, EntityStatMap.getComponentType());
            if (targetStats != null) {
                targetStats.addStatValue(healthIndex, -damage);
                totalDealt += damage;
                hitTargets.add(target);
            }
        }
        return new AoeDamageResult(totalDealt, hitTargets);
    }

    // ========================================================================
    // Helper: AoE damage with Toedliche Praezision modifier
    // ========================================================================

    /**
     * Deals AoE damage with optional execute check (double damage below 30% HP).
     *
     * @return total damage dealt across all targets
     */
    private float dealAoeDamageWithModifiers(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull Vector3d centerPos,
            double radiusSq,
            float baseDamage,
            boolean hasToedlichePraezision
    ) {
        int healthIndex = DefaultEntityStatTypes.getHealth();
        List<Ref<EntityStore>> targets = new ArrayList<>();

        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid()) continue;
                if (ref.equals(companionRef)) continue;
                if (isRegisteredCompanion(ref)) continue;
                if (isScrubyNpc(store, ref)) continue;

                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) continue;

                double distSq = distanceSquared(transform.getPosition(), centerPos);
                if (distSq <= radiusSq) {
                    targets.add(ref);
                }
            }
        });

        float totalDealt = 0;
        for (Ref<EntityStore> target : targets) {
            EntityStatMap targetStats = store.getComponent(target, EntityStatMap.getComponentType());
            if (targetStats != null) {
                float actualDmg = baseDamage;
                if (hasToedlichePraezision) {
                    EntityStatValue targetHealth = targetStats.get(healthIndex);
                    if (targetHealth != null && targetHealth.getMax() > 0
                            && targetHealth.get() / targetHealth.getMax() < 0.3f) {
                        actualDmg *= 2.0f;
                    }
                }
                targetStats.addStatValue(healthIndex, -actualDmg);
                totalDealt += actualDmg;
            }
        }
        return totalDealt;
    }

    // ========================================================================
    // Helper: Find nearest enemy NPC
    // ========================================================================

    /**
     * Finds the nearest NPC entity within maxRadiusSq of centerPos,
     * excluding registered companions.
     */
    @Nullable
    private NearbyEnemy findNearestEnemy(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull Vector3d centerPos,
            double maxRadiusSq
    ) {
        List<NearbyEnemy> candidates = new ArrayList<>();

        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid()) continue;
                if (ref.equals(companionRef)) continue;
                if (isRegisteredCompanion(ref)) continue;
                if (isScrubyNpc(store, ref)) continue;

                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) continue;

                Vector3d enemyPos = transform.getPosition();
                double distSq = distanceSquared(enemyPos, centerPos);
                if (distSq <= maxRadiusSq) {
                    candidates.add(new NearbyEnemy(ref, distSq, enemyPos));
                }
            }
        });

        NearbyEnemy best = null;
        for (NearbyEnemy candidate : candidates) {
            if (best == null || candidate.distSq < best.distSq) {
                best = candidate;
            }
        }
        return best;
    }

    // ========================================================================
    // Helper: Find random enemy NPC
    // ========================================================================

    /**
     * Finds a random NPC entity within maxRadiusSq of centerPos,
     * excluding registered companions.
     */
    @Nullable
    private NearbyEnemy findRandomEnemy(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull Vector3d centerPos,
            double maxRadiusSq
    ) {
        List<NearbyEnemy> candidates = new ArrayList<>();

        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid()) continue;
                if (ref.equals(companionRef)) continue;
                if (isRegisteredCompanion(ref)) continue;
                if (isScrubyNpc(store, ref)) continue;

                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) continue;

                double distSq = distanceSquared(transform.getPosition(), centerPos);
                if (distSq <= maxRadiusSq) {
                    candidates.add(new NearbyEnemy(ref, distSq, transform.getPosition()));
                }
            }
        });

        if (candidates.isEmpty()) return null;
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    // ========================================================================
    // Helper: Fokusfeuer target finder
    // ========================================================================

    /**
     * Finds the NPC with the lowest combined distance to player + companion
     * (the "shared target" — closest to both).
     */
    @Nullable
    private Ref<EntityStore> findFokusfeuerTarget(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull Vector3d companionPos,
            @Nonnull Vector3d ownerPos,
            double maxRadiusSq
    ) {
        List<FokusCandidate> candidates = new ArrayList<>();

        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid()) continue;
                if (ref.equals(companionRef)) continue;
                if (isRegisteredCompanion(ref)) continue;
                if (isScrubyNpc(store, ref)) continue;

                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) continue;

                Vector3d pos = transform.getPosition();
                double distToCompanionSq = distanceSquared(pos, companionPos);
                double distToOwnerSq = distanceSquared(pos, ownerPos);

                // Must be within max radius of at least the companion
                if (distToCompanionSq <= maxRadiusSq) {
                    double combinedDist = Math.sqrt(distToCompanionSq) + Math.sqrt(distToOwnerSq);
                    candidates.add(new FokusCandidate(ref, combinedDist));
                }
            }
        });

        Ref<EntityStore> bestRef = null;
        double bestCombined = Double.MAX_VALUE;
        for (FokusCandidate candidate : candidates) {
            if (candidate.combinedDist < bestCombined) {
                bestCombined = candidate.combinedDist;
                bestRef = candidate.ref;
            }
        }
        return bestRef;
    }

    // ========================================================================
    // Helper: Segen des Hueters modifier
    // ========================================================================

    private void applySegenModifier(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ownerRef) {
        EntityStatMap ownerStats = store.getComponent(ownerRef, EntityStatMap.getComponentType());
        if (ownerStats == null) return;
        int healthIndex = DefaultEntityStatTypes.getHealth();
        StaticModifier mod = new StaticModifier(
                Modifier.ModifierTarget.MAX,
                StaticModifier.CalculationType.ADDITIVE,
                20.0f
        );
        ownerStats.putModifier(healthIndex, MOD_SEGEN_DES_HUETERS, mod);
    }

    private void removeSegenModifier(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ownerRef) {
        EntityStatMap ownerStats = store.getComponent(ownerRef, EntityStatMap.getComponentType());
        if (ownerStats == null) return;
        ownerStats.removeModifier(DefaultEntityStatTypes.getHealth(), MOD_SEGEN_DES_HUETERS);
    }

    // ========================================================================
    // Helper: Combat report
    // ========================================================================

    private void sendCombatReport(@Nonnull PlayerRef playerRef, @Nonnull UUID ownerUuid, @Nonnull String locale, @Nonnull ScrubyOwnerBindingComponent binding) {
        float damage = reportDamageDealt.getOrDefault(ownerUuid, 0f);
        float healing = reportHealingDone.getOrDefault(ownerUuid, 0f);
        if ((damage > 0 || healing > 0) && !ScrubyMuteCombatLogCommand.isMuted(binding)) {
            StringBuilder sb = new StringBuilder(ScrubyLang.get(locale, "chat.skill.combat_report"));
            if (damage > 0) {
                sb.append(" ").append(ScrubyLang.get(locale, "chat.skill.damage", Math.round(damage)));
            }
            if (healing > 0) {
                if (damage > 0) sb.append(",");
                sb.append(" ").append(ScrubyLang.get(locale, "chat.skill.healing", Math.round(healing)));
            }
            playerRef.sendMessage(Message.raw(sb.toString()));
        }
        reportDamageDealt.put(ownerUuid, 0f);
        reportHealingDone.put(ownerUuid, 0f);
    }

    // ========================================================================
    // Helper: Check if a ref is a registered companion
    // ========================================================================

    private boolean isRegisteredCompanion(@Nonnull Ref<EntityStore> ref) {
        for (Map.Entry<UUID, Ref<EntityStore>> entry : registry.entriesSnapshot()) {
            if (entry.getValue() != null && entry.getValue().equals(ref)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the given entity is a Scruby companion (any role variant).
     * Checks the NPC role name for the "Scruby_" prefix.
     */
    private static boolean isScrubyNpc(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null) return false;
        String roleName = npc.getRoleName();
        return roleName != null && roleName.contains("Scruby_");
    }

    // ========================================================================
    // Helper: Get all abilities from profile
    // ========================================================================

    @Nonnull
    private static String getAllAbilities(@Nonnull CompanionProfile profile) {
        String f = profile.getFixedAbilities();
        String c = profile.getChosenAbilities();
        if (f.isEmpty()) return c;
        if (c.isEmpty()) return f;
        return f + "," + c;
    }

    /** Aggressive mode AoE provocation radius: 20 blocks. */
    private static final double AGGRESSIVE_RADIUS_SQ = 20.0 * 20.0;

    // ========================================================================
    // Helper: Companion attack target
    // ========================================================================

    /**
     * Gets the companion's current attack target via MarkedEntitySupport.
     * Returns null if companion has no target (not in combat).
     */
    @Nullable
    private Ref<EntityStore> getCompanionAttackTarget(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef
    ) {
        try {
            NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
            if (npcEntity == null) return null;
            Role role = npcEntity.getRole();
            if (role == null) return null;
            MarkedEntitySupport mes = role.getMarkedEntitySupport();
            if (mes == null) return null;
            Ref<EntityStore> target = mes.getMarkedEntityRef(MarkedEntitySupport.DEFAULT_TARGET_SLOT);
            if (target != null && target.isValid()) return target;
        } catch (Exception e) {
            // Silently ignore
        }
        return null;
    }

    // Helper: Distance
    // ========================================================================

    private static double distanceSquared(@Nonnull Vector3d a, @Nonnull Vector3d b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    // ========================================================================
    // Inner classes
    // ========================================================================

    /** Holder for a nearby enemy reference, its squared distance, and position. */
    private static final class NearbyEnemy {
        final Ref<EntityStore> ref;
        final double distSq;
        final Vector3d position;

        NearbyEnemy(@Nonnull Ref<EntityStore> ref, double distSq, @Nonnull Vector3d position) {
            this.ref = ref;
            this.distSq = distSq;
            this.position = position;
        }
    }

    /** Holder for Fokusfeuer candidate with combined distance metric. */
    private static final class FokusCandidate {
        final Ref<EntityStore> ref;
        final double combinedDist;

        FokusCandidate(@Nonnull Ref<EntityStore> ref, double combinedDist) {
            this.ref = ref;
            this.combinedDist = combinedDist;
        }
    }

    /** Result of an AoE damage operation — total damage dealt plus the list of hit targets. */
    private static final class AoeDamageResult {
        final float totalDamage;
        final List<Ref<EntityStore>> hitTargets;

        AoeDamageResult(float totalDamage, List<Ref<EntityStore>> hitTargets) {
            this.totalDamage = totalDamage;
            this.hitTargets = hitTargets;
        }
    }

    // Kill-loot cleanup moved to ScrubyItemCleanupSystem (EntityTickingSystem with CommandBuffer)
}
