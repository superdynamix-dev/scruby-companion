package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ScrubyDebugSetLevelCommand extends AbstractPlayerCommand {

    private static final String CMD_PREFIX = "scruby-debug-setlevel";

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;
    private final ScrubyEvolutionService evolutionService;
    private final ScrubyCompanionResetService resetService;
    private final ScrubyCompanionSpawnService spawnService;
    private final ScrubySkillService skillService;

    public ScrubyDebugSetLevelCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry,
            @Nonnull ScrubyEvolutionService evolutionService,
            @Nonnull ScrubyCompanionResetService resetService,
            @Nonnull ScrubyCompanionSpawnService spawnService,
            @Nonnull ScrubySkillService skillService
    ) {
        super("scruby-debug-setlevel", "Debug: Set companion level.");
        setAllowsExtraArguments(true);
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.evolutionService = Objects.requireNonNull(evolutionService, "evolutionService");
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.spawnService = Objects.requireNonNull(spawnService, "spawnService");
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
        if (binding == null || !binding.hasBinding()) {
            commandContext.sendMessage(Message.raw("[Scruby-Debug] No binding found."));
            return;
        }

        String levelArg = parseArg(commandContext.getInputString());
        if (levelArg == null) {
            commandContext.sendMessage(Message.raw("[Scruby-Debug] Usage: /scruby-debug-setlevel <level>"));
            return;
        }

        int targetLevel;
        try {
            targetLevel = Integer.parseInt(levelArg);
        } catch (NumberFormatException e) {
            commandContext.sendMessage(Message.raw("[Scruby-Debug] Invalid level: " + levelArg));
            return;
        }

        if (targetLevel < 1 || targetLevel > ScrubyXPService.MAX_LEVEL) {
            commandContext.sendMessage(Message.raw("[Scruby-Debug] Level must be 1-" + ScrubyXPService.MAX_LEVEL));
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        profile.setLevel(targetLevel);
        profile.setCurrentXp(0);

        int newStage = evolutionService.calculateEvolutionStage(targetLevel);
        profile.setEvolutionStage(newStage);

        // 2 attribute points per level
        profile.setAttributePointsAvailable(targetLevel * 2);

        // Replay skill unlocks from level 1 to target
        profile.setFixedAbilities("");
        for (int lvl = 1; lvl <= targetLevel; lvl++) {
            List<String> newSkills = skillService.processLevelUp(profile, lvl);
            for (String skillId : newSkills) {
                String desc = skillService.getSkillDescription(skillId, ScrubyLang.DEFAULT_LOCALE);
                commandContext.sendMessage(Message.raw("[Scruby] Neue Faehigkeit: " + skillId + "! (" + desc + ")"));
            }
        }

        binding.setActiveProfile(profile);

        // Despawn existing companion and respawn with correct role
        UUID ownerUuid = playerRef.getUuid();
        Ref<EntityStore> companionRef = registry.getCompanionRef(ownerUuid);
        if (companionRef != null && companionRef.isValid()) {
            NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
            if (npcEntity != null) {
                resetService.clearLockedTarget(store, companionRef);
                npcEntity.remove();
            }
            registry.unregister(ownerUuid);

            profile.setCompanionEntityUuid("");
            profile.setEntityMissing(false);
            profile.setManuallyDespawned(false);
            binding.setActiveProfile(profile);
        }

        UUID newUuid = spawnService.spawnCompanion(store, ref, playerRef, ownerUuid);

        String roleName = evolutionService.getRoleNameForProfile(profile);
        commandContext.sendMessage(Message.raw("[Scruby-Debug] Level auf " + targetLevel + " gesetzt. Stage: " + newStage
                + ". Attribut-Punkte: " + targetLevel + ". Role: " + roleName
                + ". Spawn: " + (newUuid != null ? "OK" : "FAILED")));
    }

    @Nullable
    private static String parseArg(@Nonnull String inputString) {
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
