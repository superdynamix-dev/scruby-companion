// Datei: ScrubyAdminCleanupOrphansCommand.java
package org.example.plugin;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ScrubyAdminCleanupOrphansCommand extends AbstractPlayerCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ComponentType<EntityStore, ScrubyOwnerBindingComponent> bindingComponentType;
    private final ScrubyActiveCompanionRegistry companionRegistry;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyCompanionSpawnService spawnService;
    private final ScrubyEvolutionService evolutionService;

    public ScrubyAdminCleanupOrphansCommand(
            @Nonnull ComponentType<EntityStore, ScrubyOwnerBindingComponent> bindingComponentType,
            @Nonnull ScrubyActiveCompanionRegistry companionRegistry,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyCompanionSpawnService spawnService,
            @Nonnull ScrubyEvolutionService evolutionService
    ) {
        super("scruby-admin-cleanup-orphans",
                "Admin: Despawn all Scruby NPCs in current world and rebuild from bindings.");
        this.bindingComponentType = Objects.requireNonNull(bindingComponentType);
        this.companionRegistry = Objects.requireNonNull(companionRegistry);
        this.resetService = Objects.requireNonNull(resetService);
        this.spawnService = Objects.requireNonNull(spawnService);
        this.evolutionService = Objects.requireNonNull(evolutionService);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        if (!PermissionsModule.get().getGroupsForUser(playerRef.getUuid()).contains("OP")) {
            ctx.sendMessage(Message.raw("[Scruby-Admin] No permission."));
            return;
        }

        UUID adminUuid = playerRef.getUuid();
        String worldName = world.getName() != null ? world.getName() : "?";

        world.execute(() -> {
            try {
                runCleanup(store, world, worldName, adminUuid, ctx);
            } catch (Exception e) {
                LOGGER.atSevere().log("[Scruby-Cleanup] Aborted: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ctx.sendMessage(Message.raw("[Scruby-Admin] Cleanup failed: " + e.getMessage()));
            }
        });
    }

    private void runCleanup(
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull String worldName,
            @Nonnull UUID adminUuid,
            @Nonnull CommandContext ctx
    ) {
        LOGGER.atInfo().log("[Scruby-Cleanup] Started in world=" + worldName + " by admin=" + adminUuid);

        // Phase 1: collect all Scruby_*-role NPC refs (collect-then-remove avoids
        // mutating the chunk during iteration).
        List<ScrubyHit> hits = new ArrayList<>();
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid()) continue;
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null) continue;
                String roleName = npc.getRoleName();
                if (roleName == null || !roleName.startsWith("Scruby_")) continue;

                String entityUuid = "?";
                try {
                    UUIDComponent uuidComp = chunk.getComponent(i, UUIDComponent.getComponentType());
                    if (uuidComp != null) entityUuid = uuidComp.getUuid().toString();
                } catch (Exception ignored) {}

                hits.add(new ScrubyHit(ref, roleName, entityUuid));
            }
        });

        // Phase 2: remove each. Each remove is best-effort; failures logged, count tracked.
        int removedCount = 0;
        for (ScrubyHit hit : hits) {
            if (!hit.ref.isValid()) continue;
            NPCEntity npc = store.getComponent(hit.ref, NPCEntity.getComponentType());
            if (npc == null) continue;
            try {
                if (npc.remove()) {
                    removedCount++;
                    LOGGER.atInfo().log("[Scruby-Cleanup] Removed entity uuid=" + hit.entityUuid
                            + " role=" + hit.roleName);
                } else {
                    LOGGER.atWarning().log("[Scruby-Cleanup] remove() returned false for uuid="
                            + hit.entityUuid + " role=" + hit.roleName);
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("[Scruby-Cleanup] Failed to remove entity uuid="
                        + hit.entityUuid + ": " + e.getMessage());
            }
        }

        // Phase 3: collect binding-bearing player entities in this world.
        List<BindingTuple> bindings = new ArrayList<>();
        store.forEachChunk(bindingComponentType, (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ownerRef = chunk.getReferenceTo(i);
                if (ownerRef == null || !ownerRef.isValid()) continue;
                ScrubyOwnerBindingComponent binding = chunk.getComponent(i, bindingComponentType);
                if (binding == null || !binding.hasBinding()) continue;
                PlayerRef playerRefComponent = store.getComponent(ownerRef, PlayerRef.getComponentType());
                if (playerRefComponent == null) continue;
                bindings.add(new BindingTuple(ownerRef, playerRefComponent, binding));
            }
        });

        // Phase 4: rebuild from each binding (active follow + matching stationed).
        int refreshedActiveCount = 0;
        int refreshedStationedCount = 0;

        for (BindingTuple tuple : bindings) {
            UUID ownerUuid = tuple.playerRef.getUuid();

            // Clean-slate registry: anything pre-cleanup pointed at an entity
            // we just removed in phase 2.
            companionRegistry.unregister(ownerUuid);
            resetService.resetOwnerSession(ownerUuid);

            CompanionProfile activeProfile = tuple.binding.getActiveProfile();
            boolean isActiveFollow = !activeProfile.isStationedAtBase();

            if (isActiveFollow && !activeProfile.isManuallyDespawned()) {
                UUID newUuid = spawnService.spawnCompanion(
                        store, tuple.ownerRef, tuple.playerRef, ownerUuid, false);
                if (newUuid != null) {
                    refreshedActiveCount++;
                    LOGGER.atInfo().log("[Scruby-Cleanup] Rebuilt active follow for player="
                            + ownerUuid + " entity=" + newUuid);
                } else {
                    LOGGER.atWarning().log("[Scruby-Cleanup] Active follow respawn returned null for player="
                            + ownerUuid);
                }
            }

            // Stationed profiles: respawn each one whose stationWorldId matches
            // this world (or is empty, for legacy profiles). Stationed in other
            // worlds are out of scope — admin can re-run there. Note that
            // stationWorldId stores a world NAME (set by ScrubyBaseStationService
            // from world.getName()), not a UUID — compare against worldName.
            for (CompanionProfile profile : tuple.binding.getProfiles()) {
                if (!profile.isStationedAtBase()) continue;
                if (ScrubyBaseStationService.MODE_NONE.equals(profile.getStationMode())) continue;
                if (profile.isManuallyDespawned()) continue;

                String stationWorldName = profile.getStationWorldId();
                if (!stationWorldName.isEmpty()
                        && !stationWorldName.equals(worldName)) {
                    continue;
                }

                if (spawnStationedAt(store, profile, tuple.binding, ownerUuid)) {
                    refreshedStationedCount++;
                }
            }

            ScrubyCompanionPlugin.savePlayerAsync(store, tuple.ownerRef, ownerUuid);
        }

        LOGGER.atInfo().log("[Scruby-Cleanup] Done — removed=" + removedCount
                + ", refreshed-active=" + refreshedActiveCount
                + ", refreshed-stationed=" + refreshedStationedCount
                + ", bindings-considered=" + bindings.size());
        ctx.sendMessage(Message.raw("[Scruby-Admin] Cleanup-Orphans complete in world '"
                + worldName + "':"));
        ctx.sendMessage(Message.raw("  Removed: " + removedCount + " Scruby entities"));
        ctx.sendMessage(Message.raw("  Rebuilt: " + refreshedActiveCount + " active, "
                + refreshedStationedCount + " stationed"));
        ctx.sendMessage(Message.raw("  Bindings considered: " + bindings.size()));
    }

    private boolean spawnStationedAt(
            @Nonnull Store<EntityStore> store,
            @Nonnull CompanionProfile profile,
            @Nonnull ScrubyOwnerBindingComponent binding,
            @Nonnull UUID ownerUuid
    ) {
        try {
            String role = evolutionService.getStationedRoleNameForProfile(profile);
            Vector3d pos = new Vector3d(profile.getStationX(), profile.getStationY(), profile.getStationZ());
            Pair<Ref<EntityStore>, ?> result =
                    NPCPlugin.get().spawnNPC(store, role, null, pos, new Vector3f(0f, 0f, 0f));

            if (result == null || result.left() == null) {
                LOGGER.atWarning().log("[Scruby-Cleanup] Stationed spawn returned null for slot="
                        + profile.getSlotId() + " role=" + role);
                profile.setEntityMissing(true);
                binding.updateProfileBySlot(profile);
                return false;
            }

            Ref<EntityStore> newRef = result.left();
            UUIDComponent uuidComp = store.getComponent(newRef, UUIDComponent.getComponentType());
            String newUuid = "?";
            if (uuidComp != null) {
                newUuid = uuidComp.getUuid().toString();
                profile.setCompanionEntityUuid(newUuid);
            }
            profile.setEntityMissing(false);
            ScrubyArmorService.applyVisualArmor(store, newRef, profile);
            binding.updateProfileBySlot(profile);

            if (binding.getActiveSlot() == profile.getSlotId()) {
                companionRegistry.register(ownerUuid, newRef);
            }

            LOGGER.atInfo().log("[Scruby-Cleanup] Rebuilt stationed slot=" + profile.getSlotId()
                    + " for player=" + ownerUuid + " entity=" + newUuid);
            return true;
        } catch (Exception e) {
            LOGGER.atWarning().log("[Scruby-Cleanup] Stationed spawn failed for slot="
                    + profile.getSlotId() + ": " + e.getMessage());
            return false;
        }
    }

    private static final class BindingTuple {
        final Ref<EntityStore> ownerRef;
        final PlayerRef playerRef;
        final ScrubyOwnerBindingComponent binding;
        BindingTuple(Ref<EntityStore> ownerRef, PlayerRef playerRef, ScrubyOwnerBindingComponent binding) {
            this.ownerRef = ownerRef;
            this.playerRef = playerRef;
            this.binding = binding;
        }
    }

    private static final class ScrubyHit {
        final Ref<EntityStore> ref;
        final String roleName;
        final String entityUuid;
        ScrubyHit(Ref<EntityStore> ref, String roleName, String entityUuid) {
            this.ref = ref;
            this.roleName = roleName;
            this.entityUuid = entityUuid;
        }
    }
}
