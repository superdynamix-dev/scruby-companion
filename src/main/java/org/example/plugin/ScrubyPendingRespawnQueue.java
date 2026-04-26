package org.example.plugin;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ScrubyPendingRespawnQueue {

    public record RespawnEntry(UUID ownerUuid, long respawnAtMs, boolean facingPlayer) {}

    private final ConcurrentLinkedQueue<RespawnEntry> queue = new ConcurrentLinkedQueue<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public void enqueue(@Nullable UUID ownerUuid, long respawnAtMs) {
        enqueue(ownerUuid, respawnAtMs, false);
    }

    public void enqueue(@Nullable UUID ownerUuid, long respawnAtMs, boolean facingPlayer) {
        if (ownerUuid != null && pending.add(ownerUuid)) {
            queue.add(new RespawnEntry(ownerUuid, respawnAtMs, facingPlayer));
        }
    }

    @Nullable
    public RespawnEntry pollReady() {
        Iterator<RespawnEntry> it = queue.iterator();
        long now = System.currentTimeMillis();
        while (it.hasNext()) {
            RespawnEntry entry = it.next();
            if (entry.respawnAtMs() <= now) {
                it.remove();
                pending.remove(entry.ownerUuid());
                return entry;
            }
        }
        return null;
    }
}
