package org.example.plugin;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;

public final class ScrubyCommand extends CommandBase {

    private static final String CMD_COLOR = "#50c878";
    private static final String DESC_COLOR = "#ffcc00";

    public ScrubyCommand() {
        super("scruby", "Scruby companion plugin root command.");
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        String locale = ScrubyLang.DEFAULT_LOCALE;
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.help.header")).color(CMD_COLOR));
        helpLine(ctx, locale, "cmd.help.bind");
        helpLine(ctx, locale, "cmd.help.unbind");
        helpLine(ctx, locale, "cmd.help.spawn");
        helpLine(ctx, locale, "cmd.help.despawn");
        helpLine(ctx, locale, "cmd.help.info");
        helpLine(ctx, locale, "cmd.help.name");
        helpLine(ctx, locale, "cmd.help.menu");
        helpLine(ctx, locale, "cmd.help.skills_info");
        helpLine(ctx, locale, "cmd.help.respec");
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.help.section_paths")).color(DESC_COLOR));
        helpLine(ctx, locale, "cmd.help.path_dps");
        helpLine(ctx, locale, "cmd.help.path_healer");
        helpLine(ctx, locale, "cmd.help.path_tank");
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.help.section_attr")).color(DESC_COLOR));
        helpLine(ctx, locale, "cmd.help.attr_hp");
        helpLine(ctx, locale, "cmd.help.attr_dmg");
        helpLine(ctx, locale, "cmd.help.attr_cd");
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.help.section_skills")).color(DESC_COLOR));
        helpLine(ctx, locale, "cmd.help.choose1");
        helpLine(ctx, locale, "cmd.help.choose2");
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.help.section_base")).color(DESC_COLOR));
        helpLine(ctx, locale, "cmd.help.station");
        helpLine(ctx, locale, "cmd.help.recall");
        helpLine(ctx, locale, "cmd.help.list");
        helpLine(ctx, locale, "cmd.help.switch");
        helpLine(ctx, locale, "cmd.help.prestige");
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.help.section_combat")).color(DESC_COLOR));
        helpLine(ctx, locale, "cmd.help.passive");
        helpLine(ctx, locale, "cmd.help.aggressive");
        helpLine(ctx, locale, "cmd.help.mute_inv");
        helpLine(ctx, locale, "cmd.help.mute_greeting");
        helpLine(ctx, locale, "cmd.help.mute_combat");
        helpLine(ctx, locale, "cmd.help.mute_skills");
        helpLine(ctx, locale, "cmd.help.hud");
    }

    private static void helpLine(@Nonnull CommandContext ctx, @Nonnull String locale, @Nonnull String key) {
        String text = ScrubyLang.get(locale, key);
        int dashIdx = text.indexOf(" - ");
        if (dashIdx >= 0) {
            String cmd = text.substring(0, dashIdx);
            String desc = text.substring(dashIdx);
            ctx.sendMessage(Message.raw(cmd).color(CMD_COLOR).insert(Message.raw(desc).color(DESC_COLOR)));
        } else {
            ctx.sendMessage(Message.raw(text).color(CMD_COLOR));
        }
    }
}
