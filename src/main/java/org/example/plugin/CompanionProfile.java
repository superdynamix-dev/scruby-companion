package org.example.plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone profile for a single companion slot.
 * Serialized as a JSON string and stored in ScrubyOwnerBindingComponent.
 */
public final class CompanionProfile {

    private int slotId;
    private String companionType;
    private String companionName;
    private String companionEntityUuid;
    private boolean entityMissing;
    private boolean manuallyDespawned;
    private int level;
    private long currentXp;
    private long totalXp;
    private String pathChoice;
    private int evolutionStage;
    private String fixedAbilities;
    private int attributePointsAvailable;
    private int killCount;
    private String chosenAbilities;
    private int attributeVitality;
    private int attributeStrength;
    private int attributeZeal;
    private boolean stationedAtBase;
    private boolean guardModeUnlocked;
    private boolean freeRespecUsed;
    private double stationX;
    private double stationY;
    private double stationZ;
    private String stationWorldId;
    private String stationMode;
    private int deathCount;
    private long lastRespecTimestamp;
    private boolean prestigeAttempted;
    private boolean prestigeWon;
    private String inventoryItems;
    private boolean keepInventoryOnDeath;
    private Boolean autoLootOverride;
    private String locale;
    private String equipHead;
    private String equipChest;
    private String equipLegs;
    private String equipHands;
    private String combatMode;
    private String combatModeBeforeTempleOverride;
    private String companionEntityUuidBeforeTempleOverride;
    private String companionWorldUuidBeforeTempleOverride;

    public CompanionProfile() {
        this.slotId = 0;
        this.companionType = "scruby";
        this.companionName = "Scruby";
        this.companionEntityUuid = "";
        this.entityMissing = false;
        this.manuallyDespawned = false;
        this.level = 1;
        this.currentXp = 0L;
        this.totalXp = 0L;
        this.pathChoice = "NONE";
        this.evolutionStage = 1;
        this.fixedAbilities = "";
        this.attributePointsAvailable = 0;
        this.killCount = 0;
        this.chosenAbilities = "";
        this.attributeVitality = 0;
        this.attributeStrength = 0;
        this.attributeZeal = 0;
        this.stationedAtBase = false;
        this.guardModeUnlocked = false;
        this.freeRespecUsed = false;
        this.stationX = 0.0;
        this.stationY = 0.0;
        this.stationZ = 0.0;
        this.stationWorldId = "";
        this.stationMode = "NONE";
        this.deathCount = 0;
        this.lastRespecTimestamp = 0L;
        this.prestigeAttempted = false;
        this.prestigeWon = false;
        this.inventoryItems = "";
        this.keepInventoryOnDeath = true;
        this.autoLootOverride = null;
        this.equipHead = "";
        this.equipChest = "";
        this.equipLegs = "";
        this.equipHands = "";
        this.combatMode = "PASSIVE";
        this.combatModeBeforeTempleOverride = null;
        this.companionEntityUuidBeforeTempleOverride = "";
        this.companionWorldUuidBeforeTempleOverride = "";
    }

    // --- Getters and Setters ---

    public int getSlotId() { return slotId; }
    public void setSlotId(int slotId) { this.slotId = slotId; }

    @Nonnull
    public String getCompanionType() { return companionType; }
    public void setCompanionType(@Nonnull String companionType) { this.companionType = companionType; }

    @Nonnull
    public String getCompanionName() { return companionName; }
    public void setCompanionName(@Nonnull String companionName) { this.companionName = companionName; }

    @Nonnull
    public String getCompanionEntityUuid() { return companionEntityUuid; }
    public void setCompanionEntityUuid(@Nonnull String companionEntityUuid) { this.companionEntityUuid = companionEntityUuid; }

    public boolean isEntityMissing() { return entityMissing; }
    public void setEntityMissing(boolean entityMissing) { this.entityMissing = entityMissing; }

