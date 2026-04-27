package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wait-and-retry resolver for rejoin companion lookups.
 *
 * On a quick rejoin, the companion's chunk is often still loading and
 * {@code entityStore.getRefFromUUID(...)} returns null. The original code
 * spawned a fresh companion right away — when the old chunk finished
 * loading, the original entity reappeared as an orphan. This system instead
 * lets the rejoin handler queue a {@link ScrubyPlayerLifecycleListener.PendingCompanionResolve}
 * and retries the lookup every tick. On success, the companion is registered
 * via the listener's helper. After a timeout we fall back to spawning a fresh
 * companion (the original behaviour).
 *
 * Same threading rules as {@link ScrubyItemCleanupSystem}: this tick does NOT
 * run on the world thread, so any ECS access is dispatched via
 * {@link World#execute(Runnable)}.
 */
public final class ScrubyCompanionResolveSystem extends TickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ScrubyPlayerLifecycleListener lifecycleListener;

    public ScrubyCompanionResolveSystem(@Nonnull ScrubyPlayerLifecycleListener lifecycleListener) {
        this.lifecycleListener = Objects.requireNonNull(lifecycleListener, "lifecycleListener");
    }

    @Override
    public void tick(float deltaTime, int tickCount, @Nonnull Store<EntityStore> store) {
        if (ScrubyPlayerLifecycleListener.pendingResolves.isEmpty()) return;

        long now = System.currentTimeMillis();
        // Snapshot the keys so we don't iterate the live map while modifying it.
        List<UUID> ownerUuids = new ArrayList<>(ScrubyPlayerLifecycleListener.pendingResolves.keySet());

        for (UUID ownerUuid : ownerUuids) {
            ScrubyPlayerLifecycleListener.PendingCompanionResolve entry =
                    ScrubyPlayerLifecycleListener.pendingResolves.get(ownerUuid);
            if (entry == null) continue;

            World world = (entry.worldUuid != null)
                    ? Universe.get().getWorld(entry.worldUuid)
                    : null;
            if (world == null) world = Universe.get().getDefaultWorld();
            if (world == null) {
                ScrubyPlayerLifecycleListener.pendingResolves.remove(ownerUuid);
                LOGGER.atInfo().log("[Scruby-Resolve] No world for retry — dropping. Owner=" + ownerUuid);
                continue;
            }

            final World worldFinal = world;
            final long age = now - entry.enqueueTimestamp;
            final boolean timedOut = age >= ScrubyPlayerLifecycleListener.RESOLVE_TIMEOUT_MS;

            world.execute(() -> processOnWorldThread(worldFinal, ownerUuid, timedOut));
        }
    }

    private void processOnWorldThread(@Nonnull World world, @Nonnull UUID ownerUuid, boolean timedOut) {
        try {
            // Re-fetch from map — could be removed by a previous tick that already succeeded.
            ScrubyPlayerLifecycleListener.PendingCompanionResolve entry =
                    ScrubyPlayerLifecycleListener.pendingResolves.get(ownerUuid);
            if (entry == null) return;

            Store<EntityStore> store = world.getEntityStore().getStore();
            EntityStore entityStore = store.getExternalData();

            Ref<EntityStore> ownerRef = entityStore.getRefFromUUID(ownerUuid);
            if (ownerRef == null || !ownerRef.isValid()) {
                // Player left during the resolve window — abandon.
                ScrubyPlayerLifecycleListener.pendingResolves.remove(ownerUuid);
                LOGGER.atInfo().log("[Scruby-Resolve] Owner gone — dropping pending. Owner=" + ownerUuid);
                return;
            }

            UUID companionUuid;
            try {
                companionUuid = UUID.fromString(entry.companionUuidString);
            } catch (IllegalArgumentException e) {
                ScrubyPlayerLifecycleListener.pendingResolves.remove(ownerUuid);
                LOGGER.atInfo().log("[Scruby-Resolve] Bad companion UUID — dropping. Owner=" + ownerUuid);
                return;
            }

            Ref<EntityStore> companionRef = entityStore.getRefFromUUID(companionUuid);
            if (companionRef != null && companionRef.isValid() && companionRef.getStore() == store) {
                // Found! Register the existing companion via the listener's helper.
                ScrubyOwnerBindingComponent binding =
                        lifecycleListener.getBindingService().getBindingOrNull(store, ownerRef);
                if (binding == null || !binding.hasBinding()) {
                    ScrubyPlayerLifecycleListener.pendingResolves.remove(ownerUuid);
                    return;
                }
                CompanionProfile profile = binding.getActiveProfile();
                lifecycleListener.registerExistingCompanion(store, ownerRef, companionRef, binding, profile);
                ScrubyPlayerLifecycleListener.pendingResolves.remove(ownerUuid);
                LOGGER.atInfo().log("[Scruby-Resolve] Reattached companion after retry. Owner="
                        + ownerUuid + " entity=" + companionUuid
                        + " ageMs=" + (System.currentTimeMillis() - entry.enqueueTimestamp));
                return;
            }

            if (!timedOut) {
                // Not found yet, but still within the window — leave entry in
                // place and we'll retry on the next system tick.
                return;
            }

            // Timeout expired — fall back to spawning a fresh companion.
            ScrubyPlayerLifecycleListener.pendingResolves.remove(ownerUuid);

            ScrubyOwnerBindingComponent binding =
                    lifecycleListener.getBindingService().getBindingOrNull(store, ownerRef);
            if (binding == null || !binding.hasBinding()) {
                LOGGER.atInfo().log("[Scruby-Resolve] Timeout but no binding — abandoning. Owner=" + ownerUuid);
                return;
            }

            PlayerRef playerRefComponent = store.getComponent(ownerRef, PlayerRef.getComponentType());
            LOGGER.atInfo().log("[Scruby-Resolve] Timeout reached — spawning fresh companion. Owner="
                    + ownerUuid + " entity=" + companionUuid + " ageMs="
                    + (System.currentTimeMillis() - entry.enqueueTimestamp));
            lifecycleListener.spawnFreshCompanion(store, ownerRef, playerRefComponent, binding, ownerUuid);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-Resolve] Process failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
