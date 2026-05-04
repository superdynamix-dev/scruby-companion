// Datei: ScrubyCombatModeOverrideService.java
package org.example.plugin;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
    private final ComponentType<EntityStore, ScrubyOwnerBindingComponent> bindingComponentType;

    public ScrubyCombatModeOverrideService(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry companionRegistry,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ComponentType<EntityStore, ScrubyOwnerBindingComponent> bindingComponentType
    ) {
        this.bindingService = Objects.requireNonNull(bindingService);
        this.companionRegistry = Objects.requireNonNull(companionRegistry);
        this.resetService = Objects.requireNonNull(resetService);
        this.bindingComponentType = Objects.requireNonNull(bindingComponentType);
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

        // After restoring on temple-exit, sweep the new world for orphaned
        // Scruby_*-role entities that have no profile-anchor in any binding.
        // Hytale's instance-exit can migrate any of several intermediate spawns
        // (initial temple spawn, smart-follow respawn, stationed copies) into
        // the parent world. The targeted cleanup we removed only checked the
        // single most-recent profile UUID; in practice Hytale picks a different
        // one. Scanning by role-name + matching against ALL binding UUIDs
        // (live + parked, across all bound players in this world) catches
        // every variant.
        if (!enteringTemple && anyChanged) {
            sweepOrphanScrubysInWorld(store, newWorldName);
        }

        if (!anyChanged) return;

        binding.setProfiles(profiles);
        ScrubyCompanionPlugin.savePlayerAsync(store, ownerRef, ownerUuid);
    }

    /**
     * Removes every Scruby_*-role NPC entity in the given world's store whose
     * UUID is not referenced by any binding's profile (live UUID or parked
     * BeforeOverride UUID), across all bindings currently in this world.
     *
     * This is the authoritative orphan-removal path: it doesn't depend on
     * which intermediate profile UUID Hytale happened to migrate, and it
     * ignores entity history — anything not anchored to a binding right now
     * is by definition a runaway and should be removed.
     *
     * Other players' active companions are protected because their binding's
     * profile UUIDs go into the same valid-set.
     */
    private void sweepOrphanScrubysInWorld(
            @Nonnull Store<EntityStore> store,
            @Nonnull String worldName
    ) {
        // Phase 1: build the union of all binding-anchored UUIDs in this world.
        Set<UUID> validUuids = new HashSet<>();
        store.forEachChunk(bindingComponentType, (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                ScrubyOwnerBindingComponent b = chunk.getComponent(i, bindingComponentType);
                if (b == null || !b.hasBinding()) continue;
                for (CompanionProfile p : b.getProfiles()) {
                    addUuidIfParseable(validUuids, p.getCompanionEntityUuid());
                    addUuidIfParseable(validUuids, p.getCompanionEntityUuidBeforeTempleOverride());
                }
            }
        });

        // Phase 2: collect Scruby_*-role NPC refs whose UUID is NOT in validUuids.
        List<Ref<EntityStore>> orphans = new ArrayList<>();
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid()) continue;
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null) continue;
                String roleName = npc.getRoleName();
                if (roleName == null || !roleName.startsWith("Scruby_")) continue;
                UUIDComponent uc = chunk.getComponent(i, UUIDComponent.getComponentType());
                if (uc == null) continue;
                if (validUuids.contains(uc.getUuid())) continue;
                orphans.add(ref);
            }
        });

        // Phase 3: remove. Each remove is best-effort; keep counting on either
        // a thrown exception or a `false` return so the summary log is honest.
        int removed = 0;
        for (Ref<EntityStore> ref : orphans) {
            if (!ref.isValid()) continue;
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null) continue;
            try {
                if (npc.remove()) {
                    removed++;
                } else {
                    LOGGER.atWarning().log("[Scruby] Temple LEAVE sweep: remove() returned false for refIdx="
                            + ref.getIndex());
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("[Scruby] Temple LEAVE sweep: remove failed for refIdx="
                        + ref.getIndex() + ": " + e.getMessage());
            }
        }

        if (!orphans.isEmpty()) {
            LOGGER.atInfo().log("[Scruby] Temple LEAVE sweep: removed " + removed + "/" + orphans.size()
                    + " orphan Scruby entities in world='" + worldName + "'");
        }
    }

    private static void addUuidIfParseable(@Nonnull Set<UUID> target, String raw) {
        if (raw == null || raw.isEmpty()) return;
        try {
            target.add(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {}
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
        // onPlayerReadyInternal sees the parked UUID immediately. Orphan
        // cleanup of any other migrated/abandoned entities in this world is
        // handled by sweepOrphanScrubysInWorld(), called once after this
        // per-profile loop completes.
        if (profile.hasTempleEntityOverride()) {
            String parkedUuid = profile.getCompanionEntityUuidBeforeTempleOverride();

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
