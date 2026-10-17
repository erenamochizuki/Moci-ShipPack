package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.lazywizard.lazylib.MathUtils;

import java.util.HashSet;
import java.util.Set;

public class Moci_MechHeadDecoWeaponEfect_noHide extends Moci_MechProjDecoWeaponEffect_noHide{
    private boolean wasFiring = false;
    private boolean isAnimating = false;
    private static final Set<String> HEAD_AIMING_SLOTS = new HashSet<>();
    private static final Set<String> MAINWEAPON_AIMING_SLOTS = new HashSet<>();
    static {
        HEAD_AIMING_SLOTS.add("ARM_L_SOURCE");
        HEAD_AIMING_SLOTS.add("ARM_R_SOURCE");
        HEAD_AIMING_SLOTS.add("HEAD_SOURCE");
        HEAD_AIMING_SLOTS.add("HEAD_BEAM");
        MAINWEAPON_AIMING_SLOTS.add("ARM_L_MAIN");
        MAINWEAPON_AIMING_SLOTS.add("ARM_R_MAIN");
    }

    @Override
    protected boolean shouldApplyRecoil(WeaponAPI weapon) {
        return false;
    }

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        super.advance(amount,engine,weapon);

        // 同步开火逻辑
        // if (source != null && source.isFiring() && source.getChargeLevel() > 0.99f) {
        //     weapon.setForceFireOneFrame(true);
        // }

        ShipAPI ship = weapon.getShip();
        float headAngle = source != null ? source.getCurrAngle() : ship.getFacing();
        if (ship.getSelectedGroupAPI() != null) {
            WeaponAPI current = ship.getSelectedGroupAPI().getActiveWeapon();
            if (current != null && current.getSlot() != null) {
                String slotId = current.getSlot().getId();
                if (HEAD_AIMING_SLOTS.contains(slotId) || MAINWEAPON_AIMING_SLOTS.contains(slotId)) {
                    headAngle = current.getCurrAngle();
                }
            }
        }

        float diff = MathUtils.getShortestRotation(weapon.getCurrAngle(), headAngle);
        float turn = weapon.getTurnRate() * amount;
        if (Math.abs(diff) > turn) {
            diff = Math.signum(diff) * turn;
        }
        weapon.setCurrAngle(weapon.getCurrAngle() + diff);
    }
}
