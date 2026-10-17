package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.combat.listeners.WeaponBaseRangeModifier;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * 能量武器数量射程加成系统
 * 根据能量武器的数量和尺寸提供射程加成，优化武器配置平衡
 */
public class Moci_EnergyWeaponCountRangefinder extends BaseHullMod {
    private static final String TEXT_ID = "Moci_energyweaponcountrangefinder";

	// 大型武器效果：为中小型武器提供射程加成
	public static float LARGE_TO_MEDIUM_BASE = 150f;    // 大型武器对中型武器基础加成
	public static float LARGE_TO_SMALL_BASE = 150f;     // 大型武器对小型武器基础加成
	public static float LARGE_DIMINISH_RATE = 50f;      // 每额外大型武器的衰减值
	
	// 中型武器效果：为大小型武器提供射程加成
	public static float MEDIUM_TO_LARGE_BASE = 50f;     // 中型武器对大型武器基础加成
	public static float MEDIUM_TO_SMALL_BASE = 250f;    // 中型武器对小型武器基础加成
	public static float MEDIUM_DIMINISH_RATE = 50f;     // 每额外中型武器的衰减值
	
	// 小型武器效果：为中大型武器提供射程加成
	public static float SMALL_TO_MEDIUM_BASE = 50f;     // 小型武器对中型武器基础加成
	public static float SMALL_TO_LARGE_BASE = 50f;      // 小型武器对大型武器基础加成
	public static float SMALL_DIMINISH_RATE = 10f;      // 每额外小型武器的衰减值

