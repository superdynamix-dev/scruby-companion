package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

public final class ScrubyDebugDumpCommand extends AbstractPlayerCommand {

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyAttributeService attributeService;
    private final ScrubySkillService skillService;

    public ScrubyDebugDumpCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyAttributeService attributeService,
            @Nonnull ScrubySkillService skillService
    ) {
        super("scruby-debug-dump", "Debug: Dump all companion profile fields.");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.attributeService = Objects.requireNonNull(attributeService, "attributeService");
        this.skillService = Objects.requireNonNull(skillService, "skillService");
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

        commandContext.sendMessage(Message.raw("[Scruby-Debug] === Full Profile Dump ==="));

        if (binding == null || !binding.hasBinding()) {
            commandContext.sendMessage(Message.raw("  No binding."));
            return;
        }

        CompanionProfile p = binding.getActiveProfile();

        commandContext.sendMessage(Message.raw("  slotId:               " + p.getSlotId()));
        commandContext.sendMessage(Message.raw("  companionType:        " + p.getCompanionType()));
        commandContext.sendMessage(Message.raw("  companionName:        " + p.getCompanionName()));
        commandContext.sendMessage(Message.raw("  companionEntityUuid:  " + (p.getCompanionEntityUuid().isEmpty() ? "(none)" : p.getCompanionEntityUuid())));
        commandContext.sendMessage(Message.raw("  entityMissing:        " + p.isEntityMissing()));
        commandContext.sendMessage(Message.raw("  manuallyDespawned:    " + p.isManuallyDespawned()));
        commandContext.sendMessage(Message.raw("  level:                " + p.getLevel()));
        commandContext.sendMessage(Message.raw("  currentXp:            " + p.getCurrentXp()));
        commandContext.sendMessage(Message.raw("  totalXp:              " + p.getTotalXp()));
        commandContext.sendMessage(Message.raw("  pathChoice:           " + p.getPathChoice()));
        commandContext.sendMessage(Message.raw("  evolutionStage:       " + p.getEvolutionStage()));
        commandContext.sendMessage(Message.raw("  fixedAbilities:       " + (p.getFixedAbilities().isEmpty() ? "(none)" : p.getFixedAbilities())));
        commandContext.sendMessage(Message.raw("  chosenAbilities:      " + (p.getChosenAbilities().isEmpty() ? "(none)" : p.getChosenAbilities())));
        commandContext.sendMessage(Message.raw("  attrPointsAvail:      " + p.getAttributePointsAvailable()));
        commandContext.sendMessage(Message.raw("  attrVitality:         " + p.getAttributeVitality()));
        commandContext.sendMessage(Message.raw("  attrStrength:         " + p.getAttributeStrength()));
        commandContext.sendMessage(Message.raw("  attrZeal:             " + p.getAttributeZeal()));
        commandContext.sendMessage(Message.raw("  killCount:            " + p.getKillCount()));
        commandContext.sendMessage(Message.raw("  deathCount:           " + p.getDeathCount()));
        commandContext.sendMessage(Message.raw("  stationedAtBase:      " + p.isStationedAtBase()));
        commandContext.sendMessage(Message.raw("  guardModeUnlocked:    " + p.isGuardModeUnlocked()));
        commandContext.sendMessage(Message.raw("  freeRespecUsed:       " + p.isFreeRespecUsed()));
        commandContext.sendMessage(Message.raw("  stationMode:          " + p.getStationMode()));
        commandContext.sendMessage(Message.raw("  stationPos:           " + p.getStationX() + ", " + p.getStationY() + ", " + p.getStationZ()));
        commandContext.sendMessage(Message.raw("  stationWorldId:       " + (p.getStationWorldId().isEmpty() ? "(none)" : p.getStationWorldId())));

        UUID ownerUuid = playerRef.getUuid();
        boolean registered = registry.hasActiveCompanion(ownerUuid);
        commandContext.sendMessage(Message.raw("  --- Runtime ---"));
        commandContext.sendMessage(Message.raw("  registryActive:       " + registered));

        Ref<EntityStore> companionRef = registry.getCompanionRef(ownerUuid);
        if (companionRef != null && companionRef.isValid()) {
            TransformComponent transform = store.getComponent(companionRef, TransformComponent.getComponentType());
            if (transform != null) {
                commandContext.sendMessage(Message.raw("  entityRef:            valid"));
                commandContext.sendMessage(Message.raw("  position:             " + transform.getPosition()));
            } else {
                commandContext.sendMessage(Message.raw("  entityRef:            valid (no transform)"));
            }
        } else {
            commandContext.sendMessage(Message.raw("  entityRef:            " + (companionRef == null ? "null" : "invalid")));
        }

        commandContext.sendMessage(Message.raw("  --- Attribute Effects ---"));
        commandContext.sendMessage(Message.raw("  attrEffectHP:         +" + attributeService.calculateHpBonus(p.getAttributeVitality()) + " MaxHealth"));
        commandContext.sendMessage(Message.raw("  attrEffectDmg:        +" + Math.round(attributeService.calculateDmgBonus(p.getAttributeStrength()) * 100) + "% Damage (NPC hits only)"));
        commandContext.sendMessage(Message.raw("  attrEffectCD:         -" + Math.round(attributeService.calculateCdReduction(p.getAttributeZeal()) * 100) + "% Cooldown"));

        commandContext.sendMessage(Message.raw("  --- Skills ---"));
        commandContext.sendMessage(Message.raw("  fixedAbilities:       " + (p.getFixedAbilities().isEmpty() ? "(none)" : p.getFixedAbilities())));
        commandContext.sendMessage(Message.raw("  chosenAbilities:      " + (p.getChosenAbilities().isEmpty() ? "(none)" : p.getChosenAbilities())));
        commandContext.sendMessage(Message.raw("  choice1Status:        " + skillService.getChoiceStatusText(p, 1, ScrubyLang.DEFAULT_LOCALE)));
        commandContext.sendMessage(Message.raw("  choice2Status:        " + skillService.getChoiceStatusText(p, 2, ScrubyLang.DEFAULT_LOCALE)));

        // Entity MaxHealth from EntityStatMap
        String entityMaxHealth = "n/a";
        String entityCurrentHealth = "n/a";
        if (companionRef != null && companionRef.isValid()) {
            EntityStatMap statMap = store.getComponent(companionRef, EntityStatMap.getComponentType());
            if (statMap != null) {
                EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
                if (healthStat != null) {
                    entityMaxHealth = String.valueOf(healthStat.getMax());
                    entityCurrentHealth = String.valueOf(healthStat.get());
                    commandContext.sendMessage(Message.raw("  entityMaxHealth:      " + entityMaxHealth));
                    commandContext.sendMessage(Message.raw("  entityCurrentHealth:  " + entityCurrentHealth));
                }
            }
        }

        // Write dump to file
        writeDumpFile(p, ownerUuid, registered, companionRef, entityMaxHealth, entityCurrentHealth);
        commandContext.sendMessage(Message.raw("[Scruby-Debug] Dump written to debug-dumps/ folder."));
    }

    private void writeDumpFile(
            @Nonnull CompanionProfile p,
            @Nonnull UUID ownerUuid,
            boolean registered,
            @javax.annotation.Nullable Ref<EntityStore> companionRef,
            @Nonnull String entityMaxHealth,
            @Nonnull String entityCurrentHealth
    ) {
        try {
            Path dumpDir = Paths.get("debug");
            Files.createDirectories(dumpDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path dumpFile = dumpDir.resolve("scruby-dump_" + timestamp + ".txt");

            StringBuilder sb = new StringBuilder();
            sb.append("=== Scruby Debug Dump ===\n");
            sb.append("Timestamp:            ").append(timestamp).append("\n");
            sb.append("OwnerUUID:            ").append(ownerUuid).append("\n");
            sb.append("\n--- Profile ---\n");
            sb.append("slotId:               ").append(p.getSlotId()).append("\n");
            sb.append("companionType:        ").append(p.getCompanionType()).append("\n");
            sb.append("companionName:        ").append(p.getCompanionName()).append("\n");
            sb.append("companionEntityUuid:  ").append(p.getCompanionEntityUuid().isEmpty() ? "(none)" : p.getCompanionEntityUuid()).append("\n");
            sb.append("entityMissing:        ").append(p.isEntityMissing()).append("\n");
            sb.append("manuallyDespawned:    ").append(p.isManuallyDespawned()).append("\n");
            sb.append("level:                ").append(p.getLevel()).append("\n");
            sb.append("currentXp:            ").append(p.getCurrentXp()).append("\n");
            sb.append("totalXp:              ").append(p.getTotalXp()).append("\n");
            sb.append("pathChoice:           ").append(p.getPathChoice()).append("\n");
            sb.append("evolutionStage:       ").append(p.getEvolutionStage()).append("\n");
            sb.append("fixedAbilities:       ").append(p.getFixedAbilities().isEmpty() ? "(none)" : p.getFixedAbilities()).append("\n");
            sb.append("chosenAbilities:      ").append(p.getChosenAbilities().isEmpty() ? "(none)" : p.getChosenAbilities()).append("\n");
            sb.append("attrPointsAvail:      ").append(p.getAttributePointsAvailable()).append("\n");
            sb.append("attrVitality:         ").append(p.getAttributeVitality()).append("\n");
            sb.append("attrStrength:         ").append(p.getAttributeStrength()).append("\n");
            sb.append("attrZeal:             ").append(p.getAttributeZeal()).append("\n");
            sb.append("killCount:            ").append(p.getKillCount()).append("\n");
            sb.append("deathCount:           ").append(p.getDeathCount()).append("\n");
            sb.append("stationedAtBase:      ").append(p.isStationedAtBase()).append("\n");
            sb.append("guardModeUnlocked:    ").append(p.isGuardModeUnlocked()).append("\n");
            sb.append("freeRespecUsed:       ").append(p.isFreeRespecUsed()).append("\n");
            sb.append("stationMode:          ").append(p.getStationMode()).append("\n");
            sb.append("stationPos:           ").append(p.getStationX()).append(", ").append(p.getStationY()).append(", ").append(p.getStationZ()).append("\n");
            sb.append("stationWorldId:       ").append(p.getStationWorldId().isEmpty() ? "(none)" : p.getStationWorldId()).append("\n");
            sb.append("\n--- Runtime ---\n");
            sb.append("registryActive:       ").append(registered).append("\n");
            sb.append("entityRef:            ").append(companionRef == null ? "null" : (companionRef.isValid() ? "valid" : "invalid")).append("\n");
            sb.append("entityMaxHealth:      ").append(entityMaxHealth).append("\n");
            sb.append("entityCurrentHealth:  ").append(entityCurrentHealth).append("\n");
            sb.append("\n--- Raw JSON ---\n");
            sb.append(p.toJson()).append("\n");

            Files.writeString(dumpFile, sb.toString());
        } catch (IOException e) {
            // Silently fail — this is debug tooling
        }
    }
}
