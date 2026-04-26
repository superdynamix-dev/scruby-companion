package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which entities were recently hit by a companion's attack.
 * Used by ScrubySkillTickingSystem to only apply burn/poison damage
 * to entities that have an active hit debuff (3.5 seconds after last hit).
 *
 * Populated by ScrubyDamageDetectionSystem when a companion projectile
 * or melee attack hits an entity.
 */
public final class ScrubyCompanionHitTracker {

    /** Debuff duration in milliseconds (3.5 seconds). */
    public static final long DEBUFF_DURATION_MS = 3_500L;

    /** Map of victim entity ref index → debuff expiry timestamp. */
    private final Map<Integer, Long> activeDebuffs = new ConcurrentHashMap<>();

    /** Map of victim entity ref index → set of owner UUIDs that dealt damage (expires with debuff). */
    private final Map<Integer, Map<UUID, Long>> attackersByVictim = new ConcurrentHashMap<>();

    /** Kill credit: victim ref index → set of owner UUIDs. Never expires — cleared on entity death. */
    private final Map<Integer, Set<UUID>> killCreditByVictim = new ConcurrentHashMap<>();

    /**
     * Records a hit: the victim gets a debuff timer of 3.5 seconds.
     * If already debuffed, the timer is reset.
     */
    public void recordHit(@Nonnull Ref<EntityStore> victimRef) {
        activeDebuffs.put(victimRef.getIndex(), System.currentTimeMillis() + DEBUFF_DURATION_MS);
    }

    /**
     * Records which companion owner dealt damage to a victim.
     */
    public void recordAttacker(@Nonnull Ref<EntityStore> victimRef, @Nonnull UUID ownerUuid) {
        attackersByVictim
                .computeIfAbsent(victimRef.getIndex(), k -> new ConcurrentHashMap<>())
                .put(ownerUuid, System.currentTimeMillis() + DEBUFF_DURATION_MS);
    }

    /**
     * Records kill credit for a companion owner against a victim. Does not expire.
     */
    public void recordKillCredit(@Nonnull Ref<EntityStore> victimRef, @Nonnull UUID ownerUuid) {
        killCreditByVictim
                .computeIfAbsent(victimRef.getIndex(), k -> ConcurrentHashMap.newKeySet())
                .add(ownerUuid);
    }

    /**
     * Returns the set of owner UUIDs that have kill credit on this victim, then clears the entry.
     */
    @Nonnull
    public Set<UUID> consumeKillCredits(@Nonnull Ref<EntityStore> victimRef) {
        Set<UUID> credits = killCreditByVictim.remove(victimRef.getIndex());
        return credits != null ? credits : Collections.emptySet();
    }

    /**
     * Returns the set of owner UUIDs that dealt damage to this victim (debuff-based, expires).
     */
    @Nonnull
    public Set<UUID> getAttackers(@Nonnull Ref<EntityStore> victimRef) {
        Map<UUID, Long> attackers = attackersByVictim.get(victimRef.getIndex());
        if (attackers == null) return Collections.emptySet();
        // Remove expired entries
        long now = System.currentTimeMillis();
        attackers.entrySet().removeIf(e -> now > e.getValue());
        if (attackers.isEmpty()) {
            attackersByVictim.remove(victimRef.getIndex());
            return Collections.emptySet();
        }
        return attackers.keySet();
    }

    /**
     * Returns true if the given entity has an active burn/poison debuff.
     */
    public boolean hasActiveDebuff(@Nonnull Ref<EntityStore> entityRef) {
        Long expiry = activeDebuffs.get(entityRef.getIndex());
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            activeDebuffs.remove(entityRef.getIndex());
            return false;
        }
        return true;
    }

    /**
     * Removes expired debuffs. Called periodically to prevent map growth.
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Long>> it = activeDebuffs.entrySet().iterator();
        while (it.hasNext()) {
            if (now > it.next().getValue()) it.remove();
        }
        // Cleanup attacker tracking
        Iterator<Map.Entry<Integer, Map<UUID, Long>>> ait = attackersByVictim.entrySet().iterator();
        while (ait.hasNext()) {
            Map<UUID, Long> attackers = ait.next().getValue();
            attackers.entrySet().removeIf(e -> now > e.getValue());
            if (attackers.isEmpty()) ait.remove();
        }
    }

    /**
     * Number of currently active debuffs (for debug output).
     */
    public int activeCount() {
        return activeDebuffs.size();
    }
}
