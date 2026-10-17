package data.shipsystems.scripts;

import java.awt.Color;
import java.util.EnumSet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.impl.combat.dweller.RiftLightningEffect;
import com.fs.starfarer.api.loading.ProjectileSpawnType;
import com.fs.starfarer.api.util.Misc;

import data.scripts.util.Moci_Util;
import data.scripts.util.Moci_MathPersonal;

import org.lwjgl.util.vector.Vector2f;

public class Moci_ShroudedTemporalShellStats_new extends BaseShipSystemScript {
	
	public static final float MAX_TIME_MULT = 2f;
	public static final Color JITTER_COLOR_RED = new Color(255, 0, 0, 55);
	public static final Color JITTER_UNDER_COLOR_RED = new Color(255, 0, 0, 155);
	public static final Color ENGINE_COLOR_RED = new Color(255, 0, 0, 255);
	
	// 默认效果参数
	private static final float SPEED_BONUS = 0.33f;
	private static final float ROF_BONUS = 0.33f;
	private static final float AMMO_BONUS = 0.5f;
	private static final float ARC_INTERVAL = 0.1f;
	private static final float ARC_RANGE = 500f;
	private static final float ARC_DAMAGE = 100f;
	private static final float ARC_EMP = 100f;
	private static final float RANDOM_ARC_MIN_RANGE = 150f;
	private static final float RANDOM_ARC_MAX_RANGE = 250f;
	
	private static final Color WEAPON_GLOW_COLOR = new Color(255, 0, 0, 150);
	private static final float WEAPON_GLOW_INTENSITY = 0.5f;
	private static final EnumSet<WeaponAPI.WeaponType> WEAPON_TYPES = EnumSet.of(
		WeaponAPI.WeaponType.BALLISTIC,
		WeaponAPI.WeaponType.ENERGY
	);
	
	private static final float ARC_RANGE_SQ = ARC_RANGE * ARC_RANGE;
	
	// 过载电弧效果参数
	private static final float OVERLOAD_ARC_INTERVAL = 0.2f;
	private static final float OVERLOAD_ARC_THICKNESS = 40f;
	private static final float OVERLOAD_ARC_CORE_THICKNESS = 20f;
	private static final Color OVERLOAD_ARC_FRINGE_COLOR = new Color(150, 0, 0, 255);
	private static final float OVERLOAD_ARC_GLOW_SIZE_MULT = 5f;
	// 第一个点（起点）的位置范围
	private static final float OVERLOAD_ARC_START_RADIUS_MULT = 0.8f;  // 基础半径倍数
	private static final float OVERLOAD_ARC_START_MIN_FACTOR = 0.5f;    // 最小距离因子（相对基础半径）
	private static final float OVERLOAD_ARC_START_MAX_FACTOR = 1.0f;    // 最大距离因子（相对基础半径）
	// 第二个点（终点）相对第一个点的距离
	private static final float OVERLOAD_ARC_MIN_DISTANCE = 25f;  // 最小距离
	private static final float OVERLOAD_ARC_MAX_DISTANCE = 50f;  // 最大距离
	
	// 双粒子系统参数
	private static final float PARTICLE_SPAWN_INTERVAL = 0.1f;
	private static final float PARTICLE_SPAWN_ANGLE_MIN = 70f;  // 左右90度 - 20度
	private static final float PARTICLE_SPAWN_ANGLE_MAX = 110f; // 左右90度 + 20度
	private static final float PARTICLE_SPAWN_DISTANCE_MIN = 50f;
	private static final float PARTICLE_SPAWN_DISTANCE_MAX = 150f;
	private static final int NEGATIVE_PARTICLE_NUM = 11;
	private static final float NEGATIVE_PARTICLE_SIZE = 50f;
	private static final float NEGATIVE_PARTICLE_DURATION = 2f;
	private static final Color SHROUD_COLOR = new Color(100, 0, 25, 255);
	
