package data.scripts.weapons;

import org.lazywizard.lazylib.MathUtils;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

public class Moci_MSZ006ShieldCoverEffect implements EveryFrameWeaponEffectPlugin {
    private static final int SHIELD_FRAME = 0;
    private static final int BLANK_FRAME = 1;
    private static final float WR_SHIELD_REAR_OFFSET = 10f;

    private boolean initialized = false;
    private ShipAPI ship;
    private ShipAPI shieldModule;
    private WeaponAPI armWeapon;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null) {
            return;
        }

        if (!initialized) {
            initialized = true;
            bind(weapon);
        }

        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        boolean nonMsShape = isNonMsShape(ship);
        boolean showShield = !nonMsShape && isShieldPresent();

        if (armWeapon != null) {
            weapon.setCurrAngle(armWeapon.getCurrAngle());
        } else {
            weapon.setCurrAngle(ship.getFacing());
        }

        if (weapon.getAnimation() != null) {
            setFrame(weapon, showShield ? SHIELD_FRAME : BLANK_FRAME);
        }

        if (isShieldPresent()) {
            if (nonMsShape) {
                shieldModule.getLocation().set(MathUtils.getPointOnCircumference(
                        ship.getLocation(),
                        WR_SHIELD_REAR_OFFSET,
                        ship.getFacing() + 180f));
                shieldModule.setFacing(ship.getFacing());
            } else if (armWeapon != null) {
                shieldModule.getLocation().set(armWeapon.getLocation());
                shieldModule.setFacing(armWeapon.getCurrAngle());
            }
        }
    }

    private void bind(WeaponAPI weapon) {
        ship = weapon.getShip();
        if (ship == null || weapon.getSlot() == null) {
            return;
        }

        String slotId = weapon.getSlot().getId();
        String moduleSlotId = slotId.endsWith("_COVER")
                ? slotId.substring(0, slotId.length() - "_COVER".length())
                : slotId;
        String armSlotId = moduleSlotId.replace("SHIELD_", "ARM_");

        for (ShipAPI module : ship.getChildModulesCopy()) {
            if (module.getStationSlot() != null && moduleSlotId.equals(module.getStationSlot().getId())) {
                shieldModule = module;
                break;
            }
        }

        for (WeaponAPI candidate : ship.getAllWeapons()) {
            if (candidate.getSlot() != null && armSlotId.equals(candidate.getSlot().getId())) {
                armWeapon = candidate;
                break;
            }
        }
    }

    private boolean isShieldPresent() {
        return shieldModule != null && shieldModule.isAlive() && !shieldModule.isHulk()
                && shieldModule.getHitpoints() > 0f;
    }

    private boolean isNonMsShape(ShipAPI ship) {
        if (Boolean.TRUE.equals(ship.getCustomData().get(Moci_MechTransformController.CUSTOM_PARTS_HIDDEN))
                || Boolean.TRUE.equals(ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORMED))) {
            return true;
        }
        Object state = ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORM_STATE);
        return state != null && !"NORMAL".equals(String.valueOf(state));
    }

    private void setFrame(WeaponAPI weapon, int frame) {
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        weapon.getAnimation().pause();
        weapon.getAnimation().setFrame(Math.max(0, Math.min(frame, maxFrame)));
    }
}
