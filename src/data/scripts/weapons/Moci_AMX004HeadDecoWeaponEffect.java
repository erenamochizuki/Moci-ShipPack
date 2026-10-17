package data.scripts.weapons;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Qubeley head controller with a dedicated six-frame headlight.
 */
public class Moci_AMX004HeadDecoWeaponEffect extends Moci_MechHeadDecoWeaponEfect {
    private static final float FOLLOW_RATIO = 0.4f;
    private static final float ROTATION_SPEED = 8f;
    private static final float FRAME_STEP = 20f;
    private static final String HEAD_LIGHT_SLOT_ID = "HEAD_LIGHT";

    private WeaponAPI headLightWeapon;
    private boolean init = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        if (!init) {
            init = true;
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
            syncHeadLight(weapon, 2);
        }
    }

    private void initializeHeadLight(ShipAPI ship) {
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w != null && w.getSlot() != null && HEAD_LIGHT_SLOT_ID.equals(w.getSlot().getId())) {
                headLightWeapon = w;
                break;
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
        if (targetRelativeToShip <= -FRAME_STEP * 2f) {
            return 0;
        }
        if (targetRelativeToShip <= -FRAME_STEP) {
            return 1;
        }
        if (targetRelativeToShip < 0f) {
            return 2;
        }
        if (targetRelativeToShip < FRAME_STEP) {
            return 3;
        }
        if (targetRelativeToShip < FRAME_STEP * 2f) {
            return 4;
        }
        return 5;
    }

    private void syncHeadLight(WeaponAPI head, int frame) {
        if (headLightWeapon == null) {
            return;
        }

        headLightWeapon.setCurrAngle(head.getCurrAngle());
        if (headLightWeapon.getAnimation() != null) {
            headLightWeapon.getAnimation().pause();
            int maxFrame = Math.max(0, headLightWeapon.getAnimation().getNumFrames() - 1);
            headLightWeapon.getAnimation().setFrame(Math.max(0, Math.min(frame, maxFrame)));
        }
    }
}
