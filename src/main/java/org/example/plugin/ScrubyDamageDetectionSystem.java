package org.example.plugin;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Intercepts every Damage event in the ECS pipeline.
 * When the attacker is a registered companion (projectile or melee),
 * records the hit in ScrubyCompanionHitTracker so the victim receives
 * burn/poison damage for 3.5 seconds.
 *
 * Runs in the InspectDamage phase (after damage is calculated, before death).
 */
public final class ScrubyDamageDetectionSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyCompanionHitTracker hitTracker;

    public ScrubyDamageDetectionSystem(
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyCompanionHitTracker hitTracker
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.hitTracker = Objects.requireNonNull(hitTracker, "hitTracker");
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
    }

    @Override
    public void handle(
            int index,
            ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer,
            Damage damage
    ) {
        if (damage.isCancelled()) return;

        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EntitySource entitySource)) return;

        // Get the attacker ref (for ProjectileSource this is the shooter, not the projectile)
        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (attackerRef == null || !attackerRef.isValid()) return;

        // Get the victim ref from the chunk
        Ref<EntityStore> victimRef = chunk.getReferenceTo(index);
        if (victimRef == null || !victimRef.isValid()) return;

        // Branch A: attacker is a registered companion — full hit tracking (debuff + attacker + kill credit)
        if (registry.isCompanion(attackerRef)) {
            // DEBUG: Log Scruby-on-Scruby physical hits
            NPCEntity victimNpc = store.getComponent(victimRef, NPCEntity.getComponentType());
            String attackerRole = "unknown";
            String victimRole = "unknown";
            try {
                NPCEntity attackerNpc = store.getComponent(attackerRef, NPCEntity.getComponentType());
                if (attackerNpc != null) attackerRole = attackerNpc.getRoleName();
                if (victimNpc != null) victimRole = victimNpc.getRoleName();
            } catch (Exception ignored) {}
            if (attackerRole != null && attackerRole.contains("Scruby_") && victimRole != null && victimRole.contains("Scruby_")) {
                LOGGER.atWarning().log("[Scruby-DEBUG] SCRUBY-ON-SCRUBY PHYSICAL HIT! Attacker=" + attackerRole + " Victim=" + victimRole + " DamageType=" + damage.getSource().getClass().getSimpleName());
            }

            // Record the hit — victim gets 3.5s burn/poison debuff
            hitTracker.recordHit(victimRef);

            // Record which owner's companion dealt this damage (for kill attribution)
            for (Map.Entry<UUID, Ref<EntityStore>> entry : registry.entriesSnapshot()) {
                if (entry.getValue() != null && entry.getValue().getIndex() == attackerRef.getIndex()) {
                    hitTracker.recordAttacker(victimRef, entry.getKey());
                    hitTracker.recordKillCredit(victimRef, entry.getKey());
                    break;
                }
            }
            return;
        }

        // Branch B: attacker is a player with a live registered companion — kill credit only.
        // Intentionally no recordHit (no skill DoT debuff) and no recordAttacker (no expiring
        // attacker tracking). Player-driven kills count for XP/Loot, but the companion's skill
        // ticks remain gated on actual companion hits.
        PlayerRef attackerPlayerRef = store.getComponent(attackerRef, PlayerRef.getComponentType());
        if (attackerPlayerRef == null) return;

        UUID playerUuid = attackerPlayerRef.getUuid();
        Ref<EntityStore> companionRef = registry.getCompanionRef(playerUuid);
        if (companionRef == null || !companionRef.isValid()) return;

        hitTracker.recordKillCredit(victimRef, playerUuid);
    }
}
