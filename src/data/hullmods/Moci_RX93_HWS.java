package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

/**
 * RX-93v-2高水准规格强化系统
 * 降低武器和EMP受到的伤害50%
 * 降低舰船最高速度20%和机动性20%
 * 提高舰船20%的幅能上限
 * 降低10%护盾受到的伤害
 * 武器和引擎维修所需要的时间延长至1.35倍
 * 提高舰船35%的部署成本
 */
public class Moci_RX93_HWS extends BaseHullMod {

	public static float WEAPON_DAMAGE_MULT = 0.5f;
	public static float EMP_DAMAGE_MULT = 0.5f;
	
	// 新增属性常量
	public static float MAX_SPEED_MULT = 0.8f;       // 速度降低20%
	public static float MANEUVER_MULT = 0.8f;        // 机动性降低20%
	public static float FLUX_CAPACITY_MULT = 1.2f;   // 幅能上限增加20%
	public static float SHIELD_DAMAGE_MULT = 0.9f;   // 护盾伤害降低10%
	public static float REPAIR_TIME_MULT = 1.35f;     // 维修时间增加35%
	public static float DEPLOYMENT_COST_MULT = 1.35f; // 部署成本增加35%
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		// 原有效果
		stats.getWeaponDamageTakenMult().modifyMult(id, WEAPON_DAMAGE_MULT);
		stats.getEmpDamageTakenMult().modifyMult(id, EMP_DAMAGE_MULT);
		
		// 新增效果
		stats.getMaxSpeed().modifyMult(id, MAX_SPEED_MULT);
		stats.getAcceleration().modifyMult(id, MANEUVER_MULT);
		stats.getDeceleration().modifyMult(id, MANEUVER_MULT);
		stats.getTurnAcceleration().modifyMult(id, MANEUVER_MULT);
		stats.getMaxTurnRate().modifyMult(id, MANEUVER_MULT);
		
		stats.getFluxCapacity().modifyMult(id, FLUX_CAPACITY_MULT);
		stats.getShieldDamageTakenMult().modifyMult(id, SHIELD_DAMAGE_MULT);
		
		stats.getCombatWeaponRepairTimeMult().modifyMult(id, REPAIR_TIME_MULT);
		stats.getCombatEngineRepairTimeMult().modifyMult(id, REPAIR_TIME_MULT);
		
		// 部署成本增加
		stats.getDynamic().getMod(Stats.DEPLOYMENT_POINTS_MOD).modifyMult(id, DEPLOYMENT_COST_MULT);
	}
	
	/**
	 * 获取显示在界面上的描述参数
	 * 
	 * 描述索引对应：
	 * 0 - 武器伤害减免百分比
	 * 1 - EMP伤害减免百分比
	 * 2 - 最高速度降低百分比
	 * 3 - 机动性降低百分比
	 * 4 - 幅能上限提升百分比
	 * 5 - 护盾伤害减免百分比
	 * 6 - 维修时间增加百分比
	 * 7 - 部署成本增加百分比
	 */
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) Math.round((1f - WEAPON_DAMAGE_MULT) * 100f) + "%";
		if (index == 1) return "" + (int) Math.round((1f - EMP_DAMAGE_MULT) * 100f) + "%";
		if (index == 2) return "" + (int) Math.round((1f - MAX_SPEED_MULT) * 100f) + "%";
		if (index == 3) return "" + (int) Math.round((1f - MANEUVER_MULT) * 100f) + "%";
		if (index == 4) return "" + (int) Math.round((FLUX_CAPACITY_MULT - 1f) * 100f) + "%";
		if (index == 5) return "" + (int) Math.round((1f - SHIELD_DAMAGE_MULT) * 100f) + "%";
		if (index == 6) return "" + (int) Math.round((REPAIR_TIME_MULT - 1f) * 100f) + "%";
		if (index == 7) return "" + (int) Math.round((DEPLOYMENT_COST_MULT - 1f) * 100f) + "%";
		if (index == 8) return "" + (int) Math.round((REPAIR_TIME_MULT - 1f) * 100f) + "%";
		return null;
	}
}