    public boolean isManuallyDespawned() { return manuallyDespawned; }
    public void setManuallyDespawned(boolean manuallyDespawned) { this.manuallyDespawned = manuallyDespawned; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public long getCurrentXp() { return currentXp; }
    public void setCurrentXp(long currentXp) { this.currentXp = currentXp; }

    public long getTotalXp() { return totalXp; }
    public void setTotalXp(long totalXp) { this.totalXp = totalXp; }

    @Nonnull
    public String getPathChoice() { return pathChoice; }
    public void setPathChoice(@Nonnull String pathChoice) { this.pathChoice = pathChoice; }

    public int getEvolutionStage() { return evolutionStage; }
    public void setEvolutionStage(int evolutionStage) { this.evolutionStage = evolutionStage; }

    @Nonnull
    public String getFixedAbilities() { return fixedAbilities; }
    public void setFixedAbilities(@Nonnull String fixedAbilities) { this.fixedAbilities = fixedAbilities; }

    public int getAttributePointsAvailable() { return attributePointsAvailable; }
    public void setAttributePointsAvailable(int attributePointsAvailable) { this.attributePointsAvailable = attributePointsAvailable; }

    public int getKillCount() { return killCount; }
    public void setKillCount(int killCount) { this.killCount = killCount; }

    @Nonnull
    public String getChosenAbilities() { return chosenAbilities; }
    public void setChosenAbilities(@Nonnull String chosenAbilities) { this.chosenAbilities = chosenAbilities; }

    public int getAttributeVitality() { return attributeVitality; }
    public void setAttributeVitality(int attributeVitality) { this.attributeVitality = attributeVitality; }

    public int getAttributeStrength() { return attributeStrength; }
    public void setAttributeStrength(int attributeStrength) { this.attributeStrength = attributeStrength; }

    public int getAttributeZeal() { return attributeZeal; }
    public void setAttributeZeal(int attributeZeal) { this.attributeZeal = attributeZeal; }

    public boolean isStationedAtBase() { return stationedAtBase; }
    public void setStationedAtBase(boolean stationedAtBase) { this.stationedAtBase = stationedAtBase; }

    public boolean isGuardModeUnlocked() { return guardModeUnlocked; }
    public void setGuardModeUnlocked(boolean guardModeUnlocked) { this.guardModeUnlocked = guardModeUnlocked; }

    public boolean isFreeRespecUsed() { return freeRespecUsed; }
    public void setFreeRespecUsed(boolean freeRespecUsed) { this.freeRespecUsed = freeRespecUsed; }

    public double getStationX() { return stationX; }
    public void setStationX(double stationX) { this.stationX = stationX; }

    public double getStationY() { return stationY; }
    public void setStationY(double stationY) { this.stationY = stationY; }

    public double getStationZ() { return stationZ; }
    public void setStationZ(double stationZ) { this.stationZ = stationZ; }

    @Nonnull
    public String getStationWorldId() { return stationWorldId; }
    public void setStationWorldId(@Nonnull String stationWorldId) { this.stationWorldId = stationWorldId; }

    @Nonnull
    public String getStationMode() { return stationMode; }
    public void setStationMode(@Nonnull String stationMode) { this.stationMode = stationMode; }

    public int getDeathCount() { return deathCount; }
    public void setDeathCount(int deathCount) { this.deathCount = deathCount; }

    public long getLastRespecTimestamp() { return lastRespecTimestamp; }
    public void setLastRespecTimestamp(long lastRespecTimestamp) { this.lastRespecTimestamp = lastRespecTimestamp; }

    public boolean isPrestigeAttempted() { return prestigeAttempted; }
    public void setPrestigeAttempted(boolean prestigeAttempted) { this.prestigeAttempted = prestigeAttempted; }

    public boolean isPrestigeWon() { return prestigeWon; }
    public void setPrestigeWon(boolean prestigeWon) { this.prestigeWon = prestigeWon; }

    @Nonnull
    public String getInventoryItems() { return inventoryItems; }
    public void setInventoryItems(@Nonnull String inventoryItems) { this.inventoryItems = inventoryItems; }

    public boolean isKeepInventoryOnDeath() { return keepInventoryOnDeath; }
    public void setKeepInventoryOnDeath(boolean keepInventoryOnDeath) { this.keepInventoryOnDeath = keepInventoryOnDeath; }

    @Nullable
    public Boolean getAutoLootOverride() { return autoLootOverride; }
    public void setAutoLootOverride(@Nullable Boolean autoLootOverride) { this.autoLootOverride = autoLootOverride; }

    /**
     * Returns whether kill-loot is effectively active for this companion.
     * Override trumps server config; null defers to server config.
     */
    public boolean isKillLootActive(boolean serverKillLootEnabled) {
        if (autoLootOverride != null) return autoLootOverride;
        return serverKillLootEnabled;
    }

    public String getLocale() { return locale != null ? locale : "en"; }
    public void setLocale(String locale) { this.locale = locale; }

    @Nonnull
    public String getEquipHead() { return equipHead != null ? equipHead : ""; }
    public void setEquipHead(@Nonnull String equipHead) { this.equipHead = equipHead; }

    @Nonnull
    public String getEquipChest() { return equipChest != null ? equipChest : ""; }
    public void setEquipChest(@Nonnull String equipChest) { this.equipChest = equipChest; }

    @Nonnull
    public String getEquipLegs() { return equipLegs != null ? equipLegs : ""; }
    public void setEquipLegs(@Nonnull String equipLegs) { this.equipLegs = equipLegs; }

    @Nonnull
    public String getEquipHands() { return equipHands != null ? equipHands : ""; }
    public void setEquipHands(@Nonnull String equipHands) { this.equipHands = equipHands; }

    @Nonnull
    public String getCombatMode() { return combatMode != null ? combatMode : "PASSIVE"; }

    /**
     * Public setter used by all user-facing paths (Skilltree buttons,
     * /scruby-aggressive, /scruby-passive, admin commands). While a
     * Forgotten-Temple override is active the profile is locked into
     * PASSIVE — external callers cannot overwrite it. The override
     * service itself uses {@link #setCombatModeInternal(String)} to
     * bypass this guard when applying or restoring the override.
     */
    public void setCombatMode(@Nonnull String combatMode) {
        if (hasTempleOverride()) return;
        this.combatMode = combatMode;
    }

    /**
     * Internal setter without the temple-override guard. Only call from
     * {@link ScrubyCombatModeOverrideService} when applying or
     * restoring a temple override.
     */
    void setCombatModeInternal(@Nonnull String combatMode) {
        this.combatMode = combatMode;
    }

    public boolean isAggressive() { return "AGGRESSIVE".equals(getCombatMode()); }

    @Nullable
    public String getCombatModeBeforeTempleOverride() { return combatModeBeforeTempleOverride; }
    public void setCombatModeBeforeTempleOverride(@Nullable String value) {
        this.combatModeBeforeTempleOverride = (value == null || value.isEmpty()) ? null : value;
    }

    public boolean hasTempleOverride() {
        return combatModeBeforeTempleOverride != null && !combatModeBeforeTempleOverride.isEmpty();
    }

    @Nonnull
    public String getCompanionEntityUuidBeforeTempleOverride() {
        return companionEntityUuidBeforeTempleOverride != null ? companionEntityUuidBeforeTempleOverride : "";
    }

    public void setCompanionEntityUuidBeforeTempleOverride(@Nonnull String value) {
        this.companionEntityUuidBeforeTempleOverride = value != null ? value : "";
    }

    @Nonnull
    public String getCompanionWorldUuidBeforeTempleOverride() {
        return companionWorldUuidBeforeTempleOverride != null ? companionWorldUuidBeforeTempleOverride : "";
    }

    public void setCompanionWorldUuidBeforeTempleOverride(@Nonnull String value) {
        this.companionWorldUuidBeforeTempleOverride = value != null ? value : "";
    }

    /**
     * True while a Forgotten-Temple visit has parked the home-world companion's
     * entity UUID for restore on exit. Independent of {@link #hasTempleOverride()}
     * (which tracks only combat-mode override) — passive companions also use
     * this flag, since the orphan-on-exit bug applies regardless of combat mode.
     */
    public boolean hasTempleEntityOverride() {
        return companionEntityUuidBeforeTempleOverride != null
                && !companionEntityUuidBeforeTempleOverride.isEmpty();
    }

    // --- Convenience Methods ---

    public boolean hasCompanionEntityReference() {
        return !this.companionEntityUuid.isBlank();
    }

    public void clearCompanionEntityReference() {
        this.companionEntityUuid = "";
        this.entityMissing = true;
    }

    public boolean hasChosenAbility(@Nonnull String abilityId) {
        if (chosenAbilities.isEmpty()) return false;
        for (String s : chosenAbilities.split(",")) {
            if (s.equals(abilityId)) return true;
        }
        return false;
    }

    public void addChosenAbility(@Nonnull String abilityId) {
        if (hasChosenAbility(abilityId)) return;
        chosenAbilities = chosenAbilities.isEmpty() ? abilityId : chosenAbilities + "," + abilityId;
    }

    public void removeChosenAbility(@Nonnull String abilityId) {
        if (chosenAbilities.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String s : chosenAbilities.split(",")) {
            if (!s.equals(abilityId)) {
                if (!sb.isEmpty()) sb.append(",");
                sb.append(s);
            }
        }
        chosenAbilities = sb.toString();
    }

    public void clearAll() {
        this.companionEntityUuid = "";
        this.entityMissing = false;
        this.manuallyDespawned = false;
        this.level = 1;
        this.currentXp = 0L;
        this.totalXp = 0L;
        this.pathChoice = "NONE";
        this.evolutionStage = 1;
        this.fixedAbilities = "";
        this.attributePointsAvailable = 0;
        this.killCount = 0;
        this.chosenAbilities = "";
        this.attributeVitality = 0;
        this.attributeStrength = 0;
        this.attributeZeal = 0;
        this.stationedAtBase = false;
        this.guardModeUnlocked = false;
        this.freeRespecUsed = false;
        this.stationX = 0.0;
        this.stationY = 0.0;
        this.stationZ = 0.0;
        this.stationWorldId = "";
        this.stationMode = "NONE";
        this.deathCount = 0;
        this.lastRespecTimestamp = 0L;
        this.prestigeAttempted = false;
        this.prestigeWon = false;
        this.inventoryItems = "";
        this.keepInventoryOnDeath = true;
        this.autoLootOverride = null;
        this.equipHead = "";
        this.equipChest = "";
        this.equipLegs = "";
        this.equipHands = "";
        this.combatMode = "PASSIVE";
        this.combatModeBeforeTempleOverride = null;
        this.companionEntityUuidBeforeTempleOverride = "";
        this.companionWorldUuidBeforeTempleOverride = "";
    }

    // --- JSON Serialization ---

    @Nonnull
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        appendInt(sb, "slotId", slotId); sb.append(',');
        appendString(sb, "companionType", companionType); sb.append(',');
        appendString(sb, "companionName", companionName); sb.append(',');
        appendString(sb, "companionEntityUuid", companionEntityUuid); sb.append(',');
        appendBool(sb, "entityMissing", entityMissing); sb.append(',');
        appendBool(sb, "manuallyDespawned", manuallyDespawned); sb.append(',');
        appendInt(sb, "level", level); sb.append(',');
        appendLong(sb, "currentXp", currentXp); sb.append(',');
        appendLong(sb, "totalXp", totalXp); sb.append(',');
        appendString(sb, "pathChoice", pathChoice); sb.append(',');
        appendInt(sb, "evolutionStage", evolutionStage); sb.append(',');
        appendString(sb, "fixedAbilities", fixedAbilities); sb.append(',');
        appendInt(sb, "attributePointsAvailable", attributePointsAvailable); sb.append(',');
        appendInt(sb, "killCount", killCount); sb.append(',');
        appendString(sb, "chosenAbilities", chosenAbilities); sb.append(',');
        appendInt(sb, "attributeVitality", attributeVitality); sb.append(',');
        appendInt(sb, "attributeStrength", attributeStrength); sb.append(',');
        appendInt(sb, "attributeZeal", attributeZeal); sb.append(',');
        appendBool(sb, "stationedAtBase", stationedAtBase); sb.append(',');
        appendBool(sb, "guardModeUnlocked", guardModeUnlocked); sb.append(',');
        appendBool(sb, "freeRespecUsed", freeRespecUsed); sb.append(',');
        appendDouble(sb, "stationX", stationX); sb.append(',');
        appendDouble(sb, "stationY", stationY); sb.append(',');
        appendDouble(sb, "stationZ", stationZ); sb.append(',');
        appendString(sb, "stationWorldId", stationWorldId); sb.append(',');
        appendString(sb, "stationMode", stationMode); sb.append(',');
        appendInt(sb, "deathCount", deathCount); sb.append(',');
        appendLong(sb, "lastRespecTimestamp", lastRespecTimestamp); sb.append(',');
        appendBool(sb, "prestigeAttempted", prestigeAttempted); sb.append(',');
        appendBool(sb, "prestigeWon", prestigeWon); sb.append(',');
        appendString(sb, "inventoryItems", inventoryItems); sb.append(',');
        appendBool(sb, "keepInventoryOnDeath", keepInventoryOnDeath); sb.append(',');
        appendString(sb, "autoLootOverride", autoLootOverride == null ? "" : autoLootOverride.toString()); sb.append(',');
        appendString(sb, "locale", locale != null ? locale : ""); sb.append(',');
        appendString(sb, "equipHead", equipHead != null ? equipHead : ""); sb.append(',');
        appendString(sb, "equipChest", equipChest != null ? equipChest : ""); sb.append(',');
        appendString(sb, "equipLegs", equipLegs != null ? equipLegs : ""); sb.append(',');
        appendString(sb, "equipHands", equipHands != null ? equipHands : ""); sb.append(',');
        appendString(sb, "combatMode", combatMode != null ? combatMode : "PASSIVE"); sb.append(',');
        appendString(sb, "combatModeBeforeTempleOverride", combatModeBeforeTempleOverride != null ? combatModeBeforeTempleOverride : ""); sb.append(',');
        appendString(sb, "companionEntityUuidBeforeTempleOverride", companionEntityUuidBeforeTempleOverride != null ? companionEntityUuidBeforeTempleOverride : ""); sb.append(',');
        appendString(sb, "companionWorldUuidBeforeTempleOverride", companionWorldUuidBeforeTempleOverride != null ? companionWorldUuidBeforeTempleOverride : "");
        sb.append('}');
        return sb.toString();
    }

