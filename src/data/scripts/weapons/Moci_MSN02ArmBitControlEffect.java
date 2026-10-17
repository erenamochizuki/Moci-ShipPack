package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * MSN-02 hand LineBit controller.
 *
 * <p>Keeps the original advanced LineBit behavior. ARMBIT_L/R are guide weapons;
 * ARMBIT_WPL/WPR are the hand modules that turn at a fixed rate; the shoulder
 * station modules follow the already-limited hand module angle at the normal
 * shoulder ratio.</p>
 */
public class Moci_MSN02ArmBitControlEffect extends Moci_MSN_02_LineBit_Advanced {
    private static final float ARM_MODULE_TURN_RATE = 25f;
    private static final float SHOULDER_FOLLOW_RATIO = 0.5f;

    private boolean mociInit = false;
    private ShipAPI handModule = null;
    private ShipAPI shoulderModule = null;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        super.advance(amount, engine, weapon);

        if (engine == null || engine.isPaused() || weapon == null || weapon.getShip() == null) {
            return;
        }

        if (!mociInit) {
            mociInit = true;
            initializeModules(weapon);
        }

        ShipAPI ship = weapon.getShip();
        float targetRelativeAngle = normalizeAngle(weapon.getCurrAngle() - ship.getFacing());

        Float nextAngle = null;
        if (handModule != null && handModule.isAlive() && handModule.getStationSlot() != null) {
            handModule.ensureClonedStationSlotSpec();
            float current = normalizeAngle(handModule.getStationSlot().getAngle());
            nextAngle = moveTowardAngle(current, targetRelativeAngle, ARM_MODULE_TURN_RATE * amount);
            handModule.getStationSlot().setAngle(nextAngle);
        }

        if (shoulderModule != null && shoulderModule.isAlive() && shoulderModule.getStationSlot() != null) {
            shoulderModule.ensureClonedStationSlotSpec();
            float angle = nextAngle != null
                    ? nextAngle * SHOULDER_FOLLOW_RATIO
                    : targetRelativeAngle * SHOULDER_FOLLOW_RATIO;
            shoulderModule.getStationSlot().setAngle(angle);
        }
    }

    private void initializeModules(WeaponAPI weapon) {
        String slotId = weapon.getSlot() != null ? weapon.getSlot().getId() : null;
        if (slotId == null) {
            return;
        }

        String handSlotId = null;
        String shoulderSlotId = null;
        if ("ARMBIT_L".equals(slotId)) {
            handSlotId = "ARMBIT_WPL";
            shoulderSlotId = "ARMBIT_SHOULDER_MODULE_L";
        } else if ("ARMBIT_R".equals(slotId)) {
            handSlotId = "ARMBIT_WPR";
            shoulderSlotId = "ARMBIT_SHOULDER_MODULE_R";
        }

        if (handSlotId == null) {
            return;
        }

        for (ShipAPI module : weapon.getShip().getChildModulesCopy()) {
            if (module == null || module.getStationSlot() == null) {
                continue;
            }

            String moduleSlotId = module.getStationSlot().getId();
            if (handSlotId.equals(moduleSlotId)) {
                handModule = module;
            } else if (shoulderSlotId.equals(moduleSlotId)) {
                shoulderModule = module;
            }
        }
    }

    private static float moveTowardAngle(float current, float target, float maxDelta) {
        float diff = normalizeAngle(target - current);
        if (Math.abs(diff) <= maxDelta) {
            return target;
        }
        return normalizeAngle(current + Math.signum(diff) * maxDelta);
    }

    private static float normalizeAngle(float angle) {
        while (angle > 180f) angle -= 360f;
        while (angle < -180f) angle += 360f;
        return angle;
    }
}
