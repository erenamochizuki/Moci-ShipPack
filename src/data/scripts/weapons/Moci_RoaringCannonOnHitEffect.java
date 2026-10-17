package data.scripts.weapons;

import java.awt.Color;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;

public class Moci_RoaringCannonOnHitEffect implements OnHitEffectPlugin {


	public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target,
					  Vector2f point, boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
		if ((float) Math.random() > 0.75f && !shieldHit && target instanceof ShipAPI) {

			float emp = projectile.getEmpAmount();
			float dam = projectile.getDamageAmount();

			// 主电弧效果
			engine.spawnEmpArc(projectile.getSource(), point, target, target,
					DamageType.ENERGY,
					dam,
					emp, // emp
					100000f, // max range
					"tachyon_lance_emp_impact",
					20f, // thickness
					new Color(155, 25, 25,255),
					new Color(255,255,255,255)
			);

			// 新增：向周围散布电弧
			int arcCount = 3 + (int)(Math.random() * 3); // 随机生成3-5个电弧
			float arcRange = 200f; // 电弧散布范围

			for (int i = 0; i < arcCount; i++) {
				// 随机生成电弧起点和终点
				Vector2f startPoint = MathUtils.getRandomPointInCircle(point, arcRange * 0.5f);
				Vector2f endPoint = MathUtils.getRandomPointInCircle(point, arcRange);

				// 生成电弧效果
				engine.spawnEmpArc(
						projectile.getSource(),
						startPoint,
						null, // 不绑定特定目标
						null,
						DamageType.ENERGY,
						dam * 0.2f, // 伤害为主电弧的20%
						emp * 0.2f, // EMP为主电弧的20%
						100000f, // 最大范围
						"tachyon_lance_emp_impact",
						10f + (float)Math.random() * 10f, // 随机厚度
						new Color(155, 25, 25, 200),
						new Color(255, 255, 255, 200)
				);
			}
		}
	}
}
