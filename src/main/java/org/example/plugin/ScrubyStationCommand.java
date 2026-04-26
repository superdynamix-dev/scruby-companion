package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * Stations Scruby at the player's bed (or current position as fallback).
 * Usage: /scruby-station [idle|guard]
 */
public final class ScrubyStationCommand extends AbstractPlayerCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String CMD_PREFIX = "scruby-station";

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyBaseStationService baseStationService;
    private final ScrubyHudManager hudManager;
    private final ScrubyConfigService configService;

    public ScrubyStationCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyBaseStationService baseStationService,
            @Nonnull ScrubyHudManager hudManager,
            @Nonnull ScrubyConfigService configService
    ) {
        super("scruby-station", "Stationiert Scruby an deinem Bett (oder aktueller Position).");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.baseStationService = Objects.requireNonNull(baseStationService, "baseStationService");
        this.hudManager = Objects.requireNonNull(hudManager, "hudManager");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        if (!configService.isBaseStationEnabled()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.station.disabled")));
            return;
        }

        UUID ownerUuid = playerRef.getUuid();

        // Check companion is spawned
        Ref<EntityStore> companionRef = registry.getCompanionRef(ownerUuid);
        if (companionRef == null || !companionRef.isValid()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.station.no_active")));
            return;
        }

        // Check binding exists
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ref);
        if (binding == null || !binding.hasBinding()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.station.no_binding")));
            return;
        }

        CompanionProfile activeProfile = binding.getActiveProfile();
        String locale = activeProfile != null ? activeProfile.getLocale() : ScrubyLang.DEFAULT_LOCALE;

        // Check if another companion is already guarding
        CompanionProfile stationedProfile = null;
        for (CompanionProfile p : binding.getProfiles()) {
            if (p.isStationedAtBase() && p.getSlotId() != binding.getActiveSlot()) {
                stationedProfile = p;
                break;
            }
        }
        if (stationedProfile != null) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.station.guarding",
                    stationedProfile.getCompanionName(), stationedProfile.getSlotId() + 1)));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();

        // Check not already stationed
        if (profile.isStationedAtBase()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.station.already_hint")));
            return;
        }

        // Get Player entity for bed position lookup
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.station.player_error")));
            return;
        }

        String worldName = world.getName();

        boolean success = baseStationService.stationAtBed(player, playerRef, profile, worldName);
        if (!success) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.station.failed")));
            return;
        }

        // Despawn current companion and respawn as stationed (no follow AI)
        baseStationService.respawnAsStationed(store, ownerUuid, profile);

        // Save updated profile
        binding.setActiveProfile(profile);

        // Remove HUD — companion is now stationed, not following
        hudManager.removeHud(store, ref, playerRef, ownerUuid);

        ScrubyCompanionPlugin.instance().getSoundService().playStation(playerRef,
                profile.getStationX(), profile.getStationY(), profile.getStationZ());
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.station.success")));
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.station.position",
                String.format("%.1f, %.1f, %.1f", profile.getStationX(), profile.getStationY(), profile.getStationZ()))));
        LOGGER.atInfo().log("[Scruby-Station] Player " + ownerUuid + " stationed companion.");
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }

}
