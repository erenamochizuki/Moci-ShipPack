package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import java.util.ArrayList;
import java.util.List;

public class Moci_YamatoAirDefense extends BaseShipSystemScript {
    IntervalUtil interval = new IntervalUtil(0.1f, 0.1f);
    List<WeaponSlotAPI> slots = null;
    int currentSlotIndex = 0; // 当前要发射的槽位索引

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        stats.getBallisticWeaponRangeBonus().modifyPercent(id, 25);
        stats.getEnergyWeaponRangeBonus().modifyPercent(id, 25);
        if (stats.getEntity() instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) stats.getEntity();
            if (slots == null) {
                slots = new ArrayList<>();
                for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
                    if (slot.getWeaponType() == WeaponAPI.WeaponType.SYSTEM) {
                        slots.add(slot);
                    }
                }
            }
            interval.advance(Global.getCombatEngine().getElapsedInLastFrame());
            if (interval.intervalElapsed()) {
                // 如果槽位列表不为空，依次发射
                if (!slots.isEmpty()) {
                    WeaponSlotAPI slot = slots.get(currentSlotIndex);
                    Global.getCombatEngine().spawnProjectile(ship, null, "flak", slot.computePosition(ship),
                            slot.computeMidArcAngle(ship), ship.getVelocity());

                    // 移动到下一个槽位，循环回到开头
                    currentSlotIndex++;
                    if (currentSlotIndex >= slots.size()) {
                        currentSlotIndex = 0;
                    }
                }
            }
        }
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        stats.getBallisticWeaponRangeBonus().unmodify(id);
        stats.getEnergyWeaponRangeBonus().unmodify(id);
    }

    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (index == 0) {
            return new StatusData("射程增加 25%", false);
        }
        return null;
    }
}
