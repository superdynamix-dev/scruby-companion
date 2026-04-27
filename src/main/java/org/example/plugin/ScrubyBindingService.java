package org.example.plugin;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

public final class ScrubyBindingService {

    private final ComponentType<EntityStore, ScrubyOwnerBindingComponent> bindingComponentType;
    private final ScrubyConfigService configService;

    public ScrubyBindingService(
            @Nonnull ComponentType<EntityStore, ScrubyOwnerBindingComponent> bindingComponentType,
            @Nonnull ScrubyConfigService configService
    ) {
        this.bindingComponentType = Objects.requireNonNull(bindingComponentType, "bindingComponentType");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    @Nonnull
    public ScrubyOwnerBindingComponent ensureBinding(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(ownerRef, "ownerRef");
        ScrubyOwnerBindingComponent binding = store.ensureAndGetComponent(ownerRef, this.bindingComponentType);
        // Sync player slots to configured max (upgrade and downgrade)
        int maxSlots = configService.getMaxCompanionSlots();
        if (binding.getMaxSlots() != maxSlots) {
            binding.setMaxSlots(maxSlots);
        }
        return binding;
    }

    @Nullable
    public ScrubyOwnerBindingComponent getBindingOrNull(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(ownerRef, "ownerRef");
        return store.getComponent(ownerRef, this.bindingComponentType);
    }

    @Nullable
    public ScrubyOwnerBindingComponent getBindingOrNull(
            @Nonnull Holder<EntityStore> ownerHolder
    ) {
        Objects.requireNonNull(ownerHolder, "ownerHolder");
        return ownerHolder.getComponent(this.bindingComponentType);
    }

    @Nonnull
    public ScrubyOwnerBindingComponent bindOwner(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull UUID ownerPlayerUuid
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(ownerRef, "ownerRef");
        Objects.requireNonNull(ownerPlayerUuid, "ownerPlayerUuid");

        ScrubyOwnerBindingComponent binding = ensureBinding(store, ownerRef);

        if (!binding.hasBinding()) {
            binding.setOwnerPlayerUuid(ownerPlayerUuid.toString());
            binding.setBindingId(UUID.randomUUID().toString());
            binding.ensureActiveProfile();
            return binding;
        }

        if (!binding.getOwnerPlayerUuid().equals(ownerPlayerUuid.toString())) {
            throw new IllegalStateException(
                    "Existing Scruby binding belongs to a different owner. " +
                            "Stored owner=" + binding.getOwnerPlayerUuid() +
                            ", requested owner=" + ownerPlayerUuid
            );
        }

        return binding;
    }

    @Nonnull
    public ScrubyOwnerBindingComponent attachCompanionEntity(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull UUID ownerPlayerUuid,
            @Nonnull UUID companionEntityUuid
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(ownerRef, "ownerRef");
        Objects.requireNonNull(ownerPlayerUuid, "ownerPlayerUuid");
        Objects.requireNonNull(companionEntityUuid, "companionEntityUuid");

        ScrubyOwnerBindingComponent binding = bindOwner(store, ownerRef, ownerPlayerUuid);
        CompanionProfile profile = binding.ensureActiveProfile();
        profile.setCompanionEntityUuid(companionEntityUuid.toString());
        profile.setEntityMissing(false);
        binding.setActiveProfile(profile);

        return binding;
    }

    @Nonnull
    public ScrubyOwnerBindingComponent markCompanionEntityMissing(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(ownerRef, "ownerRef");

        ScrubyOwnerBindingComponent binding = ensureBinding(store, ownerRef);
        CompanionProfile profile = binding.getActiveProfile();
        profile.clearCompanionEntityReference();
        binding.setActiveProfile(profile);
        return binding;
    }

    @Nonnull
    public ScrubyOwnerBindingComponent resetBinding(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(ownerRef, "ownerRef");

        ScrubyOwnerBindingComponent binding = ensureBinding(store, ownerRef);
        binding.clearAll();
        return binding;
    }

    @Nonnull
    public CompanionProfile addCompanion(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef
    ) {
        ScrubyOwnerBindingComponent binding = ensureBinding(store, ownerRef);
        java.util.List<CompanionProfile> profiles = binding.getProfiles();

        if (profiles.size() >= binding.getMaxSlots()) {
            throw new IllegalStateException("All " + binding.getMaxSlots() + " companion slots are full.");
        }

        // Find next free slotId (0-4)
        java.util.Set<Integer> usedSlots = new java.util.HashSet<>();
        for (CompanionProfile p : profiles) {
            usedSlots.add(p.getSlotId());
        }
        int freeSlot = -1;
        for (int i = 0; i < binding.getMaxSlots(); i++) {
            if (!usedSlots.contains(i)) {
                freeSlot = i;
                break;
            }
        }
        if (freeSlot == -1) {
            throw new IllegalStateException("No free slot found despite size check.");
        }

        CompanionProfile newProfile = new CompanionProfile();
        newProfile.setSlotId(freeSlot);
        // Inherit locale from existing profiles so language stays consistent across slots.
        if (!profiles.isEmpty()) {
            newProfile.setLocale(profiles.get(0).getLocale());
        }
        profiles.add(newProfile);
        binding.setProfiles(profiles);

        return newProfile;
    }

    @Nullable
    public CompanionProfile switchSlot(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            int targetSlot
    ) {
        ScrubyOwnerBindingComponent binding = ensureBinding(store, ownerRef);
        java.util.List<CompanionProfile> profiles = binding.getProfiles();

        for (CompanionProfile p : profiles) {
            if (p.getSlotId() == targetSlot) {
                binding.setActiveSlot(targetSlot);
                return p;
            }
        }
        return null; // slot not found
    }

    public boolean deleteSlot(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ownerRef,
            int targetSlot
    ) {
        ScrubyOwnerBindingComponent binding = ensureBinding(store, ownerRef);
        java.util.List<CompanionProfile> profiles = binding.getProfiles();

        boolean removed = profiles.removeIf(p -> p.getSlotId() == targetSlot);
        if (!removed) {
            return false;
        }

        // Compact slotIds so addCompanion doesn't refill the just-freed slot ahead of shifted siblings.
        for (CompanionProfile p : profiles) {
            if (p.getSlotId() > targetSlot) {
                p.setSlotId(p.getSlotId() - 1);
            }
        }

        binding.setProfiles(profiles);

        int currentActive = binding.getActiveSlot();
        if (currentActive == targetSlot) {
            if (profiles.isEmpty()) {
                binding.clearAll();
            } else {
                binding.setActiveSlot(profiles.get(0).getSlotId());
            }
        } else if (currentActive > targetSlot) {
            binding.setActiveSlot(currentActive - 1);
        }

        return true;
    }

    public int getProfileCount(@Nonnull ScrubyOwnerBindingComponent binding) {
        return binding.getProfiles().size();
    }

    @Nullable
    public CompanionProfile getStationedProfile(@Nonnull ScrubyOwnerBindingComponent binding) {
        for (CompanionProfile p : binding.getProfiles()) {
            if (p.isStationedAtBase()) {
                return p;
            }
        }
        return null;
    }

}
