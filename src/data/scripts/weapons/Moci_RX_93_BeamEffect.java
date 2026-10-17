package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.util.IntervalUtil;

public class Moci_RX_93_BeamEffect implements BeamEffectPlugin {

	private IntervalUtil effectInterval = new IntervalUtil(0.2f, 0.2f);

	@Override
	public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
		if (engine == null || engine.isPaused()) {
			return;
		}

		CombatEntityAPI target = beam.getDamageTarget();

		if (target != null && beam.getBrightness() > 0.9f) { // 光束激活并命中目标
			effectInterval.advance(amount); // 按帧时间推进计时器

			if (effectInterval.intervalElapsed()) {
				// 检查效果载荷的原始条件
				if (beam.getDamage().getDpsDuration() > 0) {
					// 计算爆炸伤害 - 基于光束的原始伤害
					float baseDamage = beam.getDamage().getDamage();
					float duration = beam.getDamage().getDpsDuration();
					float explosionDamage = baseDamage * duration * 0.5f; // 爆炸伤害为光束伤害的50%
					
					// 直接使用光束终点作为爆炸位置
					Vector2f explosionPoint = beam.getTo();

					// 生成爆炸效果
					DamagingProjectileAPI explosion = engine.spawnDamagingExplosion(
							createRX93ExplosionSpec(explosionDamage), // 爆炸规格
							beam.getSource(), // 爆炸来源（发射光束的舰船）
							explosionPoint // 爆炸位置（光束命中点）
					);

					// 将当前目标添加到已受伤害列表中，避免爆炸对同一目标造成重复伤害
					explosion.addDamagedAlready(target);
				}
			}
		}
	}

	private DamagingExplosionSpec createRX93ExplosionSpec(float damage) {
		// 创建爆炸规格对象，配置RX-93专用的爆炸参数
		DamagingExplosionSpec spec = new DamagingExplosionSpec(
				0.5f, // duration - 爆炸持续时间（秒）
				50f, // radius - 爆炸影响半径（像素）
				25f, // coreRadius - 核心伤害半径（像素）
				damage, // maxDamage - 最大伤害值（核心区域）
				damage * 0.6f, // minDamage - 最小伤害值（边缘区域）
				CollisionClass.PROJECTILE_FF, // collisionClass - 碰撞类型（友军火力）
				CollisionClass.PROJECTILE_FIGHTER, // collisionClassByFighter - 战机碰撞类型
				3f, // particleSizeMin - 粒子最小尺寸
				3f, // particleSizeRange - 粒子尺寸变化范围
				2f, // particleDuration - 粒子持续时间（秒）
				200, // particleCount - 粒子数量
				new Color(207, 30, 255, 255), // particleColor - 粒子颜色（紫色）
				new Color(255, 175, 255, 100) // explosionColor - 爆炸颜色（亮紫色）
		);

		// 设置伤害类型为高爆伤害
		spec.setDamageType(DamageType.HIGH_EXPLOSIVE);

		// 设定为Flase不会叠加
		spec.setUseDetailedExplosion(false);

		// 设置音效ID为能量爆炸音效，提供合适的音频反馈
		spec.setSoundSetId("explosion_guardian");

		return spec;
	}

}