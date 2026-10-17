package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * 暗影龙主炮特效
 * 基于RiftCascadeEffect，但移除了发射点的黑色粒子特效
 */
public class Moci_ShroudedDragon_Maingun_Effect implements BeamEffectPlugin {
	
	public static Color STANDARD_RIFT_COLOR = new Color(255,100,100,255);
	public static Color EXPLOSION_UNDERCOLOR = new Color(150, 25, 25, 150);
	
	public static String RIFTCASCADE_MINELAYER = "riftcascade_minelayer";
	
	public static int MAX_RIFTS = 5;
	public static float UNUSED_RANGE_PER_SPAWN = 200;
	public static float SPAWN_SPACING = 175;
	public static float SPAWN_INTERVAL = 0.1f;
	
	protected Vector2f arcFrom = null;
	protected Vector2f prevMineLoc = null;
	
	protected boolean doneSpawningMines = false;
	protected float spawned = 0;
	protected int numToSpawn = 0;
	protected float untilNextSpawn = 0;
	protected float spawnDir = 0;
	
	public Moci_ShroudedDragon_Maingun_Effect() {
	}
	
	public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
		// 移除了spawnNegativeParticles调用，不再生成黑色粒子特效
		
		if (beam.getBrightness() < 1f) return;
		
		if (doneSpawningMines) return;
		
		if (numToSpawn <= 0 && beam.getDamageTarget() != null) {
			float range = beam.getWeapon().getRange();
			float length = beam.getLengthPrevFrame();
			numToSpawn = (int) ((range - length) / UNUSED_RANGE_PER_SPAWN) + 1;
			if (numToSpawn > MAX_RIFTS) {
				numToSpawn = MAX_RIFTS;
			}
			untilNextSpawn = 0f;
		}
		
		untilNextSpawn -= amount;
		if (untilNextSpawn > 0) return;
		
		float perSpawn = SPAWN_SPACING;
		
		ShipAPI ship = beam.getSource();
		
		boolean spawnedMine = false;
		if (beam.getLength() > beam.getWeapon().getRange() - 10f) {
			float angle = Misc.getAngleInDegrees(beam.getFrom(), beam.getRayEndPrevFrame());
			Vector2f loc = Misc.getUnitVectorAtDegreeAngle(angle);
			loc.scale(beam.getLength());
			Vector2f.add(loc, beam.getFrom(), loc);
			
			spawnMine(ship, loc);
			spawnedMine = true;
		} else if (beam.getDamageTarget() != null) {
			Vector2f arcTo = getNextArcLoc(engine, beam, perSpawn);
			float thickness = beam.getWidth();
			float dist = Misc.getDistance(arcFrom, arcTo);
			if (dist < SPAWN_SPACING * 2f) {
				EmpArcEntityAPI arc = engine.spawnEmpArcVisual(arcFrom, null, arcTo, null, thickness, beam.getFringeColor(), Color.white);
				arc.setCoreWidthOverride(Math.max(20f, thickness * 0.67f));
			}
			spawnMine(ship, arcTo);
			spawnedMine = true;
			arcFrom = arcTo;
		}

