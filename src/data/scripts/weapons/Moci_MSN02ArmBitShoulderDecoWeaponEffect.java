package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * MSN-02 shoulder decorative sync for the wire-guided arms.
 *
 * <p>SHOULDER_L/R are shoulder cover weapons. They follow the explicit shoulder
 * station modules ARMBIT_SHOULDER_MODULE_L/R; those modules are updated by
 * Moci_MSN02ArmBitControlEffect from the limited hand module angle.</p>
 */
public class Moci_MSN02ArmBitShoulderDecoWeaponEffect implements EveryFrameWeaponEffectPlugin {
    private boolean init = false;
    private ShipAPI parentShip = null;
    private ShipAPI trackedArmModule = null;
    private boolean ignoreThisWeapon = false;
    private boolean leftSide = false;
    private boolean rightSide = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null || weapon.getShip() == null) {
            return;
        }

        if (!init) {
            init = true;
            initialize(weapon);
        }

        if (ignoreThisWeapon) {
            return;
        }

        if (parentShip == null || trackedArmModule == null || !trackedArmModule.isAlive()) {
            return;
        }

        float shipFacing = parentShip.getFacing();
        float armOffset = getModuleRelativeAngle(trackedArmModule, parentShip);
        float targetAngle = normalize360(shipFacing + armOffset);

        weapon.setCurrAngle(targetAngle);
    }

    private void initialize(WeaponAPI weapon) {
        if (weapon.getShip().getParentStation() != null) {
            ignoreThisWeapon = true;
            return;
        }

        parentShip = weapon.getShip().getParentStation() != null
                ? weapon.getShip().getParentStation()
                : weapon.getShip();

        detectSide(weapon);

        String trackedSlotId = leftSide ? "ARMBIT_SHOULDER_MODULE_L" : rightSide ? "ARMBIT_SHOULDER_MODULE_R" : null;
        String fallbackSlotId = leftSide ? "ARMBIT_WPL" : rightSide ? "ARMBIT_WPR" : null;
        if (trackedSlotId != null) {
            for (ShipAPI module : parentShip.getChildModulesCopy()) {
                if (module != null && module.getStationSlot() != null
                        && trackedSlotId.equals(module.getStationSlot().getId())) {
                    trackedArmModule = module;
                    break;
                }
            }
        }
        if (trackedArmModule == null && fallbackSlotId != null) {
            for (ShipAPI module : parentShip.getChildModulesCopy()) {
                if (module != null && module.getStationSlot() != null
                        && fallbackSlotId.equals(module.getStationSlot().getId())) {
                    trackedArmModule = module;
                    break;
                }
            }
        }

        weapon.setSuspendAutomaticTurning(true);
    }

    private void detectSide(WeaponAPI weapon) {
        String slotId = weapon.getSlot() != null ? weapon.getSlot().getId() : "";
        String weaponId = weapon.getId() != null ? weapon.getId().toLowerCase() : "";
        String stationSlotId = weapon.getShip().getStationSlot() != null
                ? weapon.getShip().getStationSlot().getId()
                : "";

        leftSide = slotId.endsWith("_L")
                || slotId.endsWith("LEFT")
                || stationSlotId.endsWith("LEFT")
                || stationSlotId.endsWith("WPL")
                || weaponId.contains("left");
        rightSide = slotId.endsWith("_R")
                || slotId.endsWith("RIGHT")
                || stationSlotId.endsWith("RIGHT")
                || stationSlotId.endsWith("WPR")
                || weaponId.contains("right");
    }

    private static float getModuleRelativeAngle(ShipAPI module, ShipAPI parent) {
        if (module.getStationSlot() != null) {
            return normalizeAngle(module.getStationSlot().getAngle());
        }
        return normalizeAngle(module.getFacing() - parent.getFacing());
    }

    private static float normalizeAngle(float angle) {
        while (angle > 180f) angle -= 360f;
        while (angle < -180f) angle += 360f;
        return angle;
    }

    private static float normalize360(float angle) {
        while (angle >= 360f) angle -= 360f;
        while (angle < 0f) angle += 360f;
        return angle;
    }
}
