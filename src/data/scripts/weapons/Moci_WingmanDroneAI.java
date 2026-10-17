package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Moci_WingmanDroneAI implements ShipAIPlugin {
    // 无人机和母舰引用
    private final ShipAPI drone;
    private final ShipAPI mothership;
    
    // 威胁分析相关
    private IntervalUtil threatFinder = new IntervalUtil(0.2f, 0.3f); // 威胁检测间隔
    private CombatEntityAPI threat; // 当前威胁目标
    private WeaponAPI beamThreatSource; // 光束威胁源
    private IntervalUtil threatHolderTracker = new IntervalUtil(2f, 3f); // 威胁持有者检测间隔
    private ShipAPI potentialThreatHolder = null; // 潜在的威胁持有者
    
    // 机动相关
    private IntervalUtil updateListTracker = new IntervalUtil(0.05f, 0.1f); // 更新列表间隔
    private List<CombatEntityAPI> hardAvoidList = new ArrayList<CombatEntityAPI>(); // 需要避开的实体列表
    private static final float MAX_HARD_AVOID_RANGE = 200f; // 最大避开范围
    private static final float AVOID_RANGE = 50f; // 避开范围
    private static final float COHESION_RANGE = 100f; // 凝聚力范围
    
    // AI状态
    private ShipwideAIFlags flags = new ShipwideAIFlags();
    private ShipAIConfig config = new ShipAIConfig();
    private float elapsed = 0f; // 已过时间

    public Moci_WingmanDroneAI(ShipAPI drone, ShipAPI mothership) {
        this.drone = drone;
        this.mothership = mothership;
        doThreatAnalysis(); // 初始威胁分析
    }

    @Override
    public void advance(float amount) {
        if (Global.getCombatEngine().isPaused()) return;
        if (drone == null || mothership == null || !drone.isAlive() || !mothership.isAlive()) return;

        // 更新各种计时器
        flags.advance(amount);
        updateListTracker.advance(amount);
        threatFinder.advance(amount);
        threatHolderTracker.advance(amount);
        
        // 定期更新需要避开的实体列表
        if (updateListTracker.intervalElapsed()) {
            updateHardAvoidList();
        }
        
        // 定期进行威胁分析
        if (threatFinder.intervalElapsed()) {
            doThreatAnalysis();
        }
        
        // 定期分析威胁持有者
        if (threatHolderTracker.intervalElapsed()) {
            threatHolderAnalysis();
        }
        
        // 执行机动
        maneuver();
        
        // 更新已过时间
        if (!Global.getCombatEngine().isPaused()) {
            elapsed += amount;
        }
    }

    /**
     * 更新需要避开的实体列表
     */
    private void updateHardAvoidList() {
        hardAvoidList.clear();
        CollisionGridAPI grid = Global.getCombatEngine().getAiGridShips();
        Iterator<Object> iter = grid.getCheckIterator(drone.getLocation(), MAX_HARD_AVOID_RANGE * 2f, MAX_HARD_AVOID_RANGE * 2f);
        
        while (iter.hasNext()) {
            Object o = iter.next();
            if (!(o instanceof ShipAPI)) continue;
            
            ShipAPI s = (ShipAPI) o;
            if (s.isFighter()) continue;
            if (s.getOwner() == drone.getOwner()) continue;
            
            hardAvoidList.add(s);
        }
    }

    /**
     * 执行机动
     */
    private void maneuver() {
        if (mothership == null) return;
        
        CombatEngineAPI engine = Global.getCombatEngine();
        float avoidRange = AVOID_RANGE;
        
        // 根据战斗时间调整避开范围
        float sin = (float) Math.sin(engine.getElapsedInContactWithEnemy() * 1f);
        float mult = 1f + sin * 0.25f;
        avoidRange *= mult;
        
        Vector2f total = new Vector2f();
        
        // 处理威胁
        if (hasThreat()) {
            if (threat != null) {
                Vector2f tInter = AIUtils.getBestInterceptPoint(drone.getLocation(), drone.getMaxSpeedWithoutBoost(),
                        threat.getLocation(), threat.getVelocity());
                if (tInter != null) {
                    float dist = Misc.getDistance(drone.getLocation(), tInter);
                    Vector2f dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(drone.getLocation(), tInter));
                    float f = dist / 200f;
                    if (f > 1f) f = 1f;
                    dir.scale(f * 3f);
                    Vector2f.add(total, dir, total);
                    avoidRange *= 3f;
                }
            }
        }
        
        // 处理需要避开的实体
        boolean hardAvoiding = false;
        for (CombatEntityAPI other : hardAvoidList) {
            float dist = Misc.getDistance(drone.getLocation(), other.getLocation());
            float hardAvoidRange = other.getCollisionRadius() + avoidRange + 50f;
            if (dist < hardAvoidRange) {
                Vector2f dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(other.getLocation(), drone.getLocation()));
                float f = 1f - dist / hardAvoidRange;
                dir.scale(f * 5f);
                Vector2f.add(total, dir, total);
                hardAvoiding = f > 0.5f;
            }
        }
        
        // 处理与母舰的关系
        float sourceRejoin = mothership.getCollisionRadius() + mothership.getHullSize().ordinal() * 20f;
        float sourceRepel = mothership.getCollisionRadius() + mothership.getHullSize().ordinal() * 10f;
        float sourceCohesion = mothership.getCollisionRadius() + 200f;
        
        float dist = Misc.getDistance(drone.getLocation(), mothership.getLocation());
        
        // 如果距离太远，向母舰靠近
        if (dist > sourceRejoin) {
            Vector2f dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(drone.getLocation(), mothership.getLocation()));
            float f = dist / (sourceRejoin + 200f) - 1f;
            dir.scale(f * 0.5f);
            Vector2f.add(total, dir, total);
        }
        
        // 如果距离太近，远离母舰
        if (dist < sourceRepel) {
            Vector2f dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(mothership.getLocation(), drone.getLocation()));
            float f = 1f - dist / sourceRepel;
            dir.scale(f * 5f);
            Vector2f.add(total, dir, total);
        }
        
        // 如果母舰在移动，跟随母舰
        if (dist < sourceCohesion && mothership.getVelocity().length() > 20f) {
            Vector2f dir = new Vector2f(mothership.getVelocity());
            Misc.normalise(dir);
            float f = 1f - dist / sourceCohesion;
            dir.scale(f * 1f);
            Vector2f.add(total, dir, total);
        }
        
        // 如果没有其他移动需求，执行环绕
        if (total.length() <= 0.05f) {
            float offset = (float) (Math.random() > 0.5 ? 90f : -90f);
            if (potentialThreatHolder != null) {
                float diff = MathUtils.getShortestRotation(
                        VectorUtils.getAngle(mothership.getLocation(), drone.getLocation()),
                        VectorUtils.getAngle(mothership.getLocation(), potentialThreatHolder.getLocation()));
                if (Math.abs(diff) > 60f) {
                    offset = diff > 0 ? -90f : 90f;
                }
            }
            Vector2f dir = Misc.getUnitVectorAtDegreeAngle(
                    Misc.getAngleInDegrees(drone.getLocation(), mothership.getLocation()) + offset);
            dir.scale(1f);
            Vector2f.add(total, dir, total);
        }
        
        // 执行移动
        if (total.length() > 0) {
            float dir = Misc.getAngleInDegrees(total);
            engine.headInDirectionWithoutTurning(drone, dir, 10000);
            drone.getEngineController().forceShowAccelerating();
        }
    }

    /**
     * 分析威胁
     */
    private void doThreatAnalysis() {
        threat = null;
        beamThreatSource = null;
        float dmgMax = 0;
        
        // 检查投射物威胁
        for (DamagingProjectileAPI proj : Global.getCombatEngine().getProjectiles()) {
            if (proj.getOwner() == 100 || proj.getOwner() == mothership.getOwner()) continue;
            if (proj.getDamageAmount() < 200) continue;
            if (isAimingSource(proj, mothership) && 
                (proj.getDamageAmount() > dmgMax || (proj.getDamageAmount() == dmgMax && Math.random() > 0.5)) &&
                MathUtils.getDistanceSquared(mothership.getLocation(), proj.getLocation()) <= 4000000) {
                threat = proj;
                dmgMax = proj.getDamageAmount();
            }
        }
        
        // 检查光束威胁
        for (BeamAPI beam : Global.getCombatEngine().getBeams()) {
            if (beam.getDamageTarget() instanceof ShipAPI && ((ShipAPI) beam.getDamageTarget()) == mothership) {
                if (beam.getDamage().getDamage() >= 500f && !beam.getWeapon().isBurstBeam()) {
                    beamThreatSource = beam.getWeapon();
                    return;
                }
            }
        }
    }

    /**
     * 分析威胁持有者
     */
    private void threatHolderAnalysis() {
        if (potentialThreatHolder != null) {
            if (potentialThreatHolder.isAlive() &&
                MathUtils.getDistanceSquared(potentialThreatHolder.getLocation(), mothership.getLocation()) <= 2500f * 2500f) {
                return;
            } else {
                potentialThreatHolder = null;
            }
        }
        
        for (ShipAPI s : AIUtils.getNearbyEnemies(mothership, 2000f)) {
            if (s.getOwner() == 100 || s.getOwner() == mothership.getOwner()) continue;
            
            float sumTotal = 0;
            for (WeaponAPI w : s.getUsableWeapons()) {
                if (w.getDamage().getDamage() >= 500f && w.getCooldownRemaining() <= 2f) {
                    sumTotal += w.getDamage().getDamage();
                }
            }
            
            if (Math.random() < sumTotal / (sumTotal + 4000f)) {
                potentialThreatHolder = s;
            }
        }
    }

    /**
     * 检查是否有威胁
     */
    private boolean hasThreat() {
        return beamThreatSource != null || threat != null;
    }

    /**
     * 检查投射物是否瞄准母舰
     */
    private boolean isAimingSource(DamagingProjectileAPI proj, ShipAPI source) {
        if (proj.isFading() || proj.isExpired()) return false;
        
        if (proj instanceof MissileAPI) {
            MissileAPI m = (MissileAPI) proj;
            if (m.isGuided()) {
                if (m.getAI() instanceof GuidedMissileAI) {
                    return ((GuidedMissileAI) m.getAI()).getTarget() == source;
                }
            }
        }
        
        Vector2f closest = MathUtils.getNearestPointOnLine(source.getLocation(), proj.getLocation(),
                Vector2f.add(proj.getLocation(), (Vector2f) (new Vector2f(proj.getVelocity()).scale(1500f)), null));
        float shieldRadius = source.getShieldRadiusEvenIfNoShield();
        return MathUtils.getDistanceSquared(closest, source.getLocation()) <= shieldRadius * shieldRadius;
    }

    @Override
    public void cancelCurrentManeuver() {
    }

    @Override
    public void forceCircumstanceEvaluation() {
        doThreatAnalysis();
    }

    @Override
    public ShipwideAIFlags getAIFlags() {
        return flags;
    }

    @Override
    public ShipAIConfig getConfig() {
        return config;
    }

    @Override
    public boolean needsRefit() {
        return false;
    }

    @Override
    public void setDoNotFireDelay(float amount) {
    }
} 
