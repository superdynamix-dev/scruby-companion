package org.example.plugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-Registry für aktive Companion-Entities.
 *
 * Owner UUID -> Companion Ref<EntityStore>
 */
public final class ScrubyActiveCompanionRegistry {

    private final Map<UUID, Ref<EntityStore>> activeCompanions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> strengthByOwner = new ConcurrentHashMap<>();

    public void register(
            @Nonnull UUID ownerUuid,
            @Nonnull Ref<EntityStore> companionRef
    ) {
        activeCompanions.put(ownerUuid, companionRef);
    }

    public void unregister(@Nonnull UUID ownerUuid) {
        activeCompanions.remove(ownerUuid);
        strengthByOwner.remove(ownerUuid);
    }

    public void setStrength(@Nonnull UUID ownerUuid, int points) {
        if (points > 0) {
            strengthByOwner.put(ownerUuid, points);
        } else {
            strengthByOwner.remove(ownerUuid);
        }
    }

    public int getStrength(@Nonnull UUID ownerUuid) {
        Integer v = strengthByOwner.get(ownerUuid);
        return v == null ? 0 : v;
    }

    @Nullable
    public Ref<EntityStore> getCompanionRef(@Nonnull UUID ownerUuid) {
        return activeCompanions.get(ownerUuid);
    }

    public boolean hasActiveCompanion(@Nonnull UUID ownerUuid) {
        return activeCompanions.containsKey(ownerUuid);
    }

    /**
     * Returns true if the given entity ref is a registered companion.
     */
    public boolean isCompanion(@Nonnull Ref<EntityStore> ref) {
        for (Ref<EntityStore> companionRef : activeCompanions.values()) {
            if (companionRef.getIndex() == ref.getIndex()) return true;
        }
        return false;
    }

    @Nonnull
    public List<Map.Entry<UUID, Ref<EntityStore>>> entriesSnapshot() {
        return new ArrayList<>(activeCompanions.entrySet());
    }
}
