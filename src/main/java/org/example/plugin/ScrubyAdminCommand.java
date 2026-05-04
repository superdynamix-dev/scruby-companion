package org.example.plugin;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;

public final class ScrubyAdminCommand extends CommandBase {

    private static final String CMD_COLOR = "#ff2020";
    private static final String DESC_COLOR = "#ffcc00";
    private static final String SECTION_COLOR = "#ffcc00";

    public ScrubyAdminCommand() {
        super("scruby-admin", "Scruby admin commands overview.");
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw("[Scruby-Admin] Commands (OP only):").color(CMD_COLOR));
        ctx.sendMessage(Message.raw("  --- Inspection ---").color(SECTION_COLOR));
        helpLine(ctx, "  /scruby-admin-list <player>", "List all slots");
        helpLine(ctx, "  /scruby-admin-inspect <player> <slot>", "Inspect companion data");
        ctx.sendMessage(Message.raw("  --- Modification ---").color(SECTION_COLOR));
        helpLine(ctx, "  /scruby-admin-setlevel <player> <slot> <lvl>", "Set level");
        helpLine(ctx, "  /scruby-admin-set-path <player> <slot> <path>", "Set path");
        helpLine(ctx, "  /scruby-admin-add-xp <player> <slot> <amount>", "Add XP");
        helpLine(ctx, "  /scruby-admin-reset <player> <slot>", "Reset companion");
        helpLine(ctx, "  /scruby-admin-delete <player> <slot>", "Delete companion");
        helpLine(ctx, "  /scruby-admin-prestige-reset <player> <slot>", "Reset prestige");
        ctx.sendMessage(Message.raw("  --- Fix (Stuck States) ---").color(SECTION_COLOR));
        helpLine(ctx, "  /scruby-admin-despawn <player> <slot>", "Despawn companion");
        helpLine(ctx, "  /scruby-admin-force-recall <player> <slot>", "Fix station limbo");
        helpLine(ctx, "  /scruby-admin-clear-state <player> <slot>", "Reset stuck flags");
        helpLine(ctx, "  /scruby-admin-end-prestige <player>", "End prestige fight");
        helpLine(ctx, "  /scruby-admin-respawn <player> <slot>", "Respawn companion");
        helpLine(ctx, "  /scruby-admin-cleanup-orphans", "Despawn orphan Scrubys + rebuild from bindings (current world)");
        ctx.sendMessage(Message.raw("  --- Discovery ---").color(SECTION_COLOR));
        helpLine(ctx, "  /scruby-admin-discoveries <player>", "Show discoveries");
        helpLine(ctx, "  /scruby-admin-clear-discoveries <player>", "Clear discoveries");
        ctx.sendMessage(Message.raw("  --- Config ---").color(SECTION_COLOR));
        helpLine(ctx, "  /scruby-admin-config-reload", "Reload config");
    }

    private static void helpLine(@Nonnull CommandContext ctx, @Nonnull String cmd, @Nonnull String desc) {
        ctx.sendMessage(Message.raw(cmd).color(CMD_COLOR).insert(Message.raw(" - " + desc).color(DESC_COLOR)));
    }
}
