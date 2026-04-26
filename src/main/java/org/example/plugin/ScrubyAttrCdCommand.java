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

public final class ScrubyAttrCdCommand extends AbstractPlayerCommand {

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    private final ScrubyBindingService bindingService;
    private final ScrubyAttributeService attributeService;

    public ScrubyAttrCdCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyAttributeService attributeService
    ) {
        super("scruby-attr-cd", "Add one Haste point to your companion.");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.attributeService = Objects.requireNonNull(attributeService, "attributeService");
    }

    @Override
    protected void execute(
            @Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ref);
        if (binding == null || !binding.hasBinding()) {
            commandContext.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.attr.no_binding")));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();

        if (!attributeService.addPoint(profile, "zeal")) {
            ScrubyCompanionPlugin.instance().getSoundService().playError(playerRef);
            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.attr.no_points")));
            return;
        }
        binding.setActiveProfile(profile);

        ScrubyCompanionPlugin.instance().getSoundService().playAttribute(playerRef);
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.attr.cd_up",
                profile.getAttributeZeal(), profile.getAttributePointsAvailable())));
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }
}