	// 弹丸和光束颜色修改参数
	private static final Color BEAM_CORE_RED_COLOR = new Color(255, 100, 100, 255);
	private static final Color BEAM_FRINGE_RED_COLOR = new Color(255, 0, 0, 200);
	
	private float arcTimer = 0f;
	private float overloadArcTimer = 0f;
	private float particleTimer = 0f;

	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		ShipAPI ship = null;
		boolean player = false;

		if (stats.getEntity() instanceof ShipAPI) {
			ship = (ShipAPI) stats.getEntity();
			player = ship == Global.getCombatEngine().getPlayerShip();
			id = id + "_" + ship.getId();
		} else {
			return;
		}

		float jitterLevel = effectLevel;
		float jitterRangeBonus = 0;
		float maxRangeBonus = 10f;

		if (state == State.IN) {
			jitterLevel = effectLevel / (1f / ship.getSystem().getChargeUpDur());
			if (jitterLevel > 1) {
				jitterLevel = 1f;
			}
			jitterRangeBonus = jitterLevel * maxRangeBonus;
		} else if (state == State.ACTIVE) {
			jitterLevel = 1f;
			jitterRangeBonus = maxRangeBonus;
		} else if (state == State.OUT) {
			jitterRangeBonus = jitterLevel * maxRangeBonus;
		}

		jitterLevel = (float) Math.sqrt(jitterLevel);
		effectLevel *= effectLevel;

		// 红色抖动效果
		ship.setJitterUnder(this, JITTER_UNDER_COLOR_RED, jitterLevel, 25, 0f, 7f + jitterRangeBonus);

		// 时间加速
		float shipTimeMult = 1f + (MAX_TIME_MULT - 1f) * effectLevel;
		stats.getTimeMult().modifyMult(id, shipTimeMult);

		if (player) {
			Global.getCombatEngine().getTimeMult().modifyMult(id, 1f / shipTimeMult);
		} else {
			Global.getCombatEngine().getTimeMult().unmodify(id);
		}

		// 红色引擎效果 + 火焰延长
		ship.getEngineController().fadeToOtherColor(this, ENGINE_COLOR_RED, new Color(0, 0, 0, 0), effectLevel, 0.5f);
		ship.getEngineController().extendFlame(this, 2f * effectLevel, 0f, 0f);
		
		// 速度和机动性加成
		stats.getMaxSpeed().modifyPercent(id, SPEED_BONUS * 100f * effectLevel);
		stats.getAcceleration().modifyPercent(id, SPEED_BONUS * 100f * effectLevel);
		stats.getDeceleration().modifyPercent(id, SPEED_BONUS * 100f * effectLevel);
		stats.getMaxTurnRate().modifyPercent(id, SPEED_BONUS * 100f * effectLevel);
		stats.getTurnAcceleration().modifyPercent(id, SPEED_BONUS * 100f * effectLevel);
		
		// 武器射速加成 + 武器发光
		stats.getBallisticRoFMult().modifyPercent(id, ROF_BONUS * 100f * effectLevel);
		stats.getEnergyRoFMult().modifyPercent(id, ROF_BONUS * 100f * effectLevel);
		ship.setWeaponGlow(WEAPON_GLOW_INTENSITY * effectLevel, WEAPON_GLOW_COLOR, WEAPON_TYPES);
		
		// 弹药回复加成
		stats.getEnergyAmmoBonus().modifyPercent(id, AMMO_BONUS * 100f * effectLevel);
		stats.getBallisticAmmoBonus().modifyPercent(id, AMMO_BONUS * 100f * effectLevel);
		