		untilNextSpawn = SPAWN_INTERVAL;
		if (spawnedMine) {
			spawned++;
			if (spawned >= numToSpawn) {
				doneSpawningMines = true;
			}
		}
	}
	
	public Vector2f getNextArcLoc(CombatEngineAPI engine, BeamAPI beam, float perSpawn) {
		CombatEntityAPI target = beam.getDamageTarget();
		float radiusOverride = -1f;
		if (target instanceof ShipAPI) {
			ShipAPI ship = (ShipAPI) target;
			if (ship.getParentStation() != null && ship.getStationSlot() != null) {
				radiusOverride = Misc.getDistance(beam.getRayEndPrevFrame(), ship.getParentStation().getLocation()) + 0f;
				target = ship.getParentStation();
			}
		}
		
		if (arcFrom == null) {
			arcFrom = new Vector2f(beam.getRayEndPrevFrame());
			
			float beamAngle = Misc.getAngleInDegrees(beam.getFrom(), beam.getRayEndPrevFrame());
			float beamSourceToTarget = Misc.getAngleInDegrees(beam.getFrom(), target.getLocation());
			
			// 计算旋转方向，使地雷在更靠近光束直线的一侧生成
			spawnDir = Misc.getClosestTurnDirection(beamAngle, beamSourceToTarget);
			if (spawnDir == 0) spawnDir = 1;
			
			boolean computeNextLoc = false;
			if (prevMineLoc != null) {
				float dist = Misc.getDistance(arcFrom, prevMineLoc);
				if (dist < perSpawn) {
					perSpawn -= dist;
					computeNextLoc = true;
				}
			}
			if (!computeNextLoc) {
				return arcFrom;
			}
		}
		
		Vector2f targetLoc = target.getLocation();
		float targetRadius = target.getCollisionRadius();
		if (radiusOverride >= 0) {
			targetRadius = radiusOverride;
		}
		
		boolean hitShield = target.getShield() != null && target.getShield().isWithinArc(beam.getRayEndPrevFrame());
		if (hitShield) perSpawn *= 0.67f;
		
		float prevAngle = Misc.getAngleInDegrees(targetLoc, arcFrom);
		float anglePerSegment = 360f * perSpawn / (3.14f * 2f * targetRadius);
		if (anglePerSegment > 90f) anglePerSegment = 90f;
		float angle = prevAngle + anglePerSegment * spawnDir;
		
		Vector2f arcTo = Misc.getUnitVectorAtDegreeAngle(angle);
		arcTo.scale(targetRadius);
		Vector2f.add(targetLoc, arcTo, arcTo);
		
		float actualRadius = Global.getSettings().getTargetingRadius(arcTo, target, hitShield);
		if (radiusOverride >= 0) {
			actualRadius = radiusOverride;
		}
		if (!hitShield) {
			actualRadius += 30f + 50f * (float) Math.random();
		} else {
			actualRadius += 30f + 50f * (float) Math.random();
		}
		
		arcTo = Misc.getUnitVectorAtDegreeAngle(angle);
		arcTo.scale(actualRadius);
		Vector2f.add(targetLoc, arcTo, arcTo);
		
		// 如果目标是舰船且未击中护盾，尝试更贴近船体
		if (target instanceof ShipAPI && !hitShield) {
			ShipAPI ship = (ShipAPI) target;
			BoundsAPI bounds = ship.getExactBounds();
			if (bounds != null) {
				Vector2f best = null;
				float bestDist = Float.MAX_VALUE;
				for (SegmentAPI segment : bounds.getSegments()) {
					float test = Misc.getDistance(segment.getP1(), arcTo);
					if (test < bestDist) {
						bestDist = test;
						best = segment.getP1();
					}
				}
				if (best != null) {
					Object o = Global.getSettings().getWeaponSpec(RIFTCASCADE_MINELAYER).getProjectileSpec();
					if (o instanceof MissileSpecAPI) {
						MissileSpecAPI spec = (MissileSpecAPI) o;
						float explosionRadius = (float) spec.getBehaviorJSON().optJSONObject("explosionSpec").optDouble("coreRadius", 75f);
						float sizeMult = getSizeMult();
						explosionRadius *= sizeMult;
						
						Vector2f dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(best, arcTo));
						dir.scale(explosionRadius * 0.9f);
						Vector2f.add(best, dir, dir);
						arcTo = dir;
					}
				}
			}
		}
		
		return arcTo;
	}
	
	public float getSizeMult() {
		float sizeMult = 1f - spawned / (float) Math.max(1, numToSpawn - 1);
		sizeMult = 0.75f + (1f - sizeMult) * 0.5f;
		return sizeMult;
	}
	
	public void spawnMine(ShipAPI source, Vector2f mineLoc) {
		CombatEngineAPI engine = Global.getCombatEngine();
		
		MissileAPI mine = (MissileAPI) engine.spawnProjectile(source, null, 
															  RIFTCASCADE_MINELAYER, 
															  mineLoc, 
															  (float) Math.random() * 360f, null);
		
		// "spawned"不包括当前地雷
		float sizeMult = getSizeMult();
		mine.setCustomData("riftcascade_size_mult", sizeMult);
		
		if (source != null) {
			Global.getCombatEngine().applyDamageModifiersToSpawnedProjectileWithNullWeapon(
											source, WeaponType.ENERGY, false, mine.getDamage());
		}
		
		mine.getDamage().getModifier().modifyMult("mine_sizeMult", sizeMult);
		
		float fadeInTime = 0.05f;
		mine.getVelocity().scale(0);
		mine.fadeOutThenIn(fadeInTime);
		
		float liveTime = 0f;
		mine.setFlightTime(mine.getMaxFlightTime() - liveTime);
		mine.addDamagedAlready(source);
		mine.setNoMineFFConcerns(true);
		
		prevMineLoc = mineLoc;
	}
}
