package data.shipsystems.scripts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import java.awt.Color;

import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.impl.combat.DroneStrikeStatsAIInfoProvider;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI.SystemState;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Moci版本的无人机攻击系统（基于原版DroneStrikeStats）
 * 
 * 核心功能：将载机转换为制导导弹进行攻击
 * 工作原理：
 * 1. 系统激活时选择目标（玩家鼠标指向或AI自动选择）
 * 2. 将指定数量的无人机从舰载机舱中移除
 * 3. 生成对应的制导导弹，无人机实体跟随导弹移动
 * 4. 导弹命中目标或被摧毁时，无人机实体也被销毁并产生爆炸效果
 * 
 * 技术特点：
 * - 使用DroneMissileScript类管理无人机-导弹的绑定关系
 * - 实现DroneStrikeStatsAIInfoProvider接口为AI提供决策信息
 * - 支持玩家手动目标选择和AI自动目标选择
 * - 具有射程限制和可用性检查
 */
public class Moci_DroneStrikeStats extends BaseShipSystemScript implements DroneStrikeStatsAIInfoProvider {
	
	/**
	 * 无人机-导弹绑定脚本
	 * 
	 * 功能说明：
	 * - 将无人机实体与导弹实体绑定，使无人机跟随导弹移动
	 * - 无人机作为视觉载体，导弹作为功能载体（伤害、制导、物理）
	 * - 处理无人机的视觉效果（抖动、透明度、引擎效果）
	 * - 管理生命周期：导弹命中/摧毁时同时清理无人机实体
	 */
	public static class DroneMissileScript extends BaseCombatLayeredRenderingPlugin {
		protected ShipAPI drone;      // 绑定的无人机实体
		protected MissileAPI missile; // 绑定的导弹实体
		protected boolean done;       // 脚本是否已完成
		
		/**
		 * 构造函数：初始化无人机-导弹绑定
		 * @param drone 要绑定的无人机实体
		 * @param missile 要绑定的导弹实体
		 */
		public DroneMissileScript(ShipAPI drone, MissileAPI missile) {
			super();
			this.drone = drone;
			this.missile = missile;
			// 防止导弹因为燃料耗尽而失效
			missile.setNoFlameoutOnFizzling(true);
		}

		@Override
		public void advance(float amount) {
			super.advance(amount);
			
			if (done) return;
			
			CombatEngineAPI engine = Global.getCombatEngine();
			
			// === 导弹属性设置 ===
			missile.setEccmChanceOverride(1f);              // 100%抗电子干扰
			missile.setOwner(drone.getOriginalOwner());     // 设置导弹归属
			
			// === 无人机跟随导弹移动 ===
			drone.getLocation().set(missile.getLocation()); // 位置同步
			drone.getVelocity().set(missile.getVelocity()); // 速度同步
			drone.setCollisionClass(CollisionClass.FIGHTER); // 设置碰撞类型为战机
			drone.setFacing(missile.getFacing());            // 朝向同步
			
			// === 隐藏无人机引擎效果 ===
			drone.getEngineController().fadeToOtherColor(this, new Color(0,0,0,0), new Color(0,0,0,0), 1f, 1f);

			// === 计算抖动效果强度 ===
			// 基于飞行距离或飞行时间计算抖动强度，模拟无人机不稳定状态
			float dist = Misc.getDistance(missile.getLocation(), missile.getStart());
			float jitterFraction = dist / missile.getMaxRange();
			jitterFraction = Math.max(jitterFraction, missile.getFlightTime() / missile.getMaxFlightTime());
			
			// === 视觉效果设置 ===
			missile.setSpriteAlphaOverride(0f);              // 完全隐藏导弹贴图
			float jitterMax = 1f + 10f * jitterFraction;     // 抖动强度随距离/时间增加
			// 设置无人机抖动效果：橙红色，强度随飞行进度增加
			drone.setJitter(this, new Color(255,100,50, (int)(25 + 50 * jitterFraction)), 1f, 10, 1f, jitterMax);
			
			// === 生命周期管理 ===
			boolean droneDestroyed = drone.isHulk() || drone.getHitpoints() <= 0;
			
			// 导弹失效或无人机被摧毁时的处理
			if (missile.isFizzling() || (missile.getHitpoints() <= 0 && !missile.didDamage()) || droneDestroyed) {
				// 停止移动
				drone.getVelocity().set(0, 0);
				missile.getVelocity().set(0, 0);
				
				// 如果无人机未被摧毁，主动摧毁它
				if (!droneDestroyed) {
					Vector2f damageFrom = new Vector2f(drone.getLocation());
					damageFrom = Misc.getPointWithinRadius(damageFrom, 20);
					engine.applyDamage(drone, damageFrom, 1000000f, DamageType.ENERGY, 0, true, false, drone, false);
				}
				
				// 清理实体
				missile.interruptContrail();    // 中断导弹尾迹
				engine.removeEntity(drone);     // 移除无人机实体
				engine.removeEntity(missile);   // 移除导弹实体
				
				missile.explode();              // 触发导弹爆炸
				done = true;
				return;
			}
			
			// 导弹成功命中目标时的处理
			if (missile.didDamage()) {
				// 停止移动
				drone.getVelocity().set(0, 0);
				missile.getVelocity().set(0, 0);
				
				// 摧毁无人机
				Vector2f damageFrom = new Vector2f(drone.getLocation());
				damageFrom = Misc.getPointWithinRadius(damageFrom, 20);
				engine.applyDamage(drone, damageFrom, 1000000f, DamageType.ENERGY, 0, true, false, drone, false);
				
				// 清理实体
				missile.interruptContrail();
				engine.removeEntity(drone);
				engine.removeEntity(missile);
				done = true;
				return;
			}
		}

