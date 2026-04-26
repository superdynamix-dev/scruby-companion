package org.example.plugin;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;

import javax.annotation.Nonnull;

/**
 * Gemeinsame Slot-Berechnung für Spawn und Reposition.
 *
 * MVP-Festlegung:
 * - 1.5 Blöcke hinter dem Owner
 * - 0.75 Blöcke rechts vom Owner
 */
public final class ScrubyFollowSlots {

    private ScrubyFollowSlots() {
    }

    @Nonnull
    public static Vector3d computeDefaultFollowSlot(
            @Nonnull Vector3d ownerPosition,
            @Nonnull Vector3f ownerRotation
    ) {
        ScrubyConfigService config = ScrubyCompanionPlugin.instance().getConfigService();
        double backwardDistance = config.getFollowDistanceBackward();
        double rightDistance = config.getFollowDistanceRight();

        double yawRadians = ownerRotation.getYaw();

        double forwardX = -Math.sin(yawRadians);
        double forwardZ = -Math.cos(yawRadians);

        double rightX = Math.cos(yawRadians);
        double rightZ = -Math.sin(yawRadians);

        Vector3d slotPosition = new Vector3d();
        slotPosition.assign(ownerPosition);

        slotPosition.setX(
                ownerPosition.getX()
                        - (forwardX * backwardDistance)
                        + (rightX * rightDistance)
        );

        slotPosition.setZ(
                ownerPosition.getZ()
                        - (forwardZ * backwardDistance)
                        + (rightZ * rightDistance)
        );

        return slotPosition;
    }

    /**
     * Berechnet Spawn-Position VOR dem Owner, mit Blickrichtung zum Owner.
     * Wird beim initialen Spawn, Recall und Respawn verwendet.
     *
     * @return Array: [0] = position (Vector3d), [1] = rotation (Vector3f) facing the owner
     */
    @Nonnull
    public static Object[] computeSpawnFacingSlot(
            @Nonnull Vector3d ownerPosition,
            @Nonnull Vector3f ownerRotation
    ) {
        double spawnDistanceForward = 5.0;

        double yawRadians = ownerRotation.getYaw();

        double forwardX = -Math.sin(yawRadians);
        double forwardZ = -Math.cos(yawRadians);

        Vector3d spawnPosition = new Vector3d();
        spawnPosition.assign(ownerPosition);

        spawnPosition.setX(ownerPosition.getX() + (forwardX * spawnDistanceForward));
        spawnPosition.setZ(ownerPosition.getZ() + (forwardZ * spawnDistanceForward));

        // Scruby faces the player: yaw + π (radians)
        float facingYaw = ownerRotation.getYaw() + (float) Math.PI;
        if (facingYaw >= (float) (2.0 * Math.PI)) facingYaw -= (float) (2.0 * Math.PI);
        Vector3f spawnRotation = new Vector3f(0f, facingYaw, 0f);

        return new Object[] { spawnPosition, spawnRotation };
    }
}
