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
import java.util.Objects;

public final class ScrubySkillsInfoCommand extends AbstractPlayerCommand {

    private final ScrubyBindingService bindingService;
    private final ScrubySkillService skillService;

    public ScrubySkillsInfoCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubySkillService skillService
    ) {
        super("scruby-skills-info", "Show all unlocked skills and choice options.");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.skillService = Objects.requireNonNull(skillService, "skillService");
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
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
        if (binding == null || !binding.hasBinding()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.skills_info.no_binding")));
            return;
        }
        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();

        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.skills_info.header",
                ScrubyColors.pathDisplayName(profile.getPathChoice(), locale))));

        String fixed = profile.getFixedAbilities();
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.skills_info.fixed",
                fixed.isEmpty() ? ScrubyLang.get(locale, "cmd.skills_info.none") : fixed)));

        String chosen = profile.getChosenAbilities();
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.skills_info.chosen",
                chosen.isEmpty() ? ScrubyLang.get(locale, "cmd.skills_info.none") : chosen)));

        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.skills_info.choice1",
                skillService.getChoiceStatusText(profile, 1, locale))));
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.skills_info.choice2",
                skillService.getChoiceStatusText(profile, 2, locale))));
    }
}