		@Override
		public boolean isExpired() {
			return done;
		}
	}
	
	/**
	 * 获取系统使用的导弹武器ID
	 * 可在子类中重写以使用不同的导弹类型
	 */
	protected String getWeaponId() {
		return "terminator_missile";
	}
	
	/**
	 * 获取每次激活系统时发射的无人机数量
	 * 可在子类中重写以调整发射数量
	 */
	protected int getNumToFire() {
		return 1;
	}
	
	protected WeaponAPI weapon;  // 虚拟武器实例，用于生成导弹
	protected boolean fired = false; // 防止重复发射的标志
	
	/**
	 * 系统主要逻辑入口点
	 * 在系统激活期间每帧调用
	 * 
	 * @param stats 舰船属性
	 * @param id 系统ID
	 * @param state 系统状态
	 * @param effectLevel 效果等级（0-1，表示激活进度）
	 */
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		ShipAPI ship = null;
		if (stats.getEntity() instanceof ShipAPI) {
			ship = (ShipAPI) stats.getEntity();
		} else {
			return;
		}
		
		// 初始化虚拟武器（用于生成导弹）
		if (weapon == null) {
			weapon = Global.getCombatEngine().createFakeWeapon(ship, getWeaponId());
		}
		
		// 为所有无人机设置爆炸效果
		for (ShipAPI drone : getDrones(ship)) {
			drone.setExplosionScale(0.67f);                          // 爆炸规模
			drone.setExplosionVelocityOverride(new Vector2f());      // 爆炸速度（静止）
			drone.setExplosionFlashColorOverride(new Color(255, 100, 50, 255)); // 橙红色爆炸
		}
		
