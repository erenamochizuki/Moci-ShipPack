package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import java.util.HashSet;
import java.util.Set;

public class Moci_MechHeadDecoWeaponEfect_noHide_HeadLightSync extends Moci_MechHeadDecoWeaponEfect_noHide {
    private static final Set<String> HEAD_LIGHT_SLOT_IDS = new HashSet<>();

    static {
        HEAD_LIGHT_SLOT_IDS.add("HEADLIGHT");
        HEAD_LIGHT_SLOT_IDS.add("HEAD_LIGHT");
    }

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        super.advance(amount, engine, weapon);
        syncHeadLightAngle(weapon);
    }

    private void syncHeadLightAngle(WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }

        for (WeaponAPI other : ship.getAllWeapons()) {
            if (other != null && other.getSlot() != null && HEAD_LIGHT_SLOT_IDS.contains(other.getSlot().getId())) {
                other.setCurrAngle(weapon.getCurrAngle());
            }
        }
    }
}
