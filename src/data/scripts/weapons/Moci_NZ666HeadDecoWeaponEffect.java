package data.scripts.weapons;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * NZ-666 head controller with a ten-frame headlight.
 *
 * Frame contract:
 * - Frame 0: far left.
 * - Frame 4: default / straight ahead.
 * - Frame 9: far right.
 */
public class Moci_NZ666HeadDecoWeaponEffect extends Moci_MechHeadDecoWeaponEfect {
    private static final String HEAD_LIGHT_SLOT_ID = "HEAD_LIGHT";
    private static final float FOLLOW_RATIO = 0.4f;
    private static final float ROTATION_SPEED = 8f;
    private static final float FRAME_STEP = 15f;
    private static final int DEFAULT_FRAME = 4;

    private boolean initialized = false;
    private WeaponAPI headLightWeapon;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        if (!initialized) {
            initialized = true;
            initializeHeadLight(ship);
        }

        ShipAPI target = getTarget(ship);
        if (target != null) {
            float targetAngle = VectorUtils.getAngle(weapon.getLocation(), target.getLocation());
            float shipFacing = ship.getFacing();
            float targetRelativeToShip = MathUtils.getShortestRotation(shipFacing, targetAngle);
            float desiredAngle = shipFacing + targetRelativeToShip * FOLLOW_RATIO;

            turnToward(weapon, desiredAngle, amount);
            syncHeadLight(weapon, getHeadLightFrame(targetRelativeToShip));
        } else {
            turnToward(weapon, ship.getFacing(), amount);
            syncHeadLight(weapon, DEFAULT_FRAME);
        }
    }

    private void initializeHeadLight(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon != null && weapon.getSlot() != null
                    && HEAD_LIGHT_SLOT_ID.equals(weapon.getSlot().getId())) {
                headLightWeapon = weapon;
                return;
            }
        }
    }

    private ShipAPI getTarget(ShipAPI ship) {
        ShipAPI target = ship.getShipTarget();
        if (ship.getShipAI() != null && ship.getAIFlags().hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
            Object custom = ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM);
            if (custom instanceof ShipAPI) {
                target = (ShipAPI) custom;
            }
        }
        return target;
    }

    private void turnToward(WeaponAPI weapon, float desiredAngle, float amount) {
        float currentAngle = weapon.getCurrAngle();
        float rotation = MathUtils.getShortestRotation(currentAngle, desiredAngle);
        float maxRotation = ROTATION_SPEED * amount;
        if (Math.abs(rotation) > maxRotation) {
            rotation = Math.signum(rotation) * maxRotation;
        }
        weapon.setCurrAngle(currentAngle + rotation);
    }

    private int getHeadLightFrame(float targetRelativeToShip) {
        int frame = Math.round(DEFAULT_FRAME + targetRelativeToShip / FRAME_STEP);
        return Math.max(0, Math.min(9, frame));
    }

    private void syncHeadLight(WeaponAPI headWeapon, int frame) {
        if (headLightWeapon == null) {
            return;
        }

        headLightWeapon.setCurrAngle(headWeapon.getCurrAngle());
        if (headLightWeapon.getAnimation() != null) {
            headLightWeapon.getAnimation().pause();
            int maxFrame = Math.max(0, headLightWeapon.getAnimation().getNumFrames() - 1);
            headLightWeapon.getAnimation().setFrame(Math.max(0, Math.min(frame, maxFrame)));
        }
    }
}
