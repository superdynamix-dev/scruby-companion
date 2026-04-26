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

public final class ScrubyBindCommand extends AbstractPlayerCommand {

    private final ScrubyBindingService bindingService;
    private final ScrubyConfigService configService;

    public ScrubyBindCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyConfigService configService
    ) {
        super("scruby-bind", "Creates a persistent Scruby owner binding for the executing player.");
        this.bindingService = bindingService;
        this.configService = configService;
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(
            @Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        UUID ownerUuid = playerRef.getUuid();

        ScrubyOwnerBindingComponent existingBinding = this.bindingService.getBindingOrNull(store, ref);

        if (existingBinding != null && existingBinding.hasBinding()) {
            // Existing binding — add companion to next free slot
            String locale = existingBinding.getActiveProfile() != null
                    ? existingBinding.getActiveProfile().getLocale() : ScrubyLang.DEFAULT_LOCALE;
            int profileCount = this.bindingService.getProfileCount(existingBinding);
            int maxSlots = configService.getMaxCompanionSlots();
            if (profileCount >= maxSlots) {
                commandContext.sendMessage(Message.raw(
                        ScrubyLang.get(locale, "cmd.bind.full", maxSlots)));
                return;
            }

            try {
                CompanionProfile newProfile = this.bindingService.addCompanion(store, ref);
                ScrubyCompanionPlugin.instance().getSoundService().playBind(playerRef);
                commandContext.sendMessage(Message.raw(
                        ScrubyLang.get(locale, "cmd.bind.new_slot",
                                newProfile.getSlotId() + 1, profileCount + 1, existingBinding.getMaxSlots())));
                commandContext.sendMessage(Message.raw(
                        ScrubyLang.get(locale, "cmd.bind.switch_hint", newProfile.getSlotId() + 1)));
            } catch (IllegalStateException e) {
                commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.bind.error", e.getMessage())));
            }
            return;
        }

        // First bind — create new binding
        ScrubyOwnerBindingComponent binding = this.bindingService.bindOwner(store, ref, ownerUuid);
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, ownerUuid);
        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile != null ? profile.getLocale() : ScrubyLang.DEFAULT_LOCALE;

        ScrubyCompanionPlugin.instance().getSoundService().playBind(playerRef);
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.bind.created")));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.bind.spawn_hint")));
    }
}
