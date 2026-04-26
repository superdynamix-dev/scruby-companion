package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Objects;

public final class ScrubyMenuCommand extends AbstractPlayerCommand {

    private final ScrubyBindingService bindingService;
    private final ScrubySkillTreePage skillTreePage;

    public ScrubyMenuCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubySkillTreePage skillTreePage
    ) {
        super("scruby-menu", "Opens the Scruby companion skill tree page.");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.skillTreePage = Objects.requireNonNull(skillTreePage, "skillTreePage");
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
            this.skillTreePage.openNoScruby(store, ref, playerRef);
            return;
        }

        CompanionProfile profile = binding.getActiveProfile();
        this.skillTreePage.open(store, ref, playerRef, profile);
    }
}
