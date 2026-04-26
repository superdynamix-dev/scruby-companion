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
import javax.annotation.Nullable;
import java.util.Objects;

public final class ScrubyNameCommand extends AbstractPlayerCommand {

    private static final String CMD_PREFIX = "scruby-name";

    private final ScrubyBindingService bindingService;
    private final ScrubyConfigService configService;

    public ScrubyNameCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyConfigService configService
    ) {
        super("scruby-name", "Rename your companion (max 24 chars).");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.configService = Objects.requireNonNull(configService, "configService");
        setAllowsExtraArguments(true);
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
        ScrubyOwnerBindingComponent binding = this.bindingService.getBindingOrNull(store, ref);

        if (binding == null || !binding.hasBinding()) {
            commandContext.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.name.no_binding")));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();

        String name = parseName(commandContext.getInputString());

        if (name == null || name.isEmpty()) {
            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.name.usage")));
            return;
        }

        int maxLen = configService.getMaxNameLength();
        if (name.length() > maxLen || !name.matches("[\\w\\s\\-]+")) {
            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.name.invalid", maxLen)));
            return;
        }

        profile.setCompanionName(name);
        binding.setActiveProfile(profile);

        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.name.success", name)));
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }

    /**
     * Strips the command name prefix from the raw input and returns the trimmed name.
     * Handles both "scruby-name Foo Bar" and "Foo Bar" (if Hytale strips prefix).
     */
    @Nullable
    private static String parseName(@Nonnull String inputString) {
        String trimmed = inputString.trim();
        if (trimmed.isEmpty()) return null;

        String lower = trimmed.toLowerCase();
        if (lower.startsWith(CMD_PREFIX)) {
            String rest = trimmed.substring(CMD_PREFIX.length()).trim();
            return rest.isEmpty() ? null : rest;
        }

        return trimmed;
    }
}
