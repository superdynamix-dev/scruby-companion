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

/**
 * Recalls a stationed Scruby back to the player.
 * Usage: /scruby-recall
 */
public final class ScrubyRecallCommand extends AbstractPlayerCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyBaseStationService baseStationService;

    public ScrubyRecallCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyBaseStationService baseStationService
    ) {
        super("scruby-recall", "Ruft den stationierten Scruby zurueck.");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.baseStationService = Objects.requireNonNull(baseStationService, "baseStationService");
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
        UUID ownerUuid = playerRef.getUuid();

        // Check binding exists
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ref);
        if (binding == null || !binding.hasBinding()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.recall.no_binding")));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        String locale = profile.getLocale();

        // Check companion is stationed
        if (!profile.isStationedAtBase()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.recall.not_stationed")));
            return;
        }

        // Recall from station
        baseStationService.recallFromStation(profile);

        // Despawn stationed companion and respawn as following (with normal follow AI)
        baseStationService.respawnAsFollowing(store, ownerUuid, playerRef, profile);

        // Save updated profile
        binding.setActiveProfile(profile);

        com.hypixel.hytale.math.vector.Vector3d playerPos = playerRef.getTransform().getPosition();
        ScrubyCompanionPlugin.instance().getSoundService().playRecall(playerRef,
                playerPos.getX(), playerPos.getY(), playerPos.getZ());
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.recall.success")));
        LOGGER.atInfo().log("[Scruby-Station] Player " + ownerUuid + " recalled companion from station.");
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }

}
