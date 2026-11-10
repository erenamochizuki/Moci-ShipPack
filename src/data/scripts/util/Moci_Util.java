package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.campaign.terrain.AuroraRenderer;
import com.fs.starfarer.api.impl.campaign.terrain.RangeBlockerUtil;
import com.fs.starfarer.api.impl.campaign.terrain.RingRenderer;
import com.fs.starfarer.api.util.Misc;
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.dark.shaders.light.LightShader;
import org.dark.shaders.light.StandardLight;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

/**
 * 通用工具类
 * 提供各种实用方法
 */
public class Moci_Util {
	public static final String TAG_NO_RECOIL = "NO_RECOIL";
	public static final String TAG_HIDE_RECOIL = "HIDE_RECOIL";
	public static final String TAG_FOLLOW_RECOIL = "FOLLOW_RECOIL";

	/**
	 * 装饰武器后坐力状态
	 * 记录武器各层sprite的原始中心点，避免每帧累积偏移。
	 */
	public static class SpriteRecoilState {
		public static class LayerState {
			public boolean initialized = false;
			public float originalCenterX = 0f;
			public float originalCenterY = 0f;
		}

		public boolean initialized = false;
		public final LayerState main = new LayerState();
		public final LayerState barrel = new LayerState();
		public final LayerState under = new LayerState();
		public final LayerState glow = new LayerState();
	}

	/**
	 * 捕获武器sprite的原始中心点。
	 */
	public static void captureSpriteRecoilOrigin(WeaponAPI weapon, SpriteRecoilState state) {
		if (weapon == null || state == null || state.initialized) {
			return;
		}

		captureSpriteLayerOrigin(weapon.getSprite(), state.main);
		captureSpriteLayerOrigin(weapon.getBarrelSpriteAPI(), state.barrel);
		captureSpriteLayerOrigin(weapon.getUnderSpriteAPI(), state.under);
		captureSpriteLayerOrigin(weapon.getGlowSpriteAPI(), state.glow);
		state.initialized = state.main.initialized
				|| state.barrel.initialized
				|| state.under.initialized
				|| state.glow.initialized;
	}

	/**
	 * 按武器开火/冷却进度对装饰武器施加sprite后坐力。
	 * 对 burst beam 一类武器，满充进入照射的瞬间会立即进入最大后坐，
	 * 待照射结束后再按冷却进度回位。
	 * recoilX/recoilY 是相对原始中心点的本地sprite偏移量。
	 */
	public static void applyCooldownSpriteRecoil(WeaponAPI decorativeWeapon, WeaponAPI sourceWeapon,
			SpriteRecoilState state, float recoilX, float recoilY) {
		if (decorativeWeapon == null || state == null) {
			return;
		}

		captureSpriteRecoilOrigin(decorativeWeapon, state);
		if (!state.initialized) {
			return;
		}

		float recoilLevel = getWeaponRecoilLevel(sourceWeapon);

		float offsetX = recoilX * recoilLevel;
		float offsetY = recoilY * recoilLevel;
		applySpriteLayerRecoil(decorativeWeapon.getSprite(), state.main, offsetX, offsetY);
		applySpriteLayerRecoil(decorativeWeapon.getBarrelSpriteAPI(), state.barrel, offsetX, offsetY);
		applySpriteLayerRecoil(decorativeWeapon.getUnderSpriteAPI(), state.under, offsetX, offsetY);
		applySpriteLayerRecoil(decorativeWeapon.getGlowSpriteAPI(), state.glow, offsetX, offsetY);
	}

	public static float getWeaponRecoilLevel(WeaponAPI sourceWeapon) {
		if (sourceWeapon == null) {
			return 0f;
		}

		// Burst beam/持续照射武器在真正出光的瞬间就后坐，而不是等整段照射结束后才触发。
		if (sourceWeapon.isBeam() && sourceWeapon.isFiring() && sourceWeapon.getChargeLevel() >= 1f) {
			return 1f;
		}

		if (sourceWeapon.getCooldown() <= 0f) {
			return 0f;
		}

		float recoilLevel = sourceWeapon.getCooldownRemaining() / sourceWeapon.getCooldown();
		return Math.max(0f, Math.min(1f, recoilLevel));
	}

	/**
	 * 重置装饰武器sprite中心点到原始位置。
	 */
	public static void resetSpriteRecoil(WeaponAPI weapon, SpriteRecoilState state) {
		if (weapon == null || state == null) {
			return;
		}

		captureSpriteRecoilOrigin(weapon, state);
		if (!state.initialized) {
			return;
		}

		resetSpriteLayerRecoil(weapon.getSprite(), state.main);
		resetSpriteLayerRecoil(weapon.getBarrelSpriteAPI(), state.barrel);
		resetSpriteLayerRecoil(weapon.getUnderSpriteAPI(), state.under);
		resetSpriteLayerRecoil(weapon.getGlowSpriteAPI(), state.glow);
	}

	private static void captureSpriteLayerOrigin(SpriteAPI sprite, SpriteRecoilState.LayerState layerState) {
		if (sprite == null || layerState == null || layerState.initialized) {
			return;
		}

		layerState.originalCenterX = sprite.getCenterX();
		layerState.originalCenterY = sprite.getCenterY();
		layerState.initialized = true;
	}

	private static void applySpriteLayerRecoil(SpriteAPI sprite, SpriteRecoilState.LayerState layerState,
			float offsetX, float offsetY) {
		if (sprite == null || layerState == null || !layerState.initialized) {
			return;
		}

		sprite.setCenterX(layerState.originalCenterX + offsetX);
		sprite.setCenterY(layerState.originalCenterY + offsetY);
	}

	private static void resetSpriteLayerRecoil(SpriteAPI sprite, SpriteRecoilState.LayerState layerState) {
		if (sprite == null || layerState == null || !layerState.initialized) {
			return;
		}

		sprite.setCenterX(layerState.originalCenterX);
		sprite.setCenterY(layerState.originalCenterY);
	}

	public static boolean hasWeaponTag(WeaponAPI weapon, String tag) {
		return weapon != null && weapon.getSpec() != null && tag != null && weapon.getSpec().hasTag(tag);
	}

	public static boolean hasAnyWeaponTag(String tag, WeaponAPI... weapons) {
		if (tag == null || weapons == null) {
			return false;
		}

		for (WeaponAPI weapon : weapons) {
			if (hasWeaponTag(weapon, tag)) {
				return true;
			}
		}
		return false;
	}

	public static boolean shouldDisableRecoil(WeaponAPI... weapons) {
		return hasAnyWeaponTag(TAG_NO_RECOIL, weapons);
	}

	public static boolean shouldApplyHiddenSourceRecoil(WeaponAPI sourceWeapon, WeaponAPI... relatedWeapons) {
		if (shouldDisableRecoil(sourceWeapon) || shouldDisableRecoil(relatedWeapons)) {
			return false;
		}
		return hasWeaponTag(sourceWeapon, TAG_HIDE_RECOIL);
	}

	/**
	 * 判断舰船系统是否处于开启状态。
	 */
	public static boolean isShipSystemActive(ShipAPI ship) {
		return ship != null && ship.getSystem() != null && (ship.getSystem().isOn() || ship.getSystem().isActive());
	}

	/**
	 * 判断舰船是否正在散热或过载。
	 */
	public static boolean isVentingOrOverloaded(ShipAPI ship) {
		return ship != null && ship.getFluxTracker() != null &&
				(ship.getFluxTracker().isVenting() || ship.getFluxTracker().isOverloaded());
	}

	/**
	 * 系统开启时返回设定加成，否则返回 0。
	 */
	public static float getSystemActiveBonus(ShipAPI ship, float activeBonus) {
		return isShipSystemActive(ship) ? activeBonus : 0f;
	}

	/**
	 * 基础特效数值计算：base + primary * primaryBonus + additive * additiveBonus。
	 */
	public static float computeEffectValue(
			float baseValue,
			float primaryLevel,
			float primaryBonus,
			float additiveLevel,
			float additiveBonus
	) {
		return baseValue + primaryLevel * primaryBonus + additiveLevel * additiveBonus;
	}

	/**
	 * 基础特效数值计算：在单一主输入之外，额外叠加第二输入。
	 */
	public static float computeEffectValue(
			float baseValue,
			float primaryLevel,
			float primaryBonus,
			float secondaryLevel,
			float secondaryBonus,
			float additiveLevel,
			float additiveBonus
	) {
		return baseValue +
				primaryLevel * primaryBonus +
				secondaryLevel * secondaryBonus +
				additiveLevel * additiveBonus;
	}

	/**
	 * 对特效数值施加惩罚并设置下限，避免出现过低值。
	 */
	public static float applyPenaltyWithFloor(float value, boolean applyPenalty, float penalty, float minValue) {
		float result = applyPenalty ? value - penalty : value;
		return Math.max(minValue, result);
	}

