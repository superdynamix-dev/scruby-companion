package org.example.plugin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.world.PlaySoundEvent2D;
import com.hypixel.hytale.protocol.packets.world.PlaySoundEvent3D;
import com.hypixel.hytale.protocol.packets.world.PlaySoundEventEntity;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central sound service for the Scruby Companion Plugin.
 *
 * Resolves SFX string IDs to packet indices via the Hytale SoundEvent asset registry
 * and sends sound packets to players via their PacketHandler.
 */
public final class ScrubySoundService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // --- Companion Lifecycle ---
    public static final String SFX_BIND             = "SFX_Avatar_Powers_Enable_Local";
    public static final String SFX_SPAWN            = "SFX_Spirit_Root_Spawn";
    public static final String SFX_DESPAWN          = "SFX_Skeleton_Despawn_1";
    public static final String SFX_DEATH            = "SFX_Skeleton_Death_1";
    public static final String SFX_RESPAWN          = "SFX_Divine_Respawn";
    public static final String SFX_SLOT_SWITCH      = "SFX_Portal_Neutral_Teleport_Local";
    public static final String SFX_DELETE           = "SFX_Skeleton_Despawn_2";
    public static final String SFX_STATION          = "SFX_Deployable_Totem_Heal_Spawn";
    public static final String SFX_RECALL           = "SFX_Deployable_Totem_Heal_Despawn";

    // --- Progression ---
    public static final String SFX_XP_GAIN          = "SFX_Crops_Grow_Stage_Complete";
    public static final String SFX_LEVEL_UP         = "SFX_Memories_Unlock_Local";
    public static final String SFX_LEVEL_MAX        = "SFX_Divine_Respawn";
    public static final String SFX_LEVEL_MAX_POWERS = "SFX_Avatar_Powers_Enable_Local";
    public static final String SFX_ATTRIBUTE        = "SFX_Crops_Grow_Stage_Complete";
    public static final String SFX_SKILL_UNLOCK     = "SFX_Memories_Unlock_Local";

    // --- Milestones ---
    public static final String SFX_PATH_CHOICE      = "SFX_Avatar_Powers_Enable";
    public static final String SFX_EVOLUTION_2       = "SFX_Spirit_Root_Spawn";
    public static final String SFX_EVOLUTION_2_GROW  = "SFX_Crops_Grow";
    public static final String SFX_EVOLUTION_3       = "SFX_Golem_Earth_Wake";
    public static final String SFX_SKILL_CHOICE     = "SFX_Deployable_Totem_Heal_Effect_Local";
    public static final String SFX_RESPEC           = "SFX_Avatar_Powers_Disable_Local";

    // --- Prestige ---
    public static final String SFX_PRESTIGE_START   = "SFX_Portal_Neutral_Open";
    public static final String SFX_PRESTIGE_BOSS    = "SFX_Skeleton_Spawn_1";
    public static final String SFX_PRESTIGE_ALERT   = "SFX_Skeleton_Praetorian_Alerted";
    public static final String SFX_PRESTIGE_WIN_1   = "SFX_Divine_Respawn";
    public static final String SFX_PRESTIGE_WIN_2   = "SFX_Avatar_Powers_Enable";
    public static final String SFX_PRESTIGE_WIN_3   = "SFX_Memories_Unlock_Local";
    public static final String SFX_PRESTIGE_LOSS    = "SFX_Player_Death";
    public static final String SFX_PRESTIGE_BATTLE_MUSIC = "SFX_Scruby_Prestige_Battle_Music";

    // --- Error/Feedback ---
    public static final String SFX_ERROR            = "SFX_Generic_Crafting_Failed";
    public static final String SFX_BLOCKED          = "SFX_Unbreakable_Block";

    // --- Combat Skills ---
    public static final String SFX_BURST_ATTACK      = "SFX_Skeleton_Praetorian_Attack_1";
    public static final String SFX_EMERGENCY_HEAL     = "SFX_Deployable_Totem_Heal_Effect_Local";
    public static final String SFX_WAR_CRY            = "SFX_Golem_Earth_Wake";
    public static final String SFX_EMERGENCY_RESCUE   = "SFX_Divine_Respawn";

    /** Cache: SFX string ID -> resolved integer index. */
    private final Map<String, Integer> indexCache = new ConcurrentHashMap<>();

    /**
     * Resolves a sound event string ID to its packet index.
     * Returns -1 if the sound event is not found.
     */
    public int resolveIndex(@Nonnull String sfxId) {
        Integer cached = indexCache.get(sfxId);
        if (cached != null) {
            return cached;
        }

        try {
            int index = SoundEvent.getAssetMap().getIndex(sfxId);
            indexCache.put(sfxId, index);
            return index;
        } catch (Exception e) {
            LOGGER.atWarning().log("[Scruby-Sound] Failed to resolve SFX: " + sfxId + " — " + e.getMessage());
            return -1;
        }
    }

    /**
     * Plays a 2D (non-positional) sound to a single player.
     * Used for UI feedback, local notifications.
     */
    public void play2D(@Nullable PlayerRef playerRef, @Nonnull String sfxId, float volume, float pitch) {
        if (playerRef == null) return;

        int index = resolveIndex(sfxId);
        if (index < 0) return;

        try {
            playerRef.getPacketHandler().write(
                    new PlaySoundEvent2D(index, SoundCategory.SFX, volume, pitch)
            );
        } catch (Exception e) {
            LOGGER.atWarning().log("[Scruby-Sound] Failed to play 2D sound: " + sfxId + " — " + e.getMessage());
        }
    }

    /**
     * Plays a 2D sound with default volume (1.0) and pitch (1.0).
     */
    public void play2D(@Nullable PlayerRef playerRef, @Nonnull String sfxId) {
        play2D(playerRef, sfxId, 1.0f, 1.0f);
    }

    /**
     * Plays a 3D (positional) sound to a single player.
     * Used for world events that should feel spatially located.
     */
    public void play3D(@Nullable PlayerRef playerRef, @Nonnull String sfxId,
                       double x, double y, double z, float volume, float pitch) {
        if (playerRef == null) return;

        int index = resolveIndex(sfxId);
        if (index < 0) return;

        try {
            playerRef.getPacketHandler().write(
                    new PlaySoundEvent3D(index, SoundCategory.SFX, new Position(x, y, z), volume, pitch)
            );
        } catch (Exception e) {
            LOGGER.atWarning().log("[Scruby-Sound] Failed to play 3D sound: " + sfxId + " — " + e.getMessage());
        }
    }

    /**
     * Plays a 3D sound with default volume and pitch at the given position.
     */
    public void play3D(@Nullable PlayerRef playerRef, @Nonnull String sfxId,
                       double x, double y, double z) {
        play3D(playerRef, sfxId, x, y, z, 1.0f, 1.0f);
    }

    // ========================================================================
    // Convenience methods for specific game events
    // ========================================================================

    public void playBind(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_BIND);
    }

    public void playSpawn(@Nullable PlayerRef playerRef, double x, double y, double z) {
        play3D(playerRef, SFX_SPAWN, x, y, z);
    }

    public void playDespawn(@Nullable PlayerRef playerRef, double x, double y, double z) {
        play3D(playerRef, SFX_DESPAWN, x, y, z);
    }

    public void playDeath(@Nullable PlayerRef playerRef, double x, double y, double z) {
        play3D(playerRef, SFX_DEATH, x, y, z);
    }

    public void playRespawn(@Nullable PlayerRef playerRef, double x, double y, double z) {
        play3D(playerRef, SFX_RESPAWN, x, y, z);
    }

    public void playSlotSwitch(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_SLOT_SWITCH);
    }

    public void playDelete(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_DELETE);
    }

    public void playStation(@Nullable PlayerRef playerRef, double x, double y, double z) {
        play3D(playerRef, SFX_STATION, x, y, z);
    }

    public void playRecall(@Nullable PlayerRef playerRef, double x, double y, double z) {
        play3D(playerRef, SFX_RECALL, x, y, z);
    }

    public void playXpGain(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_XP_GAIN, 0.3f, 1.2f);
    }

    public void playLevelUp(@Nullable PlayerRef playerRef, int newLevel) {
        if (newLevel >= 20) {
            // Max level — double combo
            play2D(playerRef, SFX_LEVEL_MAX);
            play2D(playerRef, SFX_LEVEL_MAX_POWERS);
        } else {
            play2D(playerRef, SFX_LEVEL_UP);
        }
    }

    public void playAttribute(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_ATTRIBUTE);
    }

    public void playSkillUnlock(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_SKILL_UNLOCK);
    }

    public void playPathChoice(@Nullable PlayerRef playerRef, double x, double y, double z) {
        play3D(playerRef, SFX_PATH_CHOICE, x, y, z);
    }

    public void playEvolution(@Nullable PlayerRef playerRef, int newStage, double x, double y, double z) {
        if (newStage == 2) {
            play3D(playerRef, SFX_EVOLUTION_2, x, y, z);
            play3D(playerRef, SFX_EVOLUTION_2_GROW, x, y, z);
        } else if (newStage >= 3) {
            play3D(playerRef, SFX_EVOLUTION_3, x, y, z);
        }
    }

    public void playSkillChoice(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_SKILL_CHOICE);
    }

    public void playRespec(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_RESPEC);
    }

    public void playPrestigeStart(@Nullable PlayerRef playerRef, double x, double y, double z) {
        play3D(playerRef, SFX_PRESTIGE_START, x, y, z);
    }

    public void playPrestigeBossSpawn(@Nullable PlayerRef playerRef, double x, double y, double z) {
        play3D(playerRef, SFX_PRESTIGE_BOSS, x, y, z);
        play3D(playerRef, SFX_PRESTIGE_ALERT, x, y, z);
    }

    /**
     * Plays the prestige battle music attached to the boss entity.
     * The music automatically stops when the boss entity is removed (death/despawn).
     *
     * @param playerRef the player to send the sound packet to
     * @param bossNetworkId the boss entity's network ID from {@code NetworkId.getId()}
     */
    public void playPrestigeBattleMusic(@Nullable PlayerRef playerRef, int bossNetworkId) {
        if (playerRef == null) return;

        int index = resolveIndex(SFX_PRESTIGE_BATTLE_MUSIC);
        if (index < 0) {
            LOGGER.atWarning().log("[Scruby-Sound] Custom battle music SoundEvent not registered — "
                    + "SFX_Scruby_Prestige_Battle_Music not found in asset map.");
            return;
        }

        try {
            playerRef.getPacketHandler().write(
                    new PlaySoundEventEntity(index, bossNetworkId, 1.0f, 1.0f)
            );
        } catch (Exception e) {
            LOGGER.atWarning().log("[Scruby-Sound] Failed to play prestige battle music: " + e.getMessage());
        }
    }

    /**
     * Stops the prestige battle music by replaying the same SoundEvent at zero volume.
     * Since MaxInstance is 1, the new silent instance replaces the playing one.
     */
    public void stopPrestigeBattleMusic(@Nullable PlayerRef playerRef) {
        if (playerRef == null) return;

        int index = resolveIndex(SFX_PRESTIGE_BATTLE_MUSIC);
        if (index < 0) return;

        try {
            playerRef.getPacketHandler().write(
                    new PlaySoundEvent2D(index, SoundCategory.SFX, 0.0f, 1.0f)
            );
        } catch (Exception e) {
            LOGGER.atWarning().log("[Scruby-Sound] Failed to stop prestige battle music: " + e.getMessage());
        }
    }

    public void playPrestigeWin(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_PRESTIGE_WIN_1);
        play2D(playerRef, SFX_PRESTIGE_WIN_2);
        play2D(playerRef, SFX_PRESTIGE_WIN_3);
    }

    public void playPrestigeLoss(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_PRESTIGE_LOSS);
    }

    public void playError(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_ERROR);
    }

    public void playBlocked(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_BLOCKED);
    }

    public void playBurstAttack(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_BURST_ATTACK);
    }

    public void playEmergencyHeal(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_EMERGENCY_HEAL);
    }

    public void playWarCry(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_WAR_CRY);
    }

    public void playEmergencyRescue(@Nullable PlayerRef playerRef) {
        play2D(playerRef, SFX_EMERGENCY_RESCUE);
    }
}
