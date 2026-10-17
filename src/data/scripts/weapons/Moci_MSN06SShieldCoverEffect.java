package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * MSN-06S left shield cover. The visible cover follows ARM_L and hides when
 * the SHIELD_L module is gone.
 */
public class Moci_MSN06SShieldCoverEffect implements EveryFrameWeaponEffectPlugin {
    private static final int SHIELD_FRAME = 0;
    private static final int BLANK_FRAME = 1;

    private boolean initialized = false;
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

        if (armWeapon != null) {
            weapon.setCurrAngle(armWeapon.getCurrAngle());
        }

        if (weapon.getAnimation() != null) {
            weapon.getAnimation().pause();
            setSafeFrame(weapon, isShieldPresent() ? SHIELD_FRAME : BLANK_FRAME);
        }
    }

    private void bind(WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }

        for (ShipAPI module : ship.getChildModulesCopy()) {
            if (module.getStationSlot() != null && "SHIELD_L".equals(module.getStationSlot().getId())) {
                shieldModule = module;
                break;
            }
        }

        for (WeaponAPI candidate : ship.getAllWeapons()) {
            if (candidate.getSlot() != null && "ARM_L".equals(candidate.getSlot().getId())) {
                armWeapon = candidate;
                break;
            }
        }
    }

    private boolean isShieldPresent() {
        return shieldModule != null && shieldModule.isAlive() && !shieldModule.isHulk()
                && shieldModule.getHitpoints() > 0f;
    }

    private void setSafeFrame(WeaponAPI weapon, int frame) {
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        weapon.getAnimation().setFrame(Math.max(0, Math.min(frame, maxFrame)));
    }
}