	/**
	 * 基础特效数值计算：按两项输入线性叠加，并限制最大值。
	 */
	public static float computeCappedEffectValue(
			float primaryLevel,
			float primaryBonus,
			float additiveLevel,
			float additiveBonus,
			float maxValue
	) {
		return Math.min(maxValue, primaryLevel * primaryBonus + additiveLevel * additiveBonus);
	}

	/**
	 * 按每秒次数触发
	 * 用于控制粒子生成频率等
	 * 
	 * @param times 每秒触发次数
	 * @param amount 时间增量（通常是engine.getElapsedInLastFrame()）
	 * @return 是否触发
	 */
	public static boolean timesPerSec(float times, float amount) {
		return Math.random() < amount * times;
	}

	/**
	 * 获取范围内的所有弹幕和导弹
	 * 
	 * @param location 中心位置
	 * @param range 范围
	 * @return 弹幕列表
	 */
	public static List<DamagingProjectileAPI> getProjectilesAndMissilesWithinRange(Vector2f location, float range) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		
		// 获取所有弹幕
		for (DamagingProjectileAPI proj : Global.getCombatEngine().getProjectiles()) {
			if (proj.getLocation() == null) continue;
			float distance = Vector2f.sub(proj.getLocation(), location, null).length();
			if (distance <= range) {
				result.add(proj);
			}
		}
		
		// 获取所有导弹
		for (MissileAPI missile : Global.getCombatEngine().getMissiles()) {
			if (missile.getLocation() == null) continue;
			float distance = Vector2f.sub(missile.getLocation(), location, null).length();
			if (distance <= range) {
				result.add(missile);
			}
		}
		