		// 系统激活且未发射时执行转换
		if (effectLevel > 0 && !fired) {
			if (!getDrones(ship).isEmpty()) {
				ShipAPI target = findTarget(ship);  // 寻找目标
				convertDrones(ship, target);        // 转换无人机为导弹
			}
		} else if (state == State.IDLE){
			fired = false; // 系统空闲时重置发射标志
		}
	}
	
	/**
	 * 核心功能：将无人机转换为制导导弹
	 * 
	 * 执行步骤：
	 * 1. 根据目标距离对无人机进行排序（最近的优先）
	 * 2. 为指定数量的无人机生成对应的导弹
	 * 3. 设置导弹的制导目标和属性
	 * 4. 创建DroneMissileScript绑定无人机和导弹
	 * 5. 生成视觉效果（电弧）
	 * 
	 * @param ship 发射舰船
	 * @param target 攻击目标（可为null）
	 */
	public void convertDrones(ShipAPI ship, final ShipAPI target) {
		CombatEngineAPI engine = Global.getCombatEngine();
		fired = true;
		forceNextTarget = null;
		int num = 0;
		
		List<ShipAPI> drones = getDrones(ship);
		
		// 根据是否有目标进行排序
		if (target != null) {
			// 有目标时：按距离目标的远近排序，最近的优先
			Collections.sort(drones, new Comparator<ShipAPI>() {
				public int compare(ShipAPI o1, ShipAPI o2) {
					float d1 = Misc.getDistance(o1.getLocation(), target.getLocation());
					float d2 = Misc.getDistance(o2.getLocation(), target.getLocation());
					return (int)Math.signum(d1 - d2);
				}
			});
		} else {
			// 无目标时：随机排序
			Collections.shuffle(drones);
		}
		
		// 转换指定数量的无人机
		for (ShipAPI drone : drones) {
			if (num < getNumToFire()) {
				// === 生成导弹 ===
				MissileAPI missile = (MissileAPI) engine.spawnProjectile(
						ship, weapon, getWeaponId(), 
						new Vector2f(drone.getLocation()), drone.getFacing(), new Vector2f(drone.getVelocity()));
				
				// === 设置导弹制导目标 ===
				if (target != null && missile.getAI() instanceof GuidedMissileAI) {
					GuidedMissileAI ai = (GuidedMissileAI) missile.getAI();
					ai.setTarget(target);
				}
				
				// === 导弹属性调整 ===
				missile.setEmpResistance(10000); // 高EMP抗性
				
				// 射程调整：基于舰船系统射程加成
				float base = missile.getMaxRange();
				float max = getMaxRange(ship);
				missile.setMaxRange(max);
				missile.setMaxFlightTime(missile.getMaxFlightTime() * max/base);
				
				// === 处理无人机实体 ===
				// 安全地从编队中移除无人机，避免空指针异常
				if (drone.getWing() != null) {
					drone.getWing().removeMember(drone); // 从编队中移除
				}
				drone.setWing(null);                 // 清除编队引用
				drone.setExplosionFlashColorOverride(new Color(255, 100, 50, 255)); // 设置爆炸颜色
				
				// === 创建绑定脚本 ===
				engine.addLayeredRenderingPlugin(new DroneMissileScript(drone, missile));
				
				// === 生成发射视觉效果 ===
				float thickness = 26f;
				float coreWidthMult = 0.67f;
				EmpArcEntityAPI arc = engine.spawnEmpArcVisual(ship.getLocation(), ship,
						missile.getLocation(), missile, thickness, new Color(255,100,100,255), Color.white);
				arc.setCoreWidthOverride(thickness * coreWidthMult);
				arc.setSingleFlickerMode();
			} else {
				// 未被选中的无人机：取消当前机动
				// 注意：需要检查AI和编队是否有效，避免空指针异常
				if (drone.getShipAI() != null) {
					try {
						// 某些情况下编队信息可能为null，需要捕获异常
						drone.getShipAI().cancelCurrentManeuver();
					} catch (NullPointerException e) {
						// 忽略编队信息缺失导致的异常
						// 这通常发生在无人机刚从编队中移除时
					}
				}
			}
			num++;
		}
	}
	
	public void unapply(MutableShipStatsAPI stats, String id) {
		// 系统结束时调用，此系统无需特殊清理
	}
	
	protected ShipAPI forceNextTarget = null; // AI强制指定的下一个目标
	
	/**
	 * 目标选择算法
	 * 
	 * 优先级顺序：
	 * 1. AI强制指定的目标（forceNextTarget）
	 * 2. 玩家手动选择的目标（R键锁定）
	 * 3. 鼠标指向的最近敌舰
	 * 4. 射程内最近的敌舰
	 * 
	 * 特殊处理：
	 * - 如果鼠标指向战机，会尝试找到附近的较大舰船
	 * - AI舰船会使用机动目标或最近敌舰
	 * 
	 * @param ship 发射舰船
	 * @return 选中的目标，可能为null
	 */
	protected ShipAPI findTarget(ShipAPI ship) {
		if (getDrones(ship).isEmpty()) {
			return null;
		}
		
		// 优先使用AI强制指定的目标
		if (forceNextTarget != null && forceNextTarget.isAlive()) {
			return forceNextTarget;
		}
		
		float range = getMaxRange(ship);
		boolean player = ship == Global.getCombatEngine().getPlayerShip();
		ShipAPI target = ship.getShipTarget();
		
		// === AI舰船的目标选择 ===
		if (!player) {
			// 检查AI机动目标
			Object test = ship.getAIFlags().getCustom(AIFlags.MANEUVER_TARGET);
			if (test instanceof ShipAPI) {
				target = (ShipAPI) test;
				float dist = Misc.getDistance(ship.getLocation(), target.getLocation());
				float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
				if (dist > range + radSum) target = null; // 超出射程则放弃
			}
			// 没有机动目标时寻找最近敌舰
			if (target == null) {
				target = Misc.findClosestShipEnemyOf(ship, ship.getMouseTarget(), HullSize.FRIGATE, range, true);
			}
			return target;
		}
		
		// === 玩家舰船的目标选择 ===
		
		if (target != null) return target; // 玩家用R键锁定的目标优先
		
		// 寻找鼠标指向位置最近的敌舰
		target = Misc.findClosestShipEnemyOf(ship, ship.getMouseTarget(), HullSize.FIGHTER, Float.MAX_VALUE, true);
		
		// 如果指向的是战机，尝试找到附近的较大舰船
		if (target != null && target.isFighter()) {
			ShipAPI nearbyShip = Misc.findClosestShipEnemyOf(ship, target.getLocation(), HullSize.FRIGATE, 100, false);
			if (nearbyShip != null) target = nearbyShip;
		}
		
		// 没有鼠标目标时选择射程内最近的敌舰
		if (target == null) {
			target = Misc.findClosestShipEnemyOf(ship, ship.getLocation(), HullSize.FIGHTER, range, true);
		}
		
		return target;
	}

	/**
	 * 获取状态信息（用于UI显示）
	 * 此系统不显示特殊状态信息
	 */
	public StatusData getStatusData(int index, State state, float effectLevel) {
		return null;
	}

	/**
	 * 获取舰船的所有可用无人机
	 * 遍历所有机库，收集其中的无人机成员
	 * 
	 * @param ship 目标舰船
	 * @return 所有可用无人机的列表
	 */
	public List<ShipAPI> getDrones(ShipAPI ship) {
		List<ShipAPI> result = new ArrayList<ShipAPI>();
		for (FighterLaunchBayAPI bay : ship.getLaunchBaysCopy()) {
			if (bay.getWing() == null) continue;
			for (ShipAPI drone : bay.getWing().getWingMembers()) {
				result.add(drone);
			}
		}
		return result;
	}

	// === DroneStrikeStatsAIInfoProvider接口实现 ===
	// 以下方法为AI提供决策信息
	
	/**
	 * 获取系统信息文本（显示在UI上）
	 * 根据当前状态返回不同的提示信息
	 */
	@Override
	public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
		if (system.isOutOfAmmo()) return null;
		if (system.getState() != SystemState.IDLE) return null;
		
		if (getDrones(ship).isEmpty()) {
			return "NO DRONES";  // 无可用无人机
		}
		
		float range = getMaxRange(ship);
		
		ShipAPI target = findTarget(ship);
		if (target == null) {
			// 检查鼠标目标是否在射程内
			if (ship.getMouseTarget() != null) {
				float dist = Misc.getDistance(ship.getLocation(), ship.getMouseTarget());
				float radSum = ship.getCollisionRadius();
				if (dist + radSum > range) {
					return "OUT OF RANGE";  // 超出射程
				}
			}
			return "NO TARGET";  // 无目标
		}
		
		// 检查目标是否在射程内
		float dist = Misc.getDistance(ship.getLocation(), target.getLocation());
		float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
		if (dist > range + radSum) {
			return "OUT OF RANGE";  // 目标超出射程
		}
		
		return "READY";  // 准备就绪
	}

	/**
	 * 检查系统是否可用
	 * 主要检查是否有可用的无人机
	 */
	@Override
	public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
		if (ship != null && ship.getSystem() != null && ship.getSystem().getState() != SystemState.IDLE) {
			return true; // 系统激活时保持可用状态，防止最后一架无人机发射时显示弹药不足
		}
		return !getDrones(ship).isEmpty();
	}
	
	/**
	 * 获取系统最大射程
	 * 基于武器射程和舰船系统射程加成计算
	 */
	public float getMaxRange(ShipAPI ship) {
		if (weapon == null) {
			weapon = Global.getCombatEngine().createFakeWeapon(ship, getWeaponId());
		}
		return ship.getMutableStats().getSystemRangeBonus().computeEffective(weapon.getRange());
	}
	
	// === AI决策辅助方法 ===
	
	/**
	 * 无人机是否适合用作点防御
	 */
	public boolean dronesUsefulAsPD() {
		return true;
	}
	
	/**
	 * 无人机攻击是否对战机有效
	 */
	public boolean droneStrikeUsefulVsFighters() {
		return false;
	}
	
	/**
	 * 获取最大无人机数量
	 */
	public int getMaxDrones() {
		return 2;
	}
	
	/**
	 * 获取导弹速度
	 */
	public float getMissileSpeed() {
		return weapon.getProjectileSpeed();
	}
	
	/**
	 * 设置AI强制指定的下一个目标
	 */
	public void setForceNextTarget(ShipAPI forceNextTarget) {
		this.forceNextTarget = forceNextTarget;
	}
	
	/**
	 * 获取AI强制指定的下一个目标
	 */
	public ShipAPI getForceNextTarget() {
		return forceNextTarget;
	}
}








