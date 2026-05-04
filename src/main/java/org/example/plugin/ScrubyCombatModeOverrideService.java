// Datei: ScrubyCombatModeOverrideService.java
package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Forces all companions of a player into PASSIVE combat mode while the
 * player is inside a Forgotten Temple instance world, and restores the
 * previous mode when the player leaves again. The previous mode is stored
 * per-profile so multiple companions retain individual state.
 */
public final class ScrubyCombatModeOverrideService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final String TEMPLE_WORLD_PREFIX = "instance-Forgotten_Temple";

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry companionRegistry;
    private final ScrubyCompanionResetService resetService;

    public ScrubyCombatModeOverrideService(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry companionRegistry,
            @Nonnull ScrubyCompanionResetService resetService
    ) {
        this.bindingService = Objects.requireNonNull(bindingService);
        this.companionRegistry = Objects.requireNonNull(companionRegistry);
        this.resetService = Objects.requireNonNull(resetService);
    }

    public static boolean isTempleWorld(@Nonnull String worldName) {
        return worldName.startsWith(TEMPLE_WORLD_PREFIX);
    }

    /**
     * Entry point from the world-change event. Runs on the world thread.
     */
    public void handleWorldChange(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull String newWorldName
    ) {
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ownerRef);
        if (binding == null || !binding.hasBinding()) return;

        boolean enteringTemple = isTempleWorld(newWorldName);
        List<CompanionProfile> profiles = binding.getProfiles();
        if (profiles.isEmpty()) return;

        boolean anyChanged = false;

        for (CompanionProfile profile : profiles) {
            if (enteringTemple) {
                if (applyTempleOverride(store, ownerRef, playerRef, ownerUuid, profile, binding)) {
                    anyChanged = true;
                }
            } else {
                if (restoreFromTempleOverride(store, ownerRef, playerRef, ownerUuid, profile, binding)) {
                    anyChanged = true;
                }
            }
        }

        if (!anyChanged) return;

        binding.setProfiles(profiles);
        ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);
    }

    /**
     * Enter-Flow. Two independent override pieces are tracked here:
     *
     * 1. Combat-mode override: aggressive companions get forced to PASSIVE for
     *    the duration of the temple visit (existing behaviour).
     *
     * 2. Entity-UUID parking: the follow companion's home-world entity UUID is
     *    saved and the active reference is cleared, so the subsequent
     *    onPlayerReadyInternal flow spawns a fresh, throwaway temple companion
     *    instead of blocking on a stale cross-store ref. Without this, the
     *    home entity later reactivates as an unregistered orphan once the
     *    player returns and home chunks reload (#scruby-temple-orphan).
     *
     * Stationed companions don't follow into the temple, so the entity-UUID
     * parking step skips them; only the combat-mode override applies.
     */
    private boolean applyTempleOverride(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull ScrubyOwnerBindingComponent binding
    ) {
        boolean changed = false;

        // (1) Combat-mode override
        if (profile.isAggressive() && !profile.hasTempleOverride()) {
            profile.setCombatModeBeforeTempleOverride(profile.getCombatMode());
            profile.setCombatModeInternal("PASSIVE");
            LOGGER.atInfo().log("[Scruby] Temple ENTER combatMode: slot=" + profile.getSlotId()
                    + " savedMode=" + profile.getCombatModeBeforeTempleOverride());
            changed = true;
        }

        // (2) Entity-UUID parking — only for the active follow companion.
        boolean isActiveSlot = binding.getActiveSlot() == profile.getSlotId();
        if (isActiveSlot
                && !profile.isStationedAtBase()
                && !profile.hasTempleEntityOverride()
                && profile.hasCompanionEntityReference()) {

            String parkedUuid = profile.getCompanionEntityUuid();
            String currentWorldUuid = playerRef.getWorldUuid() != null
                    ? playerRef.getWorldUuid().toString() : "";

            profile.setCompanionEntityUuidBeforeTempleOverride(parkedUuid);
            profile.setCompanionWorldUuidBeforeTempleOverride(currentWorldUuid);

            // Drop the active reference so onPlayerReadyInternal falls through
            // to spawnFreshCompanion in the temple store.
            profile.clearCompanionEntityReference();

            // Clean-slate the registry: any cross-store ref left over from the
            // home world would block spawnService's existing-companion guard
            // and make smart-follow flap on the next tick.
            companionRegistry.unregister(ownerUuid);
            resetService.resetOwnerSession(ownerUuid);

            LOGGER.atInfo().log("[Scruby] Temple ENTER entityUuid: slot=" + profile.getSlotId()
                    + " parkedUuid=" + parkedUuid);
            changed = true;
        }

        return changed;
    }

    /**
     * Leave-Flow. Restores both override pieces independently — combat mode
     * back to AGGRESSIVE if it was saved, and the home-world entity UUID back
     * onto the profile so the subsequent onPlayerReadyInternal lookup can
     * reattach to it (immediately if chunks are loaded, or via the
     * pendingResolves wait-and-retry once chunks finish loading).
     *
     * The temple instance's throwaway companion is left behind and is
     * collected when the instance unloads.
     */
    private boolean restoreFromTempleOverride(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfile profile,
            @Nonnull ScrubyOwnerBindingComponent binding
    ) {
        boolean changed = false;

        // (1) Entity-UUID restore — runs before combat mode so the lookup in
        // onPlayerReadyInternal sees the parked UUID immediately.
        if (profile.hasTempleEntityOverride()) {
            String parkedUuid = profile.getCompanionEntityUuidBeforeTempleOverride();
            String straySpawnUuid = profile.getCompanionEntityUuid();

            // Defensive cleanup: Hytale's instance-exit migrates the player's
            // last in-temple companion entity into the home world along with
            // the player. Without this removal, that migrated entity lives on
            // as an unregistered orphan that follows but is not F-interactable
            // — exactly the bug we're fixing. Lookup the prior UUID in the
            // CURRENT (home) store and remove it if present.
            if (straySpawnUuid != null
                    && !straySpawnUuid.isEmpty()
                    && !straySpawnUuid.equals(parkedUuid)) {
                try {
                    UUID strayUuid = UUID.fromString(straySpawnUuid);
                    Ref<EntityStore> strayRef = store.getExternalData().getRefFromUUID(strayUuid);
                    if (strayRef != null && strayRef.isValid() && strayRef.getStore() == store) {
                        NPCEntity strayNpc = store.getComponent(strayRef, NPCEntity.getComponentType());
                        if (strayNpc != null) {
                            strayNpc.remove();
                            LOGGER.atInfo().log("[Scruby] Temple LEAVE: removed migrated temple companion "
                                    + straySpawnUuid + " from home world");
                        }
                    }
                } catch (Exception e) {
                    LOGGER.atInfo().log("[Scruby] Temple LEAVE: stray cleanup failed: " + e.getMessage());
                }
            }

            profile.setCompanionEntityUuid(parkedUuid);
            profile.setEntityMissing(false);
            profile.setCompanionEntityUuidBeforeTempleOverride("");
            profile.setCompanionWorldUuidBeforeTempleOverride("");

            // Drop the temple companion's registry entry so spawnService's
            // existing-companion guard doesn't block a fresh spawn fallback,
            // and so registerExistingCompanion has a clean slot to fill.
            companionRegistry.unregister(ownerUuid);
            resetService.resetOwnerSession(ownerUuid);

            LOGGER.atInfo().log("[Scruby] Temple LEAVE entityUuid: slot=" + profile.getSlotId()
                    + " restoredUuid=" + parkedUuid);
            changed = true;
        }

        // (2) Combat-mode restore.
        if (profile.hasTempleOverride()) {
            String restored = profile.getCombatModeBeforeTempleOverride();
            profile.setCombatModeBeforeTempleOverride(null);
            profile.setCombatModeInternal(restored);
            LOGGER.atInfo().log("[Scruby] Temple LEAVE combatMode: slot=" + profile.getSlotId()
                    + " restoredMode=" + restored);
            changed = true;
        }

        return changed;
    }

}
