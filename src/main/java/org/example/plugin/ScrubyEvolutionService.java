package org.example.plugin;

import javax.annotation.Nonnull;

public final class ScrubyEvolutionService {

    private static final String ROLE_S1_BASE = "Scruby_S1_Base";
    private static final String ROLE_S2_DPS = "Scruby_S2_DPS";
    private static final String ROLE_S2_HEALER = "Scruby_S2_Healer";
    private static final String ROLE_S2_TANK = "Scruby_S2_Tank";
    private static final String ROLE_S3_DPS = "Scruby_S3_DPS";
    private static final String ROLE_S3_HEALER = "Scruby_S3_Healer";
    private static final String ROLE_S3_TANK = "Scruby_S3_Tank";
    private static final String ROLE_PRESTIGE_DPS = "Scruby_Prestige_DPS";
    private static final String ROLE_PRESTIGE_HEALER = "Scruby_Prestige_Healer";
    private static final String ROLE_PRESTIGE_TANK = "Scruby_Prestige_Tank";

    private static final String PRESTIGE_BOSS_DPS = "Skeleton_Burnt_Gunner";
    private static final String PRESTIGE_BOSS_HEALER = "Skeleton_Burnt_Wizard";
    private static final String PRESTIGE_BOSS_TANK = "Skeleton_Burnt_Knight";

    @Nonnull
    public String getStationedRoleNameForProfile(@Nonnull CompanionProfile profile) {
        return getRoleNameForProfile(profile) + "_Stationed";
    }

    @Nonnull
    public String getRoleNameForProfile(@Nonnull CompanionProfile profile) {
        String baseRole;

        if (profile.isPrestigeWon()) {
            String path = profile.getPathChoice();
            baseRole = switch (path) {
                case "DPS" -> ROLE_PRESTIGE_DPS;
                case "HEALER" -> ROLE_PRESTIGE_HEALER;
                case "TANK" -> ROLE_PRESTIGE_TANK;
                default -> ROLE_S1_BASE;
            };
        } else {
            int stage = calculateEvolutionStage(profile.getLevel());
            String path = profile.getPathChoice();

            if (stage == 1 || "NONE".equals(path)) {
                baseRole = ROLE_S1_BASE;
            } else if (stage == 2) {
                baseRole = switch (path) {
                    case "DPS" -> ROLE_S2_DPS;
                    case "HEALER" -> ROLE_S2_HEALER;
                    case "TANK" -> ROLE_S2_TANK;
                    default -> ROLE_S1_BASE;
                };
            } else {
                // stage 3
                baseRole = switch (path) {
                    case "DPS" -> ROLE_S3_DPS;
                    case "HEALER" -> ROLE_S3_HEALER;
                    case "TANK" -> ROLE_S3_TANK;
                    default -> ROLE_S1_BASE;
                };
            }
        }

        return profile.isAggressive() ? baseRole + "_Aggressive" : baseRole;
    }

    @Nonnull
    public String getPrestigeBossRole(@Nonnull String pathChoice) {
        return switch (pathChoice) {
            case "DPS" -> PRESTIGE_BOSS_DPS;
            case "HEALER" -> PRESTIGE_BOSS_HEALER;
            case "TANK" -> PRESTIGE_BOSS_TANK;
            default -> PRESTIGE_BOSS_DPS;
        };
    }

    @Nonnull
    public String getAppearanceForProfile(@Nonnull CompanionProfile profile) {
        if (profile.isPrestigeWon()) {
            String path = profile.getPathChoice();
            return switch (path) {
                case "DPS" -> "Skeleton_Burnt_Gunner";
                case "HEALER" -> "Skeleton_Burnt_Wizard";
                case "TANK" -> "Skeleton_Burnt_Knight";
                default -> "Skeleton_Fighter";
            };
        }

        int stage = calculateEvolutionStage(profile.getLevel());
        String path = profile.getPathChoice();

        if (stage == 1) {
            return "Skeleton_Fighter";
        }
        if ("NONE".equals(path)) {
            return "Skeleton_Fighter";
        }
        if (stage == 2) {
            return switch (path) {
                case "DPS" -> "Skeleton_Archer";
                case "HEALER" -> "Skeleton_Mage";
                case "TANK" -> "Skeleton_Knight";
                default -> "Skeleton_Fighter";
            };
        }
        // stage 3
        return switch (path) {
            case "DPS" -> "Skeleton_Ranger";
            case "HEALER" -> "Skeleton_Archmage";
            case "TANK" -> "Skeleton_Burnt_Soldier";
            default -> "Skeleton_Fighter";
        };
    }

    @Nonnull
    public String getPortraitAssetName(@Nonnull CompanionProfile profile) {
        // Prestige has dedicated portrait images
        if (profile.isPrestigeWon()) {
            String path = profile.getPathChoice();
            return switch (path) {
                case "DPS" -> "portraits/scruby_prestige_dps.png";
                case "HEALER" -> "portraits/scruby_prestige_healer.png";
                case "TANK" -> "portraits/scruby_prestige_tank.png";
                default -> "portraits/scruby_s1_base.png";
            };
        }

        int stage = calculateEvolutionStage(profile.getLevel());
        String path = profile.getPathChoice();

        if (stage == 1 || "NONE".equals(path)) {
            return "portraits/scruby_s1_base.png";
        }
        if (stage == 2) {
            return switch (path) {
                case "DPS" -> "portraits/scruby_s2_dps.png";
                case "HEALER" -> "portraits/scruby_s2_healer.png";
                case "TANK" -> "portraits/scruby_s2_tank.png";
                default -> "portraits/scruby_s1_base.png";
            };
        }
        // stage 3
        return switch (path) {
            case "DPS" -> "portraits/scruby_s3_dps.png";
            case "HEALER" -> "portraits/scruby_s3_healer.png";
            case "TANK" -> "portraits/scruby_s3_tank.png";
            default -> "portraits/scruby_s1_base.png";
        };
    }

    @Nonnull
    public String getHeadAssetName(@Nonnull CompanionProfile profile) {
        // Prestige has dedicated head images
        if (profile.isPrestigeWon()) {
            String path = profile.getPathChoice();
            return switch (path) {
                case "DPS" -> "portraits/scruby_prestige_dps_head.png";
                case "HEALER" -> "portraits/scruby_prestige_healer_head.png";
                case "TANK" -> "portraits/scruby_prestige_tank_head.png";
                default -> "portraits/scruby_s1_base_head.png";
            };
        }

        int stage = calculateEvolutionStage(profile.getLevel());
        String path = profile.getPathChoice();

        if (stage == 1 || "NONE".equals(path)) {
            return "portraits/scruby_s1_base_head.png";
        }
        if (stage == 2) {
            return switch (path) {
                case "DPS" -> "portraits/scruby_s2_dps_head.png";
                case "HEALER" -> "portraits/scruby_s2_healer_head.png";
                case "TANK" -> "portraits/scruby_s2_tank_head.png";
                default -> "portraits/scruby_s1_base_head.png";
            };
        }
        // stage 3
        return switch (path) {
            case "DPS" -> "portraits/scruby_s3_dps_head.png";
            case "HEALER" -> "portraits/scruby_s3_healer_head.png";
            case "TANK" -> "portraits/scruby_s3_tank_head.png";
            default -> "portraits/scruby_s1_base_head.png";
        };
    }

    public int calculateEvolutionStage(int level) {
        if (level >= 13) return 3;
        if (level >= 5) return 2;
        return 1;
    }
}
