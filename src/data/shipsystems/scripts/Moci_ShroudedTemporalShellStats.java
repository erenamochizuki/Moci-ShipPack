package data.shipsystems.scripts;

import java.awt.Color;
import java.util.EnumSet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.impl.combat.dweller.RiftLightningEffect;
import com.fs.starfarer.api.util.Misc;

import data.scripts.util.Moci_Util;
import data.scripts.util.Moci_MathPersonal;

import org.lwjgl.util.vector.Vector2f;

/**
 * 遮罩护盾系统 - 3倍时间加速
 * 
 * 船插增强：
 * - shrouded_mantle: +33%速度和机动性，红色引擎
 * - shrouded_lens: +33%实弹/能量武器射速，武器发光
 * - shrouded_thunderhead: 每0.1秒释放裂隙闪电
 */
public class Moci_ShroudedTemporalShellStats extends BaseShipSystemScript {
	
	public static final float MAX_TIME_MULT = 3f;
	public static final Color JITTER_COLOR = new Color(90, 165, 255, 55);
	public static final Color JITTER_UNDER_COLOR = new Color(90, 165, 255, 155);
	public static final Color JITTER_COLOR_RED = new Color(255, 0, 0, 55);
	public static final Color JITTER_UNDER_COLOR_RED = new Color(255, 0, 0, 155);
	public static final Color ENGINE_COLOR_RED = new Color(255, 0, 0, 255);
	
	private static final String HULLMOD_MANTLE = "shrouded_mantle";
	private static final String HULLMOD_LENS = "shrouded_lens";
	private static final String HULLMOD_THUNDERHEAD = "shrouded_thunderhead";
	
	private static final float MANTLE_SPEED_BONUS = 0.33f;
	private static final float LENS_ROF_BONUS = 0.33f;
	private static final float THUNDERHEAD_ARC_INTERVAL = 0.1f;
	private static final float THUNDERHEAD_ARC_RANGE = 500f;
	private static final float THUNDERHEAD_ARC_DAMAGE = 100f;
	private static final float THUNDERHEAD_ARC_EMP = 100f;
	private static final float THUNDERHEAD_RANDOM_ARC_MIN_RANGE = 150f;
	private static final float THUNDERHEAD_RANDOM_ARC_MAX_RANGE = 250f;
	
	private static final Color LENS_WEAPON_GLOW_COLOR = new Color(255, 0, 0, 150);
	private static final float LENS_WEAPON_GLOW_INTENSITY = 0.5f;
	private static final EnumSet<WeaponAPI.WeaponType> LENS_WEAPON_TYPES = EnumSet.of(
		WeaponAPI.WeaponType.BALLISTIC,
		WeaponAPI.WeaponType.ENERGY
	);
	
	private static final float THUNDERHEAD_ARC_RANGE_SQ = THUNDERHEAD_ARC_RANGE * THUNDERHEAD_ARC_RANGE;
	
	private float thunderheadTimer = 0f;

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
		
		boolean hasMantle = ship.getVariant().hasHullMod(HULLMOD_MANTLE);
		boolean hasLens = ship.getVariant().hasHullMod(HULLMOD_LENS);
		boolean hasThunderhead = ship.getVariant().hasHullMod(HULLMOD_THUNDERHEAD);
		boolean hasShroudedMod = hasMantle || hasLens || hasThunderhead;
		
		Color jitterColor = hasShroudedMod ? JITTER_COLOR_RED : JITTER_COLOR;
		Color jitterUnderColor = hasShroudedMod ? JITTER_UNDER_COLOR_RED : JITTER_UNDER_COLOR;
		Color engineColor = hasMantle ? ENGINE_COLOR_RED : jitterColor;

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

		ship.setJitterUnder(this, jitterUnderColor, jitterLevel, 25, 0f, 7f + jitterRangeBonus);

		float shipTimeMult = 1f + (MAX_TIME_MULT - 1f) * effectLevel;
		stats.getTimeMult().modifyMult(id, shipTimeMult);

		if (player) {
			Global.getCombatEngine().getTimeMult().modifyMult(id, 1f / shipTimeMult);
		} else {
			Global.getCombatEngine().getTimeMult().unmodify(id);
		}

		ship.getEngineController().fadeToOtherColor(this, engineColor, new Color(0, 0, 0, 0), effectLevel, 0.5f);
		
		if (hasMantle) {
			ship.getEngineController().extendFlame(this, 2f * effectLevel, 0f, 0f);
		} else {
			ship.getEngineController().extendFlame(this, -0.25f, -0.25f, -0.25f);
		}
		
