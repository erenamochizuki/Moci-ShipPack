package data.ai;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.Misc;

import data.hullmods.Moci_RepairBayScript;

/**
 * 返航引导层：不直接接管 ShipAI，只补一个轻量回航意图。
 */
public class Moci_ReturnToCarrierModule {

    public static final String DATA_KEY = "Moci_ReturnToCarrierModule";
    private static final float DEFAULT_HANDOFF_DISTANCE = 450f;

    private final ShipAPI ship;
    private ShipAPI carrier;
    private float targetX;
    private float targetY;
    private float handoffDistance = DEFAULT_HANDOFF_DISTANCE;

    public Moci_ReturnToCarrierModule(ShipAPI ship) {
        this.ship = ship;
    }

    public void setTarget(Moci_RepairBayScript.Moci_RepairBay bay) {
        if (bay == null) {
            clearTarget();
            return;
        }

        float desiredHandoff = Math.max(DEFAULT_HANDOFF_DISTANCE,
                bay.getShip() != null ? bay.getShip().getCollisionRadius() + 180f : DEFAULT_HANDOFF_DISTANCE);
        setTarget(bay.getShip(), bay.getLocation().x, bay.getLocation().y, desiredHandoff);
    }

    public void setTarget(ShipAPI carrier, float targetX, float targetY, float handoffDistance) {
        this.carrier = carrier;
        this.targetX = targetX;
        this.targetY = targetY;
        this.handoffDistance = Math.max(200f, handoffDistance);
    }

    public void clearTarget() {
        this.carrier = null;
        this.handoffDistance = DEFAULT_HANDOFF_DISTANCE;
    }

    public boolean hasTarget() {
        return ship != null && carrier != null;
    }

    public boolean shouldHandOffToLandingAI() {
        if (!hasTarget()) {
            return false;
        }
        Vector2f targetLoc = new Vector2f(targetX, targetY);
        return Misc.getDistanceSq(ship.getLocation(), targetLoc) <= handoffDistance * handoffDistance;
    }

    public void advance(float amount) {
        if (!hasTarget() || !ship.isAlive() || ship.isLanding() || ship.isFinishedLanding()) {
            return;
        }
        if (!carrier.isAlive() || carrier.isHulk()) {
            clearTarget();
            return;
        }

        ShipwideAIFlags flags = ship.getAIFlags();
        if (flags != null) {
            flags.setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, 0.5f);
            flags.setFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE, 0.5f, 10000f);
            flags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, 0.5f);
            flags.unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF);
        }

        Vector2f targetLoc = new Vector2f(targetX, targetY);
        float targetAngle = Misc.getAngleInDegrees(ship.getLocation(), targetLoc);
        float angleDiff = MathUtils.getShortestRotation(ship.getFacing(), targetAngle);
        float distanceSq = Misc.getDistanceSq(ship.getLocation(), targetLoc);

        if (Math.abs(angleDiff) > 5f) {
            if (angleDiff > 0f) {
                ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
            } else {
                ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
            }
        }

        float accelerateDistance = handoffDistance * 0.9f;
        float decelerateDistance = handoffDistance * 0.6f;
        if (distanceSq > accelerateDistance * accelerateDistance) {
            ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        } else if (distanceSq < decelerateDistance * decelerateDistance) {
            ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        }

        if (Math.abs(angleDiff) < 20f) {
            if (angleDiff > 0f) {
                ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            } else if (angleDiff < 0f) {
                ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
            }
        }
    }
}