		return result;
	}

	/**
	 * 获取范围内的敌方弹幕和导弹
	 * 
	 * @param location 中心位置
	 * @param range 范围
	 * @param owner 己方阵营
	 * @return 敌方弹幕列表
	 */
	public static List<DamagingProjectileAPI> getEnemyProjectilesAndMissilesWithinRange(Vector2f location, float range, int owner) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		
		for (DamagingProjectileAPI proj : getProjectilesAndMissilesWithinRange(location, range)) {
			if (proj.getOwner() != owner) {
				result.add(proj);
			}
		}
		
		return result;
	}

	/**
	 * 添加光源（使用GraphicsLib）
	 * 如果GraphicsLib不可用，则不执行任何操作
	 * 
	 * @param location 光源位置
	 * @param size 光源大小
	 * @param intensity 光源强度
	 * @param duration 持续时间
	 * @param color 光源颜色
	 */
	public static void addLight(Vector2f location, float size, float intensity, float duration, Color color) {
		try {
			StandardLight light = new StandardLight(location, new Vector2f(), new Vector2f(), null);
			light.setSize(size);
			light.setIntensity(intensity);
			light.setLifetime(duration);
			light.setColor(color);
			light.fadeIn(0.1f);
			light.fadeOut(duration * 0.5f);

			LightShader.addLight(light);
		} catch (Exception e) {
			// GraphicsLib初始化失败时忽略，保留战斗逻辑。
		}
	}

	/**
	 * 显示浮动文本
	 * 
	 * @param anchor 锚点实体
	 * @param at 显示位置
	 * @param text 文本内容
	 */
	public static void showText(CombatEntityAPI anchor, Vector2f at, String text) {
		Global.getCombatEngine().addFloatingText(at, text, 25f, Color.RED, anchor, 1f, 1f);
	}

	/**
	 * 显示浮动数字
	 * 
	 * @param anchor 锚点实体
	 * @param at 显示位置
	 * @param amount 数字
	 */
	public static void showText(CombatEntityAPI anchor, Vector2f at, float amount) {
		showText(anchor, at, "" + amount);
	}

	// ==================== Grid遍历工具方法 ====================

	/**
	 * 使用Grid遍历获取范围内的导弹
	 * 性能优化：使用空间索引，只检查指定区域内的导弹
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的导弹列表
	 */
	public static List<MissileAPI> getMissilesInRange(Vector2f center, float radius) {
		List<MissileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getMissileGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof MissileAPI)) continue;
			
			MissileAPI missile = (MissileAPI) obj;
			if (missile.isExpired() || !engine.isEntityInPlay(missile)) continue;
			
			float dist = Vector2f.sub(missile.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(missile);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的敌方导弹
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @param owner 己方阵营
	 * @return 范围内的敌方导弹列表
	 */
	public static List<MissileAPI> getEnemyMissilesInRange(Vector2f center, float radius, int owner) {
		List<MissileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getMissileGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof MissileAPI)) continue;
			
			MissileAPI missile = (MissileAPI) obj;
			if (missile.getOwner() == owner) continue;
			if (missile.isExpired() || !engine.isEntityInPlay(missile)) continue;
			
			float dist = Vector2f.sub(missile.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(missile);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的所有弹丸（包括导弹）
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的弹丸列表
	 */
	public static List<DamagingProjectileAPI> getAllProjectilesInRange(Vector2f center, float radius) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getAllObjectGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof DamagingProjectileAPI)) continue;
			
			DamagingProjectileAPI proj = (DamagingProjectileAPI) obj;
			if (proj.isExpired() || !engine.isEntityInPlay(proj)) continue;
			
			float dist = Vector2f.sub(proj.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(proj);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的敌方弹丸（包括导弹）
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @param owner 己方阵营
	 * @return 范围内的敌方弹丸列表
	 */
	public static List<DamagingProjectileAPI> getEnemyProjectilesInRange(Vector2f center, float radius, int owner) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getAllObjectGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof DamagingProjectileAPI)) continue;
			
			DamagingProjectileAPI proj = (DamagingProjectileAPI) obj;
			if (proj.getOwner() == owner) continue;
			if (proj.isExpired() || !engine.isEntityInPlay(proj)) continue;
			
			float dist = Vector2f.sub(proj.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(proj);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的舰船
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的舰船列表
	 */
	public static List<ShipAPI> getShipsInRange(Vector2f center, float radius) {
		List<ShipAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getShipGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof ShipAPI)) continue;
			
			ShipAPI ship = (ShipAPI) obj;
			if (ship.isHulk() || !engine.isEntityInPlay(ship)) continue;
			
			float dist = Vector2f.sub(ship.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(ship);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的敌方舰船
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @param owner 己方阵营
	 * @return 范围内的敌方舰船列表
	 */
	public static List<ShipAPI> getEnemyShipsInRange(Vector2f center, float radius, int owner) {
		List<ShipAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getShipGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof ShipAPI)) continue;
			
			ShipAPI ship = (ShipAPI) obj;
			if (ship.getOwner() == owner) continue;
			if (ship.isHulk() || !engine.isEntityInPlay(ship)) continue;
			
			float dist = Vector2f.sub(ship.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(ship);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的小行星
	 * 
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的小行星列表
	 */
	public static List<CombatEntityAPI> getAsteroidsInRange(Vector2f center, float radius) {
		List<CombatEntityAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getAsteroidGrid().getCheckIterator(
			center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof CombatEntityAPI)) continue;
			
			CombatEntityAPI asteroid = (CombatEntityAPI) obj;
			if (!engine.isEntityInPlay(asteroid)) continue;
			
			float dist = Vector2f.sub(asteroid.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(asteroid);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取矩形区域内的导弹
	 * 适用于光束、光剑等线性武器的检测
	 * 
	 * @param center 中心位置
	 * @param width 宽度
	 * @param height 高度
	 * @return 区域内的导弹列表
	 */
	public static List<MissileAPI> getMissilesInRect(Vector2f center, float width, float height) {
		List<MissileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		java.util.Iterator<Object> iter = engine.getMissileGrid().getCheckIterator(
			center, width, height
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof MissileAPI)) continue;
			
			MissileAPI missile = (MissileAPI) obj;
			if (missile.isExpired() || !engine.isEntityInPlay(missile)) continue;
			
			result.add(missile);
		}

		return result;
	}

	/**
	 * 获取范围内的所有舰船（不包括穿梭机）
	 * 兼容SWP_Util的方法名
	 * 
	 * @param location 中心位置
	 * @param range 范围
	 * @return 舰船列表
	 */
	public static List<ShipAPI> getShipsWithinRange(Vector2f location, float range) {
		List<ShipAPI> ships = new ArrayList<>();

		for (ShipAPI tmp : Global.getCombatEngine().getShips()) {
			// 跳过穿梭机
			if (tmp.isShuttlePod()) {
				continue;
			}

			// 检查距离
			if (MathUtils.isWithinRange(tmp, location, range)) {
				ships.add(tmp);
			}
		}

		return ships;
	}

	/**
	 * 获取最近的伤害点
	 * 用于计算碰撞检测的最近点
	 * 
	 * @param source 源点
	 * @param entity 目标实体
	 * @return 最近点
	 */
	public static Vector2f getNearestPointForDamage(Vector2f source, CombatEntityAPI entity) {
		// 如果是弹丸，直接返回位置
		if (entity instanceof DamagingProjectileAPI) {
			return entity.getLocation();
		}

		// 否则返回边界上的最近点
		return CollisionUtils.getNearestPointOnBounds(source, entity);
	}

	/**
	 * 过滤被遮挡的目标
	 * 用于区域伤害时排除被护盾或其他物体遮挡的目标
	 * 
	 * @param primaryTarget 主要目标（直接命中的目标）
	 * @param originPoint 爆炸原点
	 * @param nearbyTargets 附近目标列表（会被修改）
	 * @param filterModules 是否过滤模块（如果主目标是母舰，则移除其模块）
	 * @param filterShielded 是否过滤被护盾保护的目标
	 * @param filterBlocked 是否过滤被其他物体遮挡的目标
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void filterObscuredTargets(CombatEntityAPI primaryTarget, Vector2f originPoint, 
											  List nearbyTargets, boolean filterModules, 
											  boolean filterShielded, boolean filterBlocked) {
		Iterator<CombatEntityAPI> iter = nearbyTargets.iterator();
		
		while (iter.hasNext()) {
			CombatEntityAPI nearbyTarget = iter.next();
			
			// 跳过无碰撞的实体
			if (nearbyTarget.getCollisionClass() == CollisionClass.NONE) {
				iter.remove();
				continue;
			}

			// 过滤模块：如果目标是主目标的模块，则移除
			if (filterModules && (nearbyTarget instanceof ShipAPI)) {
				ShipAPI ship = (ShipAPI) nearbyTarget;
				if (ship.getParentStation() == primaryTarget) {
					iter.remove();
					continue;
				}
			}

			// 获取目标上最近的伤害点
			Vector2f nearestPoint = getNearestPointForDamage(originPoint, nearbyTarget);

			boolean remove = false;
			
			// 检查是否被其他目标遮挡
			for (Object otherTarget : nearbyTargets) {
				if (!(otherTarget instanceof CombatEntityAPI)) {
					continue;
				}
				CombatEntityAPI otherEntity = (CombatEntityAPI) otherTarget;

				// 只检查同阵营的遮挡物，且不是自己
				if ((nearbyTarget.getOwner() != otherEntity.getOwner()) || (otherEntity == nearbyTarget)) {
					continue;
				}

				// 检查是否被护盾遮挡
				if (filterShielded && (otherEntity.getShield() != null)) {
					ShieldAPI shield = otherEntity.getShield();
					if (shield.isWithinArc(nearestPoint) && shield.isOn() && 
						MathUtils.isWithinRange(nearestPoint, shield.getLocation(), shield.getRadius())) {
						remove = true;
						break;
					}
				}

				// 检查是否被实体本体遮挡
				if (filterBlocked && CollisionUtils.getCollides(originPoint, nearestPoint, 
																otherEntity.getLocation(), 
																otherEntity.getCollisionRadius())) {
					// 更新边界信息（LazyLib的workaround）
					BoundsAPI bounds = otherEntity.getExactBounds();
					if (bounds != null) {
						bounds.update(otherEntity.getLocation(), otherEntity.getFacing());
					}
					
					// 精确碰撞检测
					if (CollisionUtils.getCollisionPoint(nearestPoint, originPoint, otherEntity) != null) {
						remove = true;
						break;
					}
				}
			}

			// 如果被遮挡，则移除
			if (remove) {
				iter.remove();
			}
		}
	}

	/**
	 * 使用Grid遍历获取矩形区域内的所有弹丸
	 * 适用于光束、光剑等线性武器的检测
	 * 
	 * @param center 中心位置
	 * @param width 宽度
	 * @param height 高度
	 * @return 区域内的弹丸列表
	 */
	public static List<DamagingProjectileAPI> getAllProjectilesInRect(Vector2f center, float width, float height) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		java.util.Iterator<Object> iter = engine.getAllObjectGrid().getCheckIterator(
			center, width, height
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof DamagingProjectileAPI)) continue;
			
			DamagingProjectileAPI proj = (DamagingProjectileAPI) obj;
			if (proj.isExpired() || !engine.isEntityInPlay(proj)) continue;
			
			result.add(proj);
		}

		return result;
	}

	// ==================== 极光(Aurora)特效 ====================
	//
	// 原版 com.fs.starfarer.api.impl.campaign.terrain.AuroraRenderer 的战斗可用封装。
	// 原版 advance() 依赖 Global.getSector().getClock()，战斗/标题界面里不可靠，phaseAngle 又是私有字段，
	// 所以下面的 AuroraRing 按原版算法移植（双光带反向流动 + 外缘脉动 + 外缘摆动 + 纹理平铺）。
	// 参数对象 AuroraParams 仍实现原版 AuroraRenderer.AuroraRendererDelegate，也可以直接交给原版渲染器。
	//
	// 用法：
	//   // 1) 固定位置，完整显示 3 秒（另有 0.5 秒淡入、0.5 秒淡出）
	//   Moci_Util.spawnAurora(point, 80f, 260f, new Color(100, 200, 255), 3f);
	//
	//   // 2) 跟随舰船，舰船死亡/变残骸/离场时自动淡出；duration < 0 表示一直存在
	//   Moci_Util.AuroraCombatPlugin aura = Moci_Util.spawnAuroraOnEntity(ship, 60f, 220f, new Color(120, 255, 180), -1f);
	//   aura.fadeOutAndExpire(); // 需要时手动结束
	//
	//   // 3) 完整参数
	//   Moci_Util.spawnAurora(new Moci_Util.AuroraParams()
	//       .setAnchor(ship).setRadius(50f, 300f)
	//       .setColor(new Color(255, 120, 220)).setAlphaMult(0.8f)
	//       .setShortenMult(0.85f).setPhaseSpeed(30f)
	//       .setLifetime(0.3f, 5f, 1f)
	//       .setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER));
	//
	// 需要按角度变色/变粗时，继承 AuroraParams 覆写 getAuroraColorForAngle / getAuroraThicknessMult 等方法。
	// 自己的渲染器里也可以直接 new AuroraRing(params)，每帧调用 advance(amount) 和 render(alphaMult)。

	/** 原版极光纹理的 settings.json 分类（graphics/planets/aurorae.png，512x512，左右并排两条 256px 光带） */
	public static final String AURORA_TEXTURE_CATEGORY = "terrain";
	/** 原版极光纹理的 settings.json 键名 */
	public static final String AURORA_TEXTURE_KEY = "aurora";
	/** 原版相位速度（度/秒）：原版每游戏日转 180°，CampaignClock.SECONDS_PER_GAME_DAY = 10，即 18°/秒 */
	public static final float AURORA_VANILLA_PHASE_SPEED = 18f;
	/** 原版每段弧长（像素），同时是纹理平铺和外缘摆动幅度的基准 */
	public static final float AURORA_VANILLA_PIXELS_PER_SEGMENT = 50f;

	private static final int AURORA_MAX_ERROR_REPORTS = 5;
	private static int auroraErrorReports = 0;

	/**
	 * 极光参数，同时实现原版 AuroraRenderer.AuroraRendererDelegate。
	 * 默认值取自原版 SpatialAnomalyTerrainPlugin：aurorae.png 纹理、(255,165,100) 橙色、shortenMult 0.5、其余倍率 1。
	 * 原版其他预设：MagneticFieldTerrainPlugin 的 shortenMult 为 0（只流动不脉动），
	 * StarCoronaTerrainPlugin 的 shortenMult 为 0.85（脉动更强）。
	 *
	 * 外缘在 outerRadius ± (outerRadius - innerRadius) * shortenMult 范围内脉动，另有 wobble 像素的摆动。
	 * phaseSpeed、wobble、分段、alphaMult、生命周期、layer 只对 AuroraRing / AuroraCombatPlugin 生效，原版渲染器不读取。
	 * 子类覆写 getAuroraXxx(angle) 可以做按角度变化的效果，angle 为世界角度 0~360，0 指向 +X。
	 * estimateMaxExtent() 默认沿一圈取 16 个角度估算最远绘制距离；倍率在取样角之间有尖峰时请覆写它，避免被视口裁剪。
	 * 生成后修改字段会实时生效（layer 除外）。
	 */
	public static class AuroraParams implements AuroraRenderer.AuroraRendererDelegate {
		/** estimateMaxExtent 沿一圈取样的角度数 */
		private static final int EXTENT_SAMPLES = 16;

		/** 内半径 */
		public float innerRadius = 50f;
		/** 外半径（外缘脉动的中心位置） */
		public float outerRadius = 200f;
		/** 没有锚点时使用的中心点 */
		public final Vector2f center = new Vector2f();
		/** 锚点实体；设置后中心点跟随它的位置 */
		public CombatEntityAPI anchor = null;
		/** 锚点死亡、变成残骸、过期或离开战场时是否自动淡出（AuroraCombatPlugin 使用） */
		public boolean expireWithAnchor = true;

		/** 基础颜色，alpha 通道也参与计算 */
		public Color color = new Color(255, 165, 100, 255);
		/** 整体透明度倍率 */
		public float alphaMult = 1f;
		/** 外缘脉动幅度（相对内外半径差），0 = 不脉动 */
		public float shortenMult = 0.5f;
		/** 内缘随脉动内收的倍率（原版内缘位移为外缘的 10%） */
		public float innerOffsetMult = 1f;
		/** 厚度倍率 */
		public float thicknessMult = 1f;
		/** 额外厚度（像素） */
		public float thicknessFlat = 0f;
		/** 纹理沿圆周方向的平铺密度倍率 */
		public float texPerSegmentMult = 1f;
		/** 纹理中单条光带的宽度（像素），原版 aurorae.png 为 256 */
		public float bandWidthInTexture = 256f;
		/** 可选的原版遮挡器，见 setBlocker() */
		public RangeBlockerUtil blocker = null;

		/** 相位速度（度/秒），原版为 18 */
		public float phaseSpeed = AURORA_VANILLA_PHASE_SPEED;
		/** 外缘摆动幅度（像素），原版为 50 * 0.33 = 16.5 */
		public float wobble = AURORA_VANILLA_PIXELS_PER_SEGMENT * 0.33f;
		/** 期望的每段弧长（像素），越小越圆滑；原版固定 50 */
		public float segmentLength = 20f;
		/** 最少分段数（原版没有下限，小半径时只有十几段，外缘呈锯齿状） */
		public int minSegments = 120;
		/** 最多分段数 */
		public int maxSegments = 720;

		/** 淡入时间（秒） */
		public float fadeIn = 0.5f;
		/** 完整显示时间（秒，不含淡入淡出），小于 0 表示一直存在 */
		public float duration = -1f;
		/** 淡出时间（秒） */
		public float fadeOut = 0.5f;
		/** 战斗渲染层，需要在 spawnAurora 之前设置 */
		public CombatEngineLayers layer = CombatEngineLayers.BELOW_SHIPS_LAYER;

		private String textureCategory = AURORA_TEXTURE_CATEGORY;
		private String textureKey = AURORA_TEXTURE_KEY;
		private transient SpriteAPI texture = null;
		private transient boolean textureFailed = false;

		public AuroraParams setRadius(float innerRadius, float outerRadius) {
			this.innerRadius = innerRadius;
			this.outerRadius = outerRadius;
			return this;
		}

		/** 设置固定中心点（复制坐标，之后修改传入的向量不影响极光） */
		public AuroraParams setCenter(Vector2f loc) {
			if (loc != null) center.set(loc);
			return this;
		}

		/** 设置跟随的实体，null = 不跟随 */
		public AuroraParams setAnchor(CombatEntityAPI anchor) {
			this.anchor = anchor;
			if (anchor != null && anchor.getLocation() != null) center.set(anchor.getLocation());
			return this;
		}

		public AuroraParams setExpireWithAnchor(boolean expireWithAnchor) {
			this.expireWithAnchor = expireWithAnchor;
			return this;
		}

		public AuroraParams setColor(Color color) {
			if (color != null) this.color = color;
			return this;
		}

		public AuroraParams setAlphaMult(float alphaMult) {
			this.alphaMult = alphaMult;
			return this;
		}

		/** 淡入、完整显示、淡出时间（秒）；duration 小于 0 表示一直存在 */
		public AuroraParams setLifetime(float fadeIn, float duration, float fadeOut) {
			this.fadeIn = fadeIn;
			this.duration = duration;
			this.fadeOut = fadeOut;
			return this;
		}

		public AuroraParams setLayer(CombatEngineLayers layer) {
			if (layer != null) this.layer = layer;
			return this;
		}

		public AuroraParams setShortenMult(float shortenMult) {
			this.shortenMult = shortenMult;
			return this;
		}

		public AuroraParams setThickness(float thicknessMult, float thicknessFlat) {
			this.thicknessMult = thicknessMult;
			this.thicknessFlat = thicknessFlat;
			return this;
		}

		public AuroraParams setPhaseSpeed(float degreesPerSecond) {
			this.phaseSpeed = degreesPerSecond;
			return this;
		}

		public AuroraParams setWobble(float wobble) {
			this.wobble = wobble;
			return this;
		}

		public AuroraParams setSegments(float segmentLength, int minSegments, int maxSegments) {
			this.segmentLength = segmentLength;
			this.minSegments = minSegments;
			this.maxSegments = maxSegments;
			return this;
		}

		/** 恢复原版分段方式：每段 50 像素、不设实际下限（小半径下会出现原版那种锯齿感） */
		public AuroraParams useVanillaSegments() {
			return setSegments(AURORA_VANILLA_PIXELS_PER_SEGMENT, 3, Integer.MAX_VALUE);
		}

		/** 按 settings.json graphics 分类 + 键名加载纹理；纹理需像 aurorae.png 一样左右并排两条光带 */
		public AuroraParams setTexture(String category, String key, float bandWidthInTexture) {
			this.textureCategory = category;
			this.textureKey = key;
			this.bandWidthInTexture = bandWidthInTexture;
			this.texture = null;
			this.textureFailed = false;
			return this;
		}

		/** 直接指定纹理（左右并排两条光带，bandWidthInTexture 为单条宽度）；null = 恢复按分类 + 键名加载 */
		public AuroraParams setTexture(SpriteAPI sprite, float bandWidthInTexture) {
			this.texture = sprite;
			this.textureFailed = false;
			this.bandWidthInTexture = bandWidthInTexture;
			return this;
		}

		/**
		 * 设置原版遮挡器。注意 new RangeBlockerUtil(...) 的各角度上限初始为 0，
		 * 需要先 updateAndSync()（依赖战役实体）或自行写入上限，否则极光宽度为 0、看不见。
		 */
		public AuroraParams setBlocker(RangeBlockerUtil blocker) {
			this.blocker = blocker;
			return this;
		}

		/** 估算最远绘制距离（视口裁剪用）：沿一圈均匀取 16 个角度的倍率取最大值；倍率随角度剧烈变化时可覆写 */
		public float estimateMaxExtent() {
			float radStart = Math.max(0f, getAuroraInnerRadius());
			float radEnd = Math.max(radStart + 10f, getAuroraOuterRadius());
			float thickness = radEnd - radStart;
			float max = radStart;
			for (int i = 0; i < EXTENT_SAMPLES; i++) {
				float angle = i * (360f / EXTENT_SAMPLES);
				float pulse = Math.abs(thickness * getAuroraShortenMult(angle));
				float outer = radStart + Math.abs(thickness * getAuroraThicknessMult(angle)) + pulse
						+ Math.abs(getAuroraThicknessFlat(angle));
				float inner = radStart + pulse * 0.1f * Math.abs(getAuroraInnerOffsetMult(angle));
				max = Math.max(max, Math.max(outer, inner));
			}
			return max + Math.abs(wobble);
		}

		@Override
		public float getAuroraInnerRadius() {
			return innerRadius;
		}

		@Override
		public float getAuroraOuterRadius() {
			return outerRadius;
		}

		@Override
		public Vector2f getAuroraCenterLoc() {
			return anchor != null ? anchor.getLocation() : center;
		}

		@Override
		public Color getAuroraColorForAngle(float angle) {
			return color;
		}

		@Override
		public float getAuroraAlphaMultForAngle(float angle) {
			return 1f;
		}

		@Override
		public float getAuroraShortenMult(float angle) {
			return shortenMult;
		}

		@Override
		public float getAuroraInnerOffsetMult(float angle) {
			return innerOffsetMult;
		}

		@Override
		public float getAuroraThicknessMult(float angle) {
			return thicknessMult;
		}

		@Override
		public float getAuroraThicknessFlat(float angle) {
			return thicknessFlat;
		}

		@Override
		public float getAuroraTexPerSegmentMult() {
			return texPerSegmentMult;
		}

		@Override
		public float getAuroraBandWidthInTexture() {
			return bandWidthInTexture;
		}

		@Override
		public SpriteAPI getAuroraTexture() {
			if (texture != null || textureFailed) return texture;
			try {
				SpriteAPI sprite = Global.getSettings().getSprite(textureCategory, textureKey);
				if (sprite != null && sprite.getTextureId() == 0) {
					// 纹理未加载时补加载一次
					Global.getSettings().loadTexture(Global.getSettings().getSpriteName(textureCategory, textureKey));
					sprite = Global.getSettings().getSprite(textureCategory, textureKey);
				}
				texture = sprite;
				if (texture == null) reportAuroraError("找不到极光纹理: " + textureCategory + "/" + textureKey, null);
			} catch (Exception e) {
				texture = null;
				reportAuroraError("加载极光纹理失败: " + textureCategory + "/" + textureKey, e);
			}
			textureFailed = texture == null;
			return texture;
		}

		@Override
		public RangeBlockerUtil getAuroraBlocker() {
			return blocker;
		}
	}

	/**
	 * 原版 AuroraRenderer 的移植版：几何、纹理平铺、双光带反向流动、外缘脉动和摆动都按原版算法计算。
	 * 与原版的区别：
	 *  - 相位按 params.phaseSpeed（度/秒）推进，不依赖 Sector 时钟，战斗、标题界面都能用；
	 *  - 分段数可调（原版固定每段 50 像素），纹理平铺仍按原版 50 像素基准计算，分段数只影响圆滑程度；
	 *  - alpha 夹在 0~255（原版直接转 byte，超过 255 会溢出变暗）；
	 *  - 在 finally 中恢复 GL 状态；几何数据复用数组，每帧不产生临时对象。
	 * 一个 AuroraRing 对应一个 AuroraParams，可以在任意渲染回调里使用。
	 */
	public static class AuroraRing {
		private static final int HARD_MAX_SEGMENTS = 4096;

		private final AuroraParams params;
		private float phaseAngle = 0f;
		private float lastMaxExtent = 0f;

		private int capacity = 0;
		private float[] innerX, innerY, outerX, outerY, texV;
		private byte[] rgba;
		private int trigSegments = -1;
		private float[] segCos, segSin;
		private float leftTX0, rightTX0, leftTX1, rightTX1;

		public AuroraRing(AuroraParams params) {
			this.params = params != null ? params : new AuroraParams();
		}

		/** 推进相位，amount 为秒；暂停时传 0 或不调用 */
		public void advance(float amount) {
			if (!(amount > 0f) || !Float.isFinite(amount)) return;
			float next = phaseAngle + amount * params.phaseSpeed;
			if (Float.isFinite(next)) phaseAngle = Misc.normalizeAngle(next);
		}

		/** 绘制一帧（世界坐标），alphaMult 为额外透明度，会再乘 params.alphaMult */
		public void render(float alphaMult) {
			float alpha = alphaMult * params.alphaMult;
			if (!(alpha > 0f)) return;
			SpriteAPI tex = params.getAuroraTexture();
			if (tex == null) return;
			Vector2f loc = params.getAuroraCenterLoc();
			if (loc == null || !Float.isFinite(loc.x) || !Float.isFinite(loc.y)) return;

			int segments = buildGeometry(tex, alpha);
			if (segments <= 0) return;

			GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
			GL11.glPushMatrix();
			try {
				GL11.glTranslatef(loc.x, loc.y, 0f);
				GL11.glEnable(GL11.GL_TEXTURE_2D);
				tex.bindTexture();
				GL11.glEnable(GL11.GL_BLEND);
				GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

				int stride = segments + 1;
				for (int iter = 0; iter < 2; iter++) {
					float leftTX = iter == 0 ? leftTX0 : leftTX1;
					float rightTX = iter == 0 ? rightTX0 : rightTX1;
					int base = iter * stride;
					GL11.glBegin(GL11.GL_QUAD_STRIP);
					for (int i = 0; i < stride; i++) {
						int k = base + i;
						int c = k * 4;
						GL11.glColor4ub(rgba[c], rgba[c + 1], rgba[c + 2], rgba[c + 3]);
						GL11.glTexCoord2f(leftTX, texV[k]);
						GL11.glVertex2f(innerX[k], innerY[k]);
						GL11.glTexCoord2f(rightTX, texV[k]);
						GL11.glVertex2f(outerX[k], outerY[k]);
					}
					GL11.glEnd();
				}
			} finally {
				GL11.glPopMatrix();
				GL11.glPopAttrib();
			}
		}

		/** 计算两条光带的顶点（相对中心）、纹理坐标和颜色，返回分段数；数据异常时返回 0，本帧不绘制 */
		private int buildGeometry(SpriteAPI tex, float alphaMult) {
			float radStart = params.getAuroraInnerRadius();
			float radEnd = params.getAuroraOuterRadius();
			if (!Float.isFinite(radStart) || !Float.isFinite(radEnd)) return 0;
			if (radStart < 0f) radStart = 0f;
			if (radEnd < radStart + 10f) radEnd = radStart + 10f;
			float thickness = radEnd - radStart;

			float circ = (float) (Math.PI * 2f * (radStart + radEnd) / 2f);
			int segments = computeSegments(circ);
			ensureCapacity(segments);
			updateTrig(segments);

			// 纹理平铺：按原版每段 50 像素的基准算出整圈平铺次数，再均分到实际分段上
			float bandWidthInTexture = params.getAuroraBandWidthInTexture();
			float imageWidth = tex.getWidth();
			float imageHeight = tex.getHeight();
			if (!(imageWidth > 0f) || !(imageHeight > 0f)) return 0;
			int refSegments = Math.max(1, Math.round(circ / AURORA_VANILLA_PIXELS_PER_SEGMENT));
			float refTexPerSegment = AURORA_VANILLA_PIXELS_PER_SEGMENT * tex.getTextureHeight() / imageHeight
					* bandWidthInTexture / thickness * params.getAuroraTexPerSegmentMult();
			float totalTex = Math.max(1f, Math.round(refTexPerSegment * refSegments));
			if (!Float.isFinite(totalTex)) return 0;
			float texPerSegment = totalTex / segments;
			// 原版第二条光带接着第一条的纹理进度继续，起点多走了一段
			float texStart1 = totalTex + totalTex / refSegments;

			float texWidth = tex.getTextureWidth();
			leftTX0 = texWidth * bandWidthInTexture / imageWidth;
			rightTX0 = 2f * texWidth * bandWidthInTexture / imageWidth - 0.001f;
			leftTX1 = 0f;
			rightTX1 = texWidth * bandWidthInTexture / imageWidth - 0.001f;

			RangeBlockerUtil blocker = params.getAuroraBlocker();
			float phaseRadBase = (float) Math.toRadians(phaseAngle);
			float wobble = Float.isFinite(params.wobble) ? params.wobble : 0f;
			float anglePerSegment = (float) (Math.PI * 2f) / segments;
			int stride = segments + 1;
			float maxExtent = 0f;

			for (int iter = 0; iter < 2; iter++) {
				float texStart = iter == 0 ? 0f : texStart1;
				for (int i = 0; i < stride; i++) {
					int segIndex = i % segments;
					float phaseRad;
					if (iter == 0) {
						phaseRad = phaseRadBase + (segIndex * anglePerSegment * 10f);
					} else {
						phaseRad = -phaseRadBase + (segIndex * anglePerSegment * 5f);
					}

					float angle = (float) Math.toDegrees(segIndex * anglePerSegment);
					if (iter == 1) {
						angle += 180f;
						if (angle >= 360f) angle -= 360f;
					}

					float blockerMax = 100000f;
					if (blocker != null) {
						blockerMax = blocker.getCurrMaxAt(angle) * 0.75f;
						if (blockerMax > blocker.getMaxRange()) blockerMax = blocker.getMaxRange();
					}

					float pulseSin = (float) Math.sin(phaseRad);
					float pulseCos = (float) Math.cos(phaseRad);
					float pulseMax = thickness * params.getAuroraShortenMult(angle);
					if (pulseMax > blockerMax * 0.5f) pulseMax = blockerMax * 0.5f;
					float pulseAmount = pulseSin * pulseMax;
					float pulseInner = pulseAmount * 0.1f * params.getAuroraInnerOffsetMult(angle);

					float rInner = radStart - pulseInner;
					if (rInner < radStart * 0.9f) rInner = radStart * 0.9f;
					float rOuter = radStart + thickness * params.getAuroraThicknessMult(angle) - pulseAmount
							+ params.getAuroraThicknessFlat(angle);
					if (blocker != null) {
						if (rOuter > blockerMax - pulseAmount) {
							rOuter = blockerMax - pulseAmount;
							if (rOuter < radStart) rOuter = radStart;
						}
						if (rInner > rOuter) rInner = rOuter;
					}
					if (!Float.isFinite(rInner) || !Float.isFinite(rOuter)) return 0;

					float cos = segCos[segIndex];
					float sin = segSin[segIndex];
					float x1 = cos * rInner;
					float y1 = sin * rInner;
					float x2 = cos * rOuter + pulseCos * wobble;
					float y2 = sin * rOuter + pulseSin * wobble;
					if (iter == 1) {
						// 原版在两条光带之间 glRotatef(180)，这里直接把坐标取反，效果相同
						x1 = -x1;
						y1 = -y1;
						x2 = -x2;
						y2 = -y2;
					}

					Color color = params.getAuroraColorForAngle(angle);
					if (color == null) return 0;
					float a = params.getAuroraAlphaMultForAngle(angle);
					if (blocker != null) a *= blocker.getAlphaAt(angle);
					int alphaByte = (int) (color.getAlpha() * alphaMult * a);
					if (alphaByte < 0) alphaByte = 0;
					if (alphaByte > 255) alphaByte = 255;

					int k = iter * stride + i;
					innerX[k] = x1;
					innerY[k] = y1;
					outerX[k] = x2;
					outerY[k] = y2;
					texV[k] = texStart + i * texPerSegment;
					int c = k * 4;
					rgba[c] = (byte) color.getRed();
					rgba[c + 1] = (byte) color.getGreen();
					rgba[c + 2] = (byte) color.getBlue();
					rgba[c + 3] = (byte) alphaByte;

					float extent = Math.max(Math.abs(rInner), Math.abs(rOuter) + Math.abs(wobble));
					if (extent > maxExtent) maxExtent = extent;
				}
			}
			lastMaxExtent = maxExtent;
			return segments;
		}

		private int computeSegments(float circumference) {
			int maxSeg = Math.max(3, Math.min(HARD_MAX_SEGMENTS, params.maxSegments));
			int minSeg = Math.max(3, Math.min(maxSeg, params.minSegments));
			int segments = minSeg;
			float len = params.segmentLength;
			if (len > 0f && Float.isFinite(len) && Float.isFinite(circumference)) {
				segments = Math.round(circumference / len);
			}
			if (segments < minSeg) segments = minSeg;
			if (segments > maxSeg) segments = maxSeg;
			return segments;
		}

		private void ensureCapacity(int segments) {
			int needed = (segments + 1) * 2;
			if (needed <= capacity) return;
			// 留 50% 余量，半径逐帧变化时不会每帧重新分配
			capacity = Math.min(needed + needed / 2, (HARD_MAX_SEGMENTS + 1) * 2);
			innerX = new float[capacity];
			innerY = new float[capacity];
			outerX = new float[capacity];
			outerY = new float[capacity];
			texV = new float[capacity];
			rgba = new byte[capacity * 4];
		}

		private void updateTrig(int segments) {
			if (segments == trigSegments) return;
			if (segCos == null || segCos.length < segments) {
				int len = Math.max(segments, capacity / 2);
				segCos = new float[len];
				segSin = new float[len];
			}
			float anglePerSegment = (float) (Math.PI * 2f) / segments;
			for (int i = 0; i < segments; i++) {
				float theta = anglePerSegment * i;
				segCos[i] = (float) Math.cos(theta);
				segSin[i] = (float) Math.sin(theta);
			}
			trigSegments = segments;
		}

		public AuroraParams getParams() {
			return params;
		}

		public float getPhaseAngle() {
			return phaseAngle;
		}

		/** 设置相位（度），可让多个同时生成的极光错开 */
		public void setPhaseAngle(float degrees) {
			if (Float.isFinite(degrees)) phaseAngle = Misc.normalizeAngle(degrees);
		}

		/** 上一次绘制时最远顶点到中心的距离 */
		public float getLastMaxExtent() {
			return lastMaxExtent;
		}
	}

	/**
	 * 战斗内的极光渲染插件：淡入 → 持续 → 淡出，跟随锚点，暂停时冻结，按参数估算视口裁剪半径。
	 * 一般通过 Moci_Util.spawnAurora(...) / spawnAuroraOnEntity(...) 创建。
	 */
	public static class AuroraCombatPlugin extends BaseCombatLayeredRenderingPlugin {
		private static final float RENDER_RADIUS_PADDING = 50f;
		/** 锚点可能在加入战场之前就被绑定（例如在 applyEffectsAfterShipCreation 中生成），这段时间内不因"不在场"判定死亡 */
		private static final float ANCHOR_SPAWN_GRACE = 1f;

		private final AuroraParams params;
		private final AuroraRing ring;
		private final EnumSet<CombatEngineLayers> activeLayers;
		private float elapsed = 0f;
		private boolean fadingOut = false;
		private float fadeOutElapsed = 0f;
		private float fadeOutStartAlpha = 1f;
		private boolean expired = false;
		private boolean anchorSeenInPlay = false;

		public AuroraCombatPlugin(AuroraParams params) {
			super(params != null && params.layer != null ? params.layer : CombatEngineLayers.BELOW_SHIPS_LAYER);
			this.params = params != null ? params : new AuroraParams();
			this.ring = new AuroraRing(this.params);
			this.activeLayers = EnumSet.of(layer);
		}

		@Override
		public void init(CombatEntityAPI entity) {
			super.init(entity);
			syncHostLocation();
		}

		@Override
		public void advance(float amount) {
			if (expired) return;
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine == null || engine.isPaused()) return;
			if (!(amount > 0f) || !Float.isFinite(amount)) return;

			elapsed += amount;
			ring.advance(amount);

			if (!fadingOut) {
				if (params.anchor != null && params.expireWithAnchor && !isAnchorAlive(engine, params.anchor)) {
					beginFadeOut();
				} else if (params.duration >= 0f && elapsed >= auroraNonNegative(params.fadeIn) + params.duration) {
					beginFadeOut();
				}
			} else {
				fadeOutElapsed += amount;
				if (fadeOutElapsed >= auroraNonNegative(params.fadeOut)) expired = true;
			}
			syncHostLocation();
		}

		@Override
		public void render(CombatEngineLayers currLayer, ViewportAPI viewport) {
			if (expired) return;
			float alpha = getCurrentAlpha() * (viewport != null ? viewport.getAlphaMult() : 1f);
			if (!(alpha > 0f)) return;
			try {
				ring.render(alpha);
			} catch (RuntimeException e) {
				expired = true;
				reportAuroraError("极光渲染异常，已停止该特效", e);
			}
		}

		@Override
		public float getRenderRadius() {
			if (expired) return RENDER_RADIUS_PADDING;
			try {
				float r = Math.max(params.estimateMaxExtent(), ring.getLastMaxExtent());
				if (!Float.isFinite(r) || r < 0f) r = ring.getLastMaxExtent();
				return r + RENDER_RADIUS_PADDING;
			} catch (RuntimeException e) {
				expired = true;
				reportAuroraError("极光裁剪半径计算异常，已停止该特效", e);
				return RENDER_RADIUS_PADDING;
			}
		}

		@Override
		public EnumSet<CombatEngineLayers> getActiveLayers() {
			return activeLayers;
		}

		@Override
		public boolean isExpired() {
			return expired;
		}

		/** 按 params.fadeOut 淡出后移除 */
		public void fadeOutAndExpire() {
			beginFadeOut();
		}

		/** 立即移除 */
		public void expireNow() {
			expired = true;
		}

		public boolean isFadingOut() {
			return fadingOut;
		}

		/** 当前淡入淡出进度对应的透明度（0~1） */
		public float getCurrentAlpha() {
			if (expired) return 0f;
			if (fadingOut) {
				float fadeOut = auroraNonNegative(params.fadeOut);
				if (fadeOut <= 0f) return 0f;
				return fadeOutStartAlpha * Math.max(0f, 1f - fadeOutElapsed / fadeOut);
			}
			float fadeIn = auroraNonNegative(params.fadeIn);
			if (fadeIn > 0f && elapsed < fadeIn) return elapsed / fadeIn;
			return 1f;
		}

		public AuroraParams getParams() {
			return params;
		}

		public AuroraRing getRing() {
			return ring;
		}

		private void beginFadeOut() {
			if (fadingOut || expired) return;
			fadeOutStartAlpha = getCurrentAlpha();
			fadingOut = true;
			fadeOutElapsed = 0f;
			if (!(auroraNonNegative(params.fadeOut) > 0f)) expired = true;
		}

		private void syncHostLocation() {
			setAuroraHostLocation(entity, params.getAuroraCenterLoc());
		}

		private boolean isAnchorAlive(CombatEngineAPI engine, CombatEntityAPI anchor) {
			if (anchor.isExpired()) return false;
			if (engine.isEntityInPlay(anchor)) {
				anchorSeenInPlay = true;
			} else if (anchorSeenInPlay || elapsed > ANCHOR_SPAWN_GRACE) {
				return false;
			}
			if (anchor instanceof ShipAPI) {
				ShipAPI ship = (ShipAPI) anchor;
				return ship.isAlive() && !ship.isHulk();
			}
			return true;
		}
	}

	/**
	 * 在战斗中生成极光（完整参数版）。
	 *
	 * @param params 极光参数，生成后修改字段会实时生效（layer 除外）
	 * @return 极光插件，可调用 fadeOutAndExpire() 提前结束；不在战斗中时返回 null
	 */
	public static AuroraCombatPlugin spawnAurora(AuroraParams params) {
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return null;
		AuroraCombatPlugin plugin = new AuroraCombatPlugin(params);
		CombatEntityAPI host = engine.addLayeredRenderingPlugin(plugin);
		setAuroraHostLocation(host, plugin.getParams().getAuroraCenterLoc());
		return plugin;
	}

	/**
	 * 在固定位置生成极光，淡入、淡出各 0.5 秒。
	 *
	 * @param center 中心点（复制坐标）
	 * @param innerRadius 内半径
	 * @param outerRadius 外半径
	 * @param color 颜色
	 * @param duration 完整显示时长（秒，不含淡入淡出），小于 0 表示一直存在直到 fadeOutAndExpire()
	 * @return 极光插件；不在战斗中时返回 null
	 */
	public static AuroraCombatPlugin spawnAurora(Vector2f center, float innerRadius, float outerRadius, Color color, float duration) {
		return spawnAurora(new AuroraParams()
				.setCenter(center)
				.setRadius(innerRadius, outerRadius)
				.setColor(color)
				.setLifetime(0.5f, duration, 0.5f));
	}

	/**
	 * 生成跟随实体的极光，实体死亡、变成残骸、过期或离开战场时自动淡出；淡入、淡出各 0.5 秒。
	 *
	 * @param anchor 跟随的实体（舰船、导弹、弹丸等）
	 * @param innerRadius 内半径
	 * @param outerRadius 外半径
	 * @param color 颜色
	 * @param duration 完整显示时长（秒，不含淡入淡出），小于 0 表示一直存在
	 * @return 极光插件；anchor 为 null 或不在战斗中时返回 null
	 */
	public static AuroraCombatPlugin spawnAuroraOnEntity(CombatEntityAPI anchor, float innerRadius, float outerRadius, Color color, float duration) {
		if (anchor == null) return null;
		return spawnAurora(new AuroraParams()
				.setAnchor(anchor)
				.setRadius(innerRadius, outerRadius)
				.setColor(color)
				.setLifetime(0.5f, duration, 0.5f));
	}

	private static void setAuroraHostLocation(CombatEntityAPI host, Vector2f loc) {
		if (host == null || loc == null || host.getLocation() == null) return;
		if (!Float.isFinite(loc.x) || !Float.isFinite(loc.y)) return;
		host.getLocation().set(loc);
	}

	private static float auroraNonNegative(float value) {
		return Float.isFinite(value) && value > 0f ? value : 0f;
	}

	// ==================== 圆环 / 螺旋(RingRenderer)特效 ====================
	// 原版 RingRenderer 不依赖战役时钟，直接复用其绘制算法。
	// 快捷生成：spawnRing(point, 80f, 120f, Color.CYAN, 3f);
	// 跟随实体：spawnRingOnEntity(ship, 80f, 120f, Color.CYAN, -1f);
	// 螺旋：spawnRing(new RingParams().setAnchor(ship).setRadius(0f, 240f)
	//         .setSpiral(true).setRotationSpeed(30f).setLifetime(0.3f, 3f, 0.5f));
	// 在自己的渲染回调中也可 new RingEffect(params)，自行调用 advance(amount) / render(alphaMult)。

	public static final String RING_TEXTURE_CATEGORY = "systemMap";
	public static final String RING_TEXTURE_KEY = "map_ring";
	private static int ringErrorReports = 0;

	/**
	 * 原版圆环渲染器的参数。默认使用星图圆环纹理、普通透明混合（非加法发光）。
	 * 半径为世界单位；scale 只缩放半径，不缩放中心坐标。
	 * 生成后可修改参数，layer 仅在创建插件时读取。
	 */
	public static class RingParams {
		public float innerRadius = 80f;
		public float outerRadius = 120f;
		public final Vector2f center = new Vector2f();
		public CombatEntityAPI anchor = null;
		public boolean expireWithAnchor = true;
		public Color color = Color.WHITE;
		public float alphaMult = 1f;
		/** false = 闭合圆环；true = 从外向内盘旋、两端渐隐的原版螺旋 */
		public boolean spiral = false;
		public float scale = 1f;
		/** 初始旋转角度（度）；正数逆时针 */
		public float angle = 0f;
		/** 旋转速度（度/秒），默认静止 */
		public float rotationSpeed = 0f;
		public float fadeIn = 0.5f;
		/** 完整显示时长，不含淡入淡出；小于 0 表示无限 */
		public float duration = -1f;
		public float fadeOut = 0.5f;
		public CombatEngineLayers layer = CombatEngineLayers.BELOW_SHIPS_LAYER;

		private String textureCategory = RING_TEXTURE_CATEGORY;
		private String textureKey = RING_TEXTURE_KEY;
		private transient RingRenderer renderer;
		private transient boolean textureFailed;

		public RingParams setRadius(float innerRadius, float outerRadius) {
			this.innerRadius = innerRadius;
			this.outerRadius = outerRadius;
			return this;
		}

		/** 复制固定中心坐标；已有锚点时仍优先跟随锚点，setAnchor(null) 可解除 */
		public RingParams setCenter(Vector2f loc) {
			if (loc != null) center.set(loc);
			return this;
		}

		public RingParams setAnchor(CombatEntityAPI anchor) {
			this.anchor = anchor;
			if (anchor != null && anchor.getLocation() != null) center.set(anchor.getLocation());
			return this;
		}

		public RingParams setExpireWithAnchor(boolean expireWithAnchor) {
			this.expireWithAnchor = expireWithAnchor;
			return this;
		}

		public RingParams setColor(Color color) {
			if (color != null) this.color = color;
			return this;
		}

		public RingParams setAlphaMult(float alphaMult) {
			this.alphaMult = alphaMult;
			return this;
		}

		public RingParams setSpiral(boolean spiral) {
			this.spiral = spiral;
			return this;
		}

		public RingParams setScale(float scale) {
			this.scale = scale;
			return this;
		}

		public RingParams setAngle(float degrees) {
			this.angle = degrees;
			return this;
		}

		public RingParams setRotationSpeed(float degreesPerSecond) {
			this.rotationSpeed = degreesPerSecond;
			return this;
		}

		public RingParams setLifetime(float fadeIn, float duration, float fadeOut) {
			this.fadeIn = fadeIn;
			this.duration = duration;
			this.fadeOut = fadeOut;
			return this;
		}

		public RingParams setLayer(CombatEngineLayers layer) {
			if (layer != null) this.layer = layer;
			return this;
		}

		/** settings.json 的 graphics 分类和键名，例如 systemMap/map_asteroid_belt */
		public RingParams setTexture(String category, String key) {
			textureCategory = category;
			textureKey = key;
			renderer = null;
			textureFailed = false;
			return this;
		}

		public Vector2f getCenterLoc() {
			return anchor != null ? anchor.getLocation() : center;
		}

		/** 圆环和螺旋均不超出缩放后的外半径 */
		public float estimateMaxExtent() {
			float extent = outerRadius * scale;
			return Float.isFinite(extent) && extent > 0f ? extent : 0f;
		}

		private RingRenderer getRenderer() {
			if (renderer != null || textureFailed) return renderer;
			try {
				SpriteAPI sprite = Global.getSettings().getSprite(textureCategory, textureKey);
				if (sprite != null && sprite.getTextureId() == 0) {
					Global.getSettings().loadTexture(Global.getSettings().getSpriteName(textureCategory, textureKey));
					sprite = Global.getSettings().getSprite(textureCategory, textureKey);
				}
				if (sprite == null || sprite.getTextureId() == 0
						|| !(sprite.getWidth() > 0f) || !Float.isFinite(sprite.getWidth())
						|| !(sprite.getHeight() > 0f) || !Float.isFinite(sprite.getHeight())) {
					throw new IllegalArgumentException("圆环纹理未加载或尺寸无效");
				}
				renderer = new RingRenderer(textureCategory, textureKey);
			} catch (Exception e) {
				textureFailed = true;
				reportRingError("加载圆环纹理失败: " + textureCategory + "/" + textureKey, e);
			}
			return renderer;
		}
	}

	/**
	 * 可在任意世界坐标渲染回调中使用的原版 RingRenderer 包装。
	 * advance 只推进旋转；自行使用时由调用方处理暂停、寿命和淡入淡出。
	 */
	public static class RingEffect {
		// 原版每段约 5 像素，螺旋会按圈数倍增。限制原版循环工作量，异常参数不进入 GL。
		private static final int MAX_RENDER_SEGMENTS = 65536;
		private final RingParams params;
		private final Vector2f localCenter = new Vector2f();
		private float rotation = 0f;
		private boolean invalidGeometryReported = false;

		public RingEffect(RingParams params) {
			this.params = params != null ? params : new RingParams();
		}

		public void advance(float amount) {
			if (!(amount > 0f) || !Float.isFinite(amount) || !Float.isFinite(params.rotationSpeed)) return;
			rotation = (float) ((rotation + (double) amount * params.rotationSpeed) % 360d);
		}

		/** alphaMult 再乘参数透明度和颜色 alpha，结果限制为 0~1，避免原版 byte 溢出 */
		public void render(float alphaMult) {
			float alpha = alphaMult * params.alphaMult;
			if (!(alpha > 0f) || !Float.isFinite(alpha) || params.color == null) return;
			Vector2f loc = params.getCenterLoc();
			if (loc == null || !Float.isFinite(loc.x) || !Float.isFinite(loc.y) || !Float.isFinite(params.angle)) return;
			if (!hasValidGeometry()) {
				if (!invalidGeometryReported) {
					invalidGeometryReported = true;
					reportRingError("圆环半径/缩放无效或超过 65536 段，本帧跳过绘制", null);
				}
				return;
			}
			RingRenderer renderer = params.getRenderer();
			if (renderer == null) return;

			GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
			GL11.glPushMatrix();
			try {
				GL11.glTranslatef(loc.x, loc.y, 0f);
				GL11.glRotatef((float) (((double) params.angle + rotation) % 360d), 0f, 0f, 1f);
				// 原版 factor 还会缩放 loc；使用局部原点，让 scale 只改变大小。
				renderer.render(localCenter, params.innerRadius, params.outerRadius,
						params.color, params.spiral, params.scale, Math.min(1f, alpha));
			} finally {
				GL11.glPopMatrix();
				GL11.glPopAttrib();
			}
		}

		/** 按原版 float 运算检查除数及圆环/螺旋循环上限，避免极薄螺旋产生海量迭代 */
		private boolean hasValidGeometry() {
			float min = params.innerRadius, max = params.outerRadius, scale = params.scale;
			if (!Float.isFinite(min) || !Float.isFinite(max) || !Float.isFinite(scale)
					|| min < 0f || !(max > min) || !(scale > 0f)) return false;
			float middle = (min + (max - min) / 2f) * scale;
			float thickness = (max - min) * scale;
			float radius = middle - thickness / 2f;
			float segments = Math.round((float) (Math.PI * 2f * middle) / 5f);
			if (!Float.isFinite(middle) || !Float.isFinite(thickness) || !(thickness > 0f)
					|| !Float.isFinite(radius) || radius < 0f || segments < 3f || segments > MAX_RENDER_SEGMENTS
					|| !Float.isFinite(max * scale)) return false;
			if (params.spiral) {
				float outer = radius + thickness;
				thickness = Math.min(thickness, outer * 0.25f);
				radius = outer - thickness;
				float turns = Math.max(2f, (radius - radius * 0.01f) / (thickness * 1.25f));
				return thickness > 0f && radius > 0f && Float.isFinite(turns)
						&& segments * turns <= MAX_RENDER_SEGMENTS;
			}
			return true;
		}

		public RingParams getParams() {
			return params;
		}
	}

	/** 战斗内自动推进、暂停冻结、跟随锚点、淡入淡出和视口裁剪的圆环插件 */
	public static class RingCombatPlugin extends BaseCombatLayeredRenderingPlugin {
		private final RingParams params;
		private final RingEffect ring;
		private final EnumSet<CombatEngineLayers> activeLayers;
		private float elapsed = 0f;
		private boolean fadingOut = false;
		private float fadeOutElapsed = 0f;
		private float fadeOutStartAlpha = 1f;
		private boolean expired = false;
		private boolean anchorSeenInPlay = false;

		public RingCombatPlugin(RingParams params) {
			super(params != null && params.layer != null ? params.layer : CombatEngineLayers.BELOW_SHIPS_LAYER);
			this.params = params != null ? params : new RingParams();
			this.ring = new RingEffect(this.params);
			this.activeLayers = EnumSet.of(layer);
		}

		@Override
		public void init(CombatEntityAPI entity) {
			super.init(entity);
			syncHostLocation();
		}

		@Override
		public void advance(float amount) {
			if (expired) return;
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine == null || engine.isPaused() || !(amount > 0f) || !Float.isFinite(amount)) return;
			elapsed += amount;
			ring.advance(amount);
			if (!fadingOut) {
				if (params.anchor != null && params.expireWithAnchor && !isAnchorAlive(engine)) {
					beginFadeOut();
				} else if (params.duration >= 0f && elapsed >= auroraNonNegative(params.fadeIn) + params.duration) {
					beginFadeOut();
				}
			} else {
				fadeOutElapsed += amount;
				if (fadeOutElapsed >= auroraNonNegative(params.fadeOut)) expired = true;
			}
			syncHostLocation();
		}

		@Override
		public void render(CombatEngineLayers currLayer, ViewportAPI viewport) {
			if (expired) return;
			try {
				ring.render(getCurrentAlpha() * (viewport != null ? viewport.getAlphaMult() : 1f));
			} catch (RuntimeException e) {
				expired = true;
				reportRingError("圆环渲染异常，已停止该特效", e);
			}
		}

		@Override
		public float getRenderRadius() {
			return params.estimateMaxExtent() + 50f;
		}

		@Override
		public EnumSet<CombatEngineLayers> getActiveLayers() {
			return activeLayers;
		}

		@Override
		public boolean isExpired() {
			return expired;
		}

		public void fadeOutAndExpire() {
			beginFadeOut();
		}

		public void expireNow() {
			expired = true;
		}

		public boolean isFadingOut() {
			return fadingOut;
		}

		public float getCurrentAlpha() {
			if (expired) return 0f;
			if (fadingOut) {
				float fadeOut = auroraNonNegative(params.fadeOut);
				return fadeOut > 0f ? fadeOutStartAlpha * Math.max(0f, 1f - fadeOutElapsed / fadeOut) : 0f;
			}
			float fadeIn = auroraNonNegative(params.fadeIn);
			return fadeIn > 0f && elapsed < fadeIn ? elapsed / fadeIn : 1f;
		}

		public RingParams getParams() {
			return params;
		}

		public RingEffect getRing() {
			return ring;
		}

		private void beginFadeOut() {
			if (fadingOut || expired) return;
			fadeOutStartAlpha = getCurrentAlpha();
			fadingOut = true;
			fadeOutElapsed = 0f;
			if (!(auroraNonNegative(params.fadeOut) > 0f)) expired = true;
		}

		private void syncHostLocation() {
			setAuroraHostLocation(entity, params.getCenterLoc());
		}

		private boolean isAnchorAlive(CombatEngineAPI engine) {
			CombatEntityAPI anchor = params.anchor;
			if (anchor.isExpired()) return false;
			if (engine.isEntityInPlay(anchor)) {
				anchorSeenInPlay = true;
			} else if (anchorSeenInPlay || elapsed > 1f) {
				return false;
			}
			if (anchor instanceof ShipAPI) {
				ShipAPI ship = (ShipAPI) anchor;
				return ship.isAlive() && !ship.isHulk();
			}
			return true;
		}
	}

	/** 在战斗中生成圆环/螺旋；引擎自动推进和渲染，无需调用方每帧更新；无战斗引擎时返回 null */
	public static RingCombatPlugin spawnRing(RingParams params) {
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return null;
		RingCombatPlugin plugin = new RingCombatPlugin(params);
		CombatEntityAPI host = engine.addLayeredRenderingPlugin(plugin);
		setAuroraHostLocation(host, plugin.getParams().getCenterLoc());
		return plugin;
	}

	/** 固定位置圆环；duration 为完整显示秒数，淡入/淡出各 0.5 秒，负数时手动结束 */
	public static RingCombatPlugin spawnRing(Vector2f center, float innerRadius, float outerRadius, Color color, float duration) {
		return spawnRing(new RingParams().setCenter(center).setRadius(innerRadius, outerRadius)
				.setColor(color).setLifetime(0.5f, duration, 0.5f));
	}

	/** 跟随实体的圆环；死亡、残骸、过期或离场时自动淡出；anchor 为 null 时返回 null */
	public static RingCombatPlugin spawnRingOnEntity(CombatEntityAPI anchor, float innerRadius, float outerRadius, Color color, float duration) {
		if (anchor == null) return null;
		return spawnRing(new RingParams().setAnchor(anchor).setRadius(innerRadius, outerRadius)
				.setColor(color).setLifetime(0.5f, duration, 0.5f));
	}

	private static void reportRingError(String message, Throwable error) {
		if (ringErrorReports >= 5) return;
		ringErrorReports++;
		Global.getLogger(Moci_Util.class).error("[Moci_Ring] " + message, error);
	}

	/** 极光相关错误最多记录 AURORA_MAX_ERROR_REPORTS 次，避免每帧刷屏 */
	private static void reportAuroraError(String message, Throwable error) {
		if (auroraErrorReports >= AURORA_MAX_ERROR_REPORTS) return;
		auroraErrorReports++;
		Global.getLogger(Moci_Util.class).error("[Moci_Aurora] " + message, error);
	}
}
