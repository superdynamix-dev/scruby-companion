package org.example.plugin;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Multiplies outgoing damage from Scruby companions by their owner's Strength
 * attribute (+6% per point). Hooked into FilterDamageGroup so it runs after
 * damage is gathered but before it is applied to health — the same stage where
 * Hytale's own armor reduction runs.
 *
 * Only real damage events flow through this pipeline; skill-burst stat
 * mutations in ScrubySkillTickingSystem intentionally bypass it so bursts
 * keep their fixed values and the three attributes (Vitality / Zeal / Strength)
 * remain distinct build paths.
 */
public final class ScrubyDamageBuffSystem extends DamageEventSystem {

    private static final float DMG_PER_STRENGTH_POINT = 0.06f;

    private final ScrubyActiveCompanionRegistry registry;

    public ScrubyDamageBuffSystem(@Nonnull ScrubyActiveCompanionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
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

        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (attackerRef == null || !attackerRef.isValid()) return;
        if (!registry.isCompanion(attackerRef)) return;

        int attackerIdx = attackerRef.getIndex();
        for (Map.Entry<UUID, Ref<EntityStore>> entry : registry.entriesSnapshot()) {
            Ref<EntityStore> companionRef = entry.getValue();
            if (companionRef != null && companionRef.getIndex() == attackerIdx) {
                UUID ownerUuid = entry.getKey();
                int strength = registry.getStrength(ownerUuid);
                float rawAmount = damage.getAmount();
                if (strength > 0) {
                    float mult = 1.0f + strength * DMG_PER_STRENGTH_POINT;
                    damage.setAmount(rawAmount * mult);
                }
                if (ScrubySkillTickingSystem.isAttrDebugActive(ownerUuid)) {
                    ScrubySkillTickingSystem.queueAttrDebug(ownerUuid, String.format(
                            "[Scruby-ATTR] NPC-Hit — raw=%.2f boosted=%.2f (str=%d, +%d%%, cause=%s)",
                            rawAmount, damage.getAmount(), strength,
                            Math.round(strength * DMG_PER_STRENGTH_POINT * 100),
                            damage.getCause() == null ? "?" : damage.getCause().getId()));
                }
                return;
            }
        }
    }
}
