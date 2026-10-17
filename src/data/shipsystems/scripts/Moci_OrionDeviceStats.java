package data.shipsystems.scripts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * 猎户座装置 - 核脉冲推进系统
 * 
 * 这个系统模拟了核脉冲推进的工作原理：
 * 1. 在飞船后方释放"炸弹"（推进单元）
 * 2. 炸弹爆炸产生定向冲击波
 * 3. 冲击波推动推进板（pusher plate）
 * 4. 推进板通过弹簧系统传递动力给飞船
 * 5. 飞船获得强大的加速度
 * 
 * 要自定义参数，可以创建派生类并在构造函数中设置 p = <自定义参数>
 * 
 * @author Alex (原作者)
 * @author Moci (中文注释)
 * 
 * Copyright 2022 Fractal Softworks, LLC
 */
public class Moci_OrionDeviceStats extends BaseShipSystemScript {

	/**
	 * 推进板冲击脉冲
	 * 表示单次爆炸对推进板产生的冲击力
	 */
	public static class PusherPlateImpulse {
		public float force;      // 冲击力大小
		public float dur;        // 冲击持续时间
		public float elapsed;    // 已经过的时间
	}
	
	/**
	 * 推进板状态
	 * 模拟推进板的物理状态，包括压缩和速度
	 */
	public static class PusherPlateState {
		public float compression;  // 压缩量（0-1之间，1表示完全压缩）
		public float vel;          // 推进板速度
		public List<PusherPlateImpulse> impulses = new ArrayList<PusherPlateImpulse>();  // 当前作用的所有冲击
		
		/**
		 * 添加一个新的冲击脉冲
		 * @param force 冲击力
		 * @param dur 持续时间
		 */
		public void addImpulse(float force, float dur) {
			PusherPlateImpulse ppi = new PusherPlateImpulse();
			ppi.force = force;
			ppi.dur = dur;
			impulses.add(ppi);
		}
		
		/**
		 * 推进每帧的物理模拟
		 * 使用简化的弹簧-质量系统模型
		 * @param amount 时间增量
		 */
		public void advance(float amount) {
			// 移除已经结束的冲击
			List<PusherPlateImpulse> remove = new ArrayList<PusherPlateImpulse>();
			float totalForce = 0f;
			for (PusherPlateImpulse curr : impulses) {
				totalForce += curr.force;
				curr.elapsed += amount;
				if (curr.elapsed >= curr.dur) {
					remove.add(curr);
				}
			}
			impulses.removeAll(remove);
			
			// 弹簧力计算（假设弹性系数k=1，质量m=1）
			float springForce = compression;  // 胡克定律：F = -kx
			float netForce = totalForce - springForce;  // 净力 = 冲击力 - 弹簧恢复力
			
			// 更新速度和位置（牛顿第二定律）
			vel += netForce * amount;           // v = v0 + at
			compression += vel * amount;        // x = x0 + vt
			
			// 限制压缩量在合理范围内
			if (compression > 1f) {
				compression = 1f;
				vel = 0f;
			}
			float min = 0f;
			// min = -0.5f;  // 可以设置负值允许推进板"反弹"超过初始位置
			if (compression < min) {
				compression = min;
				vel = 0f;
			}
		}
	}
	
	/**
	 * 猎户座装置参数配置
	 * 包含所有可调整的参数
	 */
	public static class OrionDeviceParams {
		// === 炸弹基础参数 ===
		public float bombFadeInTime = 0.15f;                    // 炸弹淡入时间
		public float bombLiveTime = 0.25f;                      // 炸弹存活时间
		public float bombSpeed = 50f;                           // 炸弹发射速度
		public float bombInheritedVelocityFraction = 0.5f;     // 继承飞船速度的比例
		
