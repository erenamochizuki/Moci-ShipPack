package data.hullmods;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;
import data.scripts.util.Moci_TextLoader;

/**
 * 重力波天线 - 重力波发生器
 * 功能：减少舰船90%的船员需求
 * 
 * 这是重力波系统的发射端，为周围的重力波接收器提供支援
 */
public class Moci_GravityAntenna extends BaseLogisticsHullMod {
    private static final String TEXT_ID = "Moci_GravityAntenna";
    
    // 船员需求减少系数
    public static final float CREW_REDUCTION_MULT = 0.1f;
    
    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 减少90%的船员需求
        stats.getMinCrewMod().modifyMult(id, CREW_REDUCTION_MULT);
    }
    
    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        // 检查是否已经安装了重力波接收器
        if (ship != null && ship.getVariant().hasHullMod("Moci_GravityReceiver")) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_reason");
        }
        return null;
    }
    
    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        // 不能与重力波接收器同时安装
        return ship == null || !ship.getVariant().hasHullMod("Moci_GravityReceiver");
    }
    
    @Override
    public String getDescriptionParam(int index, HullSize hullSize, ShipAPI ship) {
        if (index == 0) return Moci_TextLoader.getText(TEXT_ID, "description.param_crew_reduction");
        return null;
    }
    
    @Override
    public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return true;
    }
}
