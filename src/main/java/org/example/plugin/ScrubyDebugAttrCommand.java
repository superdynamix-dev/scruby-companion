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
 * Debug command: toggles chat output for Strength (per-hit raw→boosted)
 * and Zeal (per-skill-fire base→effective cooldown).
 *
 * Usage: /scruby-debug-attr
 */
public final class ScrubyDebugAttrCommand extends AbstractPlayerCommand {

    public ScrubyDebugAttrCommand() {
        super("scruby-debug-attr", "Debug: Toggle Strength/Zeal attribute debug output.");
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
        ScrubySkillTickingSystem.toggleAttrDebug(ownerUuid);
        boolean active = ScrubySkillTickingSystem.isAttrDebugActive(ownerUuid);
        ctx.sendMessage(Message.raw("[Scruby-Debug] Attribute debug " + (active ? "ON" : "OFF")
                + " — Strength NPC-hits + Zeal skill-fires log to chat."));
    }
}
