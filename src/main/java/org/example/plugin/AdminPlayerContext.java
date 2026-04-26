package org.example.plugin;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public final class AdminPlayerContext {

    private final UUID playerUuid;
    private final String playerName;
    private final boolean online;
    @Nullable private final Store<EntityStore> store;
    @Nullable private final Ref<EntityStore> playerRef;
    @Nullable private final PlayerRef onlinePlayerRef;
    @Nullable private final Holder<EntityStore> offlineHolder;
    @Nonnull private final ScrubyOwnerBindingComponent binding;

    /** Online player constructor. */
    public AdminPlayerContext(
            @Nonnull UUID playerUuid,
            @Nonnull String playerName,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull PlayerRef onlinePlayerRef,
            @Nonnull ScrubyOwnerBindingComponent binding
    ) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.online = true;
        this.store = store;
        this.playerRef = playerRef;
        this.onlinePlayerRef = onlinePlayerRef;
        this.offlineHolder = null;
        this.binding = binding;
    }

    /** Offline player constructor. */
    public AdminPlayerContext(
            @Nonnull UUID playerUuid,
            @Nonnull String playerName,
            @Nonnull Holder<EntityStore> offlineHolder,
            @Nonnull ScrubyOwnerBindingComponent binding
    ) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.online = false;
        this.store = null;
        this.playerRef = null;
        this.onlinePlayerRef = null;
        this.offlineHolder = offlineHolder;
        this.binding = binding;
    }

    @Nonnull public UUID getPlayerUuid() { return playerUuid; }
    @Nonnull public String getPlayerName() { return playerName; }
    public boolean isOnline() { return online; }
    @Nullable public Store<EntityStore> getStore() { return store; }
    @Nullable public Ref<EntityStore> getPlayerRef() { return playerRef; }
    @Nullable public PlayerRef getOnlinePlayerRef() { return onlinePlayerRef; }
    @Nullable public Holder<EntityStore> getOfflineHolder() { return offlineHolder; }
    @Nonnull public ScrubyOwnerBindingComponent getBinding() { return binding; }
}
