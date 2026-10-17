package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import data.scripts.util.Moci_BoxBasedUtil;
import data.scripts.util.Moci_ColorData;
import data.scripts.util.Moci_Util;
import data.scripts.visual.Moci_GlowScanVisual;
import org.boxutil.base.api.InstanceDataAPI;
import org.boxutil.units.standard.entity.FlareEntity;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.LinkedList;
import java.util.List;

/**
 * 位移传送系统 - Displacer System
 * 
 * 功能概述：
 * 1. 将舰船瞬间传送到目标位置
 * 2. 传送过程中舰船逐渐透明化
 * 3. 传送时产生华丽的视觉效果（扫描光、光晕、粒子、漩涡等）
 * 4. 传送完成时清除/削弱范围内的敌方弹幕
 * 5. 智能寻找有效的传送目标点，避开障碍物和其他舰船
 * 
 * 技术特点：
 * - 使用BoxUtil库实现高级视觉效果
 * - 使用LazyLib进行数学计算和范围检测
 * - 支持系统范围加成（可通过船插等方式增强传送距离）
 * 
 * @author Polaris Prime (原作者)
 * @author Moci (移植并添加中文注释)
 */
public class Moci_DisplacerStats extends BaseShipSystemScript {

	// ==================== 颜色配置 ====================
	/** 扫描光颜色 - 浅蓝色 */
	public static final Color SCAN_COLOR = new Color(142, 206, 224);
	
	/** 光晕颜色 - 深蓝色 */
	public static final Color FLARE_COLOR = new Color(33, 170, 222);
	
	/** 透明色 - 用于隐藏某些效果 */
	public static final Color EMPTY = new Color(0, 0, 0, 0);
	
	/** 零向量 - 用于静止的粒子 */
	public static final Vector2f ZERO = new Vector2f();

	// ==================== 系统参数 ====================
	/** 基础传送距离 */
	public static final float RANGE_FACTOR = 1800f;

	// ==================== 状态变量 ====================
	/** 位移状态数据 - 存储传送相关的所有信息 */
	protected DisplacerState data = null;
	
	/** 音效播放标志 - 确保音效只播放一次 */
	protected boolean playSound = false;

	/**
	 * 获取实际传送距离
	 * 考虑了舰船的系统范围加成（如船插、技能等）
	 * 
	 * @param ship 舰船实例
	 * @return 实际传送距离
	 */
	public static float getRange(ShipAPI ship) {
		if (ship == null) return RANGE_FACTOR;
		// 应用系统范围加成
		return ship.getMutableStats().getSystemRangeBonus().computeEffective(RANGE_FACTOR);
	}

	/**
	 * 系统激活时的主要逻辑
	 * 
	 * @param stats 舰船属性
	 * @param id 效果ID
	 * @param state 系统状态（充能中/激活中/冷却中）
	 * @param effectLevel 效果等级（0-1，表示系统激活的进度）
	 */
	@Override
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		ShipAPI ship = (ShipAPI)stats.getEntity();
		if (ship == null) {
			return;
		}

		CombatEngineAPI engine = Global.getCombatEngine();
		// 暂停或舰船不在场时不执行
		if (engine.isPaused() || !engine.isEntityInPlay(ship) || !ship.isAlive()) {
			return;
		}

		// ==================== 初始化阶段 ====================
		if (data == null) {
			// 计算初始传送方向
			Vector2f vel = ship.getVelocity();
			float expectedAngle = ship.getFacing();
			// 如果舰船在移动，传送方向为速度方向
			if (!VectorUtils.isZeroVector(vel)) {
				expectedAngle = VectorUtils.getFacing(vel);
			}
			
			// 计算初始目标位置
			float expectedRange = getRange(ship);
			Vector2f initialTargetLocation = MathUtils.getPointOnCircumference(
				ship.getLocation(), expectedRange, expectedAngle
			);

			// 创建位移状态数据
			data = new DisplacerState(ship, initialTargetLocation);
			// 添加视觉效果渲染器
			engine.addLayeredRenderingPlugin(data.visual);
		}

		float amount = engine.getElapsedInLastFrame();

		// ==================== 透明度控制 ====================
		// 传送过程中舰船逐渐透明（effectLevel从0到1）
		// alphaLevel从1降到0，然后再从0升到1
		float alphaLevel = Math.max(1f - effectLevel * 2f, 0f);
		ship.setExtraAlphaMult(alphaLevel);

