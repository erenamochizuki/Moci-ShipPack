package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipSystemAPI.SystemState;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import com.fs.starfarer.api.util.Misc;
import java.awt.*;
import java.util.List;
import java.util.Map;

public class Moci_AcausalDisruptorStats extends BaseShipSystemScript {
	public static float DISRUPTION_DUR = 0.1F; //过载时间
	protected static float MIN_DISRUPTION_RANGE = 700.0F; //系统射程
	public static final Color OVERLOAD_COLOR = new Color(255, 155, 255, 255);
	public static final Color JITTER_COLOR = new Color(255, 155, 255, 75);
	public static final Color JITTER_UNDER_COLOR = new Color(255, 155, 255, 155);

	// DEBUFF参数
	public static final String SLOW_ID = "moci_acausal_slow"; // 唯一标识符
	public static final float SPEED_REDUCTION = 0.1f;         // 速度降低
	public static final float DEBUFF_DURATION = 1f;          // 持续时间

	@Override
	public void apply(MutableShipStatsAPI stats, String id, ShipSystemStatsScript.State state, float effectLevel) {
		ShipAPI ship = (ShipAPI) stats.getEntity();
		if (ship == null) return;

		// 抖动效果逻辑
		float jitterLevel = (state == State.OUT) ? effectLevel * effectLevel : effectLevel;
		ship.setJitterUnder(this, JITTER_UNDER_COLOR, jitterLevel, 21, 0.0F, 3.0F + jitterLevel * 100.0F);
		ship.setJitter(this, JITTER_COLOR, jitterLevel, 4, 0.0F, 0.0F);

		// 目标管理逻辑
		String targetKey = ship.getId() + "_acausal_target";
		Object foundTarget = Global.getCombatEngine().getCustomData().get(targetKey);

		if (state == State.IN && foundTarget == null) {
			// 系统激活时寻找目标
			ShipAPI target = findTarget(ship);
			if (target != null) {
				Global.getCombatEngine().getCustomData().put(targetKey, target);
			}
		} else if (effectLevel >= 1.0F && foundTarget instanceof ShipAPI) {
			// 系统完全生效时施加效果
			ShipAPI target = (ShipAPI) foundTarget;
			if (!target.getFluxTracker().isOverloadedOrVenting()) {
				applyEffectToTarget(ship, target);
			}
		} else if (state == State.OUT) {
			// 系统关闭时清理数据
			Global.getCombatEngine().getCustomData().remove(targetKey);
		}
	}

	// 施加过载和减速效果
	protected void applyEffectToTarget(ShipAPI ship, final ShipAPI target) {
		if (target == null || target == ship) return;

		// 施加过载效果
		target.setOverloadColor(OVERLOAD_COLOR);
		target.getFluxTracker().beginOverloadWithTotalBaseDuration(DISRUPTION_DUR);

		// 施加速度debuff
		target.getMutableStats().getMaxSpeed().modifyMult(SLOW_ID, SPEED_REDUCTION);

		// 记录debuff状态
		String dataKey = SLOW_ID + "_" + target.getId();
		Global.getCombatEngine().getCustomData().put(dataKey, DEBUFF_DURATION);

		// 添加自动清理
		Global.getCombatEngine().addPlugin(new BaseEveryFrameCombatPlugin() {
			@Override
			public void advance(float amount, List<InputEventAPI> events) {
				if (Global.getCombatEngine().isPaused()) return;
				if (!target.isAlive()) {
					removeDebuff(target);
					Global.getCombatEngine().removePlugin(this);
					return;
				}

				// 更新持续时间
				Map<String, Object> data = Global.getCombatEngine().getCustomData();
				String key = SLOW_ID + "_" + target.getId();
				Float remaining = (Float) data.get(key);

				if (remaining == null || remaining <= 0) {
					removeDebuff(target);
					Global.getCombatEngine().removePlugin(this);
				} else {
					data.put(key, remaining - amount);
				}
			}
		});
		// 显示浮字提示
		target.getFluxTracker().showOverloadFloatyIfNeeded("相位崩溃!", OVERLOAD_COLOR, 4.0F, true);
	}

	// 目标筛选逻辑
	protected ShipAPI findTarget(ShipAPI ship) {
		float range = getMaxRange(ship);
		ShipAPI target = null;

		// 优先选择手动指定目标
		if (ship.getShipAI() != null && ship.getAIFlags().hasFlag(AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
			target = (ShipAPI) ship.getAIFlags().getCustom(AIFlags.TARGET_FOR_SHIP_SYSTEM);
		} else {
			target = ship.getShipTarget();
		}

		// 目标有效性检查
		if (target != null) {
			float distance = Misc.getDistance(ship.getLocation(), target.getLocation());
			if (distance > range + ship.getCollisionRadius() + target.getCollisionRadius()) {
				target = null;
			}
		}

		// 自动搜索最近目标
		if (target == null) {
			target = Misc.findClosestShipEnemyOf(
					ship,
					ship.getMouseTarget(),
					HullSize.FIGHTER,
					range,
					true
			);
		}

		// 排除无效目标
		if (target != null && (
				hasSlowDebuff(target) ||                   // 已有减速debuff
						target.getFluxTracker().isOverloadedOrVenting() || // 目标已过载
						!isPhaseShip(target)                       // 修正后的相位判断
		)) {
			target = null;
		}

		return target != null ? target : ship; // 保底返回自身防止
	}

	// 辅助方法：检查目标是否是相位船
	public static boolean isPhaseShip(ShipAPI ship) {
		if (ship == null) return false;

		// 检查当前相位状态
		if (ship.isPhased()) return true;

		// 检查相位系统
		ShipSystemAPI system = ship.getPhaseCloak();
		if (system == null) {
			// 可能系统本身是相位系统
			system = ship.getSystem();
		}
		return system != null && system.getSpecAPI().hasTag("phase_cloak");
	}




	// 辅助方法：检查目标是否已有debuff
	private boolean hasSlowDebuff(ShipAPI target) {
		return Global.getCombatEngine().getCustomData()
				.containsKey(SLOW_ID + "_" + target.getId());
	}

	// 辅助方法：清理debuff
	private void removeDebuff(ShipAPI target) {
		target.getMutableStats().getMaxSpeed().unmodify(SLOW_ID);
		target.resetOverloadColor();
		Global.getCombatEngine().getCustomData()
				.remove(SLOW_ID + "_" + target.getId());
	}

	// 辅助方法：浮字显示条件判断
	private boolean shouldShowFloaty(ShipAPI ship, ShipAPI target) {
		return target.getFluxTracker().showFloaty() ||
				ship == Global.getCombatEngine().getPlayerShip() ||
				target == Global.getCombatEngine().getPlayerShip();
	}

	// 系统范围计算
	public static float getMaxRange(ShipAPI ship) {
		return ship.getMutableStats().getSystemRangeBonus()
				.computeEffective(MIN_DISRUPTION_RANGE);
	}

	// 系统状态提示
	@Override
	public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
		if (system.isOutOfAmmo() || system.getState() != SystemState.IDLE) return null;

		ShipAPI target = findTarget(ship);
		if (target != null && target != ship) return "READY";
		if (ship.getShipTarget() != null) return "OUT OF RANGE";
		return "NO TARGET";
	}

	// 系统可用性判断
	@Override
	public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
		return findTarget(ship) != null && findTarget(ship) != ship;
	}
}
