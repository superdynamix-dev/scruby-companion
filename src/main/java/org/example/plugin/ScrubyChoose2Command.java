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
import java.util.UUID;

public final class ScrubyChoose2Command extends AbstractPlayerCommand {

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubySkillService skillService;

    public ScrubyChoose2Command(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubySkillService skillService
    ) {
        super("scruby-choose-2", "Choose skill option 2 for the next open choice slot.");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.skillService = Objects.requireNonNull(skillService, "skillService");
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
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.choose.no_binding")));
            return;
        }
        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();

        int slot = skillService.getNextOpenChoiceSlot(profile);
        if (slot == 0) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.choose.no_choice")));
            return;
        }
        String[] options = skillService.getChoiceOptions(profile, slot);
        if (options == null) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.choose.error")));
            return;
        }
        if (!skillService.applyChoice(profile, slot, 2)) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.choose.failed")));
            return;
        }
        binding.setActiveProfile(profile);
        String chosen = options[1];
        String skillName = ScrubyLang.get(locale, "skill." + ScrubyLang.skillKey(chosen) + ".name");
        String desc = skillService.getSkillDescription(chosen, locale);
        ScrubyCompanionPlugin.instance().getSoundService().playSkillChoice(playerRef);
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.choose.success_detail", skillName, desc)));

        UUID ownerUuid = playerRef.getUuid();
        Ref<EntityStore> companionRef = registry.getCompanionRef(ownerUuid);
        if (companionRef != null && companionRef.isValid()) {
            skillService.applyPassiveSkills(store, companionRef, profile);
        }
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }
}