		// ==================== 音效播放 ====================
		if (!playSound) {
			playSound = true;
			// 计算音效的速度向量（用于多普勒效果）
			Vector2f velocity = Vector2f.sub(data.targetLocation, data.startingLocation, null);
			velocity.scale(1f / ship.getSystem().getChargeUpDur());
			Global.getSoundPlayer().playSound(
				"Moci_displacer_active2", 1f, 1f, 
				ship.getLocation(), velocity
			);
		}

		// ==================== 充能阶段效果 ====================
		if (state == State.IN) {
			// 扫描光效果（前半段）
			float scanLevel = Math.min(effectLevel * 2f, 1f);
			data.visual.setScanLevel(scanLevel);

			// 光晕位置随传送进度移动
			Vector2f flareLocation = getPointOnLine(
				data.startingLocation, data.targetLocation, effectLevel
			);
			data.flare.setLocation(flareLocation);

			// 粒子效果 - 在舰船周围生成蓝色粒子
			// 透明度越高，粒子生成越频繁
			if (Moci_Util.timesPerSec(50f * alphaLevel, amount)) {
				// 随机距离和位置
				float dis = MathUtils.getRandomNumberInRange(10f, 250f);
				Vector2f point = MathUtils.getRandomPointOnCircumference(ship.getLocation(), dis);
				// 粒子向外扩散
				Vector2f vel = Vector2f.sub(point, ship.getLocation(), null);
				vel.scale(5f);
				// 随机大小和亮度
				float size = MathUtils.getRandomNumberInRange(10f, 30f);
				float brightness = MathUtils.getRandomNumberInRange(0.8f, 1f);
				Moci_BoxBasedUtil.addSmoothParticle(
					point, vel, size, brightness, 0.25f, Moci_ColorData.SHALLOW_BLUE
				);
			}
		}

