package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * 0成员需求
 */
public class Moci_ZeroCrew extends BaseHullMod {
    
    private static final float CREW_MULT = 0f;
    private static final float FRIGATE_NO_OFFICER_CR_PENALTY = 0.15f; // 无军官CR惩罚
    
    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getMinCrewMod().modifyMult(id, CREW_MULT);
        stats.getDynamic().getMod(Stats.DEPLOYMENT_POINTS_MOD).modifyMult(id, 0f);
        stats.getSuppliesToRecover().modifyMult(id, 0f);
        stats.getSuppliesPerMonth().modifyMult(id, 0f);
		// stats.getMaxCombatReadiness().modifyFlat(id, FRIGATE_NO_OFFICER_CR_PENALTY);
    }
} 