package data.scripts.weapons;

import java.util.HashSet;
import java.util.Set;

import org.lazywizard.lazylib.MathUtils;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;

/**
 * Dedicated MSZ-010 head controller.
 *
 * HEAD_BEAM and HEAD_SOURCE behave like a main/follow pair: the selected one
 * drives the decorative head and the other head weapon angle.
 */
public class Moci_MSZ010HeadDualWeaponEffect implements EveryFrameWeaponEffectPlugin {
    private static final String HEAD_SOURCE_SLOT = "HEAD_SOURCE";
    private static final String HEAD_BEAM_SLOT = "HEAD_BEAM";

    private static final Set<String> HEAD_AIMING_SLOTS = new HashSet<String>();
    private static final Set<String> ARM_AIMING_SLOTS = new HashSet<String>();

    static {
        HEAD_AIMING_SLOTS.add(HEAD_SOURCE_SLOT);
        HEAD_AIMING_SLOTS.add(HEAD_BEAM_SLOT);

        ARM_AIMING_SLOTS.add("ARM_L_SOURCE");
        ARM_AIMING_SLOTS.add("ARM_R_SOURCE");
        ARM_AIMING_SLOTS.add("ARM_L_MAIN");
        ARM_AIMING_SLOTS.add("ARM_R_MAIN");
    }

    private boolean initialized = false;
    private WeaponAPI headSource;
    private WeaponAPI headBeam;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }

        if (!initialized) {
            initialized = true;
            findHeadWeapons(ship);
        }

        if (isTransformPreparingOrNotMs(ship)) {
            applyNoFireInNonMsShape();
            return;
        }

        boolean sourceSelected = isWeaponInSelectedGroup(ship, headSource);
        boolean beamSelected = isWeaponInSelectedGroup(ship, headBeam);
        applyExclusiveFiringControl(sourceSelected, beamSelected);

        WeaponAPI driver = getHeadDriver(sourceSelected, beamSelected);
        if (driver != null) {
            syncHeadWeaponsTo(driver.getCurrAngle(), driver);
            turnHeadToward(amount, weapon, driver.getCurrAngle());
            return;
        }

        WeaponAPI active = getActiveSelectedWeapon(ship);
        if (active != null && active.getSlot() != null) {
            String activeSlotId = active.getSlot().getId();
            if (HEAD_AIMING_SLOTS.contains(activeSlotId)) {
                syncHeadWeaponsTo(active.getCurrAngle(), active);
                turnHeadToward(amount, weapon, active.getCurrAngle());
                return;
            }
            if (ARM_AIMING_SLOTS.contains(activeSlotId)) {
                turnHeadToward(amount, weapon, active.getCurrAngle());
                return;
            }
        }

        WeaponAPI fallback = getFallbackDriver(ship);
        float targetAngle = fallback != null ? fallback.getCurrAngle() : ship.getFacing();
        syncHeadWeaponsTo(targetAngle, fallback);
        turnHeadToward(amount, weapon, targetAngle);
    }

    private void findHeadWeapons(ShipAPI ship) {
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w == null || w.getSlot() == null) {
                continue;
            }
            String slotId = w.getSlot().getId();
            if (HEAD_SOURCE_SLOT.equals(slotId)) {
                headSource = w;
            } else if (HEAD_BEAM_SLOT.equals(slotId)) {
                headBeam = w;
            }
        }
    }

    private boolean isTransformPreparingOrNotMs(ShipAPI ship) {
        Object preparing = ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORM_PREPARE);
        if (preparing instanceof Boolean && (Boolean) preparing) {
            return true;
        }

        Object state = ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORM_STATE);
        return state instanceof String && !"NORMAL".equals(state);
    }

    private void applyNoFireInNonMsShape() {
        disableWeapon(headSource);
        disableWeapon(headBeam);
    }

    private boolean isWeaponInSelectedGroup(ShipAPI ship, WeaponAPI checked) {
        return checked != null
                && ship.getSelectedGroupAPI() != null
                && ship.getSelectedGroupAPI().getWeaponsCopy().contains(checked);
    }

    private WeaponAPI getActiveSelectedWeapon(ShipAPI ship) {
        WeaponGroupAPI selected = ship.getSelectedGroupAPI();
        if (selected == null || selected.getWeaponsCopy().isEmpty()) {
            return null;
        }
        try {
            return selected.getActiveWeapon();
        } catch (IndexOutOfBoundsException ex) {
            return null;
        }
    }

    private WeaponAPI getHeadDriver(boolean sourceSelected, boolean beamSelected) {
        if (sourceSelected && beamSelected) {
            if (headBeam != null && headBeam.getCooldownRemaining() <= 0f) {
                return headBeam;
            }
            return headSource != null ? headSource : headBeam;
        }
        if (beamSelected) {
            return headBeam;
        }
        if (sourceSelected) {
            return headSource;
        }
        return null;
    }

    private WeaponAPI getFallbackDriver(ShipAPI ship) {
        if (headBeam != null && (headBeam.isFiring() || headBeam.getChargeLevel() > 0f)) {
            return headBeam;
        }
        if (headSource != null && headSource.isFiring()) {
            return headSource;
        }
        if (headBeam != null) {
            return headBeam;
        }
        return headSource;
    }

    private void syncHeadWeaponsTo(float angle, WeaponAPI driver) {
        if (headSource != null && headSource != driver) {
            headSource.setCurrAngle(angle);
        }
        if (headBeam != null && headBeam != driver) {
            headBeam.setCurrAngle(angle);
        }
    }

    private void turnHeadToward(float amount, WeaponAPI head, float targetAngle) {
        float diff = MathUtils.getShortestRotation(head.getCurrAngle(), targetAngle);
        float turn = head.getTurnRate() * amount;
        if (Math.abs(diff) > turn) {
            diff = Math.signum(diff) * turn;
        }
        head.setCurrAngle(head.getCurrAngle() + diff);
    }

    private void applyExclusiveFiringControl(boolean sourceSelected, boolean beamSelected) {
        if (sourceSelected && beamSelected) {
            boolean beamReady = headBeam != null && headBeam.getCooldownRemaining() <= 0f;
            if (beamReady) {
                disableWeapon(headSource);
            } else {
                disableWeapon(headBeam);
            }
            return;
        }

        if (beamSelected) {
            disableWeapon(headSource);
        } else if (sourceSelected) {
            disableWeapon(headBeam);
        }
    }

    private void disableWeapon(WeaponAPI weapon) {
        if (weapon != null) {
            weapon.setForceNoFireOneFrame(true);
        }
    }
}
