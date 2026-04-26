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
import java.util.Set;
import java.util.UUID;

public final class ScrubyHudPositionCommand extends AbstractPlayerCommand {

    private static final Set<String> VALID_ARGS = Set.of("tl", "tr", "bl");

    private final ScrubyBindingService bindingService;
    private final ScrubyHudManager hudManager;
    private final ScrubyActiveCompanionRegistry companionRegistry;

    @Override
    protected boolean canGeneratePermission() { return false; }

    public ScrubyHudPositionCommand(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyHudManager hudManager,
            @Nonnull ScrubyActiveCompanionRegistry companionRegistry
    ) {
        super("scruby-hud", "Move companion HUD to a screen corner (tl, tr, bl, br).");
        setAllowsExtraArguments(true);
        this.bindingService = bindingService;
        this.hudManager = hudManager;
        this.companionRegistry = companionRegistry;
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        UUID uuid = playerRef.getUuid();
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ref);
        if (binding == null) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.spawn.no_binding")));
            return;
        }

        String locale = binding.getActiveProfile().getLocale();
        String input = ctx.getInputString().trim();
        String arg = input.contains(" ") ? input.substring(input.indexOf(' ') + 1).trim().toLowerCase() : "";

        if ("br".equals(arg)) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.hud.br_blocked")));
            return;
        }

        if (arg.isEmpty() || !VALID_ARGS.contains(arg)) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.hud.usage")));
            return;
        }

        String position = switch (arg) {
            case "tl" -> "TOP_LEFT";
            case "bl" -> "BOTTOM_LEFT";
            default -> "TOP_RIGHT";
        };

        binding.setHudPosition(position);
        ScrubyCompanionPlugin.savePlayerAsync(store, ref, uuid);

        hudManager.setHudPosition(uuid, position);
        // Reset update delay so the ticking system applies the new position on the next cycle
        hudManager.resetUpdateDelay(uuid);

        String label = ScrubyLang.get(locale, "cmd.hud." + arg);
        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.hud.moved", label)));
    }
}
