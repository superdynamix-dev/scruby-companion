package org.example.plugin;

import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIHud;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

/**
 * Manages the Scruby companion HUD using HyUI Builder API only.
 *
 * Create: HudBuilder.hudForPlayer(playerRef).addElement(...).show()
 * Update: HudBuilder.detachedHud().addElement(...) → updateExisting(hud)
 *   (updateExisting calls hud.update(HudBuilder) which calls refreshOrRerender
 *    which re-builds from delegate state and sends via update(false, UICommandBuilder))
 * Remove: hud.remove()
 *
 * NO fromHtml(). NO raw UICommandBuilder.set(). Only Builder API.
 */
public final class ScrubyHudManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final int JOIN_SAFE_DELAY_TICKS = 100;
    private static final int UPDATE_DELAY_TICKS = 60;

    private final ScrubyXPService xpService;
    private final ScrubyEvolutionService evolutionService;
    private final ScrubyArmorService armorService;
    private final Map<UUID, HyUIHud> activeHuds = new HashMap<>();
    private final Map<UUID, Integer> playerJoinTick = new HashMap<>();
    private final Map<UUID, Integer> hudCreatedAtTick = new HashMap<>();
    private final Map<UUID, String> hudPositions = new HashMap<>();
    private int currentTick = 0;

    public ScrubyHudManager(@Nonnull ScrubyXPService xpService, @Nonnull ScrubyEvolutionService evolutionService, @Nonnull ScrubyArmorService armorService) {
        this.xpService = Objects.requireNonNull(xpService, "xpService");
        this.evolutionService = Objects.requireNonNull(evolutionService, "evolutionService");
        this.armorService = Objects.requireNonNull(armorService, "armorService");
    }

    public void tick() { currentTick++; }

    public void onPlayerJoin(@Nonnull UUID ownerUuid) {
        playerJoinTick.put(ownerUuid, currentTick);
    }

    public boolean hasHud(@Nonnull UUID ownerUuid) {
        return activeHuds.containsKey(ownerUuid);
    }

    public boolean tryCreateHud(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nullable Ref<EntityStore> companionRef
    ) {
        Integer joinTick = playerJoinTick.get(ownerUuid);
        if (joinTick != null && currentTick - joinTick < JOIN_SAFE_DELAY_TICKS) return false;

        // Don't show HUD if companion is stationed or despawned
        if (profile.isStationedAtBase() || profile.isManuallyDespawned()) {
            return false;
        }

        try {
            HudBuilder builder = buildHud(playerRef, ownerUuid, profile, store, companionRef);
            HyUIHud hud = builder.show();
            activeHuds.put(ownerUuid, hud);
            hudCreatedAtTick.put(ownerUuid, currentTick);
            LOGGER.atInfo().log("[Scruby-HUD] Created for " + ownerUuid);
            return true;
        } catch (Throwable e) {
            LOGGER.atInfo().log("[Scruby-HUD] Create failed: " + e.getMessage());
            return false;
        }
    }

    public void updateHud(
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nullable Store<EntityStore> store,
            @Nullable Ref<EntityStore> companionRef
    ) {
        HyUIHud hud = activeHuds.get(ownerUuid);
        if (hud == null) return;

        Integer createdAt = hudCreatedAtTick.get(ownerUuid);
        if (createdAt != null && currentTick - createdAt < UPDATE_DELAY_TICKS) return;

        // Hide HUD if companion is no longer active
        if (profile.isStationedAtBase() || profile.isManuallyDespawned()) {
            HyUIHud hiddenHud = activeHuds.remove(ownerUuid);
            if (hiddenHud != null) {
                try { hiddenHud.remove(); } catch (Exception e) {
                    LOGGER.atInfo().log("[Scruby-HUD] Hide failed: " + e.getMessage());
                }
            }
            hudCreatedAtTick.remove(ownerUuid);
            return;
        }

        try {
            HudBuilder updater = buildHudDetached(ownerUuid, profile, store, companionRef);
            updater.updateExisting(hud);
        } catch (Throwable e) {
            LOGGER.atInfo().log("[Scruby-HUD] Update failed: " + e.getMessage());
        }
    }

    /** Force an immediate HUD update (bypasses delay). Used when position changes. */
    public void forceUpdateHud(
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nullable Store<EntityStore> store,
            @Nullable Ref<EntityStore> companionRef
    ) {
        HyUIHud hud = activeHuds.get(ownerUuid);
        if (hud == null) return;
        try {
            HudBuilder updater = buildHudDetached(ownerUuid, profile, store, companionRef);
            updater.updateExisting(hud);
        } catch (Throwable e) {
            LOGGER.atInfo().log("[Scruby-HUD] Force update failed: " + e.getMessage());
        }
    }

    public void removeHud(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid
    ) {
        HyUIHud hud = activeHuds.remove(ownerUuid);
        if (hud != null) {
            try { hud.remove(); } catch (Exception e) {
                LOGGER.atInfo().log("[Scruby-HUD] Remove failed: " + e.getMessage());
            }
        }
        hudCreatedAtTick.remove(ownerUuid);
        playerJoinTick.remove(ownerUuid);
    }

    public void cleanupOrphanedHuds(@Nonnull Set<UUID> activeOwners, @Nonnull Store<EntityStore> store, @Nonnull EntityStore entityStore) {
        // World-aware: only remove HUDs whose owner actually lives in THIS world's store.
        // Owners present in a different world must not be touched here — another world's
        // tick is authoritative for them. Without this guard the HUD flickers because
        // every non-owning world's tick wipes the globally shared activeHuds map.
        var toRemove = new java.util.ArrayList<UUID>();
        for (UUID id : activeHuds.keySet()) {
            if (activeOwners.contains(id)) continue;
            Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(id);
            if (ownerRef == null || !ownerRef.isValid()) continue;
            toRemove.add(id);
        }
        for (UUID id : toRemove) {
            HyUIHud hud = activeHuds.remove(id);
            if (hud != null) { try { hud.remove(); } catch (Exception ignored) {} }
            hudCreatedAtTick.remove(id);
        }
    }

    public void onPlayerLeave(@Nonnull UUID ownerUuid) {
        activeHuds.remove(ownerUuid);
        hudCreatedAtTick.remove(ownerUuid);
        playerJoinTick.remove(ownerUuid);
        hudPositions.remove(ownerUuid);
    }

    public void setHudPosition(@Nonnull UUID ownerUuid, @Nonnull String position) {
        hudPositions.put(ownerUuid, position);
    }

    /** Reset the update delay so the next tick cycle immediately updates the HUD. */
    public void resetUpdateDelay(@Nonnull UUID ownerUuid) {
        hudCreatedAtTick.remove(ownerUuid);
    }

    public void loadHudPosition(@Nonnull UUID ownerUuid, @Nonnull String position) {
        hudPositions.put(ownerUuid, position);
    }

    // ========== Build HUD via fromHtml() ==========

    private HudBuilder buildHud(@Nonnull PlayerRef playerRef, @Nonnull UUID ownerUuid,
                                @Nonnull CompanionProfile profile,
                                @Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> companionRef) {
        String pos = hudPositions.getOrDefault(ownerUuid, "TOP_RIGHT");
        return HudBuilder.hudForPlayer(playerRef).fromHtml(buildHtml(profile, store, companionRef, pos));
    }

    private HudBuilder buildHudDetached(@Nonnull UUID ownerUuid, @Nonnull CompanionProfile profile,
                                        @Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> companionRef) {
        String pos = hudPositions.getOrDefault(ownerUuid, "TOP_RIGHT");
        return HudBuilder.detachedHud().fromHtml(buildHtml(profile, store, companionRef, pos));
    }

    private String buildHtml(@Nonnull CompanionProfile profile,
                             @Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> companionRef,
                             @Nonnull String hudPosition) {
        // ── Read companion data ──
        float[] hp = readCompanionHp(store, companionRef);
        float currentHp = Math.min(hp[0], hp[1] > 0 ? hp[1] : hp[0]);
        float maxHp = hp[1];

        long xpForNext = xpService.getXpForLevel(profile.getLevel() + 1);
        boolean maxed = profile.getLevel() >= ScrubyXPService.MAX_LEVEL;

        String locale = profile.getLocale();
        String pathChoice = profile.getPathChoice();
        String accentColor = ScrubyColors.pathAccent(pathChoice);
        String badgeLabel = ScrubyColors.pathBadgeLabel(pathChoice, locale);
        boolean hasPath = !badgeLabel.isEmpty();

        String headAsset = evolutionService.getHeadAssetName(profile);

        // ── Bar calculations ──
        int barW = 225;
        int hpFillW = maxHp > 0 ? Math.max(0, Math.min(barW, Math.round(currentHp / maxHp * barW))) : 0;
        int xpFillW = maxed ? barW : (xpForNext > 0 ? Math.max(0, Math.min(barW, Math.round((float) profile.getCurrentXp() / xpForNext * barW))) : 0);
        float hpPct = maxHp > 0 ? currentHp / maxHp : 0;
        String hpColor = hpPct > 0.5f ? ScrubyColors.VIT_COLOR : hpPct > 0.25f ? ScrubyColors.STR_COLOR : "#cc3333";

        // ── Skill data ──
        String fixedAbilities = profile.getFixedAbilities();
        String[] skills = (fixedAbilities != null && !fixedAbilities.isEmpty()) ? fixedAbilities.split(",") : new String[0];

        // ── Evolution data ──
        int evoStage = profile.getEvolutionStage();

        // ── Layout constants (388x168 panel, 25% larger) ──
        int hudW = 388, hudH = 168;
        int accentW = 3;
        int contentLeft = 18;
        int portraitSize = 48;
        int textLeft = contentLeft + portraitSize + 10; // 76
        int barLeft = contentLeft + 28; // 46

        // ── Neutral blue-gray border color (no green) ──
        String frameBorder = "#2a3a4e";
        String subtleBorder = "#243448";
        String dividerColor = "#2a3a4e";
        String barTrack = "#0e1822";

        StringBuilder sb = new StringBuilder();

        // ── Root frame with decorative border ──
        String posStyle = switch (hudPosition) {
            case "TOP_LEFT" -> "anchor-top: 16; anchor-left: 16";
            case "BOTTOM_LEFT" -> "anchor-bottom: 80; anchor-left: 16";
            default -> "anchor-top: 16; anchor-right: 16"; // TOP_RIGHT
        };
        sb.append("<div id=\"hudFrame\" style=\"").append(posStyle).append("; anchor-width: ").append(hudW).append("; anchor-height: ").append(hudH).append("; background-color: #344860;\">");
        sb.append("<div style=\"anchor-top: 5; anchor-left: 5; anchor-right: 5; anchor-bottom: 5; background-color: #1a2a3e;\">");

        // ── Accent bar (left edge, 3px wide — only green/color element) ──
        sb.append("<div style=\"anchor-top: 0; anchor-left: 0; anchor-bottom: 0; anchor-width: ").append(accentW);
        sb.append("; background-color: ").append(accentColor).append(";\"></div>");

        // ====== HEADER SECTION ======
        int hTop = 12;

        // Portrait — blue-gray border
        sb.append("<div style=\"anchor-top: ").append(hTop).append("; anchor-left: ").append(contentLeft);
        sb.append("; anchor-width: ").append(portraitSize).append("; anchor-height: ").append(portraitSize);
        sb.append("; background-color: #142030");
        sb.append("; outline-color: ").append(subtleBorder).append("; outline-size: 1;\">");
        sb.append("<img src=\"").append(headAsset).append("\" style=\"anchor-top: 3; anchor-left: 3; anchor-width: 42; anchor-height: 42;\" />");
        sb.append("</div>");

        // Level badge (bottom-right of portrait, above divider)
        sb.append("<p style=\"anchor-top: ").append(hTop + portraitSize - 12).append("; anchor-left: ").append(contentLeft + portraitSize - 16);
        sb.append("; font-size: 12; color: ").append(ScrubyColors.TEXT_BRIGHT).append("; font-weight: bold;\">").append(profile.getLevel()).append("</p>");

        // Companion name
        sb.append("<p style=\"anchor-top: ").append(hTop + 2).append("; anchor-left: ").append(textLeft);
        sb.append("; font-size: 17; color: ").append(ScrubyColors.TEXT_BRIGHT).append("; font-weight: bold;\">").append(esc(profile.getCompanionName())).append("</p>");

        // Path badge + Stage (plain text, no nested div)
        String stageNum = String.valueOf(profile.getEvolutionStage());
        String metaText = hasPath
                ? ScrubyLang.get(locale, "hud.badge", badgeLabel, stageNum)
                : ScrubyLang.get(locale, "hud.stage") + " " + stageNum;
        sb.append("<p style=\"anchor-top: ").append(hTop + 24).append("; anchor-left: ").append(textLeft);
        sb.append("; font-size: 11; color: ").append(hasPath ? accentColor : ScrubyColors.TEXT_SECONDARY).append(";\">").append(esc(metaText)).append("</p>");

        // Kills (top-right, white + bold, spaced from top edge)
        sb.append("<p style=\"anchor-top: ").append(hTop + 5).append("; anchor-left: ").append(hudW - 80);
        sb.append("; font-size: 12; color: ").append(ScrubyColors.TEXT_BRIGHT).append("; font-weight: bold;\">").append(ScrubyLang.get(locale, "hud.kills", profile.getKillCount())).append("</p>");

        // ====== DIVIDER 1 — blue-gray ======
        int divY = hTop + 52;
        sb.append("<div style=\"anchor-top: ").append(divY).append("; anchor-left: ").append(contentLeft);
        sb.append("; anchor-width: ").append(hudW - contentLeft - 14).append("; anchor-height: 1; background-color: ").append(dividerColor).append(";\"></div>");

        // ====== HP BAR ======
        int hpTop = divY + 6;

        sb.append("<p style=\"anchor-top: ").append(hpTop + 1).append("; anchor-left: ").append(contentLeft);
        sb.append("; font-size: 12; color: ").append(hpColor).append("; font-weight: bold;\">").append(ScrubyLang.get(locale, "hud.hp")).append("</p>");

        // HP track — dark blue
        sb.append("<div style=\"anchor-top: ").append(hpTop + 2).append("; anchor-left: ").append(barLeft);
        sb.append("; anchor-width: ").append(barW).append("; anchor-height: 12; background-color: ").append(barTrack).append(";\"></div>");

        // HP fill
        if (hpFillW > 0) {
            sb.append("<div style=\"anchor-top: ").append(hpTop + 2).append("; anchor-left: ").append(barLeft);
            sb.append("; anchor-width: ").append(hpFillW).append("; anchor-height: 12; background-color: ").append(hpColor).append(";\"></div>");
        }

        // HP value
        sb.append("<p style=\"anchor-top: ").append(hpTop).append("; anchor-left: ").append(barLeft + barW + 8);
        sb.append("; font-size: 12; color: ").append(hpColor).append(";\">").append(Math.round(currentHp)).append("/").append(Math.round(maxHp)).append("</p>");

        // Armor HP bonus (blue)
        int armorBonus = armorService.getTotalHpBonus(profile);
        if (armorBonus > 0) {
            sb.append("<p style=\"anchor-top: ").append(hpTop + 2).append("; anchor-left: ").append(barLeft + barW + 60);
            sb.append("; font-size: 9; color: #66aaff;\">(+").append(armorBonus).append(" HP)</p>");
        }

        // ====== XP BAR ======
        int xpTop = hpTop + 21;
        String xpColor = "#c6a86d";

        sb.append("<p style=\"anchor-top: ").append(xpTop + 1).append("; anchor-left: ").append(contentLeft);
        sb.append("; font-size: 12; color: ").append(xpColor).append("; font-weight: bold;\">").append(ScrubyLang.get(locale, "hud.xp")).append("</p>");

        // XP track — dark blue
        sb.append("<div style=\"anchor-top: ").append(xpTop + 2).append("; anchor-left: ").append(barLeft);
        sb.append("; anchor-width: ").append(barW).append("; anchor-height: 10; background-color: ").append(barTrack).append(";\"></div>");

        // XP fill
        if (xpFillW > 0) {
            sb.append("<div style=\"anchor-top: ").append(xpTop + 2).append("; anchor-left: ").append(barLeft);
            sb.append("; anchor-width: ").append(xpFillW).append("; anchor-height: 10; background-color: ").append(xpColor).append(";\"></div>");
        }

        // XP value
        String xpValText = maxed ? ScrubyLang.get(locale, "hud.max") : profile.getCurrentXp() + "/" + xpForNext;
        sb.append("<p style=\"anchor-top: ").append(xpTop).append("; anchor-left: ").append(barLeft + barW + 8);
        sb.append("; font-size: 12; color: ").append(xpColor).append(";\">").append(xpValText).append("</p>");

        // ====== DIVIDER 2 — blue-gray ======
        int div2Y = xpTop + 22;
        sb.append("<div style=\"anchor-top: ").append(div2Y).append("; anchor-left: ").append(contentLeft);
        sb.append("; anchor-width: ").append(hudW - contentLeft - 14).append("; anchor-height: 1; background-color: ").append(dividerColor).append(";\"></div>");

        // ====== BOTTOM ROW: Skills + Evo ======
        int bottomTop = div2Y + 6;
        int skillSize = 28;
        int skillGap = 5;
        int skillsEndLeft = contentLeft + 3 * (skillSize + skillGap);

        for (int i = 0; i < 3; i++) {
            boolean unlocked = i < skills.length;
            int skillLeft = contentLeft + i * (skillSize + skillGap);
            // No green borders — blue-gray for all, text color is the only accent
            String skillBg = unlocked ? "#1e3040" : "#142030";
            String skillBorder = subtleBorder;
            String skillColor = unlocked ? ScrubyColors.TEXT_BRIGHT : ScrubyColors.TEXT_DISABLED;
            String icon = unlocked ? skills[i].trim().substring(0, 1).toUpperCase() : "\u00b7";

            sb.append("<div style=\"anchor-top: ").append(bottomTop).append("; anchor-left: ").append(skillLeft);
            sb.append("; anchor-width: ").append(skillSize).append("; anchor-height: ").append(skillSize);
            sb.append("; background-color: ").append(skillBg);
            sb.append("; outline-color: ").append(skillBorder).append("; outline-size: 1;\">");
            sb.append("<p style=\"anchor-full: 0; text-align: center; vertical-align: center; font-size: 14; color: ").append(skillColor).append(";\">").append(icon).append("</p>");
            sb.append("</div>");
        }

        // Evolution indicators with I/II/III — path accent only for active, blue-gray for locked
        String[] evoLabels = {"I", "II", "III"};
        int dotSize = 20;
        int connW = 5;
        int evoTotalW = 3 * dotSize + 2 * connW;
        int evoStartLeft = hudW - 18 - evoTotalW;
        int evoCenterTop = bottomTop + 10;

        // /scruby-menu hint — between skills and evo
        int menuHintLeft = skillsEndLeft + (evoStartLeft - skillsEndLeft) / 2 - 38;
        sb.append("<p style=\"anchor-top: ").append(bottomTop + 6).append("; anchor-left: ").append(menuHintLeft);
        sb.append("; font-size: 11; color: ").append(ScrubyColors.TEXT_MUTED).append(";\">/scruby-menu</p>");

        int dotLeft = evoStartLeft;
        for (int i = 0; i < 3; i++) {
            boolean active = (i + 1) <= evoStage;
            String dotBg = active ? ScrubyColors.toRgba(accentColor, 0.4f) : "#142030";
            String dotBorder = active ? accentColor : subtleBorder;
            String dotTextColor = active ? ScrubyColors.TEXT_BRIGHT : ScrubyColors.TEXT_DISABLED;

            sb.append("<div style=\"anchor-top: ").append(evoCenterTop).append("; anchor-left: ").append(dotLeft);
            sb.append("; anchor-width: ").append(dotSize).append("; anchor-height: ").append(dotSize);
            sb.append("; background-color: ").append(dotBg);
            sb.append("; outline-color: ").append(dotBorder).append("; outline-size: 1;\">");
            sb.append("<p style=\"anchor-full: 0; text-align: center; vertical-align: center; font-size: 10; color: ");
            sb.append(dotTextColor).append("; font-weight: bold;\">").append(evoLabels[i]).append("</p>");
            sb.append("</div>");

            if (i < 2) {
                boolean connActive = (i + 2) <= evoStage;
                String connColor = connActive ? accentColor : subtleBorder;
                sb.append("<div style=\"anchor-top: ").append(evoCenterTop + 9).append("; anchor-left: ").append(dotLeft + dotSize);
                sb.append("; anchor-width: ").append(connW).append("; anchor-height: 2; background-color: ").append(connColor).append(";\"></div>");
            }

            dotLeft += dotSize + connW;
        }

        // ====== BOTTOM GLOW (accent-colored, subtle) ======
        sb.append("<div style=\"anchor-top: ").append(hudH - 3).append("; anchor-left: 50; anchor-width: 288");
        sb.append("; anchor-height: 1; background-color: ").append(ScrubyColors.toRgba(accentColor, 0.20f)).append(";\"></div>");

        sb.append("</div>"); // inner panel
        sb.append("</div>"); // outer frame border
        return sb.toString();
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Nonnull
    private static float[] readCompanionHp(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> companionRef) {
        float currentHp = 0, maxHp = 0;
        if (store != null && companionRef != null && companionRef.isValid()) {
            EntityStatMap statMap = store.getComponent(companionRef, EntityStatMap.getComponentType());
            if (statMap != null) {
                EntityStatValue h = statMap.get(DefaultEntityStatTypes.getHealth());
                if (h != null) { currentHp = h.get(); maxHp = h.getMax(); }
            }
        }
        return new float[]{currentHp, maxHp};
    }
}
