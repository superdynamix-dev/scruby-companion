package org.example.plugin;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

public final class ScrubyLang {

    public static final String CMD = "cmd.";
    public static final String UI = "ui.";
    public static final String SKILL = "skill.";
    public static final String CHAT = "chat.";
    public static final String HUD = "hud.";
    public static final String PATH = "path.";
    public static final String EVOLUTION = "evolution.";

    private static final Map<String, ResourceBundle> BUNDLES = new ConcurrentHashMap<>();
    public static final String DEFAULT_LOCALE = "en";

    private ScrubyLang() {}

    /**
     * Get a translated string for a specific locale.
     * Callers obtain locale from CompanionProfile.getLocale().
     */
    public static String get(String locale, String key, Object... args) {
        if (locale == null) locale = DEFAULT_LOCALE;
        ResourceBundle bundle = BUNDLES.computeIfAbsent(locale, ScrubyLang::loadBundle);
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException e) {
            // Fallback: try English bundle
            if (!DEFAULT_LOCALE.equals(locale)) {
                try {
                    ResourceBundle fallback = BUNDLES.computeIfAbsent(DEFAULT_LOCALE, ScrubyLang::loadBundle);
                    pattern = fallback.getString(key);
                } catch (MissingResourceException e2) {
                    return key; // last resort: return the key itself
                }
            } else {
                return key; // last resort: return the key itself
            }
        }
        if (args.length > 0) {
            try {
                return MessageFormat.format(pattern, args);
            } catch (IllegalArgumentException e) {
                return pattern; // return unformatted if args don't match
            }
        }
        return pattern;
    }

    /**
     * Map a ScrubySkillService constant like "UNIVERSAL_KAMPFINSTINKT" to the
     * properties key fragment "kampfinstinkt".
     */
    public static String skillKey(String skillConstant) {
        String raw = skillConstant.toLowerCase();
        raw = raw.replaceFirst("^(universal|dps|healer|tank)_(w[12][ab]_)?", "");
        return raw;
    }

    /**
     * Get the localized evolution stage name for a given stage and path.
     */
    public static String getEvolutionName(String locale, int stage, String path) {
        if (stage == 1) return get(locale, EVOLUTION + "stage1");
        String pathKey = switch (path) {
            case "DPS" -> "dps";
            case "HEALER" -> "healer";
            case "TANK" -> "tank";
            default -> "dps";
        };
        return get(locale, EVOLUTION + "stage" + stage + "." + pathKey);
    }

    /**
     * Clear the bundle cache. Called by /scruby-admin config-reload.
     */
    public static void clearCache() {
        BUNDLES.clear();
    }

    private static ResourceBundle loadBundle(String locale) {
        return ResourceBundle.getBundle("lang.messages", Locale.forLanguageTag(locale));
    }
}
