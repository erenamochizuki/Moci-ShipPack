package data.scripts.weapons;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 霜岚专用肩部联动。
 * 安装在 SHOULDER_UP_L/R 上，并驱动同侧的下层肩甲与肩灯一起转动。
 */
public class Moci_HoarShroudDragonShoulderDecoWeaponEffect extends Moci_MechShoulderDecoWeaponEffect {
    private final List<WeaponAPI> linkedShoulders = new ArrayList<WeaponAPI>();

    @Override
    protected void onInitialized(WeaponAPI weapon) {
        String slotId = weapon.getSlot() == null ? null : weapon.getSlot().getId();
        if (slotId == null) {
            return;
        }

        for (String linkedSlotId : getLinkedSlotIds(slotId)) {
            WeaponAPI linkedWeapon = findWeaponBySlot(weapon, linkedSlotId);
            if (linkedWeapon == null) {
                continue;
            }

            linkedWeapon.setSuspendAutomaticTurning(true);
            linkedShoulders.add(linkedWeapon);
        }
    }

    @Override
    protected void onAdvance(WeaponAPI weapon, float amount, CombatEngineAPI engine) {
        float angle = weapon.getCurrAngle();

        for (WeaponAPI linkedWeapon : linkedShoulders) {
            if (linkedWeapon == null || linkedWeapon.isDisabled() || linkedWeapon.isPermanentlyDisabled()) {
                continue;
            }

            linkedWeapon.setCurrAngle(angle);
        }
    }

    private List<String> getLinkedSlotIds(String slotId) {
        List<String> linkedSlotIds = new ArrayList<String>();

        if ("SHOULDER_UP_L".equals(slotId)) {
            linkedSlotIds.add("SHOULDER_DOWN_L");
            linkedSlotIds.add("SHOULDER_LIGHT_L");
        } else if ("SHOULDER_UP_R".equals(slotId)) {
            linkedSlotIds.add("SHOULDER_DOWN_R");
            linkedSlotIds.add("SHOULDER_LIGHT_R");
        }

        return linkedSlotIds;
    }

    private WeaponAPI findWeaponBySlot(WeaponAPI weapon, String slotId) {
        if (weapon == null || weapon.getShip() == null || slotId == null) {
            return null;
        }

        for (WeaponAPI shipWeapon : weapon.getShip().getAllWeapons()) {
            if (shipWeapon.getSlot() != null && slotId.equals(shipWeapon.getSlot().getId())) {
                return shipWeapon;
            }
        }

        return null;
    }
}
