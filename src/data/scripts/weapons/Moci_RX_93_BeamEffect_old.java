package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.util.IntervalUtil;

public class Moci_RX_93_BeamEffect_old implements BeamEffectPlugin {

	private IntervalUtil effectInterval = new IntervalUtil(0.2f, 0.3f);

	@Override
	public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
		if (engine == null || engine.isPaused()) {
			return;
		}

		CombatEntityAPI target = beam.getDamageTarget();

		if (target != null && beam.getBrightness() > 0.1f) { // 光束激活并命中目标
			effectInterval.advance(amount); // 按帧时间推进计时器

			if (effectInterval.intervalElapsed()) {
				// 检查效果载荷的原始条件
				if (beam.getDamage().getDpsDuration() > 0) {
					// 原始载荷：
					float duration = beam.getDamage().getDpsDuration();
					float dmg = beam.getDamage().getDamage() * duration;
					Vector2f loc = beam.getTo();
					engine.applyDamage(target, loc, dmg, DamageType.HIGH_EXPLOSIVE, 0, false, true, beam.getSource());

					engine.addSmoothParticle(
							loc,
							new Vector2f(),
							125f,
							1f,
							0.25f,
							new Color(207, 30, 255, 100)
					);
					engine.addSmoothParticle(
							loc,
							new Vector2f(),
							50f,
							1f,
							0.25f,
							new Color(255, 255, 255, 220)
					);
				}
			}
		}
	}
}