    @Nonnull
    public static CompanionProfile fromJson(@Nonnull String json) {
        CompanionProfile p = new CompanionProfile();
        String trimmed = json.trim();
        if (trimmed.isEmpty() || trimmed.equals("{}")) {
            return p;
        }
        if (trimmed.startsWith("{")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("}")) trimmed = trimmed.substring(0, trimmed.length() - 1);

        p.slotId = parseInt(readField(trimmed, "slotId"), 0);
        p.companionType = readStringField(trimmed, "companionType", "scruby");
        p.companionName = readStringField(trimmed, "companionName", "Scruby");
        p.companionEntityUuid = readStringField(trimmed, "companionEntityUuid", "");
        p.entityMissing = parseBool(readField(trimmed, "entityMissing"), false);
        p.manuallyDespawned = parseBool(readField(trimmed, "manuallyDespawned"), false);
        p.level = parseInt(readField(trimmed, "level"), 1);
        p.currentXp = parseLong(readField(trimmed, "currentXp"), 0L);
        p.totalXp = parseLong(readField(trimmed, "totalXp"), 0L);
        p.pathChoice = readStringField(trimmed, "pathChoice", "NONE");
        p.evolutionStage = parseInt(readField(trimmed, "evolutionStage"), 1);
        // Support legacy field name for backwards compatibility
        String fa = readStringField(trimmed, "fixedAbilities", null);
        p.fixedAbilities = fa != null ? fa : readStringField(trimmed, "skilledAbilities", "");
        String ap = readField(trimmed, "attributePointsAvailable");
        p.attributePointsAvailable = ap != null ? parseInt(ap, 0) : parseInt(readField(trimmed, "skillPointsAvailable"), 0);
        p.killCount = parseInt(readField(trimmed, "killCount"), 0);
        p.chosenAbilities = readStringField(trimmed, "chosenAbilities", "");
        p.attributeVitality = parseInt(readField(trimmed, "attributeVitality"), 0);
        p.attributeStrength = parseInt(readField(trimmed, "attributeStrength"), 0);
        p.attributeZeal = parseInt(readField(trimmed, "attributeZeal"), 0);
        p.stationedAtBase = parseBool(readField(trimmed, "stationedAtBase"), false);
        p.guardModeUnlocked = parseBool(readField(trimmed, "guardModeUnlocked"), false);
        p.freeRespecUsed = parseBool(readField(trimmed, "freeRespecUsed"), false);
        p.stationX = parseDouble(readField(trimmed, "stationX"), 0.0);
        p.stationY = parseDouble(readField(trimmed, "stationY"), 0.0);
        p.stationZ = parseDouble(readField(trimmed, "stationZ"), 0.0);
        p.stationWorldId = readStringField(trimmed, "stationWorldId", "");
        p.stationMode = readStringField(trimmed, "stationMode", "NONE");
        p.deathCount = parseInt(readField(trimmed, "deathCount"), 0);
        p.lastRespecTimestamp = parseLong(readField(trimmed, "lastRespecTimestamp"), 0L);
        p.prestigeAttempted = parseBool(readField(trimmed, "prestigeAttempted"), false);
        p.prestigeWon = parseBool(readField(trimmed, "prestigeWon"), false);
        p.inventoryItems = readStringField(trimmed, "inventoryItems", "");
        p.keepInventoryOnDeath = parseBool(readField(trimmed, "keepInventoryOnDeath"), true);
        String aloRaw = readStringField(trimmed, "autoLootOverride", "");
        p.autoLootOverride = aloRaw.isEmpty() ? null : Boolean.valueOf(aloRaw);
        p.locale = readStringField(trimmed, "locale", "");
        if (p.locale.isEmpty()) p.locale = null;
        p.equipHead = readStringField(trimmed, "equipHead", "");
        p.equipChest = readStringField(trimmed, "equipChest", "");
        p.equipLegs = readStringField(trimmed, "equipLegs", "");
        p.equipHands = readStringField(trimmed, "equipHands", "");
        p.combatMode = readStringField(trimmed, "combatMode", "PASSIVE");
        String overrideRaw = readStringField(trimmed, "combatModeBeforeTempleOverride", "");
        p.combatModeBeforeTempleOverride = overrideRaw.isEmpty() ? null : overrideRaw;
        p.companionEntityUuidBeforeTempleOverride = readStringField(trimmed, "companionEntityUuidBeforeTempleOverride", "");
        p.companionWorldUuidBeforeTempleOverride = readStringField(trimmed, "companionWorldUuidBeforeTempleOverride", "");
        return p;
    }

