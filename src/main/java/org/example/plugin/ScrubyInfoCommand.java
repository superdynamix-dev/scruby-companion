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

public final class ScrubyInfoCommand extends AbstractPlayerCommand {

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyXPService xpService;

    public ScrubyInfoCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyXPService xpService
    ) {
        super("scruby-info", "Shows Scruby companion status for the executing player.");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.xpService = Objects.requireNonNull(xpService, "xpService");
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
            commandContext.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.info.no_binding")));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();

        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.info.header")));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.info.name", profile.getCompanionName())));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.info.level", profile.getLevel())));

        long xpForNext = xpService.getXpForLevel(profile.getLevel() + 1);
        String xpDisplay = profile.getLevel() >= ScrubyXPService.MAX_LEVEL
                ? profile.getCurrentXp() + " / MAX"
                : profile.getCurrentXp() + " / " + xpForNext;
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.info.xp", profile.getCurrentXp(),
                profile.getLevel() >= ScrubyXPService.MAX_LEVEL ? "MAX" : xpForNext)));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.info.path",
                ScrubyColors.pathDisplayName(profile.getPathChoice(), locale))));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.info.stage", profile.getEvolutionStage())));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.info.kills", profile.getKillCount())));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.info.deaths", profile.getDeathCount())));
        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.info.slot", binding.getActiveSlot() + 1)));

        // Technical info (not localized — debug output)
        commandContext.sendMessage(Message.raw("  Owner UUID:         " + binding.getOwnerPlayerUuid()));
        commandContext.sendMessage(Message.raw("  Binding ID:         " + binding.getBindingId()));
        commandContext.sendMessage(Message.raw("  Companion UUID:     " + emptyOrValue(profile.getCompanionEntityUuid())));
        commandContext.sendMessage(Message.raw("  Entity Missing:     " + profile.isEntityMissing()));
        commandContext.sendMessage(Message.raw("  Manually Despawned: " + profile.isManuallyDespawned()));
        commandContext.sendMessage(Message.raw("  Total XP:           " + profile.getTotalXp()));
        commandContext.sendMessage(Message.raw("  Attribute Points:   " + profile.getAttributePointsAvailable()));

        UUID ownerUuid = playerRef.getUuid();
        boolean activeInRegistry = this.registry.hasActiveCompanion(ownerUuid);
        commandContext.sendMessage(Message.raw("  --- Runtime ---"));
        commandContext.sendMessage(Message.raw("  Active in Registry: " + activeInRegistry));

        boolean entityValid = false;
        String companionUuidString = profile.getCompanionEntityUuid();
        if (companionUuidString != null && !companionUuidString.isEmpty()) {
            try {
                UUID companionUuid = UUID.fromString(companionUuidString);
                Ref<EntityStore> companionRef = store.getExternalData().getRefFromUUID(companionUuid);
                entityValid = companionRef != null && companionRef.isValid();
            } catch (IllegalArgumentException ignored) {
            }
        }
        commandContext.sendMessage(Message.raw("  Entity Valid:       " + entityValid));
    }

    @Nonnull
    private static String emptyOrValue(@Nonnull String value) {
        return value.isEmpty() ? "(none)" : value;
    }
}
