package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Debug command to test visual effects (particles and entity effects) on enemy NPCs.
 *
 * Usage: /scruby-debug-vfxtest
 *
 * Runs three tests:
 * 1. Spawn "Effect_Fire" particle at enemy position
 * 2. Apply "Burn" EntityEffect to enemy via EffectControllerComponent
 * 3. Spawn "Explosion_Small" particle at player position (simplest test)
 *
 * Each test is independently try-caught and reports success or the exact error message.
 */
public final class ScrubyDebugVfxTestCommand extends AbstractPlayerCommand {

    private final ScrubyActiveCompanionRegistry registry;

    public ScrubyDebugVfxTestCommand(
            @Nonnull ScrubyActiveCompanionRegistry registry
    ) {
        super("scruby-debug-vfxtest", "Debug: Test VFX particles and entity effects on enemy NPCs.");
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
        Ref<EntityStore> myCompanion = registry.getCompanionRef(ownerUuid);

        commandContext.sendMessage(Message.raw("[VfxTest] === VFX DEBUG TEST ==="));

        // --- Find nearest enemy NPC (same pattern as DamageTestCommand) ---
        Vector3d playerPos = playerRef.getTransform().getPosition();

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
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq <= 20.0 * 20.0) {
                    candidates.add(new EnemyCandidate(npcRef, npcPos, distSq));
                }
            }
        });

        commandContext.sendMessage(Message.raw("[VfxTest] NPCs in 20 Bloecken: " + candidates.size()));

        if (candidates.isEmpty()) {
            commandContext.sendMessage(Message.raw("[VfxTest] Keine feindlichen NPCs in Reichweite!"));
            // Still run Test 3 (player-position particle) even without enemies
            runTest3_PlayerParticle(commandContext, store, playerPos);
            commandContext.sendMessage(Message.raw("[VfxTest] === TEST ENDE ==="));
            return;
        }

        // Find closest
        EnemyCandidate closest = candidates.get(0);
        for (EnemyCandidate c : candidates) {
            if (c.distSq < closest.distSq) closest = c;
        }

        commandContext.sendMessage(Message.raw("[VfxTest] Naechster NPC: Dist=" + String.format("%.1f", Math.sqrt(closest.distSq)) + " Bloecke"));

        // --- Test 1: Spawn "Effect_Fire" particle at enemy position ---
        runTest1_EnemyParticle(commandContext, store, closest);

        // --- Test 2: Apply "Burn" EntityEffect to enemy ---
        runTest2_BurnEffect(commandContext, store, closest);

        // --- Test 3: Spawn "Explosion_Small" particle at player position ---
        runTest3_PlayerParticle(commandContext, store, playerPos);

        commandContext.sendMessage(Message.raw("[VfxTest] === TEST ENDE ==="));
    }

    /**
     * Test 1: Spawn "Effect_Fire" particle at enemy position using ParticleUtil.
     */
    private void runTest1_EnemyParticle(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull EnemyCandidate enemy
    ) {
        ctx.sendMessage(Message.raw("[VfxTest] --- Test 1: Effect_Fire Partikel an Feind-Position ---"));
        try {
            ParticleUtil.spawnParticleEffect("Effect_Fire", enemy.position, store);
            ctx.sendMessage(Message.raw("[VfxTest] Test 1 ERFOLG: spawnParticleEffect(\"Effect_Fire\") aufgerufen."));
        } catch (Exception e) {
            ctx.sendMessage(Message.raw("[VfxTest] Test 1 FEHLER: " + e.getClass().getSimpleName() + " — " + e.getMessage()));
        }
    }

    /**
     * Test 2: Apply "Burn" EntityEffect to enemy via EffectControllerComponent.
     */
    private void runTest2_BurnEffect(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull EnemyCandidate enemy
    ) {
        ctx.sendMessage(Message.raw("[VfxTest] --- Test 2: Burn EntityEffect auf Feind ---"));

        // Step 2a: Get EffectControllerComponent from enemy
        EffectControllerComponent effectCtrl;
        try {
            effectCtrl = store.getComponent(enemy.ref, EffectControllerComponent.getComponentType());
        } catch (Exception e) {
            ctx.sendMessage(Message.raw("[VfxTest] Test 2 FEHLER (getComponent): " + e.getClass().getSimpleName() + " — " + e.getMessage()));
            return;
        }

        if (effectCtrl == null) {
            ctx.sendMessage(Message.raw("[VfxTest] Test 2 FAIL: Feind hat KEIN EffectControllerComponent!"));
            return;
        }
        ctx.sendMessage(Message.raw("[VfxTest] Test 2a OK: EffectControllerComponent gefunden."));

        // Step 2b: Resolve "Burn" EntityEffect from asset map
        EntityEffect burnEffect;
        try {
            int burnIndex = EntityEffect.getAssetMap().getIndex("Burn");
            ctx.sendMessage(Message.raw("[VfxTest] Test 2b: Burn asset index = " + burnIndex));
            burnEffect = EntityEffect.getAssetMap().getAsset(burnIndex);
        } catch (Exception e) {
            ctx.sendMessage(Message.raw("[VfxTest] Test 2b FEHLER (asset resolve): " + e.getClass().getSimpleName() + " — " + e.getMessage()));
            return;
        }

        if (burnEffect == null) {
            ctx.sendMessage(Message.raw("[VfxTest] Test 2b FAIL: Burn EntityEffect nicht im AssetMap!"));
            return;
        }
        ctx.sendMessage(Message.raw("[VfxTest] Test 2b OK: Burn EntityEffect geladen (id=" + burnEffect.getId() + ", duration=" + burnEffect.getDuration() + "s)."));

        // Step 2c: Apply effect to enemy
        try {
            boolean applied = effectCtrl.addEffect(
                    enemy.ref,
                    burnEffect,
                    5.0f,
                    OverlapBehavior.OVERWRITE,
                    store
            );
            ctx.sendMessage(Message.raw("[VfxTest] Test 2c: addEffect returned " + applied));
            if (applied) {
                ctx.sendMessage(Message.raw("[VfxTest] Test 2 ERFOLG: Burn auf Feind angewendet!"));
            } else {
                ctx.sendMessage(Message.raw("[VfxTest] Test 2 HINWEIS: addEffect returned false (evtl. Invulnerable oder Bedingung nicht erfuellt)."));
            }
        } catch (Exception e) {
            ctx.sendMessage(Message.raw("[VfxTest] Test 2c FEHLER (addEffect): " + e.getClass().getSimpleName() + " — " + e.getMessage()));
        }
    }

    /**
     * Test 3: Spawn "Explosion_Small" particle at player position (simplest test).
     */
    private void runTest3_PlayerParticle(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Vector3d playerPos
    ) {
        ctx.sendMessage(Message.raw("[VfxTest] --- Test 3: Explosion_Small Partikel an Spieler-Position ---"));
        try {
            ParticleUtil.spawnParticleEffect("Explosion_Small", playerPos, store);
            ctx.sendMessage(Message.raw("[VfxTest] Test 3 ERFOLG: spawnParticleEffect(\"Explosion_Small\") aufgerufen."));
        } catch (Exception e) {
            ctx.sendMessage(Message.raw("[VfxTest] Test 3 FEHLER: " + e.getClass().getSimpleName() + " — " + e.getMessage()));
        }
    }

    private static final class EnemyCandidate {
        final Ref<EntityStore> ref;
        final Vector3d position;
        final double distSq;

        EnemyCandidate(Ref<EntityStore> ref, Vector3d position, double distSq) {
            this.ref = ref;
            this.position = position;
            this.distSq = distSq;
        }
    }
}
