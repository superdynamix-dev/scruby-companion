package org.example.plugin;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * XP and level-up logic for CompanionProfile.
 * Pure service — no Hytale API dependencies, no TickingSystem.
 */
public final class ScrubyXPService {

    public static final int MAX_LEVEL = 20;

    private final ScrubySkillService skillService;
    private final ScrubyConfigService configService;

    public ScrubyXPService(@Nonnull ScrubySkillService skillService, @Nonnull ScrubyConfigService configService) {
        this.skillService = java.util.Objects.requireNonNull(skillService, "skillService");
        this.configService = java.util.Objects.requireNonNull(configService, "configService");
    }

    // Index 0 = Level 1, Index 19 = Level 20
    private static final long[] XP_PER_LEVEL = {
            0,     // Level 1  — starting level, no XP needed
            50,    // Level 2  — instant
            75,    // Level 3  — quick (universal skill)
            100,   // Level 4  — quick
            150,   // Level 5  — path unlock
            200,   // Level 6
            250,   // Level 7  — path skill 1
            300,   // Level 8
            400,   // Level 9  — path skill 2
            500,   // Level 10
            600,   // Level 11 — slowdown begins
            750,   // Level 12 — choice 1
            900,   // Level 13 — evolution stage 3
            1100,  // Level 14
            1300,  // Level 15 — path skill 3
            1500,  // Level 16
            1800,  // Level 17
            2100,  // Level 18
            2400,  // Level 19
            2800,  // Level 20 — choice 2, prestige
    };

    public long getXpForLevel(int level) {
        if (level < 1 || level > MAX_LEVEL) {
            return 0;
        }
        return XP_PER_LEVEL[level - 1];
    }

    @Nonnull
    public List<String> addXP(@Nonnull CompanionProfile profile, long amount, @Nonnull String locale) {
        List<String> messages = new ArrayList<>();

        if (profile.getLevel() >= MAX_LEVEL || amount <= 0) {
            return messages;
        }

        long adjustedAmount = Math.round(amount * configService.getXpMultiplier());
        if (adjustedAmount <= 0) {
            return messages;
        }

        profile.setCurrentXp(profile.getCurrentXp() + adjustedAmount);
        profile.setTotalXp(profile.getTotalXp() + adjustedAmount);

        while (profile.getLevel() < MAX_LEVEL) {
            int nextLevel = profile.getLevel() + 1;
            // Block level-up from 5 to 6 if no path chosen
            if (profile.getLevel() == 5 && "NONE".equals(profile.getPathChoice())) {
                break;
            }
            long xpNeeded = getXpForLevel(nextLevel);

            if (profile.getCurrentXp() < xpNeeded) {
                break;
            }

            profile.setCurrentXp(profile.getCurrentXp() - xpNeeded);
            profile.setLevel(nextLevel);

            messages.add(ScrubyLang.get(locale, "chat.levelup", profile.getCompanionName(), nextLevel));

            profile.setAttributePointsAvailable(profile.getAttributePointsAvailable() + 2);
            messages.add(ScrubyLang.get(locale, "chat.levelup.attr"));
            messages.add(ScrubyLang.get(locale, "chat.levelup.attr.hint"));

            List<String> newSkills = skillService.processLevelUp(profile, nextLevel);
            for (String skillId : newSkills) {
                String skillName = ScrubyLang.get(locale, ScrubyLang.SKILL + ScrubyLang.skillKey(skillId) + ".name");
                String skillDesc = skillService.getSkillDescription(skillId, locale);
                messages.add(ScrubyLang.get(locale, "chat.levelup.skill", skillName, skillDesc));
            }

            // Choice notifications
            if ((nextLevel == 12 || nextLevel == 20) && skillService.canChoose(profile, nextLevel == 12 ? 1 : 2)) {
                int slot = nextLevel == 12 ? 1 : 2;
                String[] opts = skillService.getChoiceOptions(profile, slot);
                if (opts != null) {
                    messages.add(ScrubyLang.get(locale, "chat.levelup.choice"));
                    messages.add(ScrubyLang.get(locale, "chat.levelup.choice.hint"));
                    String opt1Name = ScrubyLang.get(locale, ScrubyLang.SKILL + ScrubyLang.skillKey(opts[0]) + ".name");
                    String opt1Desc = skillService.getSkillDescription(opts[0], locale);
                    messages.add(ScrubyLang.get(locale, "chat.levelup.choice.option", 1, opt1Name, opt1Desc));
                    String opt2Name = ScrubyLang.get(locale, ScrubyLang.SKILL + ScrubyLang.skillKey(opts[1]) + ".name");
                    String opt2Desc = skillService.getSkillDescription(opts[1], locale);
                    messages.add(ScrubyLang.get(locale, "chat.levelup.choice.option", 2, opt2Name, opt2Desc));
                }
            }
        }

        return messages;
    }

    /**
     * Returns true if levelling from oldLevel to newLevel crosses an evolution boundary
     * (Stage 1→2 at level 10, Stage 2→3 at level 30).
     */
    public boolean triggersEvolution(int oldLevel, int newLevel) {
        return (oldLevel < 13 && newLevel >= 13);
    }

    private static final long XP_FLOOR = 5L;

    public long calculateKillXP(float mobMaxHP, long xpCap) {
        return Math.max(XP_FLOOR, Math.min(xpCap, Math.round(mobMaxHP / 3f)));
    }
}
