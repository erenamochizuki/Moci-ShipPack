package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import com.fs.starfarer.api.impl.campaign.ids.HullMods;

public class Moci_weapon_overload extends BaseHullMod {
	
    private static final float RANGE_BONUS = 50f;
    private static final float DAMAGE_BONUS = 50f;
    private static final float MISSILE_ROF_BONUS = 50f;
    private static final float MISSILE_AMMO_BONUS = 50f;

	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		
		stats.getBallisticWeaponRangeBonus().modifyPercent(id, RANGE_BONUS); // 实弹武器射程加成
		stats.getEnergyWeaponRangeBonus().modifyPercent(id, RANGE_BONUS); // 能量武器射程加成
		
		stats.getBallisticWeaponDamageMult().modifyPercent(id, DAMAGE_BONUS); // 实弹武器伤害加成
		stats.getEnergyWeaponDamageMult().modifyPercent(id, DAMAGE_BONUS); // 能量武器伤害加成

		stats.getBallisticRoFMult().modifyPercent(id, DAMAGE_BONUS); // 实弹武器射速加成
		stats.getEnergyRoFMult().modifyPercent(id, DAMAGE_BONUS); // 能量武器射速加成

        stats.getMissileRoFMult().modifyPercent(id, MISSILE_ROF_BONUS);      // 导弹射速翻倍
        stats.getMissileAmmoBonus().modifyPercent(id, MISSILE_AMMO_BONUS);  // 导弹备弹量翻倍
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int)Math.round(RANGE_BONUS) + "%"; 
		if (index == 1) return "" + (int)Math.round(DAMAGE_BONUS) + "%"; 
        if (index == 2) return "" + (int) MISSILE_ROF_BONUS + "%";   // 导弹射速
        if (index == 3) return "" + (int) MISSILE_AMMO_BONUS + "%";  // 导弹备弹量
		
		return null; // 返回null
	}
}