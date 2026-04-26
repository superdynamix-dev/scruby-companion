package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ScrubyPrestigeFightTracker {

    private final ScrubyConfigService configService;

    private final Map<UUID, FightState> activeFights = new ConcurrentHashMap<>();

    /**
     * Owners whose prestige boss has been confirmed dead by the kill-detection
     * listener but whose win has not yet been processed by the ticking system.
     * Set from any thread (death listener), drained from the tick thread.
     */
    private final Set<UUID> pendingWins = ConcurrentHashMap.newKeySet();

    public ScrubyPrestigeFightTracker(@Nonnull ScrubyConfigService configService) {
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    public void startFight(@Nonnull UUID ownerUuid, @Nonnull Ref<EntityStore> bossRef) {
        activeFights.put(ownerUuid, new FightState(bossRef, System.currentTimeMillis()));
    }

    public void endFight(@Nonnull UUID ownerUuid) {
        activeFights.remove(ownerUuid);
        pendingWins.remove(ownerUuid);
    }

    /** Returns the owner whose active fight tracks this boss ref, or null. */
    @Nullable
    public UUID findOwnerByBossRef(@Nonnull Ref<EntityStore> bossRef) {
        for (Map.Entry<UUID, FightState> entry : activeFights.entrySet()) {
            Ref<EntityStore> fightBoss = entry.getValue().getBossRef();
            if (fightBoss != null && fightBoss.equals(bossRef)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Marks an owner's fight as won (boss confirmed dead by kill-detection listener). */
    public void markPendingWin(@Nonnull UUID ownerUuid) {
        pendingWins.add(ownerUuid);
    }

    /** Returns true and clears the flag if the owner has a pending win. */
    public boolean consumePendingWin(@Nonnull UUID ownerUuid) {
        return pendingWins.remove(ownerUuid);
    }

    @Nullable
    public FightState getFight(@Nonnull UUID ownerUuid) {
        return activeFights.get(ownerUuid);
    }

    public boolean isInFight(@Nonnull UUID ownerUuid) {
        return activeFights.containsKey(ownerUuid);
    }

    @Nonnull
    public Map<UUID, FightState> getActiveFights() {
        return activeFights;
    }

    public boolean isFightTimedOut(@Nonnull UUID ownerUuid) {
        FightState fight = activeFights.get(ownerUuid);
        if (fight == null) return false;
        return System.currentTimeMillis() - fight.getStartTimeMs() > configService.getPrestigeFightTimeoutMs();
    }

    public static final class FightState {
        private final Ref<EntityStore> bossRef;
        private final long startTimeMs;

        public FightState(@Nonnull Ref<EntityStore> bossRef, long startTimeMs) {
            this.bossRef = bossRef;
            this.startTimeMs = startTimeMs;
        }

        @Nonnull
        public Ref<EntityStore> getBossRef() { return bossRef; }
        public long getStartTimeMs() { return startTimeMs; }
    }
}
