package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

/**
 * 大和能量转换系统 II
 * - 提高舰船20%的幅能容量
 * - 提高舰船20%的幅能耗散
 */
public class Moci_Ymato_ET_hullmods2 extends BaseHullMod {

	// 幅能容量加成
	public static float FLUX_CAPACITY_BONUS = 20f;
	
	// 幅能耗散加成
	public static float FLUX_DISSIPATION_BONUS = 20f;
	
	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		// 提高幅能容量（乘法计算）
		stats.getFluxCapacity().modifyMult(id, 1f + FLUX_CAPACITY_BONUS * 0.01f);
		
		// 提高幅能耗散（乘法计算）
		stats.getFluxDissipation().modifyMult(id, 1f + FLUX_DISSIPATION_BONUS * 0.01f);
	}

	@Override
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (1f + FLUX_CAPACITY_BONUS * 0.01f);
		if (index == 1) return "" + (1f + FLUX_DISSIPATION_BONUS * 0.01f);
		return null;
	}
	

}