		// === 定向爆炸特效参数 ===
		public float shapedExplosionOffset = 50f;               // 爆炸粒子生成偏移
		public float shapedExplosionEndSizeMin = 1f;            // 粒子结束大小最小值
		public float shapedExplosionEndSizeMax = 2f;            // 粒子结束大小最大值
		public Color shapedExplosionColor = new Color(255,125,25,155);  // 爆炸颜色（橙色）
		public int shapedExplosionNumParticles = 200;           // 粒子数量
		public float shapedExplosionMinParticleSize = 80;       // 粒子最小尺寸
		public float shapedExplosionMaxParticleSize = 100;      // 粒子最大尺寸
		public float shapedExplosionScatter = 100f;             // 粒子散布范围
		public float shapedExplosionMinParticleVel = 100;       // 粒子最小速度
		public float shapedExplosionMaxParticleVel = 350f;      // 粒子最大速度
		public float shapedExplosionMinParticleDur = 1f;        // 粒子最小持续时间
		public float shapedExplosionMaxParticleDur = 2f;        // 粒子最大持续时间
		public float shapedExplosionArc = 90f;                  // 爆炸扩散角度
		public Color jitterColor = new Color(255,125,25,55);    // 抖动效果颜色
		public float maxJitterDur = 2f;                         // 最大抖动持续时间
		
		// === 推进板参数 ===
		public float pusherPlateMaxOffset = 14f;                // 推进板最大偏移距离
		public float pusherPlateImpulseForce = 10f;             // 推进板冲击力
		public float pusherPlateImpulseDuration = 0.2f;         // 推进板冲击持续时间
		
		// === 推进效果参数 ===
		public float impactAccel = 5000f;                       // 冲击加速度
		public float impactRateMult = 4f;                       // 冲击速率倍数
		
		// === 视觉效果参数 ===
		public boolean recolorTowardsEngineColor = false;       // 是否根据引擎颜色重新着色
		
		// === 武器ID ===
		public String bombWeaponId = "od_bomblauncher";         // 炸弹发射器武器ID
	}
	
	// 参数实例
	protected OrionDeviceParams p = new OrionDeviceParams();
	// 推进板状态
	protected PusherPlateState pusherState = new PusherPlateState();
	
	/**
	 * 构造函数
	 */
	public Moci_OrionDeviceStats() {
		p = new OrionDeviceParams();
		// p.recolorTowardsEngineColor = true;  // 可以启用引擎颜色匹配
	}
	
	// 原始爆炸颜色（用于颜色插值）
	protected Color orig = null;
	
	/**
	 * 根据飞船引擎颜色重新着色爆炸效果
	 * @param ship 飞船实例
	 */
	protected void recolor(ShipAPI ship) {
		if (ship == null) return;
		if (!p.recolorTowardsEngineColor) return;
		
		if (orig == null) orig = p.shapedExplosionColor;
		
		// 获取当前引擎火焰颜色
		Color curr = ship.getEngineController().getFlameColorShifter().getCurr();
		
		// 在原始颜色和引擎颜色之间插值（75%偏向引擎颜色）
		p.shapedExplosionColor = Misc.interpolateColor(orig, curr, 0.75f);
		p.shapedExplosionColor = Misc.setAlpha(p.shapedExplosionColor, orig.getAlpha());
	}
	
	protected boolean wasIdle = false;
	protected boolean deployedBomb = false;  // 是否已经部署炸弹
	
	/**
	 * 系统激活时的主要逻辑
	 */
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		ShipAPI ship = null;
		if (stats.getEntity() instanceof ShipAPI) {
			ship = (ShipAPI) stats.getEntity();
		} else {
			return;
		}
		
		// 更新爆炸颜色
		recolor(ship);
		
