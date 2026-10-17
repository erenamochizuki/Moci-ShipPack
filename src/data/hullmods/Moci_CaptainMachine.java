package data.hullmods;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * 队长机 - Captain Machine
 * 基础效果：提高舰船10%的幅能容量、5%的散幅能力、5%的命令点回复速度、5%的伤害减免
 * 支援效果：当周围1000范围内有同级舰时，每艘同级舰提供4%的额外加成
 * 最终效果：基础5% + 每艘同级舰4% × 数量（最多5艘）= 最高25%
 * - 散幅能力: 5% + 4%×5 = 25%
 * - 伤害减免: 5% + 4%×5 = 25% 
 * - 命令点回复: 5% + 4%×5 = 25%
 */
public class Moci_CaptainMachine extends BaseHullMod {
    public static float DEPLOYMENT_COST_MULT = 1.25f; // 部署点消耗倍数

	public static float BASE_FLUX_CAPACITY_BONUS = 10f; // 基础幅能容量加成
	public static float BASE_FLUX_DISSIPATION_BONUS = 5f; // 基础散幅能力加成
	public static float FLUX_DISSIPATION_BONUS_PER_SHIP = 4f; // 每艘同级舰的散幅加成
	public static float MAX_FLUX_DISSIPATION_BONUS = 25f; // 最大散幅能力加成（减半）
	public static float MAX_DEFENSE_BONUS = 25f; // 最大防御加成（减半）
	public static float BONUS_PER_SHIP = 5f; // 每艘同级舰的加成（减半）
	public static int MAX_SUPPORTING_SHIPS = 5; // 最大支援舰船数量（改为4）
	
	// 命令点回复相关
	public static float BASE_COMMAND_POINT_RECOVERY_BONUS = 0.05f; // 基础命令点回复加成(5%)
	public static float COMMAND_POINT_BONUS_PER_SHIP = 0.04f; // 每艘同级舰的命令点回复加成(4%)
	public static float MAX_COMMAND_POINT_RECOVERY_BONUS = 0.25f; // 最大命令点回复加成(25%)
	
	// 防御相关
	public static float BASE_DEFENSE_BONUS = 5f; // 基础防御加成
	public static float DEFENSE_BONUS_PER_SHIP = 4f; // 每艘同级舰的防御加成
	
	public static float EFFECT_RANGE = 1500f; // 效果范围
	
	// 硬幅能耗散相关
	public static float BASE_HARD_FLUX_DISSIPATION = 0f; // 基础硬幅能耗散加成
	public static float HARD_FLUX_DISSIPATION_PER_SHIP = 4f; // 每艘同级舰的硬幅能耗散加成(4%)
	public static float MAX_HARD_FLUX_DISSIPATION = 20f; // 最大硬幅能耗散加成(20%)
	
	public static Object STATUS_KEY = new Object();
	public static Object STATUS_KEY_EFFECTS = new Object();
	public static Object STATUS_KEY_WINGMAN = new Object(); // 僚机状态键
	
