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
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Skill unlock, choice system, and passive skill modifier management.
 *
 * Implemented effects (stat-modifier-based):
 * - TANK_DICKER_PELZ: +16 MaxHealth (flat ADDITIVE on companion)
 * - TANK_W1A_EISENHAUT: +25 MaxHealth (flat ADDITIVE on companion; tick-heal in TickingSystem)
 * - TANK_W1B_DORNEN: +10 MaxHealth (flat ADDITIVE on companion; reflection in TickingSystem)
 *
 * All other skills are tick-based (handled in ScrubySkillTickingSystem).
 */
public final class ScrubySkillService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // === Fixed Skill IDs (auto-unlock at level thresholds) ===
    public static final String UNIVERSAL_KAMPFINSTINKT = "UNIVERSAL_KAMPFINSTINKT";
    public static final String DPS_BRENNENDE_PFEILE = "DPS_BRENNENDE_PFEILE";
    public static final String DPS_FOKUSFEUER = "DPS_FOKUSFEUER";
    public static final String DPS_RAUBTIERSINNE = "DPS_RAUBTIERSINNE";
    public static final String HEALER_ERSTE_HILFE = "HEALER_ERSTE_HILFE";
    public static final String HEALER_REGENERATION = "HEALER_REGENERATION";
    public static final String HEALER_SEGEN_DES_HUETERS = "HEALER_SEGEN_DES_HUETERS";
    public static final String TANK_PROVOKATION = "TANK_PROVOKATION";
    public static final String TANK_DICKER_PELZ = "TANK_DICKER_PELZ";
    public static final String TANK_VERGELTUNG = "TANK_VERGELTUNG";

    // === Choice Skill IDs (player picks 1 of 2) ===
    // Lv12 choices
    public static final String DPS_W1A_DOPPELSCHUSS = "DPS_W1A_DOPPELSCHUSS";
    public static final String DPS_W1B_GIFTPFEILE = "DPS_W1B_GIFTPFEILE";
    public static final String HEALER_W1A_SCHUTZZAUBER = "HEALER_W1A_SCHUTZZAUBER";
    public static final String HEALER_W1B_REINIGUNG = "HEALER_W1B_REINIGUNG";
    public static final String TANK_W1A_EISENHAUT = "TANK_W1A_EISENHAUT";
    public static final String TANK_W1B_DORNEN = "TANK_W1B_DORNEN";
    // Lv18 choices
    public static final String DPS_W2A_TOEDLICHE_PRAEZISION = "DPS_W2A_TOEDLICHE_PRAEZISION";
    public static final String DPS_W2B_UNTERDRUECKUNGSFEUER = "DPS_W2B_UNTERDRUECKUNGSFEUER";
    public static final String HEALER_W2A_LEBENSBAND = "HEALER_W2A_LEBENSBAND";
    public static final String HEALER_W2B_NOTFALL_RETTUNG = "HEALER_W2B_NOTFALL_RETTUNG";
    public static final String TANK_W2A_KRIEGSSCHREI = "TANK_W2A_KRIEGSSCHREI";
    public static final String TANK_W2B_TREUER_BESCHUETZER = "TANK_W2B_TREUER_BESCHUETZER";

    // === Modifier IDs on EntityStatMap ===
    private static final String MOD_DICKER_PELZ = "scruby_skill_dicker_pelz";
    private static final String MOD_EISENHAUT = "scruby_skill_eisenhaut";
    private static final String MOD_DORNEN = "scruby_skill_dornen";


    // ========== Fixed Skill Unlock ==========

    @Nonnull
    public List<String> processLevelUp(@Nonnull CompanionProfile profile, int newLevel) {
        List<String> unlocked = new ArrayList<>();
        String path = profile.getPathChoice();

        if (newLevel == 3) {
            if (addSkillIfAbsent(profile, UNIVERSAL_KAMPFINSTINKT)) {
                unlocked.add(UNIVERSAL_KAMPFINSTINKT);
            }
        }
        if (newLevel == 7 && !"NONE".equals(path)) {
            String skill = getFixedPathSkill(path, 1);
            if (!skill.isEmpty() && addSkillIfAbsent(profile, skill)) {
                unlocked.add(skill);
            }
        }
        if (newLevel == 9 && !"NONE".equals(path)) {
            String skill = getFixedPathSkill(path, 2);
            if (!skill.isEmpty() && addSkillIfAbsent(profile, skill)) {
                unlocked.add(skill);
            }
        }
        if (newLevel == 15 && !"NONE".equals(path)) {
            String skill = getFixedPathSkill(path, 3);
            if (!skill.isEmpty() && addSkillIfAbsent(profile, skill)) {
                unlocked.add(skill);
            }
        }
        return unlocked;
    }

    @Nonnull
    public String getSkillDescription(@Nonnull String skillId, @Nonnull String locale) {
        String key = ScrubyLang.SKILL + ScrubyLang.skillKey(skillId) + ".desc";
        return ScrubyLang.get(locale, key);
    }

    // ========== Choice System ==========

    public boolean canChoose(@Nonnull CompanionProfile profile, int choiceSlot) {
        if ("NONE".equals(profile.getPathChoice())) return false;
        if (choiceSlot == 1) {
            return profile.getLevel() >= 12 && !hasChoice1(profile);
        }
        if (choiceSlot == 2) {
            return profile.getLevel() >= 18 && !hasChoice2(profile);
        }
        return false;
    }

    @Nullable
    public String[] getChoiceOptions(@Nonnull CompanionProfile profile, int choiceSlot) {
        String path = profile.getPathChoice();
        if ("NONE".equals(path)) return null;
        if (choiceSlot == 1) {
            return switch (path) {
                case "DPS" -> new String[]{DPS_W1A_DOPPELSCHUSS, DPS_W1B_GIFTPFEILE};
                case "HEALER" -> new String[]{HEALER_W1A_SCHUTZZAUBER, HEALER_W1B_REINIGUNG};
                case "TANK" -> new String[]{TANK_W1A_EISENHAUT, TANK_W1B_DORNEN};
                default -> null;
            };
        }
        if (choiceSlot == 2) {
            return switch (path) {
                case "DPS" -> new String[]{DPS_W2A_TOEDLICHE_PRAEZISION, DPS_W2B_UNTERDRUECKUNGSFEUER};
                case "HEALER" -> new String[]{HEALER_W2A_LEBENSBAND, HEALER_W2B_NOTFALL_RETTUNG};
                case "TANK" -> new String[]{TANK_W2A_KRIEGSSCHREI, TANK_W2B_TREUER_BESCHUETZER};
                default -> null;
            };
        }
        return null;
    }

    public boolean applyChoice(@Nonnull CompanionProfile profile, int choiceSlot, int optionIndex) {
        if (!canChoose(profile, choiceSlot)) return false;
        if (optionIndex < 1 || optionIndex > 2) return false;
        String[] options = getChoiceOptions(profile, choiceSlot);
        if (options == null) return false;
        String skillId = options[optionIndex - 1];
        profile.addChosenAbility(skillId);
        return true;
    }

    /** Returns the next open choice slot (1 or 2), or 0 if none available. */
    public int getNextOpenChoiceSlot(@Nonnull CompanionProfile profile) {
        if (canChoose(profile, 1)) return 1;
        if (canChoose(profile, 2)) return 2;
        return 0;
    }

    @Nonnull
    public String getChoiceStatusText(@Nonnull CompanionProfile profile, int choiceSlot, @Nonnull String locale) {
        int requiredLevel = choiceSlot == 1 ? 12 : 18;
        if (profile.getLevel() < requiredLevel) {
            return ScrubyLang.get(locale, "skill.choice.locked", requiredLevel);
        }
        String[] options = getChoiceOptions(profile, choiceSlot);
        if (options == null) return ScrubyLang.get(locale, "skill.choice.no_path");
        boolean chosen1 = profile.hasChosenAbility(options[0]);
        boolean chosen2 = profile.hasChosenAbility(options[1]);
        if (chosen1) return ScrubyLang.get(locale, "skill.choice.chosen", options[0]);
        if (chosen2) return ScrubyLang.get(locale, "skill.choice.chosen", options[1]);
        return ScrubyLang.get(locale, "skill.choice.open", options[0], options[1]);
    }

    // ========== Passive Skill Modifiers (applied on companion entity) ==========

    public void applyPassiveSkills(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef,
            @Nonnull CompanionProfile profile
    ) {
        EntityStatMap statMap = store.getComponent(companionRef, EntityStatMap.getComponentType());
        if (statMap == null) {
            LOGGER.atInfo().log("[Scruby] applyPassiveSkills skipped — no EntityStatMap.");
            return;
        }

        String allAbilities = getAllAbilities(profile);
        int healthIndex = DefaultEntityStatTypes.getHealth();

        // TANK_DICKER_PELZ: +16 MaxHealth
        applyOrRemoveHealthModifier(statMap, healthIndex, MOD_DICKER_PELZ, allAbilities, TANK_DICKER_PELZ, 16.0f);

        // TANK_W1A_EISENHAUT: +25 MaxHealth (tick-heal handled in TickingSystem)
        applyOrRemoveHealthModifier(statMap, healthIndex, MOD_EISENHAUT, allAbilities, TANK_W1A_EISENHAUT, 25.0f);

        // TANK_W1B_DORNEN: +10 MaxHealth (reflection handled in TickingSystem)
        applyOrRemoveHealthModifier(statMap, healthIndex, MOD_DORNEN, allAbilities, TANK_W1B_DORNEN, 10.0f);

        // All other skills are tick-based (handled in ScrubySkillTickingSystem)
    }

    public void removeAllSkillModifiers(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> companionRef
    ) {
        EntityStatMap statMap = store.getComponent(companionRef, EntityStatMap.getComponentType());
        if (statMap == null) return;
        int healthIndex = DefaultEntityStatTypes.getHealth();
        statMap.removeModifier(healthIndex, MOD_DICKER_PELZ);
        statMap.removeModifier(healthIndex, MOD_EISENHAUT);
        statMap.removeModifier(healthIndex, MOD_DORNEN);
    }

    // ========== Internals ==========

    private static void applyOrRemoveHealthModifier(
            @Nonnull EntityStatMap statMap, int healthIndex,
            @Nonnull String modId, @Nonnull String allAbilities,
            @Nonnull String skillId, float amount
    ) {
        if (allAbilities.contains(skillId)) {
            StaticModifier mod = new StaticModifier(
                    Modifier.ModifierTarget.MAX,
                    StaticModifier.CalculationType.ADDITIVE,
                    amount
            );
            statMap.putModifier(healthIndex, modId, mod);
        } else {
            statMap.removeModifier(healthIndex, modId);
        }
    }

    @Nonnull
    private static String getAllAbilities(@Nonnull CompanionProfile profile) {
        String f = profile.getFixedAbilities();
        String c = profile.getChosenAbilities();
        if (f.isEmpty()) return c;
        if (c.isEmpty()) return f;
        return f + "," + c;
    }

    private boolean addSkillIfAbsent(@Nonnull CompanionProfile profile, @Nonnull String skillId) {
        String current = profile.getFixedAbilities();
        if (current.contains(skillId)) return false;
        profile.setFixedAbilities(current.isEmpty() ? skillId : current + "," + skillId);
        return true;
    }

    private boolean hasChoice1(@Nonnull CompanionProfile profile) {
        String[] opts = getChoiceOptions(profile, 1);
        if (opts == null) return true;
        return profile.hasChosenAbility(opts[0]) || profile.hasChosenAbility(opts[1]);
    }

    private boolean hasChoice2(@Nonnull CompanionProfile profile) {
        String[] opts = getChoiceOptions(profile, 2);
        if (opts == null) return true;
        return profile.hasChosenAbility(opts[0]) || profile.hasChosenAbility(opts[1]);
    }

    @Nonnull
    private static String getFixedPathSkill(@Nonnull String path, int tier) {
        return switch (path) {
            case "DPS" -> switch (tier) {
                case 1 -> DPS_BRENNENDE_PFEILE;
                case 2 -> DPS_FOKUSFEUER;
                case 3 -> DPS_RAUBTIERSINNE;
                default -> "";
            };
            case "HEALER" -> switch (tier) {
                case 1 -> HEALER_ERSTE_HILFE;
                case 2 -> HEALER_REGENERATION;
                case 3 -> HEALER_SEGEN_DES_HUETERS;
                default -> "";
            };
            case "TANK" -> switch (tier) {
                case 1 -> TANK_PROVOKATION;
                case 2 -> TANK_DICKER_PELZ;
                case 3 -> TANK_VERGELTUNG;
                default -> "";
            };
            default -> "";
        };
    }
}
