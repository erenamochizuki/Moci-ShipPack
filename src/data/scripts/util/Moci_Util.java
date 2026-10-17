package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.dark.shaders.light.LightShader;
import org.dark.shaders.light.StandardLight;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
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
}
