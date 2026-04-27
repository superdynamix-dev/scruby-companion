package org.example.plugin;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.playerdata.PlayerStorage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ScrubyAdminService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry companionRegistry;
    private final ScrubyEvolutionService evolutionService;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyCompanionSpawnService spawnService;
    private final ScrubySkillService skillService;
    private final ScrubyXPService xpService;
    private final ScrubyBaseStationService baseStationService;
    private final ScrubyPrestigeFightTracker prestigeFightTracker;
    private final ComponentType<EntityStore, ScrubyOwnerBindingComponent> bindingComponentType;

    public ScrubyAdminService(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry companionRegistry,
            @Nonnull ScrubyEvolutionService evolutionService,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyCompanionSpawnService spawnService,
            @Nonnull ScrubySkillService skillService,
            @Nonnull ScrubyXPService xpService,
            @Nonnull ScrubyBaseStationService baseStationService,
            @Nonnull ScrubyPrestigeFightTracker prestigeFightTracker,
            @Nonnull ComponentType<EntityStore, ScrubyOwnerBindingComponent> bindingComponentType
    ) {
        this.bindingService = Objects.requireNonNull(bindingService);
        this.companionRegistry = Objects.requireNonNull(companionRegistry);
        this.evolutionService = Objects.requireNonNull(evolutionService);
        this.resetService = Objects.requireNonNull(resetService);
        this.spawnService = Objects.requireNonNull(spawnService);
        this.skillService = Objects.requireNonNull(skillService);
        this.xpService = Objects.requireNonNull(xpService);
        this.baseStationService = Objects.requireNonNull(baseStationService);
        this.prestigeFightTracker = Objects.requireNonNull(prestigeFightTracker);
        this.bindingComponentType = Objects.requireNonNull(bindingComponentType);
    }

    // ---------------------------------------------------------------
    // Player Resolution
    // ---------------------------------------------------------------

    /**
     * Resolve a player by name. Tries online first, then offline via PlayerStorage.
     * Returns null if player not found anywhere.
     */
    @Nullable
    public AdminPlayerContext resolvePlayer(@Nonnull String playerName) {
        // 1. Try online
        Universe universe = Universe.get();
        PlayerRef onlinePlayer = universe.getPlayer(playerName, NameMatching.EXACT_IGNORE_CASE);

        if (onlinePlayer != null) {
            Ref<EntityStore> ref = onlinePlayer.getReference();
            Store<EntityStore> store = ref.getStore();
            ScrubyOwnerBindingComponent binding = store.getComponent(ref, bindingComponentType);
            if (binding == null) {
                return null; // Player online but no binding
            }
            return new AdminPlayerContext(
                    onlinePlayer.getUuid(),
                    onlinePlayer.getUsername(),
                    store, ref, onlinePlayer, binding
            );
        }

        // 2. Offline lookup requires UUID — name-based offline is not supported
        try {
            LOGGER.atInfo().log("[Scruby-Admin] Player '" + playerName + "' is not online. Offline lookup requires UUID.");
            return null;
        } catch (Exception e) {
            LOGGER.atWarning().log("[Scruby-Admin] Error during offline lookup: " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolve a player by UUID. Works for both online and offline players.
     */
    @Nullable
    public AdminPlayerContext resolvePlayerByUuid(@Nonnull UUID playerUuid) {
        Universe universe = Universe.get();

        // 1. Try online
        PlayerRef onlinePlayer = universe.getPlayer(playerUuid);
        if (onlinePlayer != null) {
            Ref<EntityStore> ref = onlinePlayer.getReference();
            Store<EntityStore> store = ref.getStore();
            ScrubyOwnerBindingComponent binding = store.getComponent(ref, bindingComponentType);
            if (binding == null) return null;
            return new AdminPlayerContext(
                    playerUuid, onlinePlayer.getUsername(),
                    store, ref, onlinePlayer, binding
            );
        }

        // 2. Try offline
        try {
            PlayerStorage storage = universe.getPlayerStorage();
            Holder<EntityStore> holder = storage.load(playerUuid).join();
            if (holder == null) return null;
            // Access binding component from offline holder
            ScrubyOwnerBindingComponent binding = holder.getComponent(bindingComponentType);
            if (binding == null) return null;
            return new AdminPlayerContext(playerUuid, playerUuid.toString(), holder, binding);
        } catch (Exception e) {
            LOGGER.atWarning().log("[Scruby-Admin] Offline load failed for " + playerUuid + ": " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------
    // Inspection
    // ---------------------------------------------------------------

    @Nonnull
    public String inspectSlot(@Nonnull AdminPlayerContext ctx, int slot) {
        CompanionProfile profile = getProfileForSlot(ctx.getBinding(), slot);
        if (profile == null) {
            return "[Scruby-Admin] Slot " + slot + " is empty.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Scruby-Admin] ---- Companion Slot ").append(profile.getSlotId()).append(" ----\n");
        sb.append("Name: ").append(profile.getCompanionName()).append("\n");
        sb.append("Level: ").append(profile.getLevel()).append("\n");
        sb.append("XP: ").append(profile.getCurrentXp()).append(" / Total: ").append(profile.getTotalXp()).append("\n");
        sb.append("Path: ").append(profile.getPathChoice()).append("\n");
        sb.append("Evolution: ").append(profile.getEvolutionStage()).append("\n");
        sb.append("Kills: ").append(profile.getKillCount()).append(" Deaths: ").append(profile.getDeathCount()).append("\n");
        sb.append("Attributes: VIT=").append(profile.getAttributeVitality());
        sb.append(" STR=").append(profile.getAttributeStrength());
        sb.append(" HST=").append(profile.getAttributeZeal());
        sb.append(" Free=").append(profile.getAttributePointsAvailable()).append("\n");
        sb.append("Fixed Skills: ").append(profile.getFixedAbilities()).append("\n");
        sb.append("Chosen Skills: ").append(profile.getChosenAbilities()).append("\n");
        sb.append("Stationed: ").append(profile.isStationedAtBase()).append("\n");
        sb.append("Entity UUID: ").append(profile.getCompanionEntityUuid()).append("\n");
        sb.append("Entity Missing: ").append(profile.isEntityMissing()).append("\n");
        sb.append("Manually Despawned: ").append(profile.isManuallyDespawned()).append("\n");
        sb.append("Prestige Attempted: ").append(profile.isPrestigeAttempted());
        sb.append(" Won: ").append(profile.isPrestigeWon()).append("\n");
        sb.append("Free Respec Used: ").append(profile.isFreeRespecUsed()).append("\n");
        return sb.toString();
    }

    @Nonnull
    public String listSlots(@Nonnull AdminPlayerContext ctx) {
        ScrubyOwnerBindingComponent binding = ctx.getBinding();
        StringBuilder sb = new StringBuilder();
        sb.append("[Scruby-Admin] Slots for ").append(ctx.getPlayerName());
        sb.append(" (").append(ctx.isOnline() ? "ONLINE" : "OFFLINE").append("):\n");
        int activeSlot = binding.getActiveSlot();
        for (CompanionProfile profile : binding.getProfiles()) {
            String marker = (profile.getSlotId() == activeSlot) ? " [ACTIVE]" : "";
            String stationed = profile.isStationedAtBase() ? " [STATIONED]" : "";
            sb.append("  Slot ").append(profile.getSlotId())
              .append(": Lv").append(profile.getLevel())
              .append(" ").append(profile.getPathChoice())
              .append(" \"").append(profile.getCompanionName()).append("\"")
              .append(marker).append(stationed).append("\n");
        }
        if (binding.getProfiles().isEmpty()) {
            sb.append("  (no companions)\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // Modification Operations
    // ---------------------------------------------------------------

    @Nonnull
    public String setLevel(@Nonnull AdminPlayerContext ctx, int slot, int level) {
        CompanionProfile profile = getProfileForSlot(ctx.getBinding(), slot);
        if (profile == null) return "[Scruby-Admin] Slot " + slot + " is empty.";
        int resolvedSlot = profile.getSlotId();
        if (level < 1 || level > ScrubyXPService.MAX_LEVEL) {
            return "[Scruby-Admin] Level must be 1-" + ScrubyXPService.MAX_LEVEL + ".";
        }
        // Preserve attribute distribution if it still fits the new cap.
        // Only on level-down past the already-spent total do we reset, since
        // having more points distributed than the cap allows produces an
        // unfixable inconsistent state in the menu.
        int oldTotalSpent = profile.getAttributeVitality()
                          + profile.getAttributeStrength()
                          + profile.getAttributeZeal();
        int newCap = level * 2;

        profile.setLevel(level);
        profile.setCurrentXp(0);
        profile.setEvolutionStage(evolutionService.calculateEvolutionStage(level));
        if (oldTotalSpent <= newCap) {
            profile.setAttributePointsAvailable(newCap - oldTotalSpent);
        } else {
            profile.setAttributePointsAvailable(newCap);
            profile.setAttributeVitality(0);
            profile.setAttributeStrength(0);
            profile.setAttributeZeal(0);
        }
        profile.setFixedAbilities("");
        for (int lvl = 1; lvl <= level; lvl++) {
            skillService.processLevelUp(profile, lvl);
        }
        updateProfileInBinding(ctx.getBinding(), resolvedSlot, profile);
        saveIfOffline(ctx);

        // Respawn companion with new role if online and active slot
        if (ctx.isOnline() && resolvedSlot == ctx.getBinding().getActiveSlot()) {
            UUID ownerUuid = ctx.getPlayerUuid();
            Ref<EntityStore> existing = companionRegistry.getCompanionRef(ownerUuid);
            if (existing != null && existing.isValid()) {
                resetService.clearLockedTarget(ctx.getStore(), existing);
                NPCEntity npcEntity = ctx.getStore().getComponent(existing, NPCEntity.getComponentType());
                if (npcEntity != null) {
                    npcEntity.remove();
                }
                companionRegistry.unregister(ownerUuid);
            }
            resetService.resetOwnerSession(ownerUuid);
            profile.setCompanionEntityUuid("");
            profile.setManuallyDespawned(false);
            profile.setEntityMissing(false);
            updateProfileInBinding(ctx.getBinding(), resolvedSlot, profile);
            spawnService.spawnCompanion(ctx.getStore(), ctx.getPlayerRef(), ctx.getOnlinePlayerRef(), ownerUuid, true);
        }

        return "[Scruby-Admin] Set " + ctx.getPlayerName() + " slot " + (resolvedSlot + 1) + " to level " + level + ".";
    }

    @Nonnull
    public String resetCompanion(@Nonnull AdminPlayerContext ctx, int slot) {
        CompanionProfile profile = getProfileForSlot(ctx.getBinding(), slot);
        if (profile == null) return "[Scruby-Admin] Slot " + slot + " is empty.";
        int resolvedSlot = profile.getSlotId();
        profile.clearAll();
        updateProfileInBinding(ctx.getBinding(), resolvedSlot, profile);
        saveIfOffline(ctx);
        return "[Scruby-Admin] Reset companion in slot " + (resolvedSlot + 1) + " for " + ctx.getPlayerName() + ".";
    }

    @Nonnull
    public String deleteCompanion(@Nonnull AdminPlayerContext ctx, int slot) {
        CompanionProfile profile = getProfileForSlot(ctx.getBinding(), slot);
        if (profile == null) return "[Scruby-Admin] Slot " + slot + " is already empty.";
        int resolvedSlot = profile.getSlotId();
        removeProfileFromBinding(ctx.getBinding(), resolvedSlot);
        saveIfOffline(ctx);
        return "[Scruby-Admin] Deleted companion in slot " + (resolvedSlot + 1) + " for " + ctx.getPlayerName() + ".";
    }

    @Nonnull
    public String prestigeReset(@Nonnull AdminPlayerContext ctx, int slot) {
        CompanionProfile profile = getProfileForSlot(ctx.getBinding(), slot);
        if (profile == null) return "[Scruby-Admin] Slot " + slot + " is empty.";
        int resolvedSlot = profile.getSlotId();
        profile.setPrestigeAttempted(false);
        profile.setPrestigeWon(false);
        updateProfileInBinding(ctx.getBinding(), resolvedSlot, profile);
        saveIfOffline(ctx);
        return "[Scruby-Admin] Prestige reset for " + ctx.getPlayerName() + " slot " + (resolvedSlot + 1) + ".";
    }

    @Nonnull
    public String setPath(@Nonnull AdminPlayerContext ctx, int slot, @Nonnull String path) {
        String upper = path.toUpperCase();
        if (!"DPS".equals(upper) && !"HEALER".equals(upper) && !"TANK".equals(upper) && !"NONE".equals(upper)) {
            return "[Scruby-Admin] Invalid path. Use: DPS, HEALER, TANK, NONE.";
        }
        CompanionProfile profile = getProfileForSlot(ctx.getBinding(), slot);
        if (profile == null) return "[Scruby-Admin] Slot " + slot + " is empty.";
        int resolvedSlot = profile.getSlotId();
        profile.setPathChoice(upper);
        // Replay skill unlocks for new path
        profile.setFixedAbilities("");
        profile.setChosenAbilities("");
        for (int lvl = 1; lvl <= profile.getLevel(); lvl++) {
            skillService.processLevelUp(profile, lvl);
        }
        updateProfileInBinding(ctx.getBinding(), resolvedSlot, profile);
        saveIfOffline(ctx);
        return "[Scruby-Admin] Set path to " + upper + " for " + ctx.getPlayerName() + " slot " + (resolvedSlot + 1) + ".";
    }

    @Nonnull
    public String addXp(@Nonnull AdminPlayerContext ctx, int slot, long amount) {
        CompanionProfile profile = getProfileForSlot(ctx.getBinding(), slot);
        if (profile == null) return "[Scruby-Admin] Slot " + slot + " is empty.";
        int resolvedSlot = profile.getSlotId();
        xpService.addXP(profile, amount, profile.getLocale());
        updateProfileInBinding(ctx.getBinding(), resolvedSlot, profile);
        saveIfOffline(ctx);
        return "[Scruby-Admin] Added " + amount + " XP to " + ctx.getPlayerName() + " slot " + (resolvedSlot + 1)
                + ". Now level " + profile.getLevel() + ".";
    }

    // ---------------------------------------------------------------
    // Fix Operations (online-only where noted)
    // ---------------------------------------------------------------

    @Nonnull
    public String forceDespawn(@Nonnull AdminPlayerContext ctx, int slot) {
        CompanionProfile profile = getProfileForSlot(ctx.getBinding(), slot);
        if (profile == null) return "[Scruby-Admin] Slot " + slot + " is empty.";
        int resolvedSlot = profile.getSlotId();

        // If online, remove the actual NPC entity from the world
        if (ctx.isOnline()) {
            UUID ownerUuid = ctx.getPlayerUuid();

            // Try to find and remove the NPC entity via the profile's stored UUID
            if (profile.hasCompanionEntityReference()) {
                try {
                    UUID companionUuid = UUID.fromString(profile.getCompanionEntityUuid());
                    EntityStore entityStore = ctx.getStore().getExternalData();
                    Ref<EntityStore> companionRef = entityStore.getRefFromUUID(companionUuid);
                    if (companionRef != null && companionRef.isValid()) {
                        resetService.clearLockedTarget(ctx.getStore(), companionRef);
                        NPCEntity npcEntity = ctx.getStore().getComponent(companionRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            npcEntity.remove();
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // Invalid UUID format, skip entity removal
                }
            }

            // Also check registry for any registered companion ref
            Ref<EntityStore> registryRef = companionRegistry.getCompanionRef(ownerUuid);
            if (registryRef != null && registryRef.isValid()) {
                resetService.clearLockedTarget(ctx.getStore(), registryRef);
                NPCEntity npcEntity = ctx.getStore().getComponent(registryRef, NPCEntity.getComponentType());
                if (npcEntity != null) {
                    npcEntity.remove();
                }
            }
            companionRegistry.unregister(ownerUuid);
            resetService.resetOwnerSession(ownerUuid);
        }

        // Clear profile state
        profile.setCompanionEntityUuid("");
        profile.setEntityMissing(false);
        profile.setManuallyDespawned(true);
        updateProfileInBinding(ctx.getBinding(), resolvedSlot, profile);
        saveIfOffline(ctx);
        return "[Scruby-Admin] Force despawned companion in slot " + (resolvedSlot + 1) + " for " + ctx.getPlayerName() + ".";
    }

    @Nonnull
    public String forceRecall(@Nonnull AdminPlayerContext ctx, int slot) {
        CompanionProfile profile = getProfileForSlot(ctx.getBinding(), slot);
        if (profile == null) return "[Scruby-Admin] Slot " + slot + " is empty.";
        int resolvedSlot = profile.getSlotId();
        profile.setStationedAtBase(false);
        profile.setStationX(0);
        profile.setStationY(0);
        profile.setStationZ(0);
        profile.setStationWorldId("");
        profile.setStationMode("NONE");
        updateProfileInBinding(ctx.getBinding(), resolvedSlot, profile);
        saveIfOffline(ctx);
        return "[Scruby-Admin] Force recalled companion from station for " + ctx.getPlayerName() + " slot " + (resolvedSlot + 1) + ".";
    }

    @Nonnull
    public String clearState(@Nonnull AdminPlayerContext ctx, int slot) {
        CompanionProfile profile = getProfileForSlot(ctx.getBinding(), slot);
        if (profile == null) return "[Scruby-Admin] Slot " + slot + " is empty.";
        int resolvedSlot = profile.getSlotId();
        profile.setEntityMissing(false);
        profile.setManuallyDespawned(false);
        profile.setFreeRespecUsed(false);
        profile.setLastRespecTimestamp(0L);
        updateProfileInBinding(ctx.getBinding(), resolvedSlot, profile);
        saveIfOffline(ctx);
        return "[Scruby-Admin] Cleared stuck state flags for " + ctx.getPlayerName() + " slot " + (resolvedSlot + 1) + ".";
    }

    @Nonnull
    public String endPrestige(@Nonnull AdminPlayerContext ctx) {
        UUID ownerUuid = ctx.getPlayerUuid();
        prestigeFightTracker.endFight(ownerUuid);
        return "[Scruby-Admin] Ended prestige fight for " + ctx.getPlayerName() + ".";
    }

    @Nonnull
    public String forceRespawn(@Nonnull AdminPlayerContext ctx, int slot) {
        if (!ctx.isOnline()) {
            return "[Scruby-Admin] Player must be online to respawn companion.";
        }
        CompanionProfile profile = getProfileForSlot(ctx.getBinding(), slot);
        if (profile == null) return "[Scruby-Admin] Slot " + slot + " is empty.";
        int resolvedSlot = profile.getSlotId();
        profile.setManuallyDespawned(false);
        profile.setEntityMissing(false);
        updateProfileInBinding(ctx.getBinding(), resolvedSlot, profile);
        // Despawn existing entity if any
        UUID ownerUuid = ctx.getPlayerUuid();
        Ref<EntityStore> existing = companionRegistry.getCompanionRef(ownerUuid);
        if (existing != null && existing.isValid()) {
            resetService.clearLockedTarget(ctx.getStore(), existing);
            NPCEntity npcEntity = ctx.getStore().getComponent(existing, NPCEntity.getComponentType());
            if (npcEntity != null) {
                npcEntity.remove();
            }
            companionRegistry.unregister(ownerUuid);
        }
        resetService.resetOwnerSession(ownerUuid);
        // Clear stale entity reference so spawn isn't blocked
        profile.setCompanionEntityUuid("");
        updateProfileInBinding(ctx.getBinding(), resolvedSlot, profile);
        spawnService.spawnCompanion(ctx.getStore(), ctx.getPlayerRef(), ctx.getOnlinePlayerRef(), ownerUuid);
        return "[Scruby-Admin] Force respawned companion in slot " + (resolvedSlot + 1) + " for " + ctx.getPlayerName() + ".";
    }

    // ---------------------------------------------------------------
    // Discovery Operations
    // ---------------------------------------------------------------

    @Nonnull
    public String listDiscoveries(@Nonnull AdminPlayerContext ctx) {
        String mobs = ctx.getBinding().getDiscoveredMobs();
        if (mobs.isEmpty()) {
            return "[Scruby-Admin] " + ctx.getPlayerName() + " has no discoveries yet.";
        }
        String[] types = mobs.split(",");
        StringBuilder sb = new StringBuilder();
        sb.append("[Scruby-Admin] Discoveries for ").append(ctx.getPlayerName());
        sb.append(" (").append(types.length).append(" types):\n");
        for (String type : types) {
            sb.append("  - ").append(type.replace('_', ' ')).append("\n");
        }
        return sb.toString();
    }

    @Nonnull
    public String clearDiscoveries(@Nonnull AdminPlayerContext ctx) {
        String mobs = ctx.getBinding().getDiscoveredMobs();
        if (mobs.isEmpty()) {
            return "[Scruby-Admin] " + ctx.getPlayerName() + " has no discoveries to clear.";
        }
        int count = mobs.split(",").length;
        ctx.getBinding().setDiscoveredMobs("");
        saveIfOffline(ctx);
        return "[Scruby-Admin] Cleared " + count + " discoveries for " + ctx.getPlayerName() + ".";
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    @Nullable
    private CompanionProfile getProfileForSlot(@Nonnull ScrubyOwnerBindingComponent binding, int slot) {
        // Convert from 1-based (user-facing) to 0-based (internal slotId)
        int internalSlot = slot - 1;
        for (CompanionProfile p : binding.getProfiles()) {
            if (p.getSlotId() == internalSlot) return p;
        }
        return null;
    }

    /**
     * Updates a profile in the binding's profile list by slotId.
     * Since ScrubyOwnerBindingComponent stores profiles as a serialized JSON list,
     * we need to get the list, replace the matching entry, and set it back.
     */
    private void updateProfileInBinding(@Nonnull ScrubyOwnerBindingComponent binding, int slotId, @Nonnull CompanionProfile profile) {
        List<CompanionProfile> profiles = binding.getProfiles();
        boolean found = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).getSlotId() == slotId) {
                profiles.set(i, profile);
                found = true;
                break;
            }
        }
        if (!found) {
            profile.setSlotId(slotId);
            profiles.add(profile);
        }
        binding.setProfiles(profiles);
    }

    /**
     * Removes a profile from the binding's profile list by slotId.
     */
    private void removeProfileFromBinding(@Nonnull ScrubyOwnerBindingComponent binding, int slotId) {
        List<CompanionProfile> profiles = binding.getProfiles();
        boolean removed = profiles.removeIf(p -> p.getSlotId() == slotId);
        if (!removed) {
            return;
        }
        // Compact slotIds so addCompanion doesn't refill the just-freed slot ahead of shifted siblings.
        for (CompanionProfile p : profiles) {
            if (p.getSlotId() > slotId) {
                p.setSlotId(p.getSlotId() - 1);
            }
        }
        binding.setProfiles(profiles);
        int currentActive = binding.getActiveSlot();
        if (currentActive == slotId) {
            if (profiles.isEmpty()) {
                binding.clearAll();
            } else {
                binding.setActiveSlot(profiles.get(0).getSlotId());
            }
        } else if (currentActive > slotId) {
            binding.setActiveSlot(currentActive - 1);
        }
    }

    private void saveIfOffline(@Nonnull AdminPlayerContext ctx) {
        if (ctx.isOnline()) {
            ScrubyCompanionPlugin.savePlayerAsync(ctx.getStore(), ctx.getPlayerRef(), ctx.getPlayerUuid());
        } else if (ctx.getOfflineHolder() != null) {
            try {
                PlayerStorage storage = Universe.get().getPlayerStorage();
                storage.save(ctx.getPlayerUuid(), ctx.getOfflineHolder()).join();
            } catch (Exception e) {
                LOGGER.atWarning().log("[Scruby-Admin] Failed to save offline data: " + e.getMessage());
            }
        }
    }
}
