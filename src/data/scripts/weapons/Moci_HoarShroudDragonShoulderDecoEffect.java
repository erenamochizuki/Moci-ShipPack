package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

public class Moci_HoarShroudDragonShoulderDecoEffect extends Moci_MechShoulderDecoWeaponEffect {
    private static final String SHOULDER_DOWN_PREFIX = "SHOULDER_DOWN_";
    private static final String SHOULDER_LIGHT_PREFIX = "SHOULDER_LIGHT_";

    private WeaponAPI shoulderDown;
    private WeaponAPI shoulderLight;
    private String sideSuffix = "";

    @Override
    protected void onInitialized(WeaponAPI weapon) {
        super.onInitialized(weapon);
        if (weapon == null || weapon.getSlot() == null) {
            return;
        }

        sideSuffix = extractSideSuffix(weapon.getSlot().getId());
        ShipAPI ship = weapon.getShip();
        shoulderDown = findSlotWeapon(ship, SHOULDER_DOWN_PREFIX + sideSuffix);
        shoulderLight = findSlotWeapon(ship, SHOULDER_LIGHT_PREFIX + sideSuffix);
        suspendAutoTurning(shoulderDown);
        suspendAutoTurning(shoulderLight);
    }

    @Override
    protected void onAdvance(WeaponAPI weapon, float amount, CombatEngineAPI engine) {
        super.onAdvance(weapon, amount, engine);
        if (weapon == null) {
            return;
        }

        float targetAngle = weapon.getCurrAngle();
        applyAngle(shoulderDown, targetAngle);
        applyAngle(shoulderLight, targetAngle);
    }

    private String extractSideSuffix(String slotId) {
        if (slotId == null) {
            return "";
        }
        int lastUnderscore = slotId.lastIndexOf('_');
        return lastUnderscore < 0 ? slotId : slotId.substring(lastUnderscore + 1);
    }

    private WeaponAPI findSlotWeapon(ShipAPI ship, String slotId) {
        if (ship == null || slotId == null || slotId.isEmpty()) {
            return null;
        }
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.getSlot() != null && slotId.equals(w.getSlot().getId())) {
                return w;
            }
        }
        return null;
    }

    private void suspendAutoTurning(WeaponAPI weapon) {
        if (weapon != null) {
            weapon.setSuspendAutomaticTurning(true);
        }
    }

    private void applyAngle(WeaponAPI weapon, float angle) {
        if (weapon != null) {
            weapon.setCurrAngle(angle);
        }
    }
}
