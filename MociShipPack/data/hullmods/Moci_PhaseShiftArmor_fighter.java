package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

@SuppressWarnings("unchecked")
public class Moci_PhaseShiftArmor_fighter extends BaseHullMod {

	public static final int HARD_FLUX_DISSIPATION_PERCENT = 40;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getHardFluxDissipationFraction().modifyFlat(id, (float)HARD_FLUX_DISSIPATION_PERCENT * 0.01f);
        stats.getDynamic().getMod("individual_ship_recovery_mod").modifyFlat(id, 1000.0F); //打捞恢复概率
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + HARD_FLUX_DISSIPATION_PERCENT + "%";
		return null;
	}


}
