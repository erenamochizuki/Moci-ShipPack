package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Qubeley shoulder controller. The central shoulder keeps the normal mech
 * shoulder behavior, and the front/back plates open during same-side strafing.
 */
public class Moci_AMX004ShoulderDecoWeaponEffect extends Moci_MechShoulderDecoWeaponEffect_MS {
    private static final float ROTATION_SPEED = 60f;
    private static final float FRONT_MAX_SPREAD = 10f;
    private static final float BACK_MAX_SPREAD = 20f;

    private WeaponAPI front;
    private WeaponAPI back;
    private boolean leftSide = false;
    private boolean rightSide = false;
    private float frontSpread = 0f;
    private float backSpread = 0f;

    @Override
    protected void onInitialized(WeaponAPI weapon) {
        super.onInitialized(weapon);

        String slotId = weapon.getSlot().getId();
        leftSide = slotId.endsWith("_L");
        rightSide = slotId.endsWith("_R");

        ShipAPI ship = weapon.getShip();
        String prefix = leftSide ? "SHOULDER_L" : rightSide ? "SHOULDER_R" : null;
        if (ship == null || prefix == null) {
            return;
        }

        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w == null || w.getSlot() == null) {
                continue;
            }
            String otherSlotId = w.getSlot().getId();
            if ((prefix + "_F").equals(otherSlotId)) {
                front = w;
                front.setSuspendAutomaticTurning(true);
            } else if ((prefix + "_B").equals(otherSlotId)) {
                back = w;
                back.setSuspendAutomaticTurning(true);
            }
        }
    }

    @Override
    protected void onAdvance(WeaponAPI weapon, float amount, CombatEngineAPI engine) {
        ShipAPI ship = weapon.getShip();
        if (ship == null || ship.getEngineController() == null) {
            return;
        }

        boolean active = false;
        if (rightSide) {
            active = ship.getEngineController().isStrafingRight() && !ship.getEngineController().isStrafingLeft();
        } else if (leftSide) {
            active = ship.getEngineController().isStrafingLeft() && !ship.getEngineController().isStrafingRight();
        }

        float frontTarget = active ? FRONT_MAX_SPREAD : 0f;
        float backTarget = active ? BACK_MAX_SPREAD : 0f;
        frontSpread = approach(frontSpread, frontTarget, ROTATION_SPEED * amount);
        backSpread = approach(backSpread, backTarget, ROTATION_SPEED * amount);
        applyPlateAngles(weapon);
    }

    private void applyPlateAngles(WeaponAPI shoulder) {
        if (front == null && back == null) {
            return;
        }

        float shipFacing = shoulder.getShip().getFacing();
        float baseOffset = normalizeAngle(shoulder.getCurrAngle() - shipFacing);
        float sideSign = rightSide ? 1f : -1f;

        if (front != null) {
            front.setCurrAngle(shipFacing + baseOffset + frontSpread * sideSign);
        }
        if (back != null) {
            back.setCurrAngle(shipFacing + baseOffset - backSpread * sideSign);
        }
    }

    private float approach(float current, float target, float step) {
        if (current < target) {
            return Math.min(current + step, target);
        }
        if (current > target) {
            return Math.max(current - step, target);
        }
        return current;
    }

    private float normalizeAngle(float angle) {
        while (angle > 180f) {
            angle -= 360f;
        }
        while (angle < -180f) {
            angle += 360f;
        }
        return angle;
    }
}
