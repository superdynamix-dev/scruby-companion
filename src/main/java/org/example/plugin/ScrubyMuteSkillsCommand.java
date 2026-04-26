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

/**
 * Player command {@code /scruby-mute-skills} — toggles skill activation
 * chat messages (Double Shot, War Cry, First Aid, etc.).
 * State is persistent (stored in binding component).
 */
public final class ScrubyMuteSkillsCommand extends AbstractPlayerCommand {

    private static final String MUTE_FLAG = "skills";

    private final ScrubyBindingService bindingService;

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    public ScrubyMuteSkillsCommand(@Nonnull ScrubyBindingService bindingService) {
        super("scruby-mute-skills", "Toggle skill activation chat messages.");
        this.bindingService = Objects.requireNonNull(bindingService);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ref);
        if (binding == null) return;

        boolean currentlyMuted = binding.isMuted(MUTE_FLAG);
        boolean newState = !currentlyMuted;
        binding.setMuted(MUTE_FLAG, newState);

        String locale = ScrubyLang.DEFAULT_LOCALE;

        if (newState) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.mute_skills.on")));
        } else {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.mute_skills.off")));
        }
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, playerRef.getUuid());
    }

    /**
     * Returns {@code true} if the given player has muted skill messages.
     */
    public static boolean isMuted(@Nonnull ScrubyOwnerBindingComponent binding) {
        return binding.isMuted(MUTE_FLAG);
    }
}
