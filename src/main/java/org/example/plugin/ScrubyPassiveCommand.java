package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.UUID;

/**
 * Player command {@code /scruby-passive} — switches the companion to passive combat mode.
 * Despawns and respawns with the normal NPC role so Scruby only attacks player targets.
 */
public final class ScrubyPassiveCommand extends AbstractPlayerCommand {

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry companionRegistry;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyCompanionSpawnService spawnService;

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    public ScrubyPassiveCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry companionRegistry,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyCompanionSpawnService spawnService
    ) {
        super("scruby-defensive", "Switch companion to defensive mode.");
        this.bindingService = Objects.requireNonNull(bindingService);
        this.companionRegistry = Objects.requireNonNull(companionRegistry);
        this.resetService = Objects.requireNonNull(resetService);
        this.spawnService = Objects.requireNonNull(spawnService);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ref);
        if (binding == null) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.combat.no_binding")));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        if (profile == null) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.combat.no_binding")));
            return;
        }

        String locale = profile.getLocale();

        if (!profile.isAggressive()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.combat.already_defensive")));
            return;
        }

        profile.setCombatMode("PASSIVE");
        binding.setActiveProfile(profile);

        UUID ownerUuid = playerRef.getUuid();

        // Despawn existing companion
        Ref<EntityStore> existing = companionRegistry.getCompanionRef(ownerUuid);
        if (existing != null && existing.isValid()) {
            resetService.clearLockedTarget(store, existing);
            NPCEntity npcEntity = store.getComponent(existing, NPCEntity.getComponentType());
            if (npcEntity != null) {
                npcEntity.remove();
            }
            companionRegistry.unregister(ownerUuid);
        }
        resetService.resetOwnerSession(ownerUuid);
        profile.setCompanionEntityUuid("");
        profile.setManuallyDespawned(false);
        profile.setEntityMissing(false);
        binding.setActiveProfile(profile);

        // Respawn with normal role
        spawnService.spawnCompanion(store, ref, playerRef, ownerUuid);

        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.combat.defensive")));
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, ownerUuid);
    }
}
