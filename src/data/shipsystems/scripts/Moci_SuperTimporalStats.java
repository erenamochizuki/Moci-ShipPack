package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import java.awt.Color;

public class Moci_SuperTimporalStats extends BaseShipSystemScript {

	// ===== 推进系统参数 =====
	private static final float SPEED_BOOST = 600f; // 最大速度加成基数
	private static final float TURN_SPEED = 600f; // 最大转向速度加成基数
	private static final float ACCEL_BOOST = 1200f; // 加速度加成基数
	// ===== 武器系统参数 =====
	private static final float WEAPON_BOOST = 2f; // 武器冷却基数
	private static final float WEAPON_REDUCE = 0.5f; // 武器耗能降低

	// ===== 时间操控参数 =====
	private static final float MAX_TIME_MULT = 3.33f; // 最大时间加速倍率
	private static final float MIN_TIME_MULT = 0.5f; // 最小时间减速倍率
	private static final float REPAIR_RATE_MULT = 10f; // 维修速度倍率
	private static final Color JITTER_COLOR = new Color(90, 165, 255, 55); // 外层抖动光效
	private static final Color JITTER_UNDER_COLOR = new Color(90, 165, 255, 155); // 内层抖动光效

	// ===== 系统状态跟踪 =====
	private String uniqueId; // 实例唯一标识
	private boolean isPlayerShip = false;
	private boolean soundPlayed = false; // 音效播放状态跟踪

	@Override
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		// 获取舰船实体与标识
		ShipAPI ship = validateShip(stats);
		if (ship == null)
			return;

		// 根据系统状态应用不同效果
		if (state == State.OUT) {
			handleSystemDeactivation(stats, id);
		} else {
			// 系统激活时播放音效（仅播放一次）
			if (!soundPlayed && (state == State.IN || state == State.ACTIVE)) {
				playActivationSound(ship);
				soundPlayed = true;
			}

			applyMovementBoost(stats, id, effectLevel);
			applyTimeManipulation(ship, id, effectLevel);
			applyVisualEffects(ship, state, effectLevel);
		}
	}

	@Override
	public void unapply(MutableShipStatsAPI stats, String id) {
		// 移除所有效果修饰器
		stats.getMaxSpeed().unmodify(id);
		stats.getTurnAcceleration().unmodify(id);
		stats.getAcceleration().unmodify(id);
		stats.getTimeMult().unmodify(id);
		stats.getCombatEngineRepairTimeMult().unmodify(id);
		stats.getCombatWeaponRepairTimeMult().unmodify(id);
		Global.getCombatEngine().getTimeMult().unmodify(uniqueId);

		// 重置音效播放状态，准备下次激活
		soundPlayed = false;
	}

	@Override
	public StatusData getStatusData(int index, State state, float effectLevel) {
		// 状态栏显示逻辑
		if (index == 0) {
			return new StatusData("时空加速场激活", false);
		}
		if (index == 1) {
			float timeMult = 1f + (MAX_TIME_MULT - 1f) * effectLevel;
			return new StatusData("主观时间流速 x" + String.format("%.1f", timeMult), false);
		}
		return null;
	}

	// ===== 核心功能模块 =====

	/**
	 * 舰船实体验证与初始化
	 */
	private ShipAPI validateShip(MutableShipStatsAPI stats) {
		if (!(stats.getEntity() instanceof ShipAPI))
			return null;
		ShipAPI ship = (ShipAPI) stats.getEntity();
		isPlayerShip = (ship == Global.getCombatEngine().getPlayerShip());
		uniqueId = getIdWithShip(ship); // 生成唯一ID
		return ship;
	}

	/**
	 * 应用推进系统增益
	 */
	private void applyMovementBoost(MutableShipStatsAPI stats, String id, float effectLevel) {
		stats.getMaxSpeed().modifyFlat(id, SPEED_BOOST * effectLevel);
		stats.getTurnAcceleration().modifyFlat(id, TURN_SPEED * effectLevel);
		stats.getAcceleration().modifyFlat(id, ACCEL_BOOST * effectLevel);
		// 维修系统超频
		stats.getCombatEngineRepairTimeMult().modifyMult(id, 1f / REPAIR_RATE_MULT);
		stats.getCombatWeaponRepairTimeMult().modifyMult(id, 1f / REPAIR_RATE_MULT);
		// 武器快速冷却
		stats.getEnergyAmmoRegenMult().modifyMult(id, WEAPON_BOOST);
		stats.getBallisticAmmoRegenMult().modifyMult(id, WEAPON_BOOST);
		stats.getMissileAmmoRegenMult().modifyMult(id, WEAPON_BOOST);
		// 武器耗能降低
		stats.getEnergyWeaponFluxCostMod().modifyMult(id, WEAPON_REDUCE);
		stats.getBallisticWeaponFluxCostMod().modifyMult(id, WEAPON_REDUCE);
		stats.getMissileWeaponFluxCostMod().modifyMult(id, WEAPON_REDUCE);
	}

	/**
	 * 应用时间操控效果
	 */
	private void applyTimeManipulation(ShipAPI ship, String id, float effectLevel) {
		// 计算时间倍率（二次曲线平滑）
		float timeMult = 1f + (MAX_TIME_MULT - 1f) * effectLevel * effectLevel;
		ship.getMutableStats().getTimeMult().modifyMult(id, timeMult);

		// 玩家舰船需要补偿全局时间流速
		if (isPlayerShip) {
			Global.getCombatEngine().getTimeMult().modifyMult(uniqueId, 1f / timeMult);
		}
	}

	/**
	 * 应用视觉特效
	 */
	private void applyVisualEffects(ShipAPI ship, State state, float effectLevel) {
		// 抖动效果参数计算
		float jitterLevel = (state == State.IN) ? Math.min(1f, effectLevel / ship.getSystem().getChargeUpDur())
				: effectLevel;
		jitterLevel = (float) Math.sqrt(jitterLevel); // 平方根平滑

		// 设置双层抖动效果
		ship.setJitter(this, JITTER_COLOR, jitterLevel, 3, 0, 5f);
		ship.setJitterUnder(this, JITTER_UNDER_COLOR, jitterLevel, 25, 0f, 10f);

		// 引擎火焰特效
		ship.getEngineController().fadeToOtherColor(this, JITTER_COLOR, new Color(0, 0, 0, 0), effectLevel, 0.5f);
		ship.getEngineController().extendFlame(this, -0.25f, -0.25f, -0.25f);
	}

	/**
	 * 系统关闭时的过渡处理
	 */
	private void handleSystemDeactivation(MutableShipStatsAPI stats, String id) {
		stats.getMaxSpeed().unmodify(id); // 平滑减速至正常速度
	}

	/**
	 * 播放系统激活音效
	 */
	private void playActivationSound(ShipAPI ship) {
		// 播放鲸鸣音效
		Global.getSoundPlayer().playSound("Moci_Whales_call", 1f, 0.5f,
				ship.getLocation(), ship.getVelocity());
	}

	/**
	 * 生成带舰船ID的唯一标识
	 */
	private String getIdWithShip(ShipAPI ship) {
		return this.getClass().getSimpleName() + "_" + ship.getId();
	}
}