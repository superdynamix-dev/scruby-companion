package org.example.plugin;

/**
 * Shared Luminous Fantasy color palette.
 * Used by ScrubyHudManager and ScrubySkillTreePage.
 *
 * HyUI supports rgba() which is auto-converted to hex.
 * Borders are done via: background-color: rgba(R,G,B,A) BORDER_SIZE
 * NOT via outline-color (that's text-outline only!).
 */
public final class ScrubyColors {
    private ScrubyColors() {}

    // ── Backgrounds (lightened to match scrubys-tab look) ──
    public static final String BG_PAGE = "#0c1420";
    public static final String BG_PANEL = "#121c28";
    public static final String BG_SURFACE = "#162030";
    public static final String BG_CARD = "#1a2838";

    // ── Green Accents (Primary UI) ──
    public static final String GREEN_PRIMARY = "#50c878";
    public static final String GREEN_SECONDARY = "#3a9a5a";
    public static final String GREEN_DARK = "#2a5a3a";
    public static final String GREEN_BG = "rgba(80,200,120,0.10)";

    // ── Borders ──
    public static final String BORDER_GREEN = "#285a48";
    public static final String BORDER_SUBTLE = "#243040";
    public static final String BORDER_MUTED = "#1a2030";

    // ── Text Hierarchy ──
    public static final String TEXT_BRIGHT = "#e8edf2";
    public static final String TEXT_PRIMARY = "#c0c8d4";
    public static final String TEXT_SECONDARY = "#8a94a8";
    public static final String TEXT_MUTED = "#566478";
    public static final String TEXT_DISABLED = "#3e4858";

    // ── Attribute Colors ──
    public static final String VIT_COLOR = "#ff6b6b";
    public static final String VIT_BG = "rgba(255,107,107,0.08)";
    public static final String VIT_BORDER = "#3a2222";
    public static final String STR_COLOR = "#ffc83c";
    public static final String STR_BG = "rgba(255,200,60,0.08)";
    public static final String STR_BORDER = "#3a3218";
    public static final String EIF_COLOR = "#3ca0ff";
    public static final String EIF_BG = "rgba(60,160,255,0.08)";
    public static final String EIF_BORDER = "#1a2a3a";

    // ── Path Colors ──
    public static final String DPS_PRIMARY = "#e05a3a";
    public static final String DPS_DARK = "#8b3a3a";
    public static final String DPS_BG = "rgba(224,90,58,0.10)";
    public static final String DPS_BORDER = "#3a2418";
    public static final String DPS_TEXT = "#f0d6b4";
    public static final String DPS_DESC = "#c0a090";

    public static final String HEALER_PRIMARY = "#4a9a5a";
    public static final String HEALER_DARK = "#3a6b3a";
    public static final String HEALER_BG = "rgba(74,154,90,0.10)";
    public static final String HEALER_BORDER = "#1a3a28";
    public static final String HEALER_TEXT = "#d0f0c0";
    public static final String HEALER_DESC = "#90c0a0";

    public static final String TANK_PRIMARY = "#4a7ac8";
    public static final String TANK_DARK = "#3a5a8b";
    public static final String TANK_BG = "rgba(74,122,200,0.10)";
    public static final String TANK_BORDER = "#1a2a3a";
    public static final String TANK_TEXT = "#c0d8f0";
    public static final String TANK_DESC = "#90a8c8";

    // ── Gold ──
    public static final String GOLD = "#c8aa6e";
    public static final String GOLD_DARK = "rgba(200,170,110,0.12)";

    // ── HUD-specific (pre-blended opaque hex) ──
    public static final String HUD_NAME = "#e0e0e0";
    public static final String HUD_HP_FILL = "#ff6b6b";
    public static final String HUD_HP_LABEL = "#cc7766";
    public static final String HUD_HP_VALUE = "#994444";
    public static final String HUD_XP_FILL = "#50c878";
    public static final String HUD_XP_LABEL = "#3a9a5a";
    public static final String HUD_XP_VALUE = "#2a6a3a";
    public static final String HUD_BAR_TRACK = "#182430";
    public static final String HUD_DIVIDER = "#1a3028";
    public static final String HUD_FRAME_BORDER = "#285a48";
    public static final String HUD_PORTRAIT_BORDER = "#285a48";
    public static final String HUD_EVO_ACTIVE_BORDER = "#285a48";
    public static final String HUD_EVO_LOCKED_BORDER = "#1e2838";
    public static final String HUD_SKILL_ACTIVE_BORDER = "#285a48";

    // ── HYUIML Border Backgrounds (background-color with border value) ──
    public static final String BORDER_BG_GREEN = "rgba(80,200,120,0.22) 1";
    public static final String BORDER_BG_SUBTLE = "rgba(255,255,255,0.08) 1";
    public static final String BORDER_BG_GOLD = "rgba(200,170,110,0.22) 1";
    public static final String BORDER_BG_VIT = "rgba(255,107,107,0.18) 1";
    public static final String BORDER_BG_STR = "rgba(255,200,60,0.18) 1";
    public static final String BORDER_BG_EIF = "rgba(60,160,255,0.18) 1";

    // ── Path Theme (all colors for a path, used by Skilltree UI) ──

