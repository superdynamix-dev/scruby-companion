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

public final class ScrubyRespecCommand extends AbstractPlayerCommand {

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubySkillService skillService;
    private final ScrubyAttributeService attributeService;
    private final ScrubyConfigService configService;

    public ScrubyRespecCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubySkillService skillService,
            @Nonnull ScrubyAttributeService attributeService,
            @Nonnull ScrubyConfigService configService
    ) {
        super("scruby-respec", "Resets chosen abilities and attribute points (first respec free).");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.skillService = Objects.requireNonNull(skillService, "skillService");
        this.attributeService = Objects.requireNonNull(attributeService, "attributeService");
        this.configService = Objects.requireNonNull(configService, "configService");
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
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.respec.no_binding")));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();

        if ("NONE".equals(profile.getPathChoice())) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.respec.no_path")));
            return;
        }

        // Check if there are any chosen abilities to reset
        if (profile.getChosenAbilities().isEmpty()) {
            ScrubyCompanionPlugin.instance().getSoundService().playError(playerRef);
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.respec.no_choices")));
            return;
        }

        // Cooldown check (first respec free, then 24h cooldown)
        if (profile.isFreeRespecUsed()) {
            long now = System.currentTimeMillis();
            long cooldownMs = configService.getRespecCooldownMs();
            long elapsed = now - profile.getLastRespecTimestamp();
            if (elapsed < cooldownMs) {
                long remainingMs = cooldownMs - elapsed;
                long remainingH = remainingMs / (60L * 60L * 1000L);
                long remainingM = (remainingMs % (60L * 60L * 1000L)) / (60L * 1000L);
                ScrubyCompanionPlugin.instance().getSoundService().playError(playerRef);
                ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.respec.cooldown_detail",
                        remainingH, remainingM)));
                return;
            }
        }

        // Reset only chosen abilities (NOT attributes, NOT path)
        profile.setChosenAbilities("");
        profile.setFreeRespecUsed(true);
        profile.setLastRespecTimestamp(System.currentTimeMillis());
        binding.setActiveProfile(profile);

        // Re-apply skill modifiers on live companion (fixed skills remain, chosen cleared)
        UUID ownerUuid = playerRef.getUuid();
        Ref<EntityStore> companionRef = registry.getCompanionRef(ownerUuid);
        if (companionRef != null && companionRef.isValid()) {
            skillService.removeAllSkillModifiers(store, companionRef);
            skillService.applyPassiveSkills(store, companionRef, profile);
        }

        ScrubyCompanionPlugin.instance().getSoundService().playRespec(playerRef);
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.respec.success")));
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }
}
