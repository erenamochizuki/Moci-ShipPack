package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * 深红闪电 - Crimson Lightning
 * 舰船的战术系统最大充能、充能速度和冷却时间降低20%
 * 使用战术系统时，舰船装填导弹武器最大弹药量20%的备弹（最少1发）
 */
public class Moci_CrimsonLightning extends BaseHullMod {
    private static final String TEXT_ID = "Moci_CrimsonLightning";

	public static float SYSTEM_BONUS = 20f; // 系统性能加成
	public static float AMMO_RELOAD_PERCENT = 20f; // 弹药装填百分比
	public static float WEAPON_TURN_RATE_BONUS = 10f; // 武器转速加成
	public static float DAMAGE_TAKEN_MULT = 1.2f; // 受伤倍数
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int)Math.round(SYSTEM_BONUS) + "%";
		if (index == 1) return "" + (int)Math.round(AMMO_RELOAD_PERCENT) + "%";
		if (index == 2) return "" + (int)Math.round(WEAPON_TURN_RATE_BONUS);
		if (index == 3) return "" + (int)Math.round((DAMAGE_TAKEN_MULT - 1f) * 100f) + "%";
		return null;
	}

	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		// 战术系统性能提升
		stats.getSystemUsesBonus().modifyPercent(id, SYSTEM_BONUS); // 最大充能提升
		stats.getSystemRegenBonus().modifyPercent(id, SYSTEM_BONUS); // 充能速度提升
		stats.getSystemCooldownBonus().modifyPercent(id, SYSTEM_BONUS); // 冷却时间减少
		
		// 武器转速加成
		stats.getWeaponTurnRateBonus().modifyFlat(id, WEAPON_TURN_RATE_BONUS);
		
		// 受伤倍数增加
		stats.getShieldDamageTakenMult().modifyMult(id, DAMAGE_TAKEN_MULT);
		stats.getArmorDamageTakenMult().modifyMult(id, DAMAGE_TAKEN_MULT);
		stats.getHullDamageTakenMult().modifyMult(id, DAMAGE_TAKEN_MULT);
	}

	@Override
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		ship.addListener(new CrimsonLightningListener(ship));
	}

	public static class CrimsonLightningListener implements AdvanceableListener {
		private final ShipAPI ship;
		private boolean wasSystemActive = false;
		
		public CrimsonLightningListener(ShipAPI ship) {
			this.ship = ship;
		}
		
		@Override
		public void advance(float amount) {
			if (!ship.isAlive() || ship.isHulk() || ship.getSystem() == null) {
				return;
			}
			
			boolean isSystemActive = ship.getSystem().isActive();
			
			// 检测系统激活瞬间
			if (isSystemActive && !wasSystemActive) {
				reloadMissileWeapons();
			}
			
			wasSystemActive = isSystemActive;
		}
		
		/**
		 * 重新装填弹夹武器弹药
		 */
		private void reloadMissileWeapons() {
			// 检查主舰体武器
			reloadAmmoWeaponsForShip(ship);
			
			// 检查子模块武器
			for (ShipAPI module : ship.getChildModulesCopy()) {
				reloadAmmoWeaponsForShip(module);
			}
		}
		
		/**
		 * 为指定舰船重新装填所有弹夹武器（包括内置武器）
		 */
		private void reloadAmmoWeaponsForShip(ShipAPI targetShip) {
			for (WeaponAPI weapon : targetShip.getAllWeapons()) {
				if (weapon.isDecorative()) continue;
				if (!weapon.usesAmmo()) continue; // 只处理使用弹夹的武器
				
				// 计算装填数量：最大弹药量的20%，最少1发
				int maxAmmo = weapon.getMaxAmmo();
				int reloadAmount = Math.max(1, (int)(maxAmmo * AMMO_RELOAD_PERCENT / 100f));
				
				// 装填弹药，不超过最大容量
				int currentAmmo = weapon.getAmmo();
				int newAmmo = Math.min(maxAmmo, currentAmmo + reloadAmount);
				weapon.setAmmo(newAmmo);
			}
		}
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
		
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.system_bonus",
						Moci_TextLoader.mapOf("system_bonus", String.valueOf((int) SYSTEM_BONUS))),
				opad, new Color[]{good, good},
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.system_bonus_highlights",
						Moci_TextLoader.mapOf("system_bonus", String.valueOf((int) SYSTEM_BONUS))).toArray(new String[0]));
		
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.turn_rate",
						Moci_TextLoader.mapOf("turn_rate_bonus", String.valueOf((int) WEAPON_TURN_RATE_BONUS))),
				opad, good,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.turn_rate_highlights",
						Moci_TextLoader.mapOf("turn_rate_bonus", String.valueOf((int) WEAPON_TURN_RATE_BONUS))).toArray(new String[0]));
		
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.damage_taken",
						Moci_TextLoader.mapOf("damage_penalty", String.valueOf((int) ((DAMAGE_TAKEN_MULT - 1f) * 100f)))),
				opad, bad,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.damage_taken_highlights",
						Moci_TextLoader.mapOf("damage_penalty", String.valueOf((int) ((DAMAGE_TAKEN_MULT - 1f) * 100f)))).toArray(new String[0]));
		
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.ammo_reload",
						Moci_TextLoader.mapOf("ammo_reload", String.valueOf((int) AMMO_RELOAD_PERCENT))),
				opad, good,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.ammo_reload_highlights",
						Moci_TextLoader.mapOf("ammo_reload", String.valueOf((int) AMMO_RELOAD_PERCENT))).toArray(new String[0]));
	}
} 