		if (hasMantle) {
			stats.getMaxSpeed().modifyPercent(id, MANTLE_SPEED_BONUS * 100f * effectLevel);
			stats.getAcceleration().modifyPercent(id, MANTLE_SPEED_BONUS * 100f * effectLevel);
			stats.getDeceleration().modifyPercent(id, MANTLE_SPEED_BONUS * 100f * effectLevel);
			stats.getMaxTurnRate().modifyPercent(id, MANTLE_SPEED_BONUS * 100f * effectLevel);
			stats.getTurnAcceleration().modifyPercent(id, MANTLE_SPEED_BONUS * 100f * effectLevel);
		}
		
		if (hasLens) {
			stats.getBallisticRoFMult().modifyPercent(id, LENS_ROF_BONUS * 100f * effectLevel);
			stats.getEnergyRoFMult().modifyPercent(id, LENS_ROF_BONUS * 100f * effectLevel);
			ship.setWeaponGlow(LENS_WEAPON_GLOW_INTENSITY * effectLevel, LENS_WEAPON_GLOW_COLOR, LENS_WEAPON_TYPES);
		}
		
		if (hasThunderhead && state == State.ACTIVE) {
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine != null && !engine.isPaused()) {
				float amount = engine.getElapsedInLastFrame();
				thunderheadTimer += amount;
				
				if (thunderheadTimer >= THUNDERHEAD_ARC_INTERVAL) {
					thunderheadTimer -= THUNDERHEAD_ARC_INTERVAL;
					spawnThunderheadArc(ship, engine);
				}
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
		
		ship.setWeaponGlow(0f, LENS_WEAPON_GLOW_COLOR, LENS_WEAPON_TYPES);
		thunderheadTimer = 0f;
	}
	
	private void spawnThunderheadArc(ShipAPI ship, CombatEngineAPI engine) {
		if (ship == null || engine == null) return;
		
		CombatEntityAPI target = null;
		Vector2f targetPoint = null;
		float minDistanceSq = THUNDERHEAD_ARC_RANGE_SQ;
		
		for (MissileAPI missile : Moci_Util.getMissilesInRange(ship.getLocation(), THUNDERHEAD_ARC_RANGE)) {
			if (missile.getOwner() == ship.getOwner()) continue;
			if (missile.isFading()) continue;
			
			float distSq = getDistanceSquared(ship.getLocation(), missile.getLocation());
			if (distSq < minDistanceSq) {
				minDistanceSq = distSq;
				target = missile;
				targetPoint = missile.getLocation();
			}
		}
		
		if (target == null) {
			for (ShipAPI otherShip : Moci_Util.getEnemyShipsInRange(ship.getLocation(), THUNDERHEAD_ARC_RANGE, ship.getOwner())) {
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
		
		if (target == null) {
			targetPoint = Moci_MathPersonal.randomPointInRing(
				ship.getLocation(),
				THUNDERHEAD_RANDOM_ARC_MIN_RANGE,
				THUNDERHEAD_RANDOM_ARC_MAX_RANGE
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
		
		Global.getSoundPlayer().playSound("rift_lightning_fire", 1f, 0.75f, from, ship.getVelocity());
		
		if (target != null) {
			engine.spawnEmpArc(
				ship,
				from,
				ship,
				target,
				DamageType.ENERGY,
				THUNDERHEAD_ARC_DAMAGE,
				THUNDERHEAD_ARC_EMP,
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

	public StatusData getStatusData(int index, State state, float effectLevel) {
		ShipAPI ship = null;
		if (Global.getCombatEngine() != null && Global.getCombatEngine().getPlayerShip() != null) {
			ship = Global.getCombatEngine().getPlayerShip();
		}
		
		if (ship == null) {
			if (index == 0) return new StatusData("遮罩护盾", false);
			return null;
		}
		
		boolean hasMantle = ship.getVariant().hasHullMod(HULLMOD_MANTLE);
		boolean hasLens = ship.getVariant().hasHullMod(HULLMOD_LENS);
		boolean hasThunderhead = ship.getVariant().hasHullMod(HULLMOD_THUNDERHEAD);
		
		if (index == 0) {
			return new StatusData("遮罩护盾", false);
		}
		
		int currentIndex = 1;
		
		if (hasMantle) {
			if (index == currentIndex) {
				float bonus = MANTLE_SPEED_BONUS * 100f * effectLevel * effectLevel;
				return new StatusData("+" + (int)Math.round(bonus) + "% 速度和机动性", false);
			}
			currentIndex++;
		}
		
		if (hasLens) {
			if (index == currentIndex) {
				float bonus = LENS_ROF_BONUS * 100f * effectLevel * effectLevel;
				return new StatusData("+" + (int)Math.round(bonus) + "% 武器射速", false);
			}
			currentIndex++;
		}
		
		if (hasThunderhead) {
			if (index == currentIndex) {
				return new StatusData("裂隙闪电攻击", false);
			}
			currentIndex++;
		}
		
		return null;
	}
}
