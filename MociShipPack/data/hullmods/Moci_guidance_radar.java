package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;

public class Moci_guidance_radar extends BaseHullMod {
	
	private static final float RANGE_BONUS = 10f;
	private static final float SPEED_BONUS = 10f;
	private static final float DAMAGE_BONUS = 10f;
	
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {

		stats.getSensorStrength().modifyFlat(id, 10*(Float) RANGE_BONUS);			//提高雷达范围
		stats.getSightRadiusMod().modifyFlat(id, 10*(Float) RANGE_BONUS);            //战场视野范围
		stats.getMissileWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);	//导弹武器射程		
		stats.getMissileMaxSpeedBonus().modifyPercent(id, SPEED_BONUS);		//导弹最大速度加成
		stats.getMissileAccelerationBonus().modifyPercent(id, SPEED_BONUS);	//导弹加速度
		stats.getMissileMaxTurnRateBonus().modifyPercent(id, SPEED_BONUS);	//导弹最大转向速度
		stats.getMissileTurnAccelerationBonus().modifyPercent(id, SPEED_BONUS);	//导弹转向加速度	
		stats.getMissileWeaponDamageMult().modifyPercent(id, DAMAGE_BONUS);		//导弹类武器伤害
        }
		
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + 10*(int)Math.round(RANGE_BONUS);
		if (index == 1) return "" + 10*(int)Math.round(RANGE_BONUS)+ "%";
		if (index == 2) return "" + (int)Math.round(SPEED_BONUS)+ "%";
		if (index == 3) return "" + (int)Math.round(DAMAGE_BONUS)+ "%";
		return null;
	}

}

