package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

/**
 * 超级弹夹船插
 * 基础效果：弹夹容量 +100%，装弹速度 -50%
 * S-Mod效果：额外 +50% 弹夹容量（总共 +150%）
 */
public class Moci_SuperLongMagazines extends BaseHullMod {

	// 基础弹夹容量加成
	public static float AMMO_BONUS = 100f;
	
	// S-Mod额外弹夹容量加成
	public static float SMOD_AMMO_BONUS = 50f;
	
	// 装弹速度惩罚（负值表示降低）
	public static float RELOAD_SPEED_PENALTY = -50f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		boolean sMod = isSMod(stats);
		
		// 基础弹夹容量加成
		float totalAmmoBonus = AMMO_BONUS;
		if (sMod) {
			totalAmmoBonus += SMOD_AMMO_BONUS;
		}
		
		stats.getBallisticAmmoBonus().modifyPercent(id, totalAmmoBonus);
		stats.getEnergyAmmoBonus().modifyPercent(id, totalAmmoBonus);
		
		// 装弹速度惩罚（对所有情况都生效）
		// 使用 modifyMult 降低装弹速度（弹药回复速度）
		stats.getBallisticAmmoRegenMult().modifyMult(id, 1f + RELOAD_SPEED_PENALTY / 100f);
		stats.getEnergyAmmoRegenMult().modifyMult(id, 1f + RELOAD_SPEED_PENALTY / 100f);
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) SMOD_AMMO_BONUS + "%";
		return null;
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) AMMO_BONUS + "%";
		if (index == 1) return "" + (int) Math.abs(RELOAD_SPEED_PENALTY) + "%";
		return null;
	}
}
