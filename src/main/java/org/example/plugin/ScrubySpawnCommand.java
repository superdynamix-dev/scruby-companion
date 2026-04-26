// Datei: ScrubySpawnCommand.java
package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.UUID;

public final class ScrubySpawnCommand extends AbstractPlayerCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ScrubyBindingService bindingService;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyCompanionSpawnService spawnService;

    public ScrubySpawnCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyCompanionSpawnService spawnService
    ) {
        super("scruby-spawn", "Spawns the Scruby MVP test companion for the executing player.");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.spawnService = Objects.requireNonNull(spawnService, "spawnService");
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
            commandContext.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.spawn.no_binding")));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();

        if (profile.hasCompanionEntityReference()) {
            EntityStore entityStore = store.getExternalData();
            boolean companionStillExists = false;

            try {
                UUID existingCompanionUuid = UUID.fromString(profile.getCompanionEntityUuid());
                Ref<EntityStore> existingRef = entityStore.getRefFromUUID(existingCompanionUuid);
                companionStillExists = existingRef != null && existingRef.isValid();
            } catch (IllegalArgumentException ignored) {
            }

            if (companionStillExists) {
                commandContext.sendMessage(Message.raw(
                        ScrubyLang.get(locale, "cmd.spawn.blocked", profile.getCompanionEntityUuid())
                ));
                return;
            }

            LOGGER.atInfo().log("[Scruby] Stale binding detected, cleaning up.");
            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.spawn.stale")));
            this.resetService.resetOwnerSession(playerRef.getUuid());
            this.bindingService.markCompanionEntityMissing(store, ref);
        }

        // Re-read profile after potential cleanup, clear manuallyDespawned and station state
        CompanionProfile freshProfile = binding.getActiveProfile();
        freshProfile.setManuallyDespawned(false);
        freshProfile.setStationedAtBase(false);
        freshProfile.setStationMode("NONE");
        binding.setActiveProfile(freshProfile);

        UUID companionUuid = this.spawnService.spawnCompanion(store, ref, playerRef, playerRef.getUuid(), true);

        if (companionUuid == null) {
            commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.spawn.failed")));
            return;
        }

        ScrubyOwnerBindingComponent updatedBinding = this.bindingService.getBindingOrNull(store, ref);
        CompanionProfile updatedProfile = updatedBinding.getActiveProfile();

        commandContext.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.spawn.success")));
        commandContext.sendMessage(Message.raw("Owner UUID: " + updatedBinding.getOwnerPlayerUuid()));
        commandContext.sendMessage(Message.raw("Binding ID: " + updatedBinding.getBindingId()));
        commandContext.sendMessage(Message.raw("Companion UUID: " + updatedProfile.getCompanionEntityUuid()));
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }
}
