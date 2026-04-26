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
 * Uses World.getEntityStore().getStore()
 * gives access to the REAL item Store (different from the Store passed to
 * system ticks). Then forEachChunk(ItemComponent) finds items, and the
 * CommandBuffer from that callback can removeEntity with proper client sync.
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

        // Get the REAL item store via World.
        // The Store passed to tick() does NOT contain item entities.
        // World.getEntityStore().getStore() gives the store where items actually live.
        try {
            World world = Universe.get().getDefaultWorld();
            if (world == null) return;

            Store<EntityStore> itemStore = world.getEntityStore().getStore();

            int removed = 0;
            for (ScrubyKillDetectionSystem.PendingCleanup cleanup : ready) {
                final Vector3d killPos = cleanup.position;
                final double radiusSq = 5.0 * 5.0;

                // forEachChunk on the World's store with ItemComponent — this finds items!
                final int[] count = {0};
                itemStore.forEachChunk(ItemComponent.getComponentType(), (chunk, cmdBuf) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                        if (transform == null) continue;

                        Vector3d itemPos = transform.getPosition();
                        double dx = itemPos.getX() - killPos.getX();
                        double dy = itemPos.getY() - killPos.getY();
                        double dz = itemPos.getZ() - killPos.getZ();
                        double distSq = dx * dx + dy * dy + dz * dz;

                        if (distSq <= radiusSq) {
                            Ref<EntityStore> itemRef = chunk.getReferenceTo(i);
                            if (itemRef != null && itemRef.isValid()) {
                                cmdBuf.removeEntity(itemRef, RemoveReason.REMOVE);
                                count[0]++;
                            }
                        }
                    }
                });
                removed += count[0];
            }

            if (removed > 0) {
                LOGGER.atInfo().log("[Scruby-KillLoot] Cleaned up " + removed + " ground items via World store");
            }
        } catch (Exception e) {
            LOGGER.atInfo().log("[Scruby-KillLoot] Cleanup failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
