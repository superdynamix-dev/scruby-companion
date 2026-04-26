package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

public final class ScrubyCompanionResetService {

    private static final String LOCKED_TARGET_SLOT = "LockedTarget";

    private final ScrubyBindingService bindingService;
    private final ScrubyActiveCompanionRegistry registry;

    public ScrubyCompanionResetService(
            @Nonnull ScrubyBindingService bindingService,
            @Nonnull ScrubyActiveCompanionRegistry registry
    ) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void resetOwnerSession(@Nonnull UUID ownerUuid) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        this.registry.unregister(ownerUuid);
    }

    public boolean clearLockedTarget(
            @Nonnull Store<EntityStore> store,
            @Nullable Ref<EntityStore> companionRef
    ) {
        Objects.requireNonNull(store, "store");

        if (companionRef == null || !companionRef.isValid()) {
            return false;
        }

        NPCEntity npcEntity = store.getComponent(companionRef, NPCEntity.getComponentType());
        if (npcEntity == null) {
            return false;
        }

        Role role = npcEntity.getRole();
        if (role == null) {
            return false;
        }

        return clearLockedTarget(role.getMarkedEntitySupport());
    }

    private static boolean clearLockedTarget(@Nullable MarkedEntitySupport markedEntitySupport) {
        if (markedEntitySupport == null) {
            return false;
        }

        int slotCount = markedEntitySupport.getMarkedEntitySlotCount();
        boolean cleared = false;

        for (int i = 0; i < slotCount; i++) {
            String slotName = safeGetSlotName(markedEntitySupport, i);
            if (!LOCKED_TARGET_SLOT.equals(slotName)) {
                continue;
            }

            if (markedEntitySupport.getMarkedEntityRef(i) != null) {
                cleared = true;
            }

            markedEntitySupport.clearMarkedEntity(i);
        }

        return cleared;
    }

    @Nullable
    private static String safeGetSlotName(
            @Nonnull MarkedEntitySupport markedEntitySupport,
            int slotIndex
    ) {
        try {
            return markedEntitySupport.getSlotName(slotIndex);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
