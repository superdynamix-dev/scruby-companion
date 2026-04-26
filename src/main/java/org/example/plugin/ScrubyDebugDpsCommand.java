package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Debug command: toggles a 2-second chat output showing DPS aura stats
 * (burn, poison, radius, damage dealt, active skills).
 *
 * Usage: /scruby-debug-dps
 */
public final class ScrubyDebugDpsCommand extends AbstractPlayerCommand {

    public ScrubyDebugDpsCommand() {
        super("scruby-debug-dps", "Debug: Toggle DPS aura tick debug output.");
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
        ScrubySkillTickingSystem.toggleDpsDebug(ownerUuid);
        boolean active = ScrubySkillTickingSystem.isDpsDebugActive(ownerUuid);
        ctx.sendMessage(Message.raw("[Scruby-Debug] DPS debug " + (active ? "ON" : "OFF")
                + " — chat output every 2s while active."));
    }
}
