package org.example.plugin;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.playerdata.PlayerStorage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

import javax.annotation.Nonnull;

public class ScrubyCompanionPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static ScrubyCompanionPlugin instance;

    private ComponentType<EntityStore, ScrubyOwnerBindingComponent> scrubyOwnerBindingComponentType;

    private ScrubyConfigService configService;
    private ScrubyCompanionHitTracker hitTracker;
    private ScrubyBindingService bindingService;
    private ScrubyActiveCompanionRegistry companionRegistry;
    private ScrubyCompanionResetService resetService;
    private ScrubyEvolutionService evolutionService;
    private ScrubyAttributeService attributeService;
    private ScrubySkillService skillService;
    private ScrubyHudManager hudManager;
    private ScrubyCompanionSpawnService spawnService;
    private ScrubyXPService xpService;
    private ScrubyBaseStationService baseStationService;
    private ScrubySoundService soundService;
    private ScrubyInventoryService inventoryService;
    private ScrubyArmorService armorService;
    private ScrubyAdminService adminService;

    public ScrubyCompanionPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        LOGGER.atInfo().log("[Scruby] Plugin loaded.");
    }

    @Override
    protected void setup() {

        this.configService = new ScrubyConfigService(this.getDataDirectory());

        this.scrubyOwnerBindingComponentType =
                this.getEntityStoreRegistry().registerComponent(
                        ScrubyOwnerBindingComponent.class,
                        "ScrubyOwnerBindingComponent",
                        ScrubyOwnerBindingComponent.CODEC
                );

        this.bindingService = new ScrubyBindingService(this.scrubyOwnerBindingComponentType, this.configService);
        this.companionRegistry = new ScrubyActiveCompanionRegistry();
        this.resetService = new ScrubyCompanionResetService(
                this.bindingService,
                this.companionRegistry
        );

        this.hitTracker = new ScrubyCompanionHitTracker();
        this.soundService = new ScrubySoundService();
        this.inventoryService = new ScrubyInventoryService();
        this.evolutionService = new ScrubyEvolutionService();
        this.armorService = new ScrubyArmorService();
        this.attributeService = new ScrubyAttributeService(this.armorService, this.companionRegistry);
        this.skillService = new ScrubySkillService();
        this.xpService = new ScrubyXPService(this.skillService, this.configService);
        this.hudManager = new ScrubyHudManager(this.xpService, this.evolutionService, this.armorService);

        this.baseStationService = new ScrubyBaseStationService(
                this.bindingService,
                this.companionRegistry,
                this.evolutionService,
                this.configService
        );

        this.spawnService = new ScrubyCompanionSpawnService(
                this.bindingService,
                this.companionRegistry,
                this.resetService,
                this.evolutionService,
                this.attributeService,
                this.skillService,
                this.hudManager,
                this.soundService
        );

        ScrubyPendingRespawnQueue pendingRespawnQueue = new ScrubyPendingRespawnQueue();
        ScrubyPrestigeFightTracker prestigeFightTracker = new ScrubyPrestigeFightTracker(this.configService);

        ScrubySkillTreePage skillTreePage = new ScrubySkillTreePage(
                this.skillService,
                this.evolutionService,
                this.xpService,
                this.attributeService,
                this.companionRegistry,
                this.bindingService,
                this.spawnService,
                this.resetService,
                this.hudManager,
                this.baseStationService,
                this.soundService,
                prestigeFightTracker,
                this.inventoryService,
                this.armorService
        );

        this.getEntityStoreRegistry().registerSystem(
                new ScrubyXPSystem(
                        this.bindingService,
                        this.companionRegistry,
                        this.xpService,
                        this.evolutionService,
                        pendingRespawnQueue,
                        this.spawnService,
                        this.soundService,
                        this.configService
                )
        );

        this.getEntityStoreRegistry().registerSystem(
                new ScrubySkillTickingSystem(
                        this.bindingService,
                        this.companionRegistry,
                        this.hudManager,
                        this.soundService,
                        this.inventoryService,
                        this.hitTracker
                )
        );

        this.getEntityStoreRegistry().registerSystem(
                new ScrubyBaseStationTickingSystem(
                        this.bindingService,
                        this.companionRegistry,
                        this.configService
                )
        );

        ScrubyPlayerRespawnSystem playerRespawnSystem = new ScrubyPlayerRespawnSystem(
                this.bindingService,
                this.companionRegistry,
                this.spawnService,
                this.hudManager,
                this.soundService
        );

        this.getEntityStoreRegistry().registerSystem(playerRespawnSystem);

        this.getEntityStoreRegistry().registerSystem(
                new ScrubyKillDetectionSystem(
                        this.bindingService,
                        this.companionRegistry,
                        this.hitTracker,
                        this.xpService,
                        this.evolutionService,
                        pendingRespawnQueue,
                        this.hudManager,
                        this.soundService,
                        this.configService,
                        this.inventoryService,
                        playerRespawnSystem,
                        prestigeFightTracker
                )
        );

        this.getEntityStoreRegistry().registerSystem(
                new ScrubyPrestigeTickingSystem(
                        this.bindingService,
                        this.companionRegistry,
                        prestigeFightTracker,
                        pendingRespawnQueue,
                        this.soundService
                )
        );

        this.getEntityStoreRegistry().registerSystem(
                new ScrubySmartFollowSystem(
                        this.bindingService,
                        this.companionRegistry,
                        this.spawnService
                )
        );

        this.getEntityStoreRegistry().registerSystem(
                new ScrubyDamageDetectionSystem(
                        this.companionRegistry,
                        this.hitTracker
                )
        );

        this.getEntityStoreRegistry().registerSystem(
                new ScrubyDamageBuffSystem(this.companionRegistry)
        );

        this.getEntityStoreRegistry().registerSystem(new ScrubyItemCleanupSystem());

        ScrubyCombatModeOverrideService combatModeOverrideService =
                new ScrubyCombatModeOverrideService(
                        this.bindingService,
                        this.companionRegistry,
                        this.resetService,
                        this.spawnService,
                        this.evolutionService
                );

        ScrubyPlayerLifecycleListener lifecycleListener =
                new ScrubyPlayerLifecycleListener(
                        this.bindingService,
                        this.companionRegistry,
                        this.resetService,
                        this.spawnService,
                        this.hudManager,
                        this.evolutionService,
                        this.configService,
                        combatModeOverrideService
                );

        this.getEventRegistry().registerGlobal(
                PlayerReadyEvent.class,
                lifecycleListener::onPlayerReady
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyCommand()
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyBindCommand(this.bindingService, this.configService)
        );

        this.getCommandRegistry().registerCommand(
                new ScrubySpawnCommand(
                        this.bindingService,
                        this.resetService,
                        this.spawnService
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyDespawnCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.resetService,
                        this.hudManager
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyUnbindCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.resetService,
                        this.hudManager
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyInfoCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.xpService
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyPathDpsCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.resetService,
                        pendingRespawnQueue,
                        this.evolutionService
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyPathHealerCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.resetService,
                        pendingRespawnQueue,
                        this.evolutionService
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyPathTankCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.resetService,
                        pendingRespawnQueue,
                        this.evolutionService
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyNameCommand(this.bindingService, this.configService)
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyAttrHpCommand(this.bindingService, this.companionRegistry, this.attributeService)
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyAttrDmgCommand(this.bindingService, this.attributeService)
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyAttrCdCommand(this.bindingService, this.attributeService)
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyChoose1Command(this.bindingService, this.companionRegistry, this.skillService)
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyChoose2Command(this.bindingService, this.companionRegistry, this.skillService)
        );

        this.getCommandRegistry().registerCommand(
                new ScrubySkillsInfoCommand(this.bindingService, this.skillService)
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyMenuCommand(this.bindingService, skillTreePage)
        );

        com.hypixel.hytale.server.core.io.adapter.PacketAdapters.registerInbound(
                new ScrubyInteractionHandler(
                        this.bindingService,
                        this.companionRegistry,
                        skillTreePage
                )
        );
        LOGGER.atInfo().log("[Scruby] Interaction handler registered (F-key).");

        this.getCommandRegistry().registerCommand(
                new ScrubyRespecCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.skillService,
                        this.attributeService,
                        this.configService
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyStationCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.baseStationService,
                        this.hudManager,
                        this.configService
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyRecallCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.baseStationService
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyListCommand(this.bindingService)
        );

        this.getCommandRegistry().registerCommand(
                new ScrubySwitchCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.spawnService,
                        this.resetService,
                        this.hudManager
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyDeleteCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.resetService,
                        this.hudManager
                )
        );

        this.getCommandRegistry().registerCommand(
                new ScrubyPrestigeCommand(
                        this.bindingService,
                        this.companionRegistry,
                        this.resetService,
                        this.hudManager,
                        prestigeFightTracker,
                        this.evolutionService,
                        this.configService
                )
        );

        this.getCommandRegistry().registerCommand(new ScrubyAdminCommand());

        // Admin service + commands
        this.adminService = new ScrubyAdminService(
                this.bindingService,
                this.companionRegistry,
                this.evolutionService,
                this.resetService,
                this.spawnService,
                this.skillService,
                this.xpService,
                this.baseStationService,
                prestigeFightTracker,
                this.scrubyOwnerBindingComponentType
        );

        this.getCommandRegistry().registerCommand(new ScrubyAdminInspectCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminListCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminSetLevelCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminResetCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminDeleteCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminPrestigeResetCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminSetPathCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminAddXpCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminDespawnCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminForceRecallCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminClearStateCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminEndPrestigeCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminRespawnCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminDiscoveriesCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminClearDiscoveriesCommand(this.adminService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminConfigReloadCommand(this.configService));
        this.getCommandRegistry().registerCommand(new ScrubyMuteInventoryCommand(this.bindingService));
        this.getCommandRegistry().registerCommand(new ScrubyMuteGreetingCommand(this.bindingService));
        this.getCommandRegistry().registerCommand(new ScrubyMuteCombatLogCommand(this.bindingService));
        this.getCommandRegistry().registerCommand(new ScrubyMuteSkillsCommand(this.bindingService));
        this.getCommandRegistry().registerCommand(new ScrubyHudPositionCommand(this.bindingService, this.hudManager, this.companionRegistry));
        this.getCommandRegistry().registerCommand(new ScrubyAdminInventoryKeepCommand(this.adminService, this.bindingService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminInventoryClearCommand(this.adminService, this.bindingService, this.inventoryService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminAutoLootCommand(this.adminService, this.bindingService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminGiveItemCommand(this.adminService, this.inventoryService));
        this.getCommandRegistry().registerCommand(new ScrubyAdminInventoryListCommand(this.adminService, this.bindingService, this.inventoryService));

        this.getCommandRegistry().registerCommand(new ScrubyPassiveCommand(this.bindingService, this.companionRegistry, this.resetService, this.spawnService));
        this.getCommandRegistry().registerCommand(new ScrubyAggressiveCommand(this.bindingService, this.companionRegistry, this.resetService, this.spawnService));

        this.getCommandRegistry().registerCommand(new ScrubyDebugDpsCommand());
        this.getCommandRegistry().registerCommand(new ScrubyDebugAttrCommand());
        LOGGER.atInfo().log("[Scruby] Admin commands registered.");

        LOGGER.atInfo().log("[Scruby] Setup complete.");
        LOGGER.atInfo().log("[Scruby] Owner binding component registered.");
        LOGGER.atInfo().log("[Scruby] Lifecycle listener registered.");
        LOGGER.atInfo().log("[Scruby] Commands registered.");
    }

    @Nonnull
    public static ScrubyCompanionPlugin instance() {

        if (instance == null) {
            throw new IllegalStateException("Plugin instance not initialized yet.");
        }

        return instance;
    }

    @Nonnull
    public ScrubyBindingService getBindingService() {
        return this.bindingService;
    }

    @Nonnull
    public ScrubyActiveCompanionRegistry getCompanionRegistry() {
        return this.companionRegistry;
    }

    @Nonnull
    public ScrubyBaseStationService getBaseStationService() {
        return this.baseStationService;
    }

    @Nonnull
    public ScrubySoundService getSoundService() {
        return this.soundService;
    }

    @Nonnull
    public ScrubyConfigService getConfigService() {
        return this.configService;
    }

    /**
     * Saves an online player's data to disk immediately.
     * Call after important state changes (level-up, skill choice, attribute confirm, bind/unbind)
     * so progress survives server crashes between Hytale's auto-save intervals.
     */
    public static void savePlayerAsync(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ownerRef, @Nonnull UUID ownerUuid) {
        try {
            var holder = store.copySerializableEntity(ownerRef);
            PlayerStorage storage = Universe.get().getPlayerStorage();
            storage.save(ownerUuid, holder).whenComplete((v, ex) -> {
                if (ex != null) {
                    LOGGER.atWarning().log("[Scruby] Save failed for " + ownerUuid + ": " + ex.getMessage());
                }
            });
        } catch (Exception e) {
            LOGGER.atWarning().log("[Scruby] Save failed for " + ownerUuid + ": " + e.getMessage());
        }
    }
}
