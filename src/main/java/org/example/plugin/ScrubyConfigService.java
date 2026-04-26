package org.example.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ScrubyConfigService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "scruby-config.json";

    private final Path configFilePath;

    // --- Defaults ---
    private double xpMultiplier = 1.0;
    private int maxCompanionSlots = 5;
    private int respecCooldownHours = 24;
    private long respawnDelayMs = 10_000L;
    private boolean prestigeEnabled = true;
    private boolean baseStationEnabled = true;
    private long killXpFlat = 25L;
    private boolean proximityXpEnabled = false;
    private int proximityXpClose = 2;
    private int proximityXpMid = 1;
    private long xpCapPerKill = 150L;
    private double ownerProximityRange = 40.0;
    private long prestigeFightTimeoutMs = 300_000L;
    private double guardRadiusBlocks = 20.0;
    private int maxNameLength = 24;
    private double followDistanceBackward = 1.5;
    private double followDistanceRight = 0.75;
    private boolean welcomeGreetingEnabled = true;
    private boolean firstDiscoveryBonusEnabled = true;
    private boolean killLootEnabled = true;

    public ScrubyConfigService(@Nonnull Path dataDirectory) {
        this.configFilePath = dataDirectory.resolve(CONFIG_FILE_NAME);
        load();
    }

    public void load() {
        if (!Files.exists(configFilePath)) {
            saveDefaults();
            LOGGER.atInfo().log("[Scruby-Config] Created default config at " + configFilePath);
            return;
        }
        try {
            String json = Files.readString(configFilePath);
            ConfigData data = GSON.fromJson(json, ConfigData.class);
            if (data != null) {
                applyFromData(data);
            }
            LOGGER.atInfo().log("[Scruby-Config] Config loaded from " + configFilePath);
        } catch (IOException e) {
            LOGGER.atWarning().log("[Scruby-Config] Failed to read config, using defaults: " + e.getMessage());
        }
    }

    public void reload() {
        // Reset to defaults first, then load
        resetToDefaults();
        load();
    }

    private void resetToDefaults() {
        this.xpMultiplier = 1.0;
        this.maxCompanionSlots = 5;
        this.respecCooldownHours = 24;
        this.respawnDelayMs = 10_000L;
        this.prestigeEnabled = true;
        this.baseStationEnabled = true;
        this.killXpFlat = 25L;
        this.proximityXpEnabled = false;
        this.proximityXpClose = 2;
        this.proximityXpMid = 1;
        this.xpCapPerKill = 150L;
        this.ownerProximityRange = 40.0;
        this.prestigeFightTimeoutMs = 300_000L;
        this.guardRadiusBlocks = 20.0;
        this.maxNameLength = 24;
        this.followDistanceBackward = 1.5;
        this.followDistanceRight = 0.75;
        this.welcomeGreetingEnabled = true;
        this.firstDiscoveryBonusEnabled = true;
        this.killLootEnabled = true;
    }

    private void applyFromData(@Nonnull ConfigData data) {
        if (data.xpMultiplier != null) this.xpMultiplier = data.xpMultiplier;
        if (data.maxCompanionSlots != null) this.maxCompanionSlots = Math.max(1, Math.min(10, data.maxCompanionSlots));
        if (data.respecCooldownHours != null) this.respecCooldownHours = data.respecCooldownHours;
        if (data.respawnDelayMs != null) this.respawnDelayMs = data.respawnDelayMs;
        if (data.prestigeEnabled != null) this.prestigeEnabled = data.prestigeEnabled;
        if (data.baseStationEnabled != null) this.baseStationEnabled = data.baseStationEnabled;
        if (data.killXpFlat != null) this.killXpFlat = data.killXpFlat;
        if (data.proximityXpEnabled != null) this.proximityXpEnabled = data.proximityXpEnabled;
        if (data.proximityXpClose != null) this.proximityXpClose = data.proximityXpClose;
        if (data.proximityXpMid != null) this.proximityXpMid = data.proximityXpMid;
        if (data.xpCapPerKill != null) this.xpCapPerKill = Math.max(1, data.xpCapPerKill);
        if (data.ownerProximityRange != null) this.ownerProximityRange = Math.max(1.0, data.ownerProximityRange);
        if (data.prestigeFightTimeoutMs != null) this.prestigeFightTimeoutMs = data.prestigeFightTimeoutMs;
        if (data.guardRadiusBlocks != null) this.guardRadiusBlocks = data.guardRadiusBlocks;
        if (data.maxNameLength != null) this.maxNameLength = data.maxNameLength;
        if (data.followDistanceBackward != null) this.followDistanceBackward = data.followDistanceBackward;
        if (data.followDistanceRight != null) this.followDistanceRight = data.followDistanceRight;
        if (data.welcomeGreetingEnabled != null) this.welcomeGreetingEnabled = data.welcomeGreetingEnabled;
        if (data.firstDiscoveryBonusEnabled != null) this.firstDiscoveryBonusEnabled = data.firstDiscoveryBonusEnabled;
        if (data.killLootEnabled != null) this.killLootEnabled = data.killLootEnabled;
    }

    private void saveDefaults() {
        ConfigData data = new ConfigData();
        data.xpMultiplier = this.xpMultiplier;
        data.maxCompanionSlots = this.maxCompanionSlots;
        data.respecCooldownHours = this.respecCooldownHours;
        data.respawnDelayMs = this.respawnDelayMs;
        data.prestigeEnabled = this.prestigeEnabled;
        data.baseStationEnabled = this.baseStationEnabled;
        data.killXpFlat = this.killXpFlat;
        data.proximityXpEnabled = this.proximityXpEnabled;
        data.proximityXpClose = this.proximityXpClose;
        data.proximityXpMid = this.proximityXpMid;
        data.xpCapPerKill = this.xpCapPerKill;
        data.ownerProximityRange = this.ownerProximityRange;
        data.prestigeFightTimeoutMs = this.prestigeFightTimeoutMs;
        data.guardRadiusBlocks = this.guardRadiusBlocks;
        data.maxNameLength = this.maxNameLength;
        data.followDistanceBackward = this.followDistanceBackward;
        data.followDistanceRight = this.followDistanceRight;
        data.welcomeGreetingEnabled = this.welcomeGreetingEnabled;
        data.firstDiscoveryBonusEnabled = this.firstDiscoveryBonusEnabled;
        data.killLootEnabled = this.killLootEnabled;
        try {
            Files.createDirectories(configFilePath.getParent());
            Files.writeString(configFilePath, GSON.toJson(data));
        } catch (IOException e) {
            LOGGER.atWarning().log("[Scruby-Config] Failed to write default config: " + e.getMessage());
        }
    }

    // --- Getters ---

    public double getXpMultiplier() { return xpMultiplier; }
    public int getMaxCompanionSlots() { return maxCompanionSlots; }
    public int getRespecCooldownHours() { return respecCooldownHours; }
    public long getRespecCooldownMs() { return respecCooldownHours * 60L * 60L * 1000L; }
    public long getRespawnDelayMs() { return respawnDelayMs; }
    public boolean isPrestigeEnabled() { return prestigeEnabled; }
    public boolean isBaseStationEnabled() { return baseStationEnabled; }
    public long getKillXpFlat() { return killXpFlat; }
    public boolean isProximityXpEnabled() { return proximityXpEnabled; }
    public int getProximityXpClose() { return proximityXpClose; }
    public int getProximityXpMid() { return proximityXpMid; }
    public long getXpCapPerKill() { return xpCapPerKill; }
    public double getOwnerProximityRange() { return ownerProximityRange; }
    public double getOwnerProximityRangeSq() { return ownerProximityRange * ownerProximityRange; }
    public long getPrestigeFightTimeoutMs() { return prestigeFightTimeoutMs; }
    public double getGuardRadiusBlocks() { return guardRadiusBlocks; }
    public double getGuardRadiusSq() { return guardRadiusBlocks * guardRadiusBlocks; }
    public int getMaxNameLength() { return maxNameLength; }
    public double getFollowDistanceBackward() { return followDistanceBackward; }
    public double getFollowDistanceRight() { return followDistanceRight; }
    public boolean isWelcomeGreetingEnabled() { return welcomeGreetingEnabled; }
    public boolean isFirstDiscoveryBonusEnabled() { return firstDiscoveryBonusEnabled; }
    public boolean isKillLootEnabled() { return killLootEnabled; }

    // --- Inner DTO for Gson ---

    private static final class ConfigData {
        Double xpMultiplier;
        Integer maxCompanionSlots;
        Integer respecCooldownHours;
        Long respawnDelayMs;
        Boolean prestigeEnabled;
        Boolean baseStationEnabled;
        Long killXpFlat;
        Boolean proximityXpEnabled;
        Integer proximityXpClose;
        Integer proximityXpMid;
        Long xpCapPerKill;
        Double ownerProximityRange;
        Long prestigeFightTimeoutMs;
        Double guardRadiusBlocks;
        Integer maxNameLength;
        Double followDistanceBackward;
        Double followDistanceRight;
        Boolean welcomeGreetingEnabled;
        Boolean firstDiscoveryBonusEnabled;
        Boolean killLootEnabled;
    }
}