    public record PathTheme(
        String accent,        // primary accent (header text, badge text, active evo)
        String secondary,     // darker accent (reached evo, ready skills)
        String descText,      // skill description text
        String separator,     // section separator line color
        String tileBorderUnlocked,  // skill tile outer border (unlocked)
        String tileBgUnlocked,      // skill tile inner bg (unlocked)
        String tileBorderReady,     // skill tile outer border (ready)
        String tileBgReady,         // skill tile inner bg (ready)
        String stripe,              // 3px accent stripe (null for no-path)
        String badgeBg,             // badge box background
        String milestoneBorder,     // path milestone outer border — WITH suffix
        String milestoneBg,         // path milestone inner bg
        String evoBorderReached,    // evo box reached outer
        String evoBgReached,        // evo box reached inner
        String evoBarReached,       // evo progress bar reached
        String evoBorderCurrent,    // evo box current outer — WITH suffix
        String evoBgCurrent,        // evo box current inner
        String evoBarCurrent        // evo progress bar current
    ) {}

    public static PathTheme pathTheme(String path) {
        return switch (path) {
            case "DPS" -> new PathTheme(
                DPS_PRIMARY, "#c05a3a", "#c0c8d4",
                "rgba(224,90,58,0.25)",
                "rgba(224,90,58,0.50)", "rgba(224,90,58,0.06)",
                "rgba(224,90,58,0.35)", "rgba(224,90,58,0.04)",
                "#c05a3a", "rgba(224,90,58,0.15)",
                "rgba(224,90,58,0.55) 2", "rgba(224,90,58,0.10)",
                "rgba(224,90,58,0.25)", "rgba(224,90,58,0.03)", "#c05a3a",
                "rgba(224,90,58,0.55) 2", "rgba(224,90,58,0.08)", DPS_PRIMARY
            );
            case "HEALER" -> new PathTheme(
                HEALER_PRIMARY, "#3a7a4a", "#c0c8d4",
                "rgba(74,154,90,0.25)",
                "rgba(74,154,90,0.50)", "rgba(74,154,90,0.06)",
                "rgba(74,154,90,0.35)", "rgba(74,154,90,0.04)",
                "#4a9a5a", "rgba(74,154,90,0.15)",
                "rgba(74,154,90,0.55) 2", "rgba(74,154,90,0.10)",
                "rgba(74,154,90,0.25)", "rgba(74,154,90,0.03)", "#3a7a4a",
                "rgba(74,154,90,0.55) 2", "rgba(74,154,90,0.08)", HEALER_PRIMARY
            );
            case "TANK" -> new PathTheme(
                TANK_PRIMARY, "#3a5a8b", "#c0c8d4",
                "rgba(74,122,200,0.25)",
                "rgba(74,122,200,0.50)", "rgba(74,122,200,0.06)",
                "rgba(74,122,200,0.35)", "rgba(74,122,200,0.04)",
                "#4a7ac8", "rgba(74,122,200,0.15)",
                "rgba(74,122,200,0.55) 2", "rgba(74,122,200,0.10)",
                "rgba(74,122,200,0.25)", "rgba(74,122,200,0.03)", "#3a5a8b",
                "rgba(74,122,200,0.55) 2", "rgba(74,122,200,0.08)", TANK_PRIMARY
            );
            default -> new PathTheme(
                "#8a94a8", "#566478", "#c0c8d4",
                "#243040",
                "rgba(255,255,255,0.10)", "rgba(255,255,255,0.02)",
                "rgba(255,255,255,0.08)", "rgba(255,255,255,0.02)",
                null, "rgba(255,255,255,0.06)",
                BORDER_BG_SUBTLE, BG_SURFACE,
                "rgba(255,255,255,0.08)", "rgba(255,255,255,0.02)", "#566478",
                "rgba(255,255,255,0.10)", "rgba(255,255,255,0.03)", "#8a94a8"
            );
        };
    }

    /** Returns the accent color for the given path choice. */
    public static String pathAccent(String pathChoice) {
        return switch (pathChoice) {
            case "DPS" -> DPS_PRIMARY;
            case "HEALER" -> HEALER_PRIMARY;
            case "TANK" -> TANK_PRIMARY;
            default -> GREEN_PRIMARY;
        };
    }

    /** Returns the path badge label, or empty string if NONE. */
    public static String pathBadgeLabel(String pathChoice, String locale) {
        return switch (pathChoice) {
            case "DPS" -> ScrubyLang.get(locale, "path.dps.badge");
            case "HEALER" -> ScrubyLang.get(locale, "path.healer.badge");
            case "TANK" -> ScrubyLang.get(locale, "path.tank.badge");
            default -> "";
        };
    }

    /** Returns the path display name, or empty string if NONE. */
    public static String pathDisplayName(String pathChoice, String locale) {
        return switch (pathChoice) {
            case "DPS" -> ScrubyLang.get(locale, "path.dps.name");
            case "HEALER" -> ScrubyLang.get(locale, "path.healer.name");
            case "TANK" -> ScrubyLang.get(locale, "path.tank.name");
            default -> ScrubyLang.get(locale, "path.none");
        };
    }

    /** Converts a hex color (#rrggbb) to rgba() with the given alpha. */
    public static String toRgba(String hex, float alpha) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return "rgba(" + r + "," + g + "," + b + "," + alpha + ")";
    }
}
