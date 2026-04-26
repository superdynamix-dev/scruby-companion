package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Service for distributing and applying attribute points.
 *
 * Vitality: +4 MaxHealth per point (ADDITIVE on Health stat MAX).
 *   At 30 points all-in: +120 HP — roughly doubles a 100-HP base.
 *
 * Strength: +4% Damage per point on real damage events from the Scruby NPC.
 *   Cached per owner via ScrubyActiveCompanionRegistry and applied in
 *   ScrubyDamageBuffSystem (FilterDamageGroup). Skill-burst stat mutations
 *   in ScrubySkillTickingSystem bypass the damage pipeline intentionally
 *   and are not boosted — keeps the three attributes balanced as distinct
 *   build paths (Vitality = survivability, Zeal = skill uptime, Strength = auto DPS).
 *
 * Zeal: -1.5% Cooldown per point, applied directly to skill cooldown checks
 *   in ScrubySkillTickingSystem.zealAdjusted(). Clamped at -90% (10% of base).
 *   No EntityStatMap modifier — skill cooldowns are managed in plugin code.
 */
public final class ScrubyAttributeService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String MODIFIER_VITALITY = "scruby_attr_vitality";
    // private static final String MODIFIER_STRENGTH = "scruby_attr_strength";  // TODO: no stat key
    // private static final String MODIFIER_ZEAL = "scruby_attr_zeal";          // TODO: no stat key
    private static final String MODIFIER_ARMOR = "scruby_armor_hp";

    private static final float HP_PER_VITALITY_POINT = 4.0f;

    private final ScrubyArmorService armorService;
    private final ScrubyActiveCompanionRegistry registry;

    public ScrubyAttributeService(
            @Nonnull ScrubyArmorService armorService,
            @Nonnull ScrubyActiveCompanionRegistry registry
    ) {
        this.armorService = armorService;
        this.registry = registry;
    }

    public boolean addPoint(@Nonnull CompanionProfile profile, @Nonnull String type) {
        if (profile.getAttributePointsAvailable() <= 0) {
            return false;
        }
        switch (type) {
            case "vitality" -> profile.setAttributeVitality(profile.getAttributeVitality() + 1);
            case "strength" -> profile.setAttributeStrength(profile.getAttributeStrength() + 1);
            case "zeal" -> profile.setAttributeZeal(profile.getAttributeZeal() + 1);
            default -> { return false; }
        }
        profile.setAttributePointsAvailable(profile.getAttributePointsAvailable() - 1);
        return true;
    }

    public void applyAttributes(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull CompanionProfile profile,
            @Nonnull UUID ownerUuid
    ) {
        // Strength: cache for ScrubyDamageBuffSystem (FilterDamageGroup).
        // Applies only to real damage events (physical NPC hits),
        // not to skill-burst stat mutations — keeps Vitality/Zeal/Strength balance distinct.
        registry.setStrength(ownerUuid, profile.getAttributeStrength());

        EntityStatMap statMap = store.getComponent(companionRef, EntityStatMap.getComponentType());
        if (statMap == null) {
            LOGGER.atInfo().log("[Scruby] applyAttributes skipped — no EntityStatMap on companion.");
            return;
        }

        int healthIndex = DefaultEntityStatTypes.getHealth();

        // Vitality: flat HP bonus
        int vitality = profile.getAttributeVitality();
        if (vitality > 0) {
            float hpBonus = vitality * HP_PER_VITALITY_POINT;
            StaticModifier mod = new StaticModifier(
                    Modifier.ModifierTarget.MAX,
                    StaticModifier.CalculationType.ADDITIVE,
                    hpBonus
            );
            statMap.putModifier(healthIndex, MODIFIER_VITALITY, mod);
            LOGGER.atInfo().log("[Scruby] Applied vitality modifier: +" + hpBonus + " MaxHealth");
        } else {
            statMap.removeModifier(healthIndex, MODIFIER_VITALITY);
        }

        // Armor: flat HP bonus from equipped armor pieces
        int armorHp = armorService.getTotalHpBonus(profile);
        if (armorHp > 0) {
            StaticModifier armorMod = new StaticModifier(
                    Modifier.ModifierTarget.MAX,
                    StaticModifier.CalculationType.ADDITIVE,
                    (float) armorHp
            );
            statMap.putModifier(healthIndex, MODIFIER_ARMOR, armorMod);
            LOGGER.atInfo().log("[Scruby] Applied armor modifier: +" + armorHp + " MaxHealth");
        } else {
            statMap.removeModifier(healthIndex, MODIFIER_ARMOR);
        }

        // TODO: Strength modifier — no Damage stat key found in DefaultEntityStatTypes
        // TODO: Haste modifier — no CooldownReduction stat key found in DefaultEntityStatTypes
    }

    public void removeAttributes(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef
    ) {
        EntityStatMap statMap = store.getComponent(companionRef, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        int healthIndex = DefaultEntityStatTypes.getHealth();
        statMap.removeModifier(healthIndex, MODIFIER_VITALITY);
        statMap.removeModifier(healthIndex, MODIFIER_ARMOR);
    }

    public float calculateHpBonus(int vitality) {
        return vitality * HP_PER_VITALITY_POINT;
    }

    public float calculateDmgBonus(int strength) {
        return strength * 0.04f;
    }

    public float calculateCdReduction(int zeal) {
        return zeal * 0.015f;
    }

    public int calculateArmorHpBonus(@Nonnull CompanionProfile profile) {
        return armorService.getTotalHpBonus(profile);
    }

    /**
     * One-way top-up migration: ensures the profile's available attribute points
     * reflect the current "2 per level" rate. Profiles saved under the old "1 per
     * level" rate get their pool topped up without touching already-invested points.
     *
     * Idempotent: once the profile matches or exceeds the expected pool it is a no-op.
     * Never decreases available points — only raises them.
     *
     * @return true if the profile was modified (caller should persist)
     */
    public static boolean migrateAttributePoints(@Nonnull CompanionProfile profile) {
        int expectedTotal = profile.getLevel() * 2;
        int spent = profile.getAttributeVitality()
                + profile.getAttributeStrength()
                + profile.getAttributeZeal();
        int expectedAvailable = Math.max(0, expectedTotal - spent);
        if (profile.getAttributePointsAvailable() < expectedAvailable) {
            profile.setAttributePointsAvailable(expectedAvailable);
            return true;
        }
        return false;
    }
}
