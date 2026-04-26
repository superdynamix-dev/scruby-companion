package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.UUID;

/**
 * Debug command to test Guard Mode via StateSupport.setSubState().
 * Toggles between normal following and "Guard" sub-state.
 * Purpose: Determine if setting an undefined sub-state stops FlockLeader Seek.
 */
public final class ScrubyTestGuardCommand extends AbstractPlayerCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ScrubyActiveCompanionRegistry registry;

    public ScrubyTestGuardCommand(
            @Nonnull ScrubyActiveCompanionRegistry registry
    ) {
        super("scruby-testguard", "Debug: toggle Guard sub-state on companion.");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        UUID ownerUuid = playerRef.getUuid();
        Ref<EntityStore> companionRef = registry.getCompanionRef(ownerUuid);

        if (companionRef == null || !companionRef.isValid()) {
            ctx.sendMessage(Message.raw("[Scruby-TestGuard] Kein aktiver Companion."));
            return;
        }

        NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
        if (npcEntity == null) {
            ctx.sendMessage(Message.raw("[Scruby-TestGuard] NPCEntity nicht gefunden."));
            return;
        }

        Role role = npcEntity.getRole();
        if (role == null) {
            ctx.sendMessage(Message.raw("[Scruby-TestGuard] Role nicht gefunden."));
            return;
        }

        StateSupport stateSupport = role.getStateSupport();
        if (stateSupport == null) {
            ctx.sendMessage(Message.raw("[Scruby-TestGuard] StateSupport nicht verfuegbar."));
            return;
        }

        String currentState = stateSupport.getStateName();
        ctx.sendMessage(Message.raw("[Scruby-TestGuard] Aktueller State: " + currentState));

        // Toggle: if currently in Guard sub-state, go back to default
        boolean inGuard = stateSupport.inState("Idle", "Guard");

        if (inGuard) {
            // Exit guard: reset to empty sub-state
            try {
                stateSupport.setSubState("");
                ctx.sendMessage(Message.raw("[Scruby-TestGuard] Guard OFF — setSubState(\"\") aufgerufen."));
            } catch (Exception e) {
                LOGGER.atInfo().log("[Scruby-TestGuard] setSubState(\"\") fehlgeschlagen: " + e.getMessage());
                ctx.sendMessage(Message.raw("[Scruby-TestGuard] setSubState(\"\") ERROR: " + e.getClass().getSimpleName() + " — " + e.getMessage()));
            }
        } else {
            // Enter guard
            try {
                stateSupport.setSubState("Guard");
                ctx.sendMessage(Message.raw("[Scruby-TestGuard] Guard ON — setSubState(\"Guard\") aufgerufen."));
            } catch (Exception e) {
                LOGGER.atInfo().log("[Scruby-TestGuard] setSubState(\"Guard\") fehlgeschlagen: " + e.getMessage());
                ctx.sendMessage(Message.raw("[Scruby-TestGuard] setSubState(\"Guard\") ERROR: " + e.getClass().getSimpleName() + " — " + e.getMessage()));
            }
        }

        // Report new state after toggle
        try {
            String newState = stateSupport.getStateName();
            boolean nowInGuard = stateSupport.inState("Idle", "Guard");
            ctx.sendMessage(Message.raw("[Scruby-TestGuard] Neuer State: " + newState + " | inState(Idle,Guard)=" + nowInGuard));
        } catch (Exception e) {
            ctx.sendMessage(Message.raw("[Scruby-TestGuard] State-Abfrage nach Toggle fehlgeschlagen: " + e.getMessage()));
        }
    }
}