	/**
	 * 船只创建前的效果应用 - 此模块在此阶段不执行任何操作
	 */
	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
	}

	/**
	 * 统计指定类型的能量武器数量
	 * @param ship 目标船只
	 * @return [大型武器数量, 中型武器数量, 小型武器数量]
	 */
	public static int[] countEnergyWeapons(ShipAPI ship) {
		if (ship == null) return new int[]{0, 0, 0};
		
		int largeCount = 0;
		int mediumCount = 0;
		int smallCount = 0;
		
		// 遍历所有武器
		for (WeaponAPI weapon : ship.getAllWeapons()) {
			// 跳过不符合条件的武器
			if (!isValidWeapon(weapon)) continue;
			
			// 统计各尺寸武器数量
			switch (weapon.getSize()) {
			case LARGE:
				largeCount++;
				break;
			case MEDIUM:
				mediumCount++;
				break;
			case SMALL:
				smallCount++;
				break;
			}
		}
		
		return new int[]{largeCount, mediumCount, smallCount};
	}
	
	/**
	 * 检查武器是否符合加成条件
	 * @param weapon 要检查的武器
	 * @return 是否符合条件
	 */
	public static boolean isValidWeapon(WeaponAPI weapon) {
		if (weapon == null || weapon.getSpec() == null) return false;
		
		// 跳过内置武器槽位上的武器
		if (weapon.getSlot() != null && weapon.getSlot().isBuiltIn()) return false;
		
		// 只处理能量武器，排除导弹、复合、混合武器
		WeaponType mountType = weapon.getSpec().getMountType();
		if (mountType != WeaponType.ENERGY) return false;
		
		// 跳过点防武器
		if (weapon.hasAIHint(AIHints.PD)) return false;
		
		return true;
	}

	/**
	 * 船只创建后的效果应用 - 添加射程修改监听器
	 */
	@Override
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		int[] weaponCounts = countEnergyWeapons(ship);
		
		// 如果没有符合条件的武器则不生效
		if (weaponCounts[0] + weaponCounts[1] + weaponCounts[2] == 0) return;
		
		// 为船只添加射程修改监听器
		ship.addListener(new EnergyWeaponCountRangeModifier(weaponCounts[0], weaponCounts[1], weaponCounts[2]));
	}

	/**
	 * 能量武器数量射程修改器 - 实现武器基础射程修改接口
	 */
	public static class EnergyWeaponCountRangeModifier implements WeaponBaseRangeModifier {
		private final int largeCount, mediumCount, smallCount;
		
		public EnergyWeaponCountRangeModifier(int largeCount, int mediumCount, int smallCount) {
			this.largeCount = largeCount;
			this.mediumCount = mediumCount;
			this.smallCount = smallCount;
		}
		
		/**
		 * 武器基础射程百分比修改 - 此模块不使用百分比修改
		 */
		@Override
		public float getWeaponBaseRangePercentMod(ShipAPI ship, WeaponAPI weapon) {
			return 0;
		}
		
		/**
		 * 武器基础射程倍数修改 - 此模块不使用倍数修改
		 */
		@Override
		public float getWeaponBaseRangeMultMod(ShipAPI ship, WeaponAPI weapon) {
			return 1f;
		}
		
		/**
		 * 武器基础射程固定加成修改 - 核心逻辑实现
		 */
		@Override
		public float getWeaponBaseRangeFlatMod(ShipAPI ship, WeaponAPI weapon) {
			// 只对符合条件的武器生效
			if (!isValidWeapon(weapon)) return 0f;
			
			float bonus = 0f;
			WeaponSize weaponSize = weapon.getSize();
			
			// 根据武器尺寸计算来自其他尺寸武器的加成
			switch (weaponSize) {
			case LARGE:
				// 大型武器受中型和小型武器加成影响
				if (mediumCount > 0) {
					bonus += Math.max(0, MEDIUM_TO_LARGE_BASE - (mediumCount - 1) * MEDIUM_DIMINISH_RATE);
				}
				if (smallCount > 0) {
					bonus += Math.max(0, SMALL_TO_LARGE_BASE - (smallCount - 1) * SMALL_DIMINISH_RATE);
				}
				break;
				
			case MEDIUM:
				// 中型武器受大型和小型武器加成影响
				if (largeCount > 0) {
					bonus += Math.max(0, LARGE_TO_MEDIUM_BASE - (largeCount - 1) * LARGE_DIMINISH_RATE);
				}
				if (smallCount > 0) {
					bonus += Math.max(0, SMALL_TO_MEDIUM_BASE - (smallCount - 1) * SMALL_DIMINISH_RATE);
				}
				break;
				
			case SMALL:
				// 小型武器受大型和中型武器加成影响
				if (largeCount > 0) {
					bonus += Math.max(0, LARGE_TO_SMALL_BASE - (largeCount - 1) * LARGE_DIMINISH_RATE);
				}
				if (mediumCount > 0) {
					bonus += Math.max(0, MEDIUM_TO_SMALL_BASE - (mediumCount - 1) * MEDIUM_DIMINISH_RATE);
				}
				break;
			}
			
			return bonus;
		}
	}

	/**
	 * 获取描述参数 - 此模块暂未使用参数化描述
	 */
	@Override
	public String getDescriptionParam(int index, HullSize hullSize) {
		return null;
	}
	
	/**
	 * 是否应该将描述添加到工具提示 - 此模块使用自定义描述
	 */
	@Override
	public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
		return false;
	}

	/**
	 * 添加后置描述部分 - 生成详细的工具提示说明
	 */
	@Override
	public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
		float opad = 8f;    // 标准间距
		Color h = Misc.getHighlightColor();      // 高亮颜色
		Color bad = Misc.getNegativeHighlightColor();      // 负面颜色
		Color good = Misc.getPositiveHighlightColor();
		Color g = Misc.getGrayColor();           // 灰色
		
		int[] weaponCounts = countEnergyWeapons(ship); // 获取武器数量
		
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.intro"),
				opad, h,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.intro_highlights").toArray(new String[0]));
		
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.current_loadout"),
				opad, new Color[]{h,h,h}, "" + weaponCounts[0], "" + weaponCounts[1], "" + weaponCounts[2]);
		
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.range_heading"), Alignment.MID, opad);
		
		// 大型武器效果说明
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.large_rule"), opad, h,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.large_rule_highlights").toArray(new String[0]));
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.large_rule_value"), opad, new Color[]{h,bad},
				Moci_TextLoader.getHighlights(TEXT_ID, "description.large_rule_value_highlights").toArray(new String[0]));
		
		// 中型武器效果说明
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.medium_rule"), opad, h,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.medium_rule_highlights").toArray(new String[0]));
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.medium_rule_large"), opad, new Color[]{h,bad},
				Moci_TextLoader.getHighlights(TEXT_ID, "description.medium_rule_large_highlights").toArray(new String[0]));
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.medium_rule_small"), opad, new Color[]{h,bad},
				Moci_TextLoader.getHighlights(TEXT_ID, "description.medium_rule_small_highlights").toArray(new String[0]));
		
		// 小型武器效果说明
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.small_rule"), opad, h,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.small_rule_highlights").toArray(new String[0]));
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.small_rule_value"), opad, new Color[]{h,bad},
				Moci_TextLoader.getHighlights(TEXT_ID, "description.small_rule_value_highlights").toArray(new String[0]));
		
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.current_heading"), Alignment.MID, opad);
		
		// 显示当前配置下的具体加成值
		if (weaponCounts[0] + weaponCounts[1] + weaponCounts[2] > 0) {
			float largeBonus = 0f;
			float mediumBonus = 0f; 
			float smallBonus = 0f;
			
			// 计算大型武器获得的加成
			if (weaponCounts[1] > 0) {
				largeBonus += Math.max(0, MEDIUM_TO_LARGE_BASE - (weaponCounts[1] - 1) * MEDIUM_DIMINISH_RATE);
			}
			if (weaponCounts[2] > 0) {
				largeBonus += Math.max(0, SMALL_TO_LARGE_BASE - (weaponCounts[2] - 1) * SMALL_DIMINISH_RATE);
			}
			
			// 计算中型武器获得的加成
			if (weaponCounts[0] > 0) {
				mediumBonus += Math.max(0, LARGE_TO_MEDIUM_BASE - (weaponCounts[0] - 1) * LARGE_DIMINISH_RATE);
			}
			if (weaponCounts[2] > 0) {
				mediumBonus += Math.max(0, SMALL_TO_MEDIUM_BASE - (weaponCounts[2] - 1) * SMALL_DIMINISH_RATE);
			}
			
			// 计算小型武器获得的加成
			if (weaponCounts[0] > 0) {
				smallBonus += Math.max(0, LARGE_TO_SMALL_BASE - (weaponCounts[0] - 1) * LARGE_DIMINISH_RATE);
			}
			if (weaponCounts[1] > 0) {
				smallBonus += Math.max(0, MEDIUM_TO_SMALL_BASE - (weaponCounts[1] - 1) * MEDIUM_DIMINISH_RATE);
			}
			
			tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.current_large"), opad,
					largeBonus > 0 ? good : g, "+" + (int)largeBonus);
			tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.current_medium"), opad,
					mediumBonus > 0 ? good : g, "+" + (int)mediumBonus);
			tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.current_small"), opad,
					smallBonus > 0 ? good : g, "+" + (int)smallBonus);
		} else {
			tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.no_weapon"), opad, g);
		}
		
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.interaction_heading"), Alignment.MID, opad + 7f);
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.interaction"), opad);
	}
	
	/**
	 * 获取工具提示宽度
	 */
	@Override
	public float getTooltipWidth() {
		return 420f;
	}
	
	/**
	 * 检查此改装是否适用于指定船只
	 */
	@Override
	public boolean isApplicableToShip(ShipAPI ship) {
		return getUnapplicableReason(ship) == null;
	}
	
	/**
	 * 获取不适用的原因
	 * @param ship 目标船只
	 * @return 不适用原因字符串，如果适用则返回null
	 */
	@Override
	public String getUnapplicableReason(ShipAPI ship) {
		// 检查是否已安装实弹版本
		if (ship != null && ship.getVariant().hasHullMod("Moci_ballisticweaponcountrangefinder")) {
			return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_conflict");
		}
        
		if (ship != null && 
				ship.getHullSize() == HullSize.CAPITAL_SHIP) {
			return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_capital");
		}
		
		return null;
	}
}
