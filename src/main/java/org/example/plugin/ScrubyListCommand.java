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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ScrubyListCommand extends AbstractPlayerCommand {

    private final ScrubyBindingService bindingService;

    public ScrubyListCommand(@Nonnull ScrubyBindingService bindingService) {
        super("scruby-list", "Zeigt alle Companion-Slots an.");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
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
        ScrubyOwnerBindingComponent binding = bindingService.getBindingOrNull(store, ref);
        if (binding == null || !binding.hasBinding()) {
            ctx.sendMessage(Message.raw(ScrubyLang.get(ScrubyLang.DEFAULT_LOCALE, "cmd.list.no_binding")));
            return;
        }

        String locale = binding.getActiveProfile() != null
                ? binding.getActiveProfile().getLocale() : ScrubyLang.DEFAULT_LOCALE;

        List<CompanionProfile> profiles = binding.getProfiles();
        int activeSlot = binding.getActiveSlot();

        ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.list.header", profiles.size(), binding.getMaxSlots())));

        Set<Integer> occupiedSlots = new HashSet<>();
        for (CompanionProfile p : profiles) {
            occupiedSlots.add(p.getSlotId());
        }

        for (int slot = 0; slot < binding.getMaxSlots(); slot++) {
            int displaySlot = slot + 1;
            if (occupiedSlots.contains(slot)) {
                CompanionProfile p = null;
                for (CompanionProfile candidate : profiles) {
                    if (candidate.getSlotId() == slot) {
                        p = candidate;
                        break;
                    }
                }
                if (p != null) {
                    String marker = (slot == activeSlot)
                            ? ScrubyLang.get(locale, "cmd.list.active") : "";
                    String status = p.isStationedAtBase()
                            ? ScrubyLang.get(locale, "cmd.list.stationed") : "";
                    String path = "NONE".equals(p.getPathChoice())
                            ? ScrubyLang.get(locale, "cmd.list.no_path")
                            : ScrubyColors.pathDisplayName(p.getPathChoice(), locale);
                    ctx.sendMessage(Message.raw(
                            ScrubyLang.get(locale, "cmd.list.slot",
                                    displaySlot, marker, status, p.getCompanionName(), p.getLevel(), path)
                    ));
                }
            } else {
                ctx.sendMessage(Message.raw(ScrubyLang.get(locale, "cmd.list.empty", displaySlot)));
            }
        }
    }
}
