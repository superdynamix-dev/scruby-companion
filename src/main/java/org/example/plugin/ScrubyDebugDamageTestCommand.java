package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Debug command to test if addStatValue with negative values works for dealing damage.
 * Tests on the player's own companion entity.
 *
 * Usage: /scruby-debug-damagetest
 * - Reads companion HP before and after applying -10 HP via addStatValue
 * - Reports results to chat
 */
public final class ScrubyDebugDamageTestCommand extends AbstractPlayerCommand {

    private final ScrubyActiveCompanionRegistry registry;

    public ScrubyDebugDamageTestCommand(
            @Nonnull ScrubyActiveCompanionRegistry registry
    ) {
        super("scruby-debug-damagetest", "Debug: Test negative addStatValue for damage.");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    protected void execute(
            @Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        UUID ownerUuid = playerRef.getUuid();
        Ref<EntityStore> companionRef = registry.getCompanionRef(ownerUuid);

        if (companionRef == null || !companionRef.isValid()) {
            commandContext.sendMessage(Message.raw("[DmgTest] Kein aktiver Companion. Spawne zuerst einen."));
            return;
        }

        EntityStatMap statMap = store.getComponent(companionRef, EntityStatMap.getComponentType());
        if (statMap == null) {
            commandContext.sendMessage(Message.raw("[DmgTest] FAIL: Companion hat keine EntityStatMap."));
            return;
        }

        int healthIndex = DefaultEntityStatTypes.getHealth();
        EntityStatValue healthStat = statMap.get(healthIndex);
        if (healthStat == null) {
            commandContext.sendMessage(Message.raw("[DmgTest] FAIL: Health stat nicht gefunden."));
            return;
        }

        commandContext.sendMessage(Message.raw("[DmgTest] === ENEMY DAMAGE TEST ==="));

        // Find nearest non-companion NPC to player
        Vector3d playerPos = playerRef.getTransform().getPosition();
        Ref<EntityStore> myCompanion = registry.getCompanionRef(ownerUuid);

        List<EnemyCandidate> candidates = new ArrayList<>();
        store.forEachChunk(NPCEntity.getComponentType(), (chunk, cmdBuf) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef == null || !npcRef.isValid()) continue;
                // Skip own companion
                if (myCompanion != null && npcRef.equals(myCompanion)) continue;

                TransformComponent t = chunk.getComponent(i, TransformComponent.getComponentType());
                if (t == null) continue;
                Vector3d npcPos = t.getPosition();
                double dx = playerPos.getX() - npcPos.getX();
                double dy = playerPos.getY() - npcPos.getY();
                double dz = playerPos.getZ() - npcPos.getZ();
                double distSq = dx*dx + dy*dy + dz*dz;
                if (distSq <= 20.0 * 20.0) {
                    candidates.add(new EnemyCandidate(npcRef, distSq));
                }
            }
        });

        commandContext.sendMessage(Message.raw("[DmgTest] NPCs in 20 Bloecken gefunden: " + candidates.size()));

        if (candidates.isEmpty()) {
            commandContext.sendMessage(Message.raw("[DmgTest] Keine feindlichen NPCs in Reichweite!"));
            commandContext.sendMessage(Message.raw("[DmgTest] === TEST ENDE ==="));
            return;
        }

        // Find closest
        EnemyCandidate closest = candidates.get(0);
        for (EnemyCandidate c : candidates) {
            if (c.distSq < closest.distSq) closest = c;
        }

        commandContext.sendMessage(Message.raw("[DmgTest] Naechster NPC: Dist=" + String.format("%.1f", Math.sqrt(closest.distSq)) + " Bloecke"));

        // Get enemy stat map
        EntityStatMap enemyStats = store.getComponent(closest.ref, EntityStatMap.getComponentType());
        if (enemyStats == null) {
            commandContext.sendMessage(Message.raw("[DmgTest] FAIL: Feind hat KEINE EntityStatMap!"));
            commandContext.sendMessage(Message.raw("[DmgTest] === TEST ENDE ==="));
            return;
        }

        EntityStatValue enemyHp = enemyStats.get(healthIndex);
        if (enemyHp == null) {
            commandContext.sendMessage(Message.raw("[DmgTest] FAIL: Feind hat keinen Health-Stat!"));
            commandContext.sendMessage(Message.raw("[DmgTest] === TEST ENDE ==="));
            return;
        }

        float enemyHpBefore = enemyHp.get();
        float enemyMaxHp = enemyHp.getMax();
        commandContext.sendMessage(Message.raw("[DmgTest] Feind HP vorher: " + enemyHpBefore + " / " + enemyMaxHp));

        // Apply -10 damage
        enemyStats.addStatValue(healthIndex, -10.0f);

        float enemyHpAfter = enemyStats.get(healthIndex).get();
        commandContext.sendMessage(Message.raw("[DmgTest] addStatValue(health, -10): HP jetzt: " + enemyHpAfter));

        float diff = enemyHpBefore - enemyHpAfter;
        if (diff > 0) {
            commandContext.sendMessage(Message.raw("[DmgTest] ERFOLG! Feind nahm " + diff + " Schaden!"));
        } else {
            commandContext.sendMessage(Message.raw("[DmgTest] FEHLSCHLAG: Feind HP unveraendert!"));
        }

        commandContext.sendMessage(Message.raw("[DmgTest] === TEST ENDE ==="));
    }

    private static final class EnemyCandidate {
        final Ref<EntityStore> ref;
        final double distSq;
        EnemyCandidate(Ref<EntityStore> ref, double distSq) {
            this.ref = ref;
            this.distSq = distSq;
        }
    }
}