		// 当系统完全激活时，发射炸弹
		if (effectLevel >= 1 && !deployedBomb) {
			// 遍历所有系统槽位
			for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
				if (slot.isSystemSlot()) {
					spawnBomb(ship, slot);
				}
			}
			deployedBomb = true;
		} else if (state == State.COOLDOWN) {
			// 冷却时重置标志
			deployedBomb = false;
		}
		
		// 更新推进板物理状态
		float amount = Global.getCombatEngine().getElapsedInLastFrame();
		pusherState.advance(amount);
		
		// 更新推进板视觉位置
		for (WeaponAPI w : ship.getAllWeapons()) {
			Vector2f offset = new Vector2f(p.pusherPlateMaxOffset, 0f);
			offset.scale(pusherState.compression);  // 根据压缩量缩放偏移
			if (w.getSpec().hasTag("pusherplate")) {
				// 只对标记为"pusherplate"的装饰性武器应用偏移
				w.setRenderOffsetForDecorativeBeamWeaponsOnly(offset);
			}
		}
		
		// 调用子类可重写的方法
		advanceImpl(amount, ship, state, effectLevel);
	}
	
	/**
	 * 子类可以重写此方法来添加额外的逻辑
	 */
	protected void advanceImpl(float amount, ShipAPI ship, State state, float effectLevel) {
		// 留空供子类实现
	}
	
	/**
	 * 系统取消激活时调用
	 */
	public void unapply(MutableShipStatsAPI stats, String id) {
		// 清理工作
	}
	
	/**
	 * 生成推进炸弹
	 * @param source 发射源飞船
	 * @param slot 发射槽位
	 */
	public void spawnBomb(ShipAPI source, WeaponSlotAPI slot) {
		CombatEngineAPI engine = Global.getCombatEngine();
		Vector2f loc = slot.computePosition(source);
		float angle = slot.computeMidArcAngle(source);
		
		// 如果推进板有压缩，调整发射位置
		if (pusherState.compression > 0) {
			Vector2f offset = new Vector2f(p.pusherPlateMaxOffset, 0f);
			offset.scale(pusherState.compression);
			offset = Misc.rotateAroundOrigin(offset, source.getFacing());
			Vector2f.add(loc, offset, loc);
		}
		
		// 生成炸弹弹体
		MissileAPI bomb = (MissileAPI) engine.spawnProjectile(source, null, 
															  p.bombWeaponId, 
															  loc, 
															  angle, source.getVelocity());
		// 应用伤害修正
		if (source != null) {
			Global.getCombatEngine().applyDamageModifiersToSpawnedProjectileWithNullWeapon(
											source, WeaponType.MISSILE, false, bomb.getDamage());
		}
		
		// 设置炸弹速度（继承部分飞船速度）
		float fadeInTime = p.bombFadeInTime;
		Vector2f inheritedVel = new Vector2f(source.getVelocity());
		inheritedVel.scale(p.bombInheritedVelocityFraction);
		float speed = p.bombSpeed;
		
		Vector2f vel = Misc.getUnitVectorAtDegreeAngle(angle);
		vel.scale(speed);
		Vector2f.add(vel, inheritedVel, vel);
		bomb.getVelocity().set(vel);
		bomb.fadeOutThenIn(fadeInTime);  // 淡入效果
		
		// 设置炸弹属性
		bomb.setCollisionClass(CollisionClass.NONE);  // 无碰撞
		bomb.setEmpResistance(1000);                  // EMP抗性
		bomb.setEccmChanceOverride(1f);               // 抗干扰
		
		float liveTime = p.bombLiveTime;
		bomb.setMaxFlightTime(liveTime);
		
		// 添加炸弹爆炸处理插件
		Global.getCombatEngine().addPlugin(createBombImpactPlugin(source, slot, bomb, loc, angle));
	}
	
	/**
	 * 通知爆炸粒子已生成（子类可重写）
	 * @param bombLoc 炸弹位置
	 */
	protected void notifySpawnedExplosionParticles(Vector2f bombLoc) {
		// 留空供子类实现
	}
	
	/**
	 * 创建炸弹冲击处理插件
	 * 这个插件负责处理炸弹爆炸后的所有效果：
	 * 1. 生成定向爆炸粒子效果
	 * 2. 对推进板施加冲击力
	 * 3. 计算并应用飞船加速度
	 * 4. 处理飞船的制动和稳定
	 * 
	 * @param ship 飞船实例
	 * @param launchSlot 发射槽位
	 * @param bomb 炸弹实例
	 * @param launchLoc 发射位置
	 * @param launchAngle 发射角度
	 * @return 战斗插件实例
	 */
	protected EveryFrameCombatPlugin createBombImpactPlugin(final ShipAPI ship, final WeaponSlotAPI launchSlot,
			final MissileAPI bomb, final Vector2f launchLoc, final float launchAngle) {
		
		return new BaseEveryFrameCombatPlugin() {
			// === 时间追踪变量 ===
			float elapsed = 0f;        // 总经过时间
			float impactTime = 0f;     // 冲击阶段时间（0-1）
			float brakingTime = 0f;    // 制动阶段时间
			float jitterTime = 0f;     // 抖动效果时间
			
			// === 状态标志 ===
			boolean triggered = false;  // 是否已触发爆炸
			boolean braking = false;    // 是否进入制动阶段
			boolean done = false;       // 是否完成
			
			// === 物理参数 ===
			float forceAngle;          // 推力方向角度
			
			@Override
			public void advance(float amount, List<InputEventAPI> events) {
				if (Global.getCombatEngine().isPaused()) return;
			
				elapsed += amount;
				
				String impactCounterId = "od_system_counter";  // 用于追踪多重冲击的计数器ID
				
				// === 炸弹爆炸触发 ===
				if (bomb.isFizzling()) {  // 炸弹开始消失（爆炸）
					if (!triggered) {
						// 对推进板施加冲击脉冲
						pusherState.addImpulse(p.pusherPlateImpulseForce, p.pusherPlateImpulseDuration);
						
						// 计算推力方向
						// 基础方向：从炸弹指向发射槽位
						forceAngle = Misc.getAngleInDegrees(bomb.getLocation(), launchSlot.computePosition(ship));
						float angleToShip = Misc.getAngleInDegrees(bomb.getLocation(), ship.getLocation());
						
						// 如果角度差超过90度，反转方向
						if (Misc.getAngleDiff(angleToShip, forceAngle) > 90f) {
							forceAngle += 180f;
						}
						
						// 微调推力方向，使其更偏向飞船中心（20%的修正）
						float diff = Misc.getAngleDiff(angleToShip, forceAngle);
						float turnDir = Misc.getClosestTurnDirection(angleToShip, forceAngle);
						forceAngle = angleToShip + diff * turnDir * 0.2f;
						
						triggered = true;
						// 增加冲击计数器（用于检测多重冲击）
						ship.getMutableStats().getDynamic().getMod(impactCounterId).modifyFlat("od_launch_" + launchLoc, 1f);
						
						// === 生成定向爆炸粒子效果 ===
						if (Global.getCombatEngine().getViewport().isNearViewport(ship.getLocation(), 800f)) {
							float angle = forceAngle + 180f;  // 粒子朝向与推力相反
							
							// 获取粒子参数
							int numParticles = p.shapedExplosionNumParticles;
							float minSize = p.shapedExplosionMinParticleSize;
							float maxSize = p.shapedExplosionMaxParticleSize;
							Color pc = p.shapedExplosionColor;
							
							float minDur = p.shapedExplosionMinParticleDur;
							float maxDur = p.shapedExplosionMaxParticleDur;
							
							float arc = p.shapedExplosionArc; 
							float scatter = p.shapedExplosionScatter;
							float minVel = p.shapedExplosionMinParticleVel;
							float maxVel = p.shapedExplosionMaxParticleVel;
							
							float launchOffset = p.shapedExplosionOffset;
							float endSizeMin = p.shapedExplosionEndSizeMin;
							float endSizeMax = p.shapedExplosionEndSizeMax;
							
							// 计算粒子生成点（在炸弹位置前方）
							Vector2f spawnPoint = Misc.getUnitVectorAtDegreeAngle(forceAngle);
							spawnPoint.scale(launchOffset);
							Vector2f.add(bomb.getLocation(), spawnPoint, spawnPoint);
							
							// 生成所有粒子
							for (int i = 0; i < numParticles; i++) {
								// 计算角度偏移（越靠近中心的粒子速度越快）
								float angleOffset = (float) Math.random();
								if (angleOffset > 0.2f) {
									angleOffset *= angleOffset;  // 非线性分布，集中在中心
								}
								float speedMult = 1f - angleOffset;
								speedMult = 0.5f + speedMult * 0.5f;  // 速度倍数：0.5-1.0
								
								// 随机选择偏移方向
								angleOffset *= Math.signum((float) Math.random() - 0.5f);
								angleOffset *= arc/2f;
								
								// 计算粒子位置（极坐标转直角坐标）
								float theta = (float) Math.toRadians(angle + angleOffset);
								float r = (float) (Math.random() * Math.random() * scatter);  // 非线性散布
								float x = (float)Math.cos(theta) * r;
								float y = (float)Math.sin(theta) * r;
								Vector2f pLoc = new Vector2f(spawnPoint.x + x, spawnPoint.y + y);
	
								// 计算粒子速度
								float speed = minVel + (maxVel - minVel) * (float) Math.random();
								speed *= speedMult;
								
								Vector2f pVel = Misc.getUnitVectorAtDegreeAngle((float) Math.toDegrees(theta));
								pVel.scale(speed);
								
								// 随机粒子大小和持续时间
								float pSize = minSize + (maxSize - minSize) * (float) Math.random();
								float pDur = minDur + (maxDur - minDur) * (float) Math.random();
								float endSize = endSizeMin + (endSizeMax - endSizeMin) * (float) Math.random();
								
								// 添加星云粒子（带有渐变效果）
								Global.getCombatEngine().addNebulaParticle(pLoc, pVel, pSize, endSize, 0.1f, 0.5f, pDur, pc);
							}
							
							// 通知子类粒子已生成
							notifySpawnedExplosionParticles(bomb.getLocation());
						}
					}
				}
				
				// 检查是否有多重冲击（多个炸弹同时爆炸）
				boolean multipleImpacts = ship.getMutableStats().getDynamic().getMod(impactCounterId).computeEffective(0) > 1;
				
				String id = "od_system_mod";
				ship.getMutableStats().getMaxSpeed().unmodifyFlat(id);
				float maxSpeedWithoutBonus = ship.getMutableStats().getMaxSpeed().getModifiedValue();
				
				// === 抖动效果 ===
				if (triggered) {
					jitterTime += amount;
					// 计算抖动强度（随时间衰减）
					float intensity = bomb.getFlightTime() / bomb.getMaxFlightTime();
					if (intensity > 1f) intensity = 1f;
					if (triggered) intensity = 1f;
					if (braking) { 
						intensity = 1f - brakingTime * 2f;
						if (intensity < 0) intensity = 0;
					}
					float alt = 1f - (jitterTime / p.maxJitterDur);
					if (alt < intensity) {
						intensity = Math.max(alt, 0f);
					}
					Color jc = p.jitterColor;
					ship.setJitter(this, jc, intensity, 3, 0f, 0f);
				}
				
				// === 冲击加速阶段 ===
				if (triggered && !braking) {
					impactTime += amount * p.impactRateMult;
					
					// 计算推力衰减曲线（二次衰减后开方，产生快速启动后缓慢衰减的效果）
					float mag = (1f - impactTime) * (1f - impactTime);
					if (mag > 0) mag = (float) Math.sqrt(mag);
					
					Vector2f forcePoint = launchSlot.computePosition(ship);
					
					// 计算推力点到飞船中心的方向
					float dirToCenter = Misc.getAngleInDegrees(forcePoint, ship.getLocation());
					float angleDiff = Misc.getAngleDiff(forceAngle, dirToCenter);
					
					// 根据角度差分配加速度到线性和角速度
					float totalAccel = p.impactAccel;
					float portionAppliedToAngularVelocity = angleDiff * 1f / 90f;
					if (portionAppliedToAngularVelocity > 1f) portionAppliedToAngularVelocity = 1f;
					
					// 计算线性加速度
					Vector2f acc = Misc.getUnitVectorAtDegreeAngle(forceAngle);
					acc.scale(totalAccel * (1f - portionAppliedToAngularVelocity * 0.2f));
					acc.scale(mag * amount);
					
					// 应用线性加速度
					Vector2f.add(ship.getVelocity(), acc, ship.getVelocity());
					
					// 计算并应用角加速度
					float angVelChange = portionAppliedToAngularVelocity * ship.getMaxTurnRate() * 0.25f;
					angVelChange *= mag;
					angVelChange *= Misc.getClosestTurnDirection(forceAngle, dirToCenter);
					ship.setAngularVelocity(ship.getAngularVelocity() + angVelChange * amount);
					
					// 临时提升最大速度（允许超过正常最大速度）
					float maxSpeedBoost = 1000f * Math.max(0f, (1f - portionAppliedToAngularVelocity) * 0.5f);
					
					if (maxSpeedBoost > 0) {
						ship.getMutableStats().getMaxSpeed().modifyFlat(id, maxSpeedBoost);
					} else {
						ship.getMutableStats().getMaxSpeed().unmodifyFlat(id);
					}
					
					// 增加减速能力（帮助控制速度）
					ship.getMutableStats().getDeceleration().modifyFlat(id, 1f * Math.max(maxSpeedWithoutBonus, ship.getVelocity().length() - maxSpeedWithoutBonus));
					
					// 阻止玩家输入的加速/减速命令，强制减速
					ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
					ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
					ship.giveCommand(ShipCommand.DECELERATE, null, 0);
					
					// 冲击阶段结束，进入制动
					if (impactTime >= 1f) {
						braking = true;
						ship.getMutableStats().getMaxSpeed().unmodify(id);
					}
				}
				
				// === 制动阶段 ===
				if (braking) {
					if (!multipleImpacts) {
						// 增强减速能力
						ship.getMutableStats().getDeceleration().modifyFlat(id, 2f * Math.max(maxSpeedWithoutBonus, ship.getVelocity().length() - maxSpeedWithoutBonus));
						
						// 强制减速
						ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
						ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
						ship.giveCommand(ShipCommand.DECELERATE, null, 0);
					}
					
					brakingTime += amount;
					float threshold = 3f;
					if (multipleImpacts) threshold = 0.1f;  // 多重冲击时快速结束
					
					// 检查是否完成制动
					if (brakingTime >= threshold || ship.getVelocity().length() <= maxSpeedWithoutBonus) {
						done = true;
					}
				}
				
				// === 清理工作 ===
				if ((!triggered && elapsed > 1f) || done) {
					Global.getCombatEngine().removePlugin(this);
					
					// 移除所有修正
					ship.getMutableStats().getDeceleration().unmodify(id);
					ship.getMutableStats().getMaxSpeed().unmodify(id);
					
					// 移除冲击计数器
					ship.getMutableStats().getDynamic().getMod(impactCounterId).unmodifyFlat("od_launch_" + launchLoc);
				}
			}
		};
	}

	/**
	 * 检查系统是否可用
	 * 引擎熄火时不可用
	 */
	@Override
	public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
		if (ship.getEngineController().isFlamedOut() || ship.getEngineController().isFlamingOut()) {
			return false;
		}
		return super.isUsable(system, ship);
	}
}
