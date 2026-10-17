package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.StatBonus;

public class Moci_radical_modification
	
 extends BaseHullMod {
	 
	private static final float SHIELD_BONUS = 30f;
	private static final float FLUX_BONUS = 30f;
	private static final float SPEED_BONUS = 10f;
	 
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getArmorBonus().modifyPercent(id, -1*(Float) SHIELD_BONUS); 			//装甲值
		stats.getHullBonus().modifyPercent(id, -1*(Float) SHIELD_BONUS);			//耐久,结构
		stats.getShieldDamageTakenMult().modifyPercent(id, SHIELD_BONUS);			//护盾承受伤害系数		
		stats.getFluxCapacity().modifyPercent(id, FLUX_BONUS);					    //Flux总量
		stats.getFluxDissipation().modifyPercent(id, FLUX_BONUS);					//Flux消退速率	
		stats.getMaxSpeed().modifyPercent(id, 1*(Float) SPEED_BONUS);				//最大速度
		stats.getAcceleration().modifyPercent(id, 3*(Float) SPEED_BONUS);			//加速度
		stats.getDeceleration().modifyPercent(id, 3*(Float) SPEED_BONUS);			//减速
		stats.getTurnAcceleration().modifyPercent(id, 3*(Float) SPEED_BONUS);		//转向加速度
		stats.getMaxTurnRate().modifyPercent(id, 3*(Float) SPEED_BONUS);			//最大转向速率		
	}

	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int)Math.round(SHIELD_BONUS) + "%";
		if (index == 1) return "" + (int)Math.round(SHIELD_BONUS)+ "%";
		if (index == 2) return "" + (int)Math.round(FLUX_BONUS)+ "%";
		if (index == 3) return "" + 2*(int)Math.round(SPEED_BONUS)+ "%";
		if (index == 4) return "" + (int)Math.round(SPEED_BONUS)+ "%";
		return null;
	}
	

}








