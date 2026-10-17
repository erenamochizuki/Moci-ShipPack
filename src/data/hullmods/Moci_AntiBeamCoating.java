package data.hullmods;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;

public class Moci_AntiBeamCoating extends BaseLogisticsHullMod {

	public static float CORONA_EFFECT_MULT = 0f; // 日冕效应乘数
	public static float BEAM_DAMAGE_MULT = 0.5f; // 光束伤害降低
	/**
	 * 在飞船创建前应用效果
	 * @param hullSize 船体尺寸
	 * @param stats 可变的飞船状态
	 * @param id 插件ID
	 */
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {

		boolean sMod = isSMod(stats);

		stats.getBeamDamageTakenMult().modifyMult(id, 1-BEAM_DAMAGE_MULT);

		stats.getDynamic().getStat(Stats.CORONA_EFFECT_MULT).modifyMult(id, CORONA_EFFECT_MULT);

		// 内插增益
		if (sMod) {
		}
	}

	/**
	 * 获取S模式描述参数
	 * @param index 参数索引
	 * @param hullSize 船体尺寸
	 * @return 格式化后的参数值
	 */
	public String getSModDescriptionParam(int index, HullSize hullSize) {

		return null;
	}

	/**
	 * 获取普通模式描述参数
	 * @param index 参数索引
	 * @param hullSize 船体尺寸
	 * @return 格式化后的参数值
	 */
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + BEAM_DAMAGE_MULT * 100 + "%"; // 光束抵抗
		return null;
	}
}