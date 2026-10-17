package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * 白狼 - White Wolf
 * 舰船的装甲值提高300点的同时再提升总装甲值的10%，舰船机动性下降20%
 * 开启战术系统时获得额外buff：
 * - 护盾、装甲、结构、EMP受到的伤害减少25%
 * - 降低50%的最大速度、机动性、射速
 * - 提高50%的射程、实弹武器伤害、弹速
 */
public class Moci_WhiteWolf extends BaseHullMod {
    private static final String TEXT_ID = "Moci_WhiteWolf";

	public static float ARMOR_FLAT_BONUS = 300f; // 装甲平坦加成
	public static float ARMOR_PERCENT_BONUS = 10f; // 装甲百分比加成
	public static float MANEUVER_PENALTY = 20f; // 机动性惩罚
	
	// 战术系统激活时的效果
	public static float SYSTEM_DAMAGE_REDUCTION = 25f; // 受伤减少
	public static float SYSTEM_SPEED_PENALTY = 50f; // 速度惩罚
	public static float SYSTEM_ROF_PENALTY = 50f; // 射速惩罚
	public static float SYSTEM_RANGE_BONUS = 50f; // 射程加成
	public static float SYSTEM_WEAPON_DAMAGE_BONUS = 50f; // 实弹伤害加成
	public static float SYSTEM_PROJECTILE_SPEED_BONUS = 50f; // 弹速加成
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int)Math.round(ARMOR_FLAT_BONUS);
		if (index == 1) return "" + (int)Math.round(ARMOR_PERCENT_BONUS) + "%";
		if (index == 2) return "" + (int)Math.round(MANEUVER_PENALTY) + "%";
		if (index == 3) return "" + (int)Math.round(SYSTEM_DAMAGE_REDUCTION) + "%";
		if (index == 4) return "" + (int)Math.round(SYSTEM_SPEED_PENALTY) + "%";
		if (index == 5) return "" + (int)Math.round(SYSTEM_ROF_PENALTY) + "%";
		if (index == 6) return "" + (int)Math.round(SYSTEM_RANGE_BONUS) + "%";
		if (index == 7) return "" + (int)Math.round(SYSTEM_WEAPON_DAMAGE_BONUS) + "%";
		if (index == 8) return "" + (int)Math.round(SYSTEM_PROJECTILE_SPEED_BONUS) + "%";
		return null;
	}

	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		// 装甲值提升：先加300点平坦加成，再加10%百分比加成
		stats.getArmorBonus().modifyFlat(id + "_flat", ARMOR_FLAT_BONUS);
		stats.getArmorBonus().modifyPercent(id + "_percent", ARMOR_PERCENT_BONUS);
		
		// 机动性下降10%
		stats.getAcceleration().modifyPercent(id + "_penalty", -MANEUVER_PENALTY);
		stats.getDeceleration().modifyPercent(id + "_penalty", -MANEUVER_PENALTY);
		stats.getTurnAcceleration().modifyPercent(id + "_penalty", -MANEUVER_PENALTY);
		stats.getMaxTurnRate().modifyPercent(id + "_penalty", -MANEUVER_PENALTY);
	}

	@Override
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		ship.addListener(new WhiteWolfSystemListener(ship));
	}

    @Override
    public int getDisplaySortOrder() {
        return 200;
    }
	
	
	@Override
	public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
		float pad = 6f;
		float opad = 5f;
		Color highlight = Misc.getHighlightColor();
		Color good = Misc.getPositiveHighlightColor();
		Color bad = Misc.getNegativeHighlightColor();
		
		// 基础效果说明
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.base_heading"), Alignment.MID, opad);
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.armor_bonus",
						Moci_TextLoader.mapOf("armor_flat", String.valueOf((int) ARMOR_FLAT_BONUS),
								"armor_percent", String.valueOf((int) ARMOR_PERCENT_BONUS))),
				pad, new Color[]{good, good},
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.armor_bonus_highlights",
						Moci_TextLoader.mapOf("armor_flat", String.valueOf((int) ARMOR_FLAT_BONUS),
								"armor_percent", String.valueOf((int) ARMOR_PERCENT_BONUS))).toArray(new String[0]));
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.maneuver_penalty",
						Moci_TextLoader.mapOf("maneuver_penalty", String.valueOf((int) MANEUVER_PENALTY))),
				pad, bad,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.maneuver_penalty_highlights",
						Moci_TextLoader.mapOf("maneuver_penalty", String.valueOf((int) MANEUVER_PENALTY))).toArray(new String[0]));
		
		// 战术系统效果说明
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.system_heading"), Alignment.MID, opad);
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.system_intro"),
				pad, Misc.getTextColor());
		
		// 防御增强
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.damage_reduction",
						Moci_TextLoader.mapOf("damage_reduction", String.valueOf((int) SYSTEM_DAMAGE_REDUCTION))),
				pad, good,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.damage_reduction_highlights",
						Moci_TextLoader.mapOf("damage_reduction", String.valueOf((int) SYSTEM_DAMAGE_REDUCTION))).toArray(new String[0]));
		
		// 机动惩罚
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.speed_penalty",
						Moci_TextLoader.mapOf("speed_penalty", String.valueOf((int) SYSTEM_SPEED_PENALTY))),
				pad, bad,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.speed_penalty_highlights",
						Moci_TextLoader.mapOf("speed_penalty", String.valueOf((int) SYSTEM_SPEED_PENALTY))).toArray(new String[0]));
		
		// 火力变化
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.rof_penalty",
						Moci_TextLoader.mapOf("rof_penalty", String.valueOf((int) SYSTEM_ROF_PENALTY))),
				pad,bad,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.rof_penalty_highlights",
						Moci_TextLoader.mapOf("rof_penalty", String.valueOf((int) SYSTEM_ROF_PENALTY))).toArray(new String[0]));
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.range_bonus",
						Moci_TextLoader.mapOf("range_bonus", String.valueOf((int) SYSTEM_RANGE_BONUS))),
				pad, good,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.range_bonus_highlights",
						Moci_TextLoader.mapOf("range_bonus", String.valueOf((int) SYSTEM_RANGE_BONUS))).toArray(new String[0]));
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.weapon_damage",
						Moci_TextLoader.mapOf("weapon_damage", String.valueOf((int) SYSTEM_WEAPON_DAMAGE_BONUS))),
				pad, good,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.weapon_damage_highlights",
						Moci_TextLoader.mapOf("weapon_damage", String.valueOf((int) SYSTEM_WEAPON_DAMAGE_BONUS))).toArray(new String[0]));
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.projectile_speed",
						Moci_TextLoader.mapOf("projectile_speed", String.valueOf((int) SYSTEM_PROJECTILE_SPEED_BONUS))),
				pad, good,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.projectile_speed_highlights",
						Moci_TextLoader.mapOf("projectile_speed", String.valueOf((int) SYSTEM_PROJECTILE_SPEED_BONUS))).toArray(new String[0]));
	}

	public static class WhiteWolfSystemListener implements AdvanceableListener {
		private final ShipAPI ship;
		private boolean wasSystemActive = false;
		
		public WhiteWolfSystemListener(ShipAPI ship) {
			this.ship = ship;
		}
		
		@Override
		public void advance(float amount) {
			if (!ship.isAlive() || ship.isHulk() || ship.getSystem() == null) {
				return;
			}
			
			boolean isSystemActive = ship.getSystem().isActive();
			
			// 检测系统状态变化
			if (isSystemActive != wasSystemActive) {
				if (isSystemActive) {
					applySystemBuffs();
				} else {
					removeSystemBuffs();
				}
			}
			
			wasSystemActive = isSystemActive;
		}
		
		/**
		 * 应用战术系统激活时的buff
		 */
		private void applySystemBuffs() {
			String id = "white_wolf_system";
			MutableShipStatsAPI stats = ship.getMutableStats();
			
			// 伤害减少50%
			stats.getShieldDamageTakenMult().modifyMult(id, 1f - SYSTEM_DAMAGE_REDUCTION / 100f);
			stats.getArmorDamageTakenMult().modifyMult(id, 1f - SYSTEM_DAMAGE_REDUCTION / 100f);
			stats.getHullDamageTakenMult().modifyMult(id, 1f - SYSTEM_DAMAGE_REDUCTION / 100f);
			stats.getEmpDamageTakenMult().modifyMult(id, 1f - SYSTEM_DAMAGE_REDUCTION / 100f);
			
			// 速度和机动性惩罚50%
			stats.getMaxSpeed().modifyMult(id, 1f - SYSTEM_SPEED_PENALTY / 100f);
			stats.getAcceleration().modifyMult(id, 1f - SYSTEM_SPEED_PENALTY / 100f);
			stats.getDeceleration().modifyMult(id, 1f - SYSTEM_SPEED_PENALTY / 100f);
			stats.getTurnAcceleration().modifyMult(id, 1f - SYSTEM_SPEED_PENALTY / 100f);
			stats.getMaxTurnRate().modifyMult(id, 1f - SYSTEM_SPEED_PENALTY / 100f);
			
			// 射速惩罚50%
			stats.getBallisticRoFMult().modifyMult(id, 1f - SYSTEM_ROF_PENALTY / 100f);
			stats.getEnergyRoFMult().modifyMult(id, 1f - SYSTEM_ROF_PENALTY / 100f);
			stats.getMissileRoFMult().modifyMult(id, 1f - SYSTEM_ROF_PENALTY / 100f);
			
			// 射程加成50%
			stats.getBallisticWeaponRangeBonus().modifyPercent(id, SYSTEM_RANGE_BONUS);
			stats.getEnergyWeaponRangeBonus().modifyPercent(id, SYSTEM_RANGE_BONUS);
			stats.getMissileWeaponRangeBonus().modifyPercent(id, SYSTEM_RANGE_BONUS);
			
			// 实弹武器伤害加成50%
			stats.getBallisticWeaponDamageMult().modifyPercent(id, SYSTEM_WEAPON_DAMAGE_BONUS);
			
			// 能量武器伤害加成50%
			stats.getEnergyWeaponDamageMult().modifyPercent(id, SYSTEM_WEAPON_DAMAGE_BONUS);
			
			// 弹速加成50%
			stats.getProjectileSpeedMult().modifyPercent(id, SYSTEM_PROJECTILE_SPEED_BONUS);
		}
		
		/**
		 * 移除战术系统的buff
		 */
		private void removeSystemBuffs() {
			String id = "white_wolf_system";
			MutableShipStatsAPI stats = ship.getMutableStats();
			
			// 移除所有系统相关的修正
			stats.getShieldDamageTakenMult().unmodify(id);
			stats.getArmorDamageTakenMult().unmodify(id);
			stats.getHullDamageTakenMult().unmodify(id);
			stats.getEmpDamageTakenMult().unmodify(id);
			
			stats.getMaxSpeed().unmodify(id);
			stats.getAcceleration().unmodify(id);
			stats.getDeceleration().unmodify(id);
			stats.getTurnAcceleration().unmodify(id);
			stats.getMaxTurnRate().unmodify(id);
			
			stats.getBallisticRoFMult().unmodify(id);
			stats.getEnergyRoFMult().unmodify(id);
			stats.getMissileRoFMult().unmodify(id);
			
			stats.getBallisticWeaponRangeBonus().unmodify(id);
			stats.getEnergyWeaponRangeBonus().unmodify(id);
			stats.getMissileWeaponRangeBonus().unmodify(id);
			
			stats.getBallisticWeaponDamageMult().unmodify(id);
			
			stats.getEnergyWeaponDamageMult().unmodify(id);
			
			stats.getProjectileSpeedMult().unmodify(id);
		}
	}
} 
