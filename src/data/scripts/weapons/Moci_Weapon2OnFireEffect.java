package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import org.lwjgl.util.vector.Vector2f;

public class Moci_Weapon2OnFireEffect implements OnFireEffectPlugin {
//
//	private WeaponAPI weapon;  // 当前武器实例
//	private ShipAPI ship;     // 所属飞船
//	private float timer = 0;  // 计时器
//	private boolean fired = false;  // 是否已发射
//	private ShipAPI target = null;  // 目标飞船
//
//	// 武器状态枚举
//	private State state = State.IDLE;
//
//	enum State {
//		IDLE,       // 空闲状态
//		CHARGE_UP,  // 蓄力状态
//		PUNCH,      // 攻击状态
//		DE_CHARGE   // 收回状态
//	}
	/**
	 * 武器发射时调用的方法
	 */
	@Override
	public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
		Moci_Weapon2Effect.Moci_EffectScript sc =Moci_Weapon2Effect.getEffectScriptInstance(weapon.getShip());
		sc.setFire();
//		this.weapon = weapon;
//		this.ship = weapon.getShip();
//
//		// 移除发射的弹丸
		engine.removeEntity(projectile);
//
//		// 添加监听器
//		if (!ship.hasListenerOfClass(Moci_Weapon2OnFireEffect.class)) {
//			ship.addListener(this);
//		}
	}

//	/**
//	 * 每帧更新方法
//	 */
//	@Override
//	public void advance(float amount) {
//		CombatEngineAPI engine = Global.getCombatEngine();
//		if (engine == null || engine.isPaused()) {
//			return;
//		}
//
//		// 保留的状态判断条件
//		if (state == State.IDLE && weapon.isFiring() && weapon.getCooldownRemaining() == 0) {
//			state = State.CHARGE_UP;
//			timer = 0;
//			fired = false;
//			target = null;
//
//			// 查找并触发使用Moci_Weapon2Effect的武器
//			for (WeaponAPI w : ship.getAllWeapons()) {
//				if (w.getEffectPlugin() instanceof Moci_Weapon2Effect) {
//					// 重置冷却并强制开火
//					w.setRemainingCooldownTo(0); // 理论上前面判断了冷却与否，这里是否有必要？
//					w.setForceFireOneFrame(true);
//					break;
//				}
//			}
//		}
//	}
}