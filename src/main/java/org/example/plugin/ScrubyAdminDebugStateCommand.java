// Datei: ScrubyAdminDebugStateCommand.java
package org.example.plugin;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Diagnostic dump for the Scruby companion world-state. OP-only.
 *
 * Lists every Scruby_*-role NPC in the player's current world together
 * with each one's binding-anchor status (matched to a profile UUID, parked
 * via temple-override, or orphan). Also dumps the active registry entries
 * and every binding-bearing player's profile state.
 *
 * Output goes to BOTH the admin's chat and the server log so the data is
 * grep-able after the fact.
 *
 * Intended use: snapshot before / inside / after a Forgotten Temple
 * round-trip, then compare to identify exactly where orphans come from.
 */
public final class ScrubyAdminDebugStateCommand extends AbstractPlayerCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ComponentType<EntityStore, ScrubyOwnerBindingComponent> bindingComponentType;
    private final ScrubyActiveCompanionRegistry companionRegistry;

    public ScrubyAdminDebugStateCommand(
            @Nonnull ComponentType<EntityStore, ScrubyOwnerBindingComponent> bindingComponentType,
            @Nonnull ScrubyActiveCompanionRegistry companionRegistry
    ) {
        super("scruby-admin-debug-state",
                "Admin: Dump Scruby companion state in current world (entities + bindings + registry).");
        this.bindingComponentType = Objects.requireNonNull(bindingComponentType);
        this.companionRegistry = Objects.requireNonNull(companionRegistry);
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
        String worldUuidStr = playerRef.getWorldUuid() != null
                ? playerRef.getWorldUuid().toString() : "?";

        world.execute(() -> {
            try {
                runDump(store, ctx, adminUuid, worldName, worldUuidStr);
            } catch (Exception e) {
                LOGGER.atSevere().log("[Scruby-Debug] Aborted: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                ctx.sendMessage(Message.raw("[Scruby-Admin] Debug dump failed: " + e.getMessage()));
            }
        });
    }

    private void runDump(
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandContext ctx,
            @Nonnull UUID adminUuid,
            @Nonnull String worldName,
            @Nonnull String worldUuidStr
    ) {
        emit(ctx, "==== Scruby Debug State ====");
        emit(ctx, "world='" + worldName + "' uuid=" + worldUuidStr);
        emit(ctx, "admin=" + adminUuid);

        // (1) Registry snapshot
        List<Map.Entry<UUID, Ref<EntityStore>>> entries = companionRegistry.entriesSnapshot();
        emit(ctx, "Registry: " + entries.size() + " active companion entries (across all worlds):");
        for (Map.Entry<UUID, Ref<EntityStore>> e : entries) {
            Ref<EntityStore> r = e.getValue();
            String storeMatch = (r != null && r.getStore() == store) ? "match" : "cross-store";
            String validity = (r != null && r.isValid()) ? "valid" : "INVALID";
            int idx = r != null ? r.getIndex() : -1;
            emit(ctx, "  owner=" + e.getKey()
                    + " refIdx=" + idx + " store=" + storeMatch + " " + validity);
        }

        // (2) Collect bindings + build entity-uuid → match map across all bindings in world
        List<BindingDump> bindings = new ArrayList<>();
        Map<UUID, String> uuidToMatch = new HashMap<>();
        store.forEachChunk(bindingComponentType, (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> ownerRef = chunk.getReferenceTo(i);
                if (ownerRef == null || !ownerRef.isValid()) continue;
                ScrubyOwnerBindingComponent binding = chunk.getComponent(i, bindingComponentType);
                if (binding == null || !binding.hasBinding()) continue;
                String ownerUuidStr = binding.getOwnerPlayerUuid();
                bindings.add(new BindingDump(ownerUuidStr, binding));

                for (CompanionProfile p : binding.getProfiles()) {
                    String live = p.getCompanionEntityUuid();
                    if (live != null && !live.isEmpty()) {
                        try {
                            uuidToMatch.put(UUID.fromString(live),
                                    "owner=" + ownerUuidStr + " slot=" + p.getSlotId() + " (live)");
                        } catch (Exception ignored) {}
                    }
                    String parked = p.getCompanionEntityUuidBeforeTempleOverride();
                    if (parked != null && !parked.isEmpty()) {
                        try {
                            uuidToMatch.put(UUID.fromString(parked),
                                    "owner=" + ownerUuidStr + " slot=" + p.getSlotId() + " (parked)");
                        } catch (Exception ignored) {}
                    }
                }
            }
        });

        // (3) Bindings dump
        emit(ctx, "Bindings in this world: " + bindings.size() + " player(s)");
        for (BindingDump bd : bindings) {
            ScrubyOwnerBindingComponent b = bd.binding;
            emit(ctx, "  owner=" + bd.ownerUuidStr + " activeSlot=" + b.getActiveSlot()
                    + " profiles=" + b.getProfiles().size());
            for (CompanionProfile p : b.getProfiles()) {
                String activeMarker = (p.getSlotId() == b.getActiveSlot()) ? " [ACTIVE]" : "";
                emit(ctx, "    Slot " + p.getSlotId() + activeMarker
                        + " stationed=" + p.isStationedAtBase()
                        + " mode=" + p.getStationMode()
                        + " uuid=" + safeShort(p.getCompanionEntityUuid())
                        + " missing=" + p.isEntityMissing()
                        + " manualDespawn=" + p.isManuallyDespawned()
                        + " combatMode=" + p.getCombatMode());
                String beforeUuid = p.getCompanionEntityUuidBeforeTempleOverride();
                String beforeMode = p.getCombatModeBeforeTempleOverride();
                if ((beforeUuid != null && !beforeUuid.isEmpty())
                        || (beforeMode != null && !beforeMode.isEmpty())) {
                    emit(ctx, "          BeforeOverride: entity=" + safeShort(beforeUuid)
                            + " world=" + p.getCompanionWorldUuidBeforeTempleOverride()
                            + " combatMode=" + (beforeMode != null ? beforeMode : ""));
                }
                if (p.isStationedAtBase()) {
                    emit(ctx, "          Station: world='" + p.getStationWorldId() + "' pos=("
                            + (int) p.getStationX() + "," + (int) p.getStationY() + ","
                            + (int) p.getStationZ() + ")");
                }
            }
        }

        // (4) All Scruby_*-role entities in this world
        List<EntityDump> dumps = new ArrayList<>();
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cmdBuf) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> r = chunk.getReferenceTo(i);
                if (r == null || !r.isValid()) continue;
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null) continue;
                String roleName = npc.getRoleName();
                if (roleName == null || !roleName.startsWith("Scruby_")) continue;

                String entityUuid = "?";
                try {
                    UUIDComponent uc = chunk.getComponent(i, UUIDComponent.getComponentType());
                    if (uc != null) entityUuid = uc.getUuid().toString();
                } catch (Exception ignored) {}

                String pos = "?";
                try {
                    TransformComponent tc = chunk.getComponent(i, TransformComponent.getComponentType());
                    if (tc != null) {
                        Vector3d p = tc.getPosition();
                        pos = "(" + (int) p.getX() + "," + (int) p.getY() + "," + (int) p.getZ() + ")";
                    }
                } catch (Exception ignored) {}

                dumps.add(new EntityDump(r.getIndex(), entityUuid, roleName, pos));
            }
        });

        int orphanCount = 0;
        emit(ctx, "Scruby_*-role entities in this world: " + dumps.size() + " total");
        for (EntityDump d : dumps) {
            String matchInfo;
            try {
                UUID u = UUID.fromString(d.uuid);
                String match = uuidToMatch.get(u);
                matchInfo = match != null ? "MATCH " + match : "ORPHAN (no binding ref)";
                if (match == null) orphanCount++;
            } catch (Exception ex) {
                matchInfo = "ORPHAN (uuid not parseable)";
                orphanCount++;
            }
            emit(ctx, "  refIdx=" + d.refIdx
                    + " uuid=" + safeShort(d.uuid)
                    + " role=" + d.roleName
                    + " pos=" + d.pos
                    + " → " + matchInfo);
        }

        emit(ctx, "==== Done. " + orphanCount + " orphan(s) found ====");
    }

    /** Send to chat AND server log under the same prefix. */
    private static void emit(@Nonnull CommandContext ctx, @Nonnull String line) {
        LOGGER.atInfo().log("[Scruby-Debug] " + line);
        ctx.sendMessage(Message.raw("[Scruby-Debug] " + line));
    }

    /** Shorten UUIDs to first 8 chars + "…" for readable chat output (server log keeps full). */
    private static String safeShort(String uuid) {
        if (uuid == null || uuid.isEmpty()) return "(empty)";
        if (uuid.length() <= 12) return uuid;
        return uuid.substring(0, 8) + "…" + uuid.substring(uuid.length() - 4);
    }

    private static final class BindingDump {
        final String ownerUuidStr;
        final ScrubyOwnerBindingComponent binding;
        BindingDump(String ownerUuidStr, ScrubyOwnerBindingComponent binding) {
            this.ownerUuidStr = ownerUuidStr;
            this.binding = binding;
        }
    }

    private static final class EntityDump {
        final int refIdx;
        final String uuid;
        final String roleName;
        final String pos;
        EntityDump(int refIdx, String uuid, String roleName, String pos) {
            this.refIdx = refIdx;
            this.uuid = uuid;
            this.roleName = roleName;
            this.pos = pos;
        }
    }
}
