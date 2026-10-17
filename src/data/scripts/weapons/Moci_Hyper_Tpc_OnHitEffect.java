package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;

import java.awt.Color;

public class Moci_Hyper_Tpc_OnHitEffect implements BeamEffectPlugin {

	// 硬辐能系统配置
	private static final float FLUX_MULT = 1f / 15f; //   占总幅能比
	private static final float MISSING_TARGET_FLUX_PENALTY = 0.5f; //未命中时产生的幅能值 功能移除
	private float accumulatedFlux = 0f;        // 已累计的硬辐能
	private float previousChargeLevel = 0f;    // 记录上一次充能等级
	private float dynamicTotalFlux; //实际产生幅能值

	// EMP系统配置
	private final IntervalUtil empInterval = new IntervalUtil(0f, 0.2f); // EMP触发间隔控制器
	private boolean wasZero = true;

	// 电弧配置
	private static final float DAMAGE_MULTIPLIER = 0.15f; // 穿盾电弧伤害比例
	private static final float EMP_MULTIPLIER = 0.15f;    // 穿盾电弧EMP伤害比例复制

	// 电弧数量配置
	private static final float FLUX_PER_ARC = 300f;   // 每300幅能增加1个电弧
	private static final float MIN_FLUX = 1400f;      // 最低触发幅能
	private static final float MAX_FLUX = 2600f;      // 最大触发上限
	private static final int BASE_ARCS = 1;           // 基础电弧数量
	private static final int MAX_ARCS = 5;            // 最大电弧数量
	// 光束宽度配置
	private static final float MIN_BEAM_WIDTH = 25f;   // 最窄宽度
	private static final float MAX_BEAM_WIDTH = 70f;   // 最宽宽度
	private static final float WIDTH_RANGE = MAX_BEAM_WIDTH - MIN_BEAM_WIDTH;

	// 电弧颜色配置
	private static final Color FRINGE_COLOR = new Color(115, 100, 255, 255);   // 电弧边缘颜色
	private static final Color CORE_COLOR = new Color(255, 255, 255, 255); // 电弧核心颜色

	@Override
	public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
		// 基础安全校验：暂停状态或无效光束直接返回
		if (engine.isPaused() || beam.getSource() == null) return;
		if (!(beam.getSource() instanceof ShipAPI)) return;

		final ShipAPI ship = (ShipAPI) beam.getSource();
		final WeaponAPI weapon = beam.getWeapon();

		handleHardFluxGeneration(ship, weapon, engine); // 处理硬辐能生成
		handleEmpEffect(engine, beam);          // 处理EMP效果
		// 动态光束宽度控制
		updateBeamWidth(beam);
	}

	/**
	 * 动态更新光束宽度
	 */
	private void updateBeamWidth(BeamAPI beam) {
		// 计算在MIN_FLUX和MAX_FLUX之间的比例
		float fluxRatio = (dynamicTotalFlux - MIN_FLUX) / (MAX_FLUX - MIN_FLUX);

		// 限制比例在0-1之间
		fluxRatio = Math.max(0f, Math.min(1f, fluxRatio));

		// 计算动态宽度
		float currentWidth = MIN_BEAM_WIDTH + (WIDTH_RANGE * fluxRatio);

		// 应用宽度变化
		beam.setWidth(currentWidth);
	}

	/**
	 * 硬辐能生成逻辑：
	 * 根据武器充能等级动态生成硬辐能
	 */
	private void handleHardFluxGeneration(ShipAPI ship, WeaponAPI weapon, CombatEngineAPI engine) {
		final float currentCharge = weapon.getChargeLevel();
		// 目标是否有效
		if (ship == null || ship.getFluxTracker() == null) return;
		// 动态获取舰船最大幅能并计算
		final float maxFlux = ship.getFluxTracker().getMaxFlux();
		dynamicTotalFlux = maxFlux * FLUX_MULT;

		if (currentCharge > 0f) {
			float fluxDelta = dynamicTotalFlux * (currentCharge - previousChargeLevel);

			if (fluxDelta > 0f) {
				ship.getFluxTracker().increaseFlux(fluxDelta, true);
				accumulatedFlux += fluxDelta;

				// 溢出检查
				if (accumulatedFlux >= dynamicTotalFlux) {
					accumulatedFlux = dynamicTotalFlux;
				}
			}
		} else {
			accumulatedFlux = 0f; // 充能中断时重置
		}
		previousChargeLevel = currentCharge; // 记录当前充能状态
	}

	/**
	 * EMP效果
	 */
	private void handleEmpEffect(CombatEngineAPI engine, BeamAPI beam) {
		if (beam.getBrightness() < 1.0f) return;

		// 目标有效性检查
		CombatEntityAPI target = beam.getDamageTarget();
		if (target == null || !engine.isEntityInPlay(target)) return;
		if (target.getLocation() == null) return; // 防止目标坐标为空

		// 时间间隔控制
                         		float dur = beam.getDamage().getDpsDuration();
		if (!wasZero) dur = 0f;
		wasZero = (beam.getDamage().getDpsDuration() <= 0f);

		empInterval.advance(dur);
		if (empInterval.intervalElapsed()) {
			Vector2f point = beam.getRayEndPrevFrame();
			// 动态计算电弧数量
			float effectiveFlux = Math.max(dynamicTotalFlux - MIN_FLUX, 0f);
			int extraArcs = (int) (effectiveFlux / FLUX_PER_ARC);
			int totalArcs = BASE_ARCS + extraArcs;
			totalArcs = Math.min(totalArcs, MAX_ARCS);

			// 根据dynamicTotalFlux范围修正电弧数量
			if (dynamicTotalFlux <= MIN_FLUX) {
				totalArcs = BASE_ARCS;
			} else if (dynamicTotalFlux >= MAX_FLUX) {
				totalArcs = MAX_ARCS;
			}

			// 生成多个电弧
			for (int i = 0; i < totalArcs; i++) {
				engine.spawnEmpArcPierceShields(
						beam.getSource(),
						point,
						target,
						target,
						DamageType.ENERGY,
						dynamicTotalFlux * DAMAGE_MULTIPLIER,
						dynamicTotalFlux * EMP_MULTIPLIER,
						100000f,
						"tachyon_lance_emp_impact",
						beam.getWidth() + 10f,
						FRINGE_COLOR,
						CORE_COLOR
				);
			}
		}
	}
}
/**
 * 护盾穿透判断逻辑：
 * 根据目标硬辐能水平和护盾状态计算穿透概率
 */
	//private boolean shouldPierceShields(ShipAPI target, BeamAPI beam) {
	//	final boolean hitShield = target.getShield() != null
	//			&& target.getShield().isWithinArc(beam.getTo());

		// 基础穿透概率 = 目标硬辐能水平 - 0.1
	//	final float basePierceChance = target.getHardFluxLevel() - 0.1f;
		// 应用目标的护盾穿透抗性修正
	//	final float modifiedPierceChance = basePierceChance
	//			* target.getMutableStats().getDynamic().getValue(Stats.SHIELD_PIERCED_MULT);

	//	return !hitShield || (Math.random() < modifiedPierceChance);
	//