		// 裂隙闪电攻击
		if (state == State.ACTIVE) {
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine != null && !engine.isPaused()) {
				float amount = engine.getElapsedInLastFrame();
				arcTimer += amount;
				overloadArcTimer += amount;
				particleTimer += amount;
				
				// 原有的裂隙闪电攻击
				if (arcTimer >= ARC_INTERVAL) {
					arcTimer -= ARC_INTERVAL;
					spawnArc(ship, engine);
				}
				
				// 过载电弧特效
				if (overloadArcTimer >= OVERLOAD_ARC_INTERVAL) {
					overloadArcTimer -= OVERLOAD_ARC_INTERVAL;
					spawnOverloadArc(ship, engine);
				}
				
				// 双粒子系统特效
				if (particleTimer >= PARTICLE_SPAWN_INTERVAL) {
					particleTimer -= PARTICLE_SPAWN_INTERVAL;
					spawnParticleEffects(ship, engine);
				}
				
				// 修改弹丸和光束颜色为红色
				modifyProjectileAndBeamColors(ship, engine);
			}
		}
	}

	public void unapply(MutableShipStatsAPI stats, String id) {
		ShipAPI ship = null;

		if (stats.getEntity() instanceof ShipAPI) {
			ship = (ShipAPI) stats.getEntity();
			id = id + "_" + ship.getId();
		} else {
			return;
		}

		Global.getCombatEngine().getTimeMult().unmodify(id);
		stats.getTimeMult().unmodify(id);
		stats.getMaxSpeed().unmodify(id);
		stats.getAcceleration().unmodify(id);
		stats.getDeceleration().unmodify(id);
		stats.getMaxTurnRate().unmodify(id);
		stats.getTurnAcceleration().unmodify(id);
		stats.getBallisticRoFMult().unmodify(id);
		stats.getEnergyRoFMult().unmodify(id);
		stats.getEnergyAmmoBonus().unmodify(id);
		
		ship.setWeaponGlow(0f, WEAPON_GLOW_COLOR, WEAPON_TYPES);
		arcTimer = 0f;
		overloadArcTimer = 0f;
		particleTimer = 0f;
	}
	
	private void spawnArc(ShipAPI ship, CombatEngineAPI engine) {
		if (ship == null || engine == null) return;
		
		CombatEntityAPI target = null;
		Vector2f targetPoint = null;
		float minDistanceSq = ARC_RANGE_SQ;
		
		// 优先攻击导弹
		for (MissileAPI missile : Moci_Util.getMissilesInRange(ship.getLocation(), ARC_RANGE)) {
			if (missile.getOwner() == ship.getOwner()) continue;
			if (missile.isFading()) continue;
			
			float distSq = getDistanceSquared(ship.getLocation(), missile.getLocation());
			if (distSq < minDistanceSq) {
				minDistanceSq = distSq;
				target = missile;
				targetPoint = missile.getLocation();
			}
		}
		
		// 其次攻击敌方舰船
		if (target == null) {
			for (ShipAPI otherShip : Moci_Util.getEnemyShipsInRange(ship.getLocation(), ARC_RANGE, ship.getOwner())) {
				if (!otherShip.isAlive()) continue;
				
				float distSq = getDistanceSquared(ship.getLocation(), otherShip.getLocation());
				
				if (otherShip.isFighter()) {
					if (distSq < minDistanceSq) {
						minDistanceSq = distSq;
						target = otherShip;
						targetPoint = otherShip.getLocation();
					}
				} else if (target == null || !((ShipAPI)target).isFighter()) {
					if (distSq < minDistanceSq) {
						minDistanceSq = distSq;
						target = otherShip;
						targetPoint = otherShip.getLocation();
					}
				}
			}
		}
		
		// 没有目标则随机释放
		if (target == null) {
			targetPoint = Moci_MathPersonal.randomPointInRing(
				ship.getLocation(),
				RANDOM_ARC_MIN_RANGE,
				RANDOM_ARC_MAX_RANGE
			);
		}
		
		if (targetPoint != null) {
			spawnRiftLightning(ship, targetPoint, target, engine);
		}
	}
	
	private void spawnRiftLightning(ShipAPI ship, Vector2f targetPoint, CombatEntityAPI target, CombatEngineAPI engine) {
		Vector2f from = ship.getLocation();
		float dist = Misc.getDistance(from, targetPoint);
		
		float arcSpeed = RiftLightningEffect.RIFT_LIGHTNING_SPEED;
		float arcWidth = 30f;
		
		EmpArcParams params = new EmpArcParams();
		params.segmentLengthMult = 8f;
		params.zigZagReductionFactor = 0.15f;
		params.fadeOutDist = 50f;
		params.minFadeOutMult = 10f;
		params.flickerRateMult = 0.3f;
		params.movementDurOverride = Math.max(0.05f, dist / arcSpeed);
		
		Color color = RiftLightningEffect.RIFT_LIGHTNING_COLOR;
		
		EmpArcEntityAPI arc = (EmpArcEntityAPI) engine.spawnEmpArcVisual(
			from, 
			ship, 
			targetPoint, 
			null,
			arcWidth,
			color,
			new Color(255, 255, 255, 255),
			params
		);
		
		arc.setCoreWidthOverride(arcWidth / 2f);
		arc.setRenderGlowAtStart(false);
		arc.setFadedOutAtStart(true);
		arc.setSingleFlickerMode(true);
		
		Global.getSoundPlayer().playSound("rift_lightning_fire", 1f, 0.25f, from, ship.getVelocity());
		
		if (target != null) {
			engine.spawnEmpArc(
				ship,
				from,
				ship,
				target,
				DamageType.ENERGY,
				ARC_DAMAGE,
				ARC_EMP,
				100000f,
				null,
				0f,
				new Color(0, 0, 0, 0),
				new Color(0, 0, 0, 0)
			);
		}
	}
	
	private float getDistanceSquared(org.lwjgl.util.vector.Vector2f a, org.lwjgl.util.vector.Vector2f b) {
		float dx = a.x - b.x;
		float dy = a.y - b.y;
		return dx * dx + dy * dy;
	}
	
	/**
	 * 生成过载电弧特效（模仿 DwellerShroud 的过载效果）
	 */
	private void spawnOverloadArc(ShipAPI ship, CombatEngineAPI engine) {
		if (ship == null || engine == null) return;
		
		EmpArcParams params = new EmpArcParams();
		params.segmentLengthMult = 4f;
		params.glowSizeMult = OVERLOAD_ARC_GLOW_SIZE_MULT;
		params.flickerRateMult = 0.5f + (float) Math.random() * 0.5f;
		
		Color fringe = OVERLOAD_ARC_FRINGE_COLOR;
		Color core = Color.white;
		
		Vector2f loc = ship.getLocation();
		
		// 计算第一个点（起点）的位置
		// 基础半径 = 舰船碰撞半径 * OVERLOAD_ARC_START_RADIUS_MULT
		float baseRadius = ship.getCollisionRadius() * OVERLOAD_ARC_START_RADIUS_MULT;
		// 实际距离在 baseRadius * MIN_FACTOR 到 baseRadius * MAX_FACTOR 之间随机
		float r = baseRadius * OVERLOAD_ARC_START_MIN_FACTOR + 
				  baseRadius * (OVERLOAD_ARC_START_MAX_FACTOR - OVERLOAD_ARC_START_MIN_FACTOR) * (float) Math.random();
		Vector2f from = Misc.getPointAtRadius(loc, r);
		
		// 在起点周围 25-50 范围内随机选择终点
		float arcDistance = OVERLOAD_ARC_MIN_DISTANCE + 
							(float) Math.random() * (OVERLOAD_ARC_MAX_DISTANCE - OVERLOAD_ARC_MIN_DISTANCE);
		float randomAngle = (float) Math.random() * 360f;
		Vector2f to = Misc.getUnitVectorAtDegreeAngle(randomAngle);
		to.scale(arcDistance);
		Vector2f.add(from, to, to);
		
		// 生成电弧
		EmpArcEntityAPI arc = (EmpArcEntityAPI) engine.spawnEmpArcVisual(
			from, 
			ship, 
			to, 
			ship, 
			OVERLOAD_ARC_THICKNESS, 
			fringe, 
			core, 
			params
		);
		
		arc.setCoreWidthOverride(OVERLOAD_ARC_CORE_THICKNESS);
		
		// 关闭音效
		// Global.getSoundPlayer().playSound("dweller_venting_or_overloaded", 0.5f, 0.8f, to, ship.getVelocity());
	}
	
	/**
	 * 生成双粒子系统特效（正粒子红雾 + 负粒子暗化）
	 */
	private void spawnParticleEffects(ShipAPI ship, CombatEngineAPI engine) {
		if (ship == null || engine == null) return;
		
		Vector2f shipLoc = ship.getLocation();
		float shipFacing = ship.getFacing();
		
		// 在左右90度 ± 20度范围内随机选择一个角度
		boolean isLeft = Math.random() < 0.5;
		float baseAngle = isLeft ? (shipFacing + 90f) : (shipFacing - 90f);
		float randomOffset = (float) (Math.random() * (PARTICLE_SPAWN_ANGLE_MAX - PARTICLE_SPAWN_ANGLE_MIN) 
									  + PARTICLE_SPAWN_ANGLE_MIN) - 90f;
		float spawnAngle = baseAngle + randomOffset;
		
		// 随机距离 50-150
		float spawnDistance = PARTICLE_SPAWN_DISTANCE_MIN + 
							  (float) Math.random() * (PARTICLE_SPAWN_DISTANCE_MAX - PARTICLE_SPAWN_DISTANCE_MIN);
		
		// 计算生成位置
		Vector2f spawnPoint = Misc.getUnitVectorAtDegreeAngle(spawnAngle);
		spawnPoint.scale(spawnDistance);
		Vector2f.add(shipLoc, spawnPoint, spawnPoint);
		
		// 生成正粒子（红色雾气）
		spawnPositiveParticles(spawnPoint, ship, engine);
		
		// 生成负粒子（暗化效果）
		spawnNegativeParticles(spawnPoint, ship, engine);
	}
	
	/**
	 * 生成正粒子（红色雾气效果）
	 */
	private void spawnPositiveParticles(Vector2f location, ShipAPI ship, CombatEngineAPI engine) {
		// 使用游戏内置的粒子效果模拟红雾
		float size = 80f + (float) Math.random() * 40f;
		float duration = 1.5f + (float) Math.random() * 0.5f;
		
		// 生成多个红色粒子形成雾气效果
		for (int i = 0; i < 3; i++) {
			Vector2f particleLoc = Misc.getPointWithinRadius(location, 30f);
			Vector2f vel = new Vector2f(ship.getVelocity());
			Vector2f randomVel = Misc.getUnitVectorAtDegreeAngle((float) Math.random() * 360f);
			randomVel.scale(20f + (float) Math.random() * 30f);
			Vector2f.add(vel, randomVel, vel);
			
			engine.addNebulaParticle(
				particleLoc,
				vel,
				size,
				2f,  // 增长速度
				0.5f / duration,  // 淡入速度
				0.5f,  // 淡出延迟
				duration,
				SHROUD_COLOR
			);
		}
	}
	
	/**
	 * 生成负粒子（暗化效果）
	 */
	private void spawnNegativeParticles(Vector2f location, ShipAPI ship, CombatEngineAPI engine) {
		// 获取暗化颜色
		Color darkenColor = new Color(50, 0, 10, 100);
		
		float baseSize = NEGATIVE_PARTICLE_SIZE;
		float baseDuration = NEGATIVE_PARTICLE_DURATION;
		Vector2f vel = new Vector2f(ship.getVelocity());
		
		// 生成多个负粒子
		for (int i = 0; i < NEGATIVE_PARTICLE_NUM; i++) {
			Vector2f point = Misc.getPointWithinRadiusUniform(location, baseSize * 0.75f, Misc.random);
			
			float dur = baseDuration + baseDuration * (float) Math.random();
			float nSize = baseSize;
			Vector2f pt = Misc.getPointWithinRadius(point, nSize * 0.5f);
			Vector2f v = Misc.getUnitVectorAtDegreeAngle((float) Math.random() * 360f);
			v.scale(nSize + nSize * (float) Math.random() * 0.5f);
			v.scale(0.2f);
			Vector2f.add(vel, v, v);
			
			// 根据速度调整持续时间
			float maxSpeed = nSize * 1.5f * 0.2f;
			float minSpeed = nSize * 1f * 0.2f;
			float overMin = v.length() - minSpeed;
			if (overMin > 0) {
				float durMult = 1f - overMin / (maxSpeed - minSpeed);
				if (durMult < 0.1f) durMult = 0.1f;
				dur *= 0.5f + 0.5f * durMult;
			}
			
			// 添加负星云粒子（产生暗化效果）
			engine.addNegativeNebulaParticle(
				pt,
				v,
				nSize,
				2f,  // 亮度
				0.5f / dur,  // 淡入速度
				0f,  // 淡出延迟
				dur,
				darkenColor
			);
		}
	}

	/**
	 * 修改舰船发射的弹丸和光束颜色为红色
	 */
	private void modifyProjectileAndBeamColors(ShipAPI ship, CombatEngineAPI engine) {
		if (ship == null || engine == null) return;
		
		// 修改 BALLISTIC_AS_BEAM 类型弹丸的颜色
		for (DamagingProjectileAPI proj : engine.getProjectiles()) {
			if (proj.getSource() == ship && !proj.isFading()) {
				// 只修改 BALLISTIC_AS_BEAM 类型的弹丸
				if (proj.getSpawnType() == ProjectileSpawnType.BALLISTIC_AS_BEAM) {
					if (proj.getProjectileSpec() != null) {
						proj.getProjectileSpec().setCoreColor(BEAM_CORE_RED_COLOR);
						proj.getProjectileSpec().setFringeColor(BEAM_FRINGE_RED_COLOR);
					}
				}
			}
		}
		
		// 修改导弹颜色（导弹也是弹丸的一种）
		for (MissileAPI missile : engine.getMissiles()) {
			if (missile.getSource() == ship && !missile.isFading()) {
				// 导弹的颜色修改比较复杂，可能需要修改其引擎颜色
				missile.getEngineController().fadeToOtherColor(
					this, 
					ENGINE_COLOR_RED, 
					new Color(0, 0, 0, 0), 
					1.0f, 
					0.5f
				);
			}
		}
		
		// 修改光束颜色
		for (BeamAPI beam : engine.getBeams()) {
			if (beam.getSource() == ship) {
				// 修改光束核心颜色
				beam.setCoreColor(BEAM_CORE_RED_COLOR);
				// 修改光束边缘颜色
				beam.setFringeColor(BEAM_FRINGE_RED_COLOR);
			}
		}
	}

	public StatusData getStatusData(int index, State state, float effectLevel) {
		if (index == 0) {
			return new StatusData("遮罩护盾", false);
		}
		
		if (index == 1) {
			float bonus = SPEED_BONUS * 100f * effectLevel * effectLevel;
			return new StatusData("+ " + (int)Math.round(bonus) + "% 速度和机动性", false);
		}
		
		if (index == 2) {
			float bonus = ROF_BONUS * 100f * effectLevel * effectLevel;
			return new StatusData("+ " + (int)Math.round(bonus) + "% 武器射速", false);
		}
		
		if (index == 3) {
			float bonus = AMMO_BONUS * 100f * effectLevel * effectLevel;
			return new StatusData("+ " + (int)Math.round(bonus) + "% 能量武器弹药", false);
		}
		
		if (index == 4) {
			return new StatusData("裂隙闪电攻击", false);
		}
		
		return null;
	}
}
