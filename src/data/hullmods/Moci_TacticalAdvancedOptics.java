package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;

/** 战术改装（火力组）：先进光学器件；沿用原版 advancedoptics 的普通效果及与高散射增幅器的互斥规则。 */
public class Moci_TacticalAdvancedOptics extends BaseHullMod {
    public static final float BEAM_RANGE_BONUS = 200f;
    public static final float BEAM_TURN_PENALTY = 30f;

    @Override public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getBeamWeaponRangeBonus().modifyFlat(id, BEAM_RANGE_BONUS);
        stats.getBeamWeaponTurnRateBonus().modifyMult(id, 1f - BEAM_TURN_PENALTY * 0.01f);
    }
    @Override public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) BEAM_RANGE_BONUS;
        if (index == 1) return (int) BEAM_TURN_PENALTY + "%";
        return null;
    }
    @Override public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && !ship.getVariant().hasHullMod(HullMods.HIGH_SCATTER_AMP);
    }
    @Override public String getUnapplicableReason(ShipAPI ship) {
        return "与高散射增幅器不兼容。";
    }
}