    @Nonnull
    public static String listToJson(@Nonnull List<CompanionProfile> profiles) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < profiles.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(profiles.get(i).toJson());
        }
        sb.append(']');
        return sb.toString();
    }

    @Nonnull
    public static List<CompanionProfile> listFromJson(@Nonnull String json) {
        List<CompanionProfile> result = new ArrayList<>();
        String trimmed = json.trim();
        if (trimmed.isEmpty() || trimmed.equals("[]")) {
            return result;
        }
        if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        trimmed = trimmed.trim();
        if (trimmed.isEmpty()) {
            return result;
        }
        List<String> objects = splitJsonObjects(trimmed);
        for (String obj : objects) {
            result.add(fromJson(obj.trim()));
        }
        return result;
    }

    // --- Private Helpers ---

    private static void appendString(StringBuilder sb, String key, String value) {
        sb.append('"').append(escapeJson(key)).append('"');
        sb.append(':');
        sb.append('"').append(escapeJson(value)).append('"');
    }

    private static void appendInt(StringBuilder sb, String key, int value) {
        sb.append('"').append(escapeJson(key)).append('"');
        sb.append(':').append(value);
    }

    private static void appendLong(StringBuilder sb, String key, long value) {
        sb.append('"').append(escapeJson(key)).append('"');
        sb.append(':').append(value);
    }

    private static void appendBool(StringBuilder sb, String key, boolean value) {
        sb.append('"').append(escapeJson(key)).append('"');
        sb.append(':').append(value);
    }

    private static void appendDouble(StringBuilder sb, String key, double value) {
        sb.append('"').append(escapeJson(key)).append('"');
        sb.append(':').append(value);
    }

    @Nonnull
    private static String escapeJson(@Nonnull String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Nullable
    private static String readField(@Nonnull String body, @Nonnull String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = body.indexOf(searchKey);
        if (keyIdx < 0) return null;
        int colonIdx = body.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;
        int valueStart = colonIdx + 1;
        while (valueStart < body.length() && body.charAt(valueStart) == ' ') valueStart++;
        if (valueStart >= body.length()) return null;
        char first = body.charAt(valueStart);
        int valueEnd;
        if (first == '"') {
            valueEnd = valueStart + 1;
            while (valueEnd < body.length()) {
                char c = body.charAt(valueEnd);
                if (c == '\\') { valueEnd += 2; continue; }
                if (c == '"') break;
                valueEnd++;
            }
            return body.substring(valueStart + 1, valueEnd);
        } else {
            valueEnd = valueStart;
            while (valueEnd < body.length() && body.charAt(valueEnd) != ',' && body.charAt(valueEnd) != '}') {
                valueEnd++;
            }
            return body.substring(valueStart, valueEnd).trim();
        }
    }

    @Nonnull
    private static String readStringField(@Nonnull String body, @Nonnull String key, @Nullable String defaultValue) {
        String raw = readField(body, key);
        if (raw == null) return defaultValue != null ? defaultValue : "";
        return raw.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static int parseInt(@Nullable String raw, int defaultValue) {
        if (raw == null || raw.isEmpty()) return defaultValue;
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static long parseLong(@Nullable String raw, long defaultValue) {
        if (raw == null || raw.isEmpty()) return defaultValue;
        try { return Long.parseLong(raw); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static boolean parseBool(@Nullable String raw, boolean defaultValue) {
        if (raw == null || raw.isEmpty()) return defaultValue;
        return "true".equalsIgnoreCase(raw.trim());
    }

    private static double parseDouble(@Nullable String raw, double defaultValue) {
        if (raw == null || raw.isEmpty()) return defaultValue;
        try { return Double.parseDouble(raw); } catch (NumberFormatException e) { return defaultValue; }
    }

    @Nonnull
    private static List<String> splitJsonObjects(@Nonnull String body) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    objects.add(body.substring(start, i + 1));
                }
            }
        }
        return objects;
    }
}
