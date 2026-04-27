package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes duplicate ground items near kill positions.
 *
 * The actual ECS work (forEachChunk + removeEntity on the world's item store)
 * MUST run on the world's own thread — Hytale's component store has a hard
 * thread-affinity assertion. We collect ready cleanups on our own tick thread
 * and then post a Runnable to the world via {@code World.execute(Runnable)}
 * (World implements {@link java.util.concurrent.Executor}). The world's tick
 * drains its task queue on its own thread.
 */
public final class ScrubyItemCleanupSystem extends TickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Override
    public void tick(float deltaTime, int tickCount, @Nonnull Store<EntityStore> store) {
        if (ScrubyKillDetectionSystem.pendingItemCleanups.isEmpty()) return;

        long now = System.currentTimeMillis();
        List<ScrubyKillDetectionSystem.PendingCleanup> ready = new ArrayList<>();

        ScrubyKillDetectionSystem.PendingCleanup entry;
        while ((entry = ScrubyKillDetectionSystem.pendingItemCleanups.peek()) != null) {
            long age = now - entry.timestamp;
            if (age >= ScrubyKillDetectionSystem.CLEANUP_DELAY_MS) {
                ScrubyKillDetectionSystem.pendingItemCleanups.poll();
                if (age <= ScrubyKillDetectionSystem.CLEANUP_MAX_AGE_MS) {
                    ready.add(entry);
                }
            } else {
                break;
            }
        }

        if (ready.isEmpty()) return;

        final World world = Universe.get().getDefaultWorld();
        if (world == null) return;

        // Hand the cleanup work to the world's own thread. Direct access to
        // world.getEntityStore() from this tick (which runs on a different
        // thread) trips an IllegalStateException via Hytale's thread assertion.
        final List<ScrubyKillDetectionSystem.PendingCleanup> readyOnWorld = ready;
        world.execute(() -> runCleanupOnWorldThread(world, readyOnWorld));
    }

    private static void runCleanupOnWorldThread(
            @Nonnull World world,
            @Nonnull List<ScrubyKillDetectionSystem.PendingCleanup> ready
    ) {
        try {
            Store<EntityStore> itemStore = world.getEntityStore().getStore();

            int removed = 0;
            int totalChunksVisited = 0;
            int totalItemsScanned = 0;
            int totalItemsInRange = 0;

            for (ScrubyKillDetectionSystem.PendingCleanup cleanup : ready) {
                final Vector3d killPos = cleanup.position;
                final double radiusSq = 5.0 * 5.0;

                final int[] chunksVisited = {0};
                final int[] itemsScanned  = {0};
                final int[] itemsInRange  = {0};
                final int[] itemsRemoved  = {0};

                itemStore.forEachChunk(ItemComponent.getComponentType(), (chunk, cmdBuf) -> {
                    chunksVisited[0]++;
                    int size = chunk.size();
                    itemsScanned[0] += size;
                    for (int i = 0; i < size; i++) {
                        TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                        if (transform == null) continue;

                        Vector3d itemPos = transform.getPosition();
                        double dx = itemPos.getX() - killPos.getX();
                        double dy = itemPos.getY() - killPos.getY();
                        double dz = itemPos.getZ() - killPos.getZ();
                        double distSq = dx * dx + dy * dy + dz * dz;

                        if (distSq <= radiusSq) {
                            itemsInRange[0]++;
                            Ref<EntityStore> itemRef = chunk.getReferenceTo(i);
                            if (itemRef != null && itemRef.isValid()) {
                                cmdBuf.removeEntity(itemRef, RemoveReason.REMOVE);
                                itemsRemoved[0]++;
                            }
                        }
                    }
                });

                LOGGER.atInfo().log("[Scruby-KillLoot] Cleanup pos=("
                        + killPos.getX() + "," + killPos.getY() + "," + killPos.getZ()
                        + ") chunksVisited=" + chunksVisited[0]
                        + " itemsScanned=" + itemsScanned[0]
                        + " itemsInRange=" + itemsInRange[0]
                        + " itemsRemoved=" + itemsRemoved[0]);

                totalChunksVisited += chunksVisited[0];
                totalItemsScanned  += itemsScanned[0];
                totalItemsInRange  += itemsInRange[0];
                removed            += itemsRemoved[0];
            }

            LOGGER.atInfo().log("[Scruby-KillLoot] Cleanup pass: pendingCleanups=" + ready.size()
                    + " totalChunksVisited=" + totalChunksVisited
                    + " totalItemsScanned=" + totalItemsScanned
                    + " totalItemsInRange=" + totalItemsInRange
                    + " removed=" + removed);
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-KillLoot] Cleanup failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