		// ==================== 传送完成时的效果 ====================
		if (effectLevel == 1f) {
			// 1. 混沌漩涡效果
			SpriteAPI chaos = Global.getSettings().getSprite("misc", "Moci_shock_wave");
			float vortexSize = ship.getCollisionRadius() * 3f;
			SpriteEntity vortex = Moci_BoxBasedUtil.addSpriteToTarget(
				chaos, ship, 0.1f, 0.2f, 0.2f, CombatEngineLayers.UNDER_SHIPS_LAYER
			);
			vortex.setBaseSizePerTiles(vortexSize * 0.5f, vortexSize * 0.5f);
			vortex.getMaterialData().setColor(Moci_ColorData.BRIGHT_EMP_ARC_FRINGE);
			vortex.setAdditiveBlend();
			// 添加旋转动画
			Moci_BoxBasedUtil.addBasicInstanceData(
				vortex, 0.1f, 0.2f, 0.3f, 
				MathUtils.getRandomNumberInRange(0f, 360f), 
				MathUtils.getRandomNumberInRange(-30f, 30f), 
				1.5f
			);

			// 2. 星云粒子爆发效果
			float nebulaSizeBase = ship.getCollisionRadius() * 2f;
			int nebulaCount = 6; // 6个星云粒子
			float[] nebulaRange = new float[]{0f, nebulaSizeBase * 0.25f}; // 生成范围
			float[] nebulaAngle = null; // 随机角度
			float[] nebulaSpeed = null; // 随机速度
			float[] nebulaSize = new float[]{nebulaSizeBase * 0.5f, nebulaSizeBase}; // 大小范围
			float[] nebulaMult = new float[]{1.5f, 2f}; // 大小倍数
			Color[] nebulaColors = new Color[]{SCAN_COLOR, FLARE_COLOR}; // 颜色渐变
			float[] timeGlobal = Moci_BoxBasedUtil.rampToTimer(1f, 0f);
			List<InstanceDataAPI> nebulaData = Moci_BoxBasedUtil.createBurstOutInstanceData(
				nebulaCount, nebulaRange, null, nebulaAngle, nebulaSpeed, 
				nebulaSize, nebulaMult, nebulaColors,
				timeGlobal[0], timeGlobal[1]
			);
			Moci_BoxBasedUtil.addNebulaParticle(
				nebulaData, ship.getLocation(), 1f, 1f, 
				timeGlobal[0], timeGlobal[1], Color.WHITE
			);

			// 3. 空间扭曲效果
			float range = ship.getCollisionRadius() * 2f;
			Moci_BoxBasedUtil.addDistortion(
				ship.getLocation(), range, 0.8f, 0.6f, 0.3f, 0f, 0.3f
			);

			// 4. 弹幕清除/削弱效果
			for (DamagingProjectileAPI proj : Moci_Util.getProjectilesAndMissilesWithinRange(ship.getLocation(), range)) {
				// 跳过友方弹幕和导弹
				if (proj.getOwner() == ship.getOwner()) continue;
				if (proj instanceof MissileAPI) continue;

				// 添加弹幕消失的视觉效果
				Moci_BoxBasedUtil.addSmoothParticle(
					proj.getLocation(), ZERO, 
					MathUtils.getRandomNumberInRange(2f, 7f) + proj.getCollisionRadius(), 
					1f, 0.5f, Moci_ColorData.NORMAL_BLUE
				);
				Moci_BoxBasedUtil.addSmoothParticle(
					proj.getLocation(), proj.getVelocity(), 
					proj.getCollisionRadius() + 20f, 
					MathUtils.getRandomNumberInRange(0.8f, 1f), 
					0.25f, Moci_ColorData.BRIGHT_EMP_ARC_FRINGE
				);

				// 高伤害弹幕削弱50%，低伤害弹幕直接移除
				if (proj.getDamageAmount() > 1000f) {
					proj.getDamage().getModifier().modifyMult(id, 0.5f);
				} else {
					engine.removeEntity(proj);
				}
			}

			// 5. 伤害爆炸效果（用于击退敌方单位）
			float damage = 300f;
			DamagingExplosionSpec spec = new DamagingExplosionSpec(
				1f, // 持续时间
				range * 1.5f, // 最大范围
				range * 0.5f, // 核心范围
				damage, // 最大伤害
				damage * 0.5f, // 最小伤害
				CollisionClass.PROJECTILE_NO_FF, // 碰撞类型
				CollisionClass.PROJECTILE_FIGHTER, // 碰撞类型
				1f, 1f, 0f, 1, 
				EMPTY, EMPTY // 不显示爆炸图形
			);
			spec.setShowGraphic(false);
			spec.setUseDetailedExplosion(false);

			// 连续三次爆炸（增强击退效果）
			engine.spawnDamagingExplosion(spec, ship, ship.getLocation());
			engine.spawnDamagingExplosion(spec, ship, ship.getLocation());
			engine.spawnDamagingExplosion(spec, ship, ship.getLocation());
		}
	}

	/**
	 * 系统结束时的清理工作
	 */
	@Override
	public void unapply(MutableShipStatsAPI stats, String id) {
		ShipAPI ship = (ShipAPI)stats.getEntity();
		if (ship == null) {
			return;
		}

		// 重置音效标志
		playSound = false;

		if (data != null) {
			// 恢复舰船透明度
			ship.setExtraAlphaMult(1f);
			// 移除视觉效果
			data.visual.setValid(false);
			// 清空数据
			data = null;
		}
	}

	/**
	 * 状态栏显示（此系统不显示状态）
	 */
	@Override
	public StatusData getStatusData(int index, State state, float effectLevel) {
		return null;
	}

	/**
	 * 位移状态数据类
	 * 存储传送过程中需要的所有数据
	 */
	public static class DisplacerState {

		/** 扫描光视觉效果 */
		protected final Moci_GlowScanVisual visual;
		
		/** 光晕实体 */
		protected final FlareEntity flare;

		/** 起始位置 */
		protected final Vector2f startingLocation;
		
		/** 目标位置 */
		protected final Vector2f targetLocation;

		/**
		 * 构造函数 - 初始化所有视觉效果和位置数据
		 * 
		 * @param ship 舰船实例
		 * @param initialTargetLocation 初始目标位置
		 */
		protected DisplacerState(ShipAPI ship, Vector2f initialTargetLocation) {

			// 创建扫描光效果
			visual = new Moci_GlowScanVisual(ship, SCAN_COLOR);

			// 创建光晕效果
			float width = ship.getCollisionRadius() * 10f; // 光晕宽度
			float height = width * 0.1f; // 光晕高度（扁平）
			float in = ship.getSystem().getChargeUpDur(); // 淡入时间
			float full = ship.getSystem().getChargeActiveDur(); // 持续时间
			float out = ship.getSystem().getChargeDownDur(); // 淡出时间
			flare = Moci_BoxBasedUtil.addSharpFlare(
				ship.getLocation(), 0f, width, height, 
				SCAN_COLOR, FLARE_COLOR, 
				in, full, out, 0.5f, 0.2f
			);
			flare.setFlickMixValue(0.5f); // 闪烁混合值
			flare.setSmoothDisc(); // 使用平滑圆盘

			// 记录起始位置
			startingLocation = new Vector2f(ship.getLocation());
			// 计算有效的目标位置（避开障碍物）
			targetLocation = getTeleportLocation(
				Global.getCombatEngine(), ship, initialTargetLocation
			);
		}
	}

	/**
	 * 获取线段上的点
	 * 用于计算传送过程中的中间位置
	 * 
	 * @param lineStart 线段起点
	 * @param lineEnd 线段终点
	 * @param t 插值参数（0-1）
	 * @return 线段上的点
	 */
	public static Vector2f getPointOnLine(Vector2f lineStart, Vector2f lineEnd, float t) {
		return new Vector2f(
			lineStart.x + t * (lineEnd.x - lineStart.x), 
			lineStart.y + t * (lineEnd.y - lineStart.y)
		);
	}

	/**
	 * 获取有效的传送位置
	 * 如果初始位置无效，会尝试在周围寻找有效位置
	 * 
	 * 算法：
	 * 1. 首先检查初始目标位置是否有效
	 * 2. 如果无效，以初始位置为中心，逐渐扩大搜索范围
	 * 3. 在每个范围内，以30度为间隔尝试12个方向
	 * 4. 最多尝试10个范围（即10倍碰撞半径）
	 * 5. 如果都无效，随机选择一个尝试过的位置
	 * 
	 * @param engine 战斗引擎
	 * @param ship 舰船实例
	 * @param initialTargetLocation 初始目标位置
	 * @return 有效的传送位置
	 */
	public static Vector2f getTeleportLocation(CombatEngineAPI engine, ShipAPI ship, Vector2f initialTargetLocation) {
		// 首先检查初始位置
		if (isLocationValid(engine, ship, initialTargetLocation)) {
			return initialTargetLocation;
		}

		float collisionRadius = ship.getCollisionRadius();
		WeightedRandomPicker<Vector2f> weightedRandomPicker = new WeightedRandomPicker<>();

		// 逐渐扩大搜索范围
		for (int tryRangeMult = 1; tryRangeMult <= 10; tryRangeMult++) {
			// 在当前范围内尝试12个方向（每30度一个）
			for (float currentAngle = 0f; currentAngle <= 360f; currentAngle += 30f) {
				// 计算尝试位置
				Vector2f tryThisLocation = MathUtils.getPoint(
					ZERO, collisionRadius * tryRangeMult, currentAngle
				);
				Vector2f.add(initialTargetLocation, tryThisLocation, tryThisLocation);
				// 添加到候选列表
				weightedRandomPicker.add(tryThisLocation);
				// 如果找到有效位置，立即返回
				if (isLocationValid(engine, ship, tryThisLocation)) {
					return tryThisLocation;
				}
			}
		}

		// 如果没有找到有效位置，随机返回一个尝试过的位置
		// 或者返回初始位置
		return weightedRandomPicker.isEmpty() ? initialTargetLocation : weightedRandomPicker.pick();
	}

	/**
	 * 检查位置是否有效
	 * 
	 * 有效条件：
	 * 1. 在地图范围内
	 * 2. 不与其他舰船（非战机）重叠
	 * 3. 不与小行星重叠
	 * 
	 * @param engine 战斗引擎
	 * @param ship 舰船实例
	 * @param location 要检查的位置
	 * @return 位置是否有效
	 */
	public static boolean isLocationValid(CombatEngineAPI engine, ShipAPI ship, Vector2f location) {

		// 检查是否在地图范围内
		if (Math.abs(location.getX()) > Math.abs(engine.getMapWidth() * 0.5f)) {
			return false;
		}
		if (Math.abs(location.getY()) > Math.abs(engine.getMapHeight() * 0.5f)) {
			return false;
		}

		float collisionRadius = ship.getCollisionRadius();
		
		// 检查是否与其他舰船重叠
		for (ShipAPI block : CombatUtils.getShipsWithinRange(location, collisionRadius)) {
			// 跳过战机和自己
			if (block.isFighter()) continue;
			if (block == ship) continue;

			// 检查碰撞半径是否重叠
			float overlapRange = block.getCollisionRadius() + collisionRadius;
			if (MathUtils.getDistanceSquared(location, block.getLocation()) < overlapRange * overlapRange) {
				return false;
			}
		}

		// 检查是否与小行星重叠
		for (CombatEntityAPI asteroid : CombatUtils.getAsteroidsWithinRange(location, collisionRadius)) {
			float overlapRange = asteroid.getCollisionRadius() + collisionRadius;
			if (MathUtils.getDistanceSquared(location, asteroid.getLocation()) < overlapRange * overlapRange) {
				return false;
			}
		}

		return true;
	}
}
