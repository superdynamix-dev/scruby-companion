package org.example.plugin;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;

/**
 * Persistente Owner-Bindung für Scruby.
 * Companion-spezifische Daten liegen im CompanionProfile (JSON-serialisiert).
 */
public final class ScrubyOwnerBindingComponent implements Component<EntityStore> {

    public static final BuilderCodec<ScrubyOwnerBindingComponent> CODEC =
            BuilderCodec.builder(ScrubyOwnerBindingComponent.class, ScrubyOwnerBindingComponent::new)
                    .append(new KeyedCodec<>("OwnerPlayerUuid", Codec.STRING),
                            (component, value) -> component.ownerPlayerUuid = value,
                            component -> component.ownerPlayerUuid)
                    .addValidator(Validators.nonNull())
                    .add()
                    .append(new KeyedCodec<>("BindingId", Codec.STRING),
                            (component, value) -> component.bindingId = value,
                            component -> component.bindingId)
                    .addValidator(Validators.nonNull())
                    .add()
                    .append(new KeyedCodec<>("ActiveSlot", Codec.INTEGER),
                            (component, value) -> component.activeSlot = value,
                            component -> component.activeSlot)
                    .add()
                    .append(new KeyedCodec<>("MaxSlots", Codec.INTEGER),
                            (component, value) -> component.maxSlots = value,
                            component -> component.maxSlots)
                    .add()
                    .append(new KeyedCodec<>("CompanionProfiles", Codec.STRING),
                            (component, value) -> component.companionProfiles = value,
                            component -> component.companionProfiles)
                    .addValidator(Validators.nonNull())
                    .add()
                    .append(new KeyedCodec<>("DiscoveredMobs", Codec.STRING),
                            (component, value) -> component.discoveredMobs = value,
                            component -> component.discoveredMobs)
                    .addValidator(Validators.nonNull())
                    .add()
                    .append(new KeyedCodec<>("HudPosition", Codec.STRING),
                            (component, value) -> component.hudPosition = value,
                            component -> component.hudPosition)
                    .addValidator(Validators.nonNull())
                    .add()
                    .append(new KeyedCodec<>("MuteFlags", Codec.STRING),
                            (component, value) -> component.muteFlags = value,
                            component -> component.muteFlags)
                    .addValidator(Validators.nonNull())
                    .add()
                    .build();

    private String ownerPlayerUuid;
    private String bindingId;
    private int activeSlot;
    private int maxSlots;
    private String companionProfiles;
    private String discoveredMobs;
    private String hudPosition;
    private String muteFlags;

    public ScrubyOwnerBindingComponent() {
        this.ownerPlayerUuid = "";
        this.bindingId = "";
        this.activeSlot = 0;
        this.maxSlots = 5;
        this.companionProfiles = "[]";
        this.discoveredMobs = "";
        this.hudPosition = "TOP_RIGHT";
        this.muteFlags = "";
    }

    public ScrubyOwnerBindingComponent(@Nonnull ScrubyOwnerBindingComponent other) {
        this.ownerPlayerUuid = other.ownerPlayerUuid;
        this.bindingId = other.bindingId;
        this.activeSlot = other.activeSlot;
        this.maxSlots = other.maxSlots;
        this.companionProfiles = other.companionProfiles;
        this.discoveredMobs = other.discoveredMobs;
        this.hudPosition = other.hudPosition;
        this.muteFlags = other.muteFlags;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        return new ScrubyOwnerBindingComponent(this);
    }

    // --- Owner fields ---

    @Nonnull
    public String getOwnerPlayerUuid() { return ownerPlayerUuid; }

    public void setOwnerPlayerUuid(@Nonnull String ownerPlayerUuid) {
        this.ownerPlayerUuid = Objects.requireNonNull(ownerPlayerUuid);
    }

    @Nonnull
    public String getBindingId() { return bindingId; }

    public void setBindingId(@Nonnull String bindingId) {
        this.bindingId = Objects.requireNonNull(bindingId);
    }

    public int getActiveSlot() { return activeSlot; }

    public void setActiveSlot(int activeSlot) { this.activeSlot = activeSlot; }

    public int getMaxSlots() { return maxSlots; }

    public void setMaxSlots(int maxSlots) { this.maxSlots = maxSlots; }

    @Nonnull
    public String getCompanionProfilesJson() { return companionProfiles; }

    public void setCompanionProfilesJson(@Nonnull String companionProfiles) {
        this.companionProfiles = Objects.requireNonNull(companionProfiles);
    }

    // --- Discovered mobs (player-wide, shared across all companions) ---

    @Nonnull
    public String getDiscoveredMobs() { return discoveredMobs != null ? discoveredMobs : ""; }

