package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class Moci_legion_VIII extends BaseHullMod {

	// 第14领域战斗群 - 能量武器专精型改装
	private static final float ENERGY_RANGE_BONUS = 10f;  // 能量武器射程加成
	private static final float ARMOR_PENALTY = -200f;  // 装甲减少
	private static final float SHIELD_DAMAGE_REDUCTION = 0.2f;  // 护盾伤害减免
	private static final float CAPACITY_MULT = 1.10f;  // 幅能容量
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		// 能量武器射程提升
		stats.getEnergyWeaponRangeBonus().modifyPercent(id, ENERGY_RANGE_BONUS);
		
		// 装甲降低
		stats.getArmorBonus().modifyFlat(id, ARMOR_PENALTY);
		
		// 护盾伤害减免
		stats.getShieldDamageTakenMult().modifyMult(id, 1f - SHIELD_DAMAGE_REDUCTION);
		
		// 幅能系统提升
		stats.getFluxCapacity().modifyMult(id, CAPACITY_MULT);
		stats.getFluxDissipation().modifyMult(id, CAPACITY_MULT);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) Math.round(ENERGY_RANGE_BONUS) + "%";
		if (index == 1) return "" + (int) Math.round((CAPACITY_MULT - 1f) * 100f) + "%";
		if (index == 2) return "" + (int) Math.round(SHIELD_DAMAGE_REDUCTION * 100f) + "%";
		if (index == 3) return "" + (int) Math.round(-ARMOR_PENALTY);
		return null;
	}

}