	// 特殊同级舰映射表 - 定义哪些舰船可以把其他舰船当作同级舰
	private static final Map<String, List<String>> SPECIAL_SAME_CLASS_MAP = new HashMap<>();
	static {
		// 高达系列 - 可以把各种吉姆当作同级舰
		List<String> gundamAllies = Arrays.asList(
			"Moci_RGM_79", "Moci_RGM_79P", "Moci_RGM_79SP", "Moci_RMS_179", "Moci_RGM_86"
		);
		SPECIAL_SAME_CLASS_MAP.put("Moci_RX_78_2", gundamAllies);
		SPECIAL_SAME_CLASS_MAP.put("Moci_RX_78_1", gundamAllies);
		SPECIAL_SAME_CLASS_MAP.put("Moci_RX_78_3", gundamAllies);
		SPECIAL_SAME_CLASS_MAP.put("Moci_FA_78_3", gundamAllies);
		
		// 高级吉姆系列 - 可以把基础吉姆当作同级舰
		List<String> eliteGimAllies = Arrays.asList(
			"Moci_RGM_79", "Moci_RMS_179", "Moci_RGM_86"
		);
		SPECIAL_SAME_CLASS_MAP.put("Moci_RGM_79SP", eliteGimAllies);
		SPECIAL_SAME_CLASS_MAP.put("Moci_RGM_79P", eliteGimAllies);
	}

	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int)Math.round(BASE_FLUX_CAPACITY_BONUS) + "%";
		if (index == 1) return "" + (int)Math.round(BASE_FLUX_DISSIPATION_BONUS) + "%";
		if (index == 2) return "" + (int)Math.round(BASE_COMMAND_POINT_RECOVERY_BONUS * 100) + "%";
		if (index == 3) return "" + (int)Math.round(BASE_DEFENSE_BONUS) + "%";
		if (index == 4) return "" + (int)Math.round(EFFECT_RANGE);
		if (index == 5) return "" + (int)Math.round(MAX_FLUX_DISSIPATION_BONUS) + "%";
		if (index == 6) return "" + (int)Math.round(MAX_DEFENSE_BONUS) + "%";
		if (index == 7) return "" + (int)Math.round(MAX_COMMAND_POINT_RECOVERY_BONUS * 100) + "%";
		if (index == 8) return "" + (int)Math.round(COMMAND_POINT_BONUS_PER_SHIP * 100) + "%";
		if (index == 9) return "" + MAX_SUPPORTING_SHIPS;
		if (index == 10) return "" + (int)Math.round(HARD_FLUX_DISSIPATION_PER_SHIP) + "%";
		if (index == 11) return "" + (int)Math.round(MAX_HARD_FLUX_DISSIPATION) + "%";
		return null;
	}
    
    @Override
    public int getDisplaySortOrder() {
        return 300;
    }

	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 增加部署点消耗作为副作用
        stats.getDynamic().getMod("deployment_points_mod").modifyMult(id, DEPLOYMENT_COST_MULT);
		// 基础效果：幅能容量和散幅能力提升
		stats.getFluxCapacity().modifyPercent(id, BASE_FLUX_CAPACITY_BONUS);
		stats.getFluxDissipation().modifyPercent(id, BASE_FLUX_DISSIPATION_BONUS);
		
		// 基础命令点回复速度提升
		stats.getDynamic().getMod(Stats.COMMAND_POINT_RATE_FLAT).modifyFlat(id, BASE_COMMAND_POINT_RECOVERY_BONUS);
		
		// 基础防御加成
		stats.getShieldDamageTakenMult().modifyMult(id + "_base", 1f - BASE_DEFENSE_BONUS / 100f);
		stats.getArmorDamageTakenMult().modifyMult(id + "_base", 1f - BASE_DEFENSE_BONUS / 100f);
		stats.getHullDamageTakenMult().modifyMult(id + "_base", 1f - BASE_DEFENSE_BONUS / 100f);
		stats.getEmpDamageTakenMult().modifyMult(id + "_base", 1f - BASE_DEFENSE_BONUS / 100f);
	}

	public static String CM_DATA_KEY = "moci_captain_machine_data_key";
	public static class CaptainMachineData {
		IntervalUtil interval = new IntervalUtil(0.9f, 1.1f);
		int supportingShips = 0;
		float bonusMagnitude = 0f;
	}

	/**
	 * 获取舰船的基础船体ID（处理皮肤舰船）
	 */
	private String getBaseHullId(ShipAPI ship) {
		String hullId = ship.getHullSpec().getHullId();
		String baseHullId = ship.getHullSpec().getBaseHullId();
		
		// 如果有baseHullId，优先使用baseHullId
		if (baseHullId != null && !baseHullId.isEmpty()) {
			return baseHullId;
		}
		
		// 处理_Hull后缀（队长机通常带这个后缀）
		if (hullId.endsWith("_Hull")) {
			return hullId.substring(0, hullId.length() - 5); // 移除"_Hull"
		}
		
		// 处理其他常见的队长机后缀
		if (hullId.endsWith("_leader")) {
			return hullId.substring(0, hullId.length() - 7); // 移除"_leader"
		}
		
		if (hullId.endsWith("_captain")) {
			return hullId.substring(0, hullId.length() - 8); // 移除"_captain"
		}
		
		return hullId;
	}

	/**
	 * 检查两艘舰船是否为同级舰（包含特殊规则）
	 */
	private boolean isSameClass(ShipAPI ship1, ShipAPI ship2) {
		if (ship1 == ship2) return false; // 不包括自己
		
		String baseHull1 = getBaseHullId(ship1);
		String baseHull2 = getBaseHullId(ship2);
		
		// 首先检查基础同级舰逻辑
		if (baseHull1.equals(baseHull2)) {
			return true;
		}
		
		// 检查特殊同级舰规则 - ship1是否可以把ship2当作同级舰
		List<String> specialAllies = SPECIAL_SAME_CLASS_MAP.get(baseHull1);
		if (specialAllies != null && specialAllies.contains(baseHull2)) {
			return true;
		}
		
		return false;
	}

	public void applyCMEffect(ShipAPI ship, int supportingShips) {
		String id = "captain_machine_bonus" + ship.getId();
		MutableShipStatsAPI stats = ship.getMutableStats();
		
		// 计算支援舰船数量（不超过最大值）
		int actualSupporting = Math.min(supportingShips, MAX_SUPPORTING_SHIPS);
		
		// 应用额外的支援效果（不包括基础效果，基础效果已在applyEffectsBeforeShipCreation中应用）
		if (supportingShips > 0) {
			// 额外散幅能力加成
			float extraFluxDissipation = actualSupporting * FLUX_DISSIPATION_BONUS_PER_SHIP;
			stats.getFluxDissipation().modifyPercent(id + "_extra", extraFluxDissipation);
			
			// 额外防御加成
			float extraDefenseBonus = actualSupporting * DEFENSE_BONUS_PER_SHIP;
			stats.getShieldDamageTakenMult().modifyMult(id + "_shield", 1f - extraDefenseBonus / 100f);
			stats.getArmorDamageTakenMult().modifyMult(id + "_armor", 1f - extraDefenseBonus / 100f);
			stats.getHullDamageTakenMult().modifyMult(id + "_hull", 1f - extraDefenseBonus / 100f);
			stats.getEmpDamageTakenMult().modifyMult(id + "_emp", 1f - extraDefenseBonus / 100f);
			
			// 额外命令点回复加成
			float extraCommandPointRecovery = actualSupporting * COMMAND_POINT_BONUS_PER_SHIP;
			stats.getDynamic().getMod(Stats.COMMAND_POINT_RATE_FLAT).modifyFlat(id + "_extra_cp", extraCommandPointRecovery);
			
			// 硬幅能耗散加成
			float hardFluxDissipation = actualSupporting * HARD_FLUX_DISSIPATION_PER_SHIP / 100f;
			stats.getHardFluxDissipationFraction().modifyFlat(id + "_hard_flux", hardFluxDissipation);
		} else {
			// 清除额外效果
			stats.getFluxDissipation().unmodify(id + "_extra");
			stats.getShieldDamageTakenMult().unmodify(id + "_shield");
			stats.getArmorDamageTakenMult().unmodify(id + "_armor");
			stats.getHullDamageTakenMult().unmodify(id + "_hull");
			stats.getEmpDamageTakenMult().unmodify(id + "_emp");
			stats.getDynamic().getMod(Stats.COMMAND_POINT_RATE_FLAT).unmodify(id + "_extra_cp");
			stats.getHardFluxDissipationFraction().unmodify(id + "_hard_flux");
		}
	}

	/**
	 * 为僚机应用队长机的光环效果
	 * 僚机在队长机周围时获得机动性+30%、速度+30%的增益
	 */
	public void applyWingmanBonus(ShipAPI wingman, ShipAPI captain) {
		String id = "captain_machine_wingman_bonus" + wingman.getId();
		MutableShipStatsAPI stats = wingman.getMutableStats();
		
		// 检查距离
		float dist = Misc.getDistance(wingman.getLocation(), captain.getLocation());
		
		if (dist < EFFECT_RANGE) {
			// 在范围内，应用增益
			stats.getMaxSpeed().modifyPercent(id, 30f);
			stats.getAcceleration().modifyPercent(id, 30f);
			stats.getDeceleration().modifyPercent(id, 30f);
			stats.getTurnAcceleration().modifyPercent(id, 30f);
			stats.getMaxTurnRate().modifyPercent(id, 30f);
			
			// 如果僚机是玩家舰船，显示状态栏
			if (wingman == Global.getCombatEngine().getPlayerShip()) {
				String icon = Global.getSettings().getSpriteName("ui", "icon_tactical_escort_package");
				String hullmodId = "Moci_CaptainMachine";
				
				String title = Moci_TextLoader.getText(hullmodId, "status.wingman_title");
				String effects = Moci_TextLoader.getText(hullmodId, "status.wingman_effects");
				
				Global.getCombatEngine().maintainStatusForPlayerShip(
					STATUS_KEY_WINGMAN, icon, title, effects, false);
			}
		} else {
			// 超出范围，移除增益
			stats.getMaxSpeed().unmodify(id);
			stats.getAcceleration().unmodify(id);
			stats.getDeceleration().unmodify(id);
			stats.getTurnAcceleration().unmodify(id);
			stats.getMaxTurnRate().unmodify(id);
		}
	}

	@Override
	public void advanceInCombat(ShipAPI ship, float amount) {
		super.advanceInCombat(ship, amount);

		if (!ship.isAlive()) return;
		if (amount <= 0f) return;
		
		CombatEngineAPI engine = Global.getCombatEngine();
		
		String key = CM_DATA_KEY + "_" + ship.getId();
		CaptainMachineData data = (CaptainMachineData) engine.getCustomData().get(key);
		if (data == null) {
			data = new CaptainMachineData();
			engine.getCustomData().put(key, data);
		}

		boolean playerShip = ship == Global.getCombatEngine().getPlayerShip();
		
		data.interval.advance(amount * 4f);
		if (data.interval.intervalElapsed() || playerShip) {
			float checkSize = EFFECT_RANGE + ship.getCollisionRadius() + 300f;
			checkSize *= 2f;
			
			Iterator<Object> iter = Global.getCombatEngine().getShipGrid().getCheckIterator(
											ship.getLocation(), checkSize, checkSize);
			
			int supportingShips = 0;
			List<ShipAPI> nearbyWingmen = new ArrayList<>();
			
			while (iter.hasNext()) {
				Object next = iter.next();
				if (!(next instanceof ShipAPI)) continue;
				
				ShipAPI other = (ShipAPI) next;
				
				if (other.getOwner() != ship.getOwner()) continue;
				if (other.isHulk()) continue;
				
				// 检查是否为同级舰
				if (!isSameClass(ship, other)) continue;
				
				float dist = Misc.getDistance(ship.getLocation(), other.getLocation());
				
				if (dist < EFFECT_RANGE) {
					supportingShips++;
					nearbyWingmen.add(other);
				}
			}
			
			applyCMEffect(ship, supportingShips);
			
			// 为附近的僚机应用增益效果
			for (ShipAPI wingman : nearbyWingmen) {
				applyWingmanBonus(wingman, ship);
			}
			
			data.supportingShips = supportingShips;
			data.bonusMagnitude = Math.min((float)supportingShips / MAX_SUPPORTING_SHIPS, 1f);
		}
		
		if (playerShip) {
			String icon = Global.getSettings().getSpriteName("ui", "icon_tactical_escort_package");
			String hullmodId = "Moci_CaptainMachine";
			
			if (data.supportingShips > 0) {
				// 计算具体的加成数值
				int actualSupporting = Math.min(data.supportingShips, MAX_SUPPORTING_SHIPS);
				int fluxBonus = (int)(BASE_FLUX_DISSIPATION_BONUS + actualSupporting * FLUX_DISSIPATION_BONUS_PER_SHIP);
				int defenseBonus = (int)(BASE_DEFENSE_BONUS + actualSupporting * DEFENSE_BONUS_PER_SHIP);
				int cpBonus = (int)((BASE_COMMAND_POINT_RECOVERY_BONUS + actualSupporting * COMMAND_POINT_BONUS_PER_SHIP) * 100);
				int hardFluxBonus = (int)(actualSupporting * HARD_FLUX_DISSIPATION_PER_SHIP);
				
				// 准备替换映射
				Map<String, String> statusReplacements = new HashMap<>();
				statusReplacements.put("count", String.valueOf(data.supportingShips));
				statusReplacements.put("max", String.valueOf(MAX_SUPPORTING_SHIPS));
				statusReplacements.put("flux", String.valueOf(fluxBonus));
				statusReplacements.put("defense", String.valueOf(defenseBonus));
				statusReplacements.put("cp", String.valueOf(cpBonus));
				statusReplacements.put("hard_flux", String.valueOf(hardFluxBonus));
				
				// 第一条状态：支援舰船数量
				String title = Moci_TextLoader.getText(hullmodId, "status.captain_title");
				String supportText = Moci_TextLoader.getTextWithReplacements(hullmodId, "status.captain_support", statusReplacements);
				Global.getCombatEngine().maintainStatusForPlayerShip(
						STATUS_KEY, icon, title, supportText, false);
				
				// 第二条状态：具体效果
				String synergyTitle = Moci_TextLoader.getText(hullmodId, "status.synergy_title");
				String effectsText = Moci_TextLoader.getTextWithReplacements(hullmodId, "status.synergy_effects", statusReplacements);
				Global.getCombatEngine().maintainStatusForPlayerShip(
						STATUS_KEY_EFFECTS, icon, synergyTitle, effectsText, false);
			} else {
				String title = Moci_TextLoader.getText(hullmodId, "status.captain_title");
				String noSupport = Moci_TextLoader.getText(hullmodId, "status.captain_no_support");
				Global.getCombatEngine().maintainStatusForPlayerShip(
						STATUS_KEY, icon, title, noSupport, true);
			}
		}
	}

	@Override
	public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
		float pad = 10f;
		float opad = 5f;
		Color highlight = Misc.getHighlightColor();
		Color good = Misc.getPositiveHighlightColor();
		Color bad = Misc.getNegativeHighlightColor();
		
		// 船插ID
		String hullmodId = "Moci_CaptainMachine";
		
		// 准备数值替换映射
		Map<String, String> replacements = new HashMap<>();
		replacements.put("cost", String.valueOf((int)((DEPLOYMENT_COST_MULT - 1) * 100)));
		replacements.put("flux_capacity", String.valueOf((int)BASE_FLUX_CAPACITY_BONUS));
		replacements.put("base_bonus", String.valueOf((int)BASE_FLUX_DISSIPATION_BONUS));
		replacements.put("range", String.valueOf((int)EFFECT_RANGE));
		replacements.put("max_ships", String.valueOf(MAX_SUPPORTING_SHIPS));
		replacements.put("flux_per_ship", String.valueOf((int)FLUX_DISSIPATION_BONUS_PER_SHIP));
		replacements.put("defense_per_ship", String.valueOf((int)DEFENSE_BONUS_PER_SHIP));
		replacements.put("cp_per_ship", String.valueOf((int)(COMMAND_POINT_BONUS_PER_SHIP * 100)));
		replacements.put("hard_flux_per_ship", String.valueOf((int)HARD_FLUX_DISSIPATION_PER_SHIP));
		replacements.put("max_flux", String.valueOf((int)MAX_FLUX_DISSIPATION_BONUS));
		replacements.put("base_flux", String.valueOf((int)BASE_FLUX_DISSIPATION_BONUS));
		replacements.put("max_defense", String.valueOf((int)MAX_DEFENSE_BONUS));
		replacements.put("base_defense", String.valueOf((int)BASE_DEFENSE_BONUS));
		replacements.put("max_cp", String.valueOf((int)(MAX_COMMAND_POINT_RECOVERY_BONUS * 100)));
		replacements.put("base_cp", String.valueOf((int)(BASE_COMMAND_POINT_RECOVERY_BONUS * 100)));
		replacements.put("max_hard_flux", String.valueOf((int)MAX_HARD_FLUX_DISSIPATION));
		replacements.put("base_hard_flux", String.valueOf((int)BASE_HARD_FLUX_DISSIPATION));
		
		// 部署点消耗
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.deployment_cost", replacements),
			pad, bad,
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.deployment_cost_highlights", replacements).toArray(new String[0])
		);
		
		// 基础效果说明
		tooltip.addSectionHeading(
			Moci_TextLoader.getText(hullmodId, "description.base_effects_heading"),
			Alignment.MID, opad
		);
		
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.base_effects", replacements),
			pad,
			new Color[] { good, good },
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.base_effects_highlights", replacements).toArray(new String[0])
		);
		
		// 协同效果说明
		tooltip.addSectionHeading(
			Moci_TextLoader.getText(hullmodId, "description.synergy_heading"),
			Alignment.MID, opad
		);
		
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.synergy_range", replacements),
			pad,
			new Color[] { highlight, highlight },
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.synergy_range_highlights", replacements).toArray(new String[0])
		);
		
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.synergy_per_ship", replacements),
			opad,
			new Color[] { good, good, good, good },
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.synergy_per_ship_highlights", replacements).toArray(new String[0])
		);
		
		// 满状态效果说明
		tooltip.addSectionHeading(
			Moci_TextLoader.getText(hullmodId, "description.full_squad_heading"),
			Alignment.MID, opad
		);
		
		// 散幅能力
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.full_flux", replacements),
			pad,
			new Color[] { highlight, good, good, good, good },
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.full_flux_highlights", replacements).toArray(new String[0])
		);
		
		// 伤害减免
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.full_defense", replacements),
			opad,
			new Color[] { highlight, good, good, good, good },
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.full_defense_highlights", replacements).toArray(new String[0])
		);
		
		// 命令点回复
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.full_cp", replacements),
			opad,
			new Color[] { highlight, good, good, good, good },
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.full_cp_highlights", replacements).toArray(new String[0])
		);
		
		// 硬幅能耗散
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.full_hard_flux", replacements),
			opad,
			new Color[] { highlight, good, good, good, good },
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.full_hard_flux_highlights", replacements).toArray(new String[0])
		);
	}
} 