    public void setDiscoveredMobs(@Nonnull String discoveredMobs) {
        this.discoveredMobs = Objects.requireNonNull(discoveredMobs);
    }

    public boolean hasDiscoveredMob(@Nonnull String mobType) {
        String mobs = getDiscoveredMobs();
        if (mobs.isEmpty()) return false;
        for (String s : mobs.split(",")) {
            if (s.equals(mobType)) return true;
        }
        return false;
    }

    public void addDiscoveredMob(@Nonnull String mobType) {
        if (hasDiscoveredMob(mobType)) return;
        String mobs = getDiscoveredMobs();
        discoveredMobs = mobs.isEmpty() ? mobType : mobs + "," + mobType;
    }

    // --- HUD position ---

    @Nonnull
    public String getHudPosition() { return hudPosition != null ? hudPosition : "TOP_RIGHT"; }

    public void setHudPosition(@Nonnull String hudPosition) {
        this.hudPosition = Objects.requireNonNull(hudPosition);
    }

    // --- Mute flags (player-wide, comma-separated: "combat,greeting,inventory") ---

    @Nonnull
    public String getMuteFlags() { return muteFlags != null ? muteFlags : ""; }

    public void setMuteFlags(@Nonnull String muteFlags) {
        this.muteFlags = Objects.requireNonNull(muteFlags);
    }

    public boolean isMuted(@Nonnull String flag) {
        String flags = getMuteFlags();
        if (flags.isEmpty()) return false;
        for (String s : flags.split(",")) {
            if (s.equals(flag)) return true;
        }
        return false;
    }

    public void setMuted(@Nonnull String flag, boolean muted) {
        if (muted) {
            if (!isMuted(flag)) {
                String flags = getMuteFlags();
                muteFlags = flags.isEmpty() ? flag : flags + "," + flag;
            }
        } else {
            String flags = getMuteFlags();
            if (flags.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            for (String s : flags.split(",")) {
                if (!s.equals(flag)) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(s);
                }
            }
            muteFlags = sb.toString();
        }
    }

    // --- Profile access ---

    @Nonnull
    public List<CompanionProfile> getProfiles() {
        return CompanionProfile.listFromJson(this.companionProfiles);
    }

    public void setProfiles(@Nonnull List<CompanionProfile> profiles) {
        this.companionProfiles = CompanionProfile.listToJson(profiles);
    }

    @Nonnull
    public CompanionProfile getActiveProfile() {
        List<CompanionProfile> profiles = getProfiles();
        for (CompanionProfile p : profiles) {
            if (p.getSlotId() == this.activeSlot) {
                return p;
            }
        }
        CompanionProfile defaultProfile = new CompanionProfile();
        defaultProfile.setSlotId(this.activeSlot);
        return defaultProfile;
    }

    public void setActiveProfile(@Nonnull CompanionProfile profile) {
        List<CompanionProfile> profiles = getProfiles();
        boolean found = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).getSlotId() == this.activeSlot) {
                profiles.set(i, profile);
                found = true;
                break;
            }
        }
        if (!found) {
            profile.setSlotId(this.activeSlot);
            profiles.add(profile);
        }
        setProfiles(profiles);
    }

    /**
     * Persists a profile back to its OWN slotId, regardless of the current activeSlot.
     *
     * Use this when saving changes to any profile — active, stationed, or bench — to
     * avoid accidentally overwriting the active slot with a non-active profile (which
     * would duplicate data and corrupt the binding). Prefer this over setActiveProfile
     * in inventory/transfer flows where the profile is not guaranteed to be the active one.
     */
    public void updateProfileBySlot(@Nonnull CompanionProfile profile) {
        List<CompanionProfile> profiles = getProfiles();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).getSlotId() == profile.getSlotId()) {
                profiles.set(i, profile);
                setProfiles(profiles);
                return;
            }
        }
        profiles.add(profile);
        setProfiles(profiles);
    }

    @Nonnull
    public CompanionProfile ensureActiveProfile() {
        List<CompanionProfile> profiles = getProfiles();
        for (CompanionProfile p : profiles) {
            if (p.getSlotId() == this.activeSlot) {
                return p;
            }
        }
        CompanionProfile newProfile = new CompanionProfile();
        newProfile.setSlotId(this.activeSlot);
        profiles.add(newProfile);
        setProfiles(profiles);
        return newProfile;
    }

    // --- Binding state ---

    public boolean hasBinding() {
        return !this.ownerPlayerUuid.isBlank() && !this.bindingId.isBlank();
    }

    public void clearAll() {
        this.ownerPlayerUuid = "";
        this.bindingId = "";
        this.activeSlot = 0;
        this.maxSlots = 5;
        this.companionProfiles = "[]";
        this.discoveredMobs = "";
        this.muteFlags = "";
    }
}
