package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class Moci_SuperAlloyArmor extends BaseHullMod {
    // 装甲最低值增加量
    private static final float MIN_ARMOR_BONUS = 150f;

    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getMinArmorFraction().modifyFlat(id, MIN_ARMOR_BONUS);  //最小装甲
        stats.getDynamic().getMod("individual_ship_recovery_mod").modifyFlat(id, 1000.0F); //打捞恢复概率
    }
    
    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) {
            return String.format("%d", (int)MIN_ARMOR_BONUS);
        }
        return null;
    }
    
    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null;
    }
} 