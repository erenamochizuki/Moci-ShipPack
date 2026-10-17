package data.shipsystems.scripts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import org.lazywizard.lazylib.MathUtils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import data.scripts.Moci_RadarVisualPlugin;

/**
 * 导弹防御雷达系统
 * 被动：每5秒扫描1500范围内导弹，根据数量执行不同反制措施
 * 主动：每0.2秒向最近目标发射电弧
 */
public class Moci_MissileDefenseRadarStats extends BaseShipSystemScript {
    
    // ========== 系统参数配置 ==========
    
    /** 扫描范围 */
    private static final float SCAN_RANGE = 1500f;
    
    /** 被动扫描间隔（5秒） */
    private final IntervalUtil passiveScanInterval = new IntervalUtil(5f, 5f);
    
    /** 主动攻击间隔（0.2秒） */
    private final IntervalUtil activeAttackInterval = new IntervalUtil(0.2f, 0.2f);
    
    /** 电弧伤害 */
    private static final float ARC_DAMAGE = 1f;
    
    /** 电弧EMP伤害 */
    private static final float ARC_EMP_DAMAGE = 5000f;
    
    /** 震荡波推力强度 */
    private static final float SHOCKWAVE_FORCE = 200f;
    
    // ========== 电弧颜色配置 ==========
    
    /** 电弧边缘颜色 */
    private static final Color ARC_FRINGE_COLOR = new Color(100, 200, 255, 255);
    
    /** 电弧核心颜色 */
    private static final Color ARC_CORE_COLOR = new Color(255, 255, 255, 255);
    
    // ========== 视觉效果相关 ==========
    
    /** 扫描效果旋转速度 */
    private static final float SCAN_ROTATION_SPEED = 30f;
    
    /** 雷达指针旋转速度 */
    private static final float RADAR_POINTER_SPEED = 120f;
    
    /** 当前扫描效果角度 */
    private float scanRotation = 0f;
    
    /** 当前雷达指针角度 */
    private float radarPointerRotation = 0f;
    
    /** 扫描效果缩放 */
    private float scanScale = 1f;
    
    /** 是否需要取消应用效果 */
    private boolean needsUnapply = false;
    
    /** 是否已初始化 */
    private boolean initialized = false;
    
    /** 视觉效果渲染插件 */
    private Moci_RadarVisualPlugin visualPlugin;
    
    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship = (ShipAPI) stats.getEntity();
        if (ship == null || ship.isHulk()) {
            if (needsUnapply) {
                unapplyEffects(id, stats);
                needsUnapply = false;
            }
            return;
        }
        
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) return;
        
        float amount = engine.getElapsedInLastFrame();
        
        // 初始化检查
        if (!initialized) {
            initialize(ship);
        }
        
        boolean isActive = state == State.IN || state == State.ACTIVE || state == State.OUT;
        
        if (isActive) {
            applyActiveEffects(id, stats, effectLevel);
            needsUnapply = true;
        } else {
            if (needsUnapply) {
                unapplyEffects(id, stats);
                needsUnapply = false;
            }
        }
        
        // 处理被动扫描效果
        handlePassiveScanning(ship, amount, engine);
        
        // 处理主动攻击效果
        if (state == State.ACTIVE) {
            handleActiveAttack(ship, amount, engine, effectLevel);
        }
        
        // 更新视觉效果
        updateVisualEffects(amount, isActive);
    }
    
    /**
     * 初始化系统
     */
    private void initialize(ShipAPI ship) {
        initialized = true;
        
        // 创建并添加视觉效果插件
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null && visualPlugin == null) {
            visualPlugin = new Moci_RadarVisualPlugin(ship);
            engine.addLayeredRenderingPlugin(visualPlugin);
            Global.getLogger(this.getClass()).info("导弹防御雷达视觉插件已添加");
        }
        
        Global.getLogger(this.getClass()).info("导弹防御雷达系统已初始化，舰船：" + ship.getHullSpec().getHullName());
    }
    
    /**
     * 应用主动效果
     */
    private void applyActiveEffects(String id, MutableShipStatsAPI stats, float effectLevel) {
        // 提升传感器范围和精度
        stats.getSightRadiusMod().modifyPercent(id, 25f * effectLevel);
    }
    
    /**
     * 取消应用效果
     */
    private void unapplyEffects(String id, MutableShipStatsAPI stats) {
        stats.getSightRadiusMod().unmodifyPercent(id);
    }
    
    /**
     * 处理被动扫描逻辑
     */
    private void handlePassiveScanning(ShipAPI ship, float amount, CombatEngineAPI engine) {
        passiveScanInterval.advance(amount);
        
        if (passiveScanInterval.intervalElapsed()) {
            // 使用Grid遍历获取范围内的导弹
            List<MissileAPI> nearbyMissiles = getMissilesInRange(ship, SCAN_RANGE, engine);
            
            // 过滤敌方导弹
            List<MissileAPI> enemyMissiles = new ArrayList<MissileAPI>();
            for (MissileAPI missile : nearbyMissiles) {
                if (missile.getOwner() != ship.getOwner() && 
                    !missile.isExpired() && 
                    engine.isEntityInPlay(missile) &&
                    !missile.isFlare()) {
                    enemyMissiles.add(missile);
                }
            }
            
            int missileCount = enemyMissiles.size();
            
            // 根据导弹数量执行不同策略
            if (missileCount > 0 && missileCount < 5) {
                // 少于5枚：精确电弧攻击
                executeTargetedEMPStrike(ship, enemyMissiles, engine);
            } else if (missileCount >= 5 && missileCount <= 15) {
                // 5-15枚：震荡波推开
                executeShockwavePush(ship, enemyMissiles, engine);
            }
            // 大于15枚：系统过载，无效果
            
            // 触发扫描视觉效果
            triggerScanEffect();
        }
    }
    
    /**
     * 处理主动攻击逻辑
     */
    private void handleActiveAttack(ShipAPI ship, float amount, CombatEngineAPI engine, float effectLevel) {
        activeAttackInterval.advance(amount);
        
        if (activeAttackInterval.intervalElapsed()) {
            // 寻找最近的目标
            CombatEntityAPI target = findNearestTarget(ship, engine);
            
            if (target != null) {
                // 发射电弧
                generateTargetedArc(ship, target, engine);
            } else {
                // 无目标时生成纯视觉效果
                generateVisualOnlyArc(ship, engine);
            }
        }
    }
    
    /**
     * 使用Grid遍历获取范围内的导弹
     */
    private List<MissileAPI> getMissilesInRange(ShipAPI ship, float range, CombatEngineAPI engine) {
        List<MissileAPI> missiles = new ArrayList<MissileAPI>();
        
        // 使用导弹专用Grid进行高效检索
        Iterator<Object> missileIter = engine.getMissileGrid().getCheckIterator(
            ship.getLocation(),
            range * 2f,  // 检查宽度
            range * 2f   // 检查高度
        );
        
        while (missileIter.hasNext()) {
            Object obj = missileIter.next();
            if (!(obj instanceof MissileAPI)) continue;
            
            MissileAPI missile = (MissileAPI) obj;
            
            // 精确距离检查
            float distance = Misc.getDistance(ship.getLocation(), missile.getLocation());
            if (distance <= range) {
                missiles.add(missile);
            }
        }
        
        return missiles;
    }
    
    /**
     * 执行精确EMP打击
     */
    private void executeTargetedEMPStrike(ShipAPI ship, List<MissileAPI> targets, CombatEngineAPI engine) {
        for (MissileAPI missile : targets) {
            engine.spawnEmpArc(
                ship,                    // 来源舰船
                ship.getLocation(),      // 起始点
                ship,                    // 起始绑定实体
                missile,                 // 目标
                DamageType.ENERGY,       // 伤害类型
                ARC_DAMAGE,              // 实体伤害
                ARC_EMP_DAMAGE,          // EMP伤害
                100000f,                 // 最大射程
                "tachyon_lance_emp_impact", // 音效
                15f,                     // 电弧宽度
                ARC_FRINGE_COLOR,        // 边缘颜色
                ARC_CORE_COLOR           // 核心颜色
            );
        }
        
        Global.getLogger(this.getClass()).info("精确EMP打击：攻击了 " + targets.size() + " 枚导弹");
    }
    
    /**
     * 执行震荡波推开效果
     */
    private void executeShockwavePush(ShipAPI ship, List<MissileAPI> targets, CombatEngineAPI engine) {
        Vector2f shipLocation = ship.getLocation();
        
        for (MissileAPI missile : targets) {
            // 计算推开方向（从舰船指向导弹）
            Vector2f pushDirection = Vector2f.sub(missile.getLocation(), shipLocation, null);
            pushDirection.normalise();
            
            // 应用推力
            float mass = Math.max(1f, missile.getMass());
            float force = SHOCKWAVE_FORCE / mass;
            
            Vector2f impulse = new Vector2f(pushDirection);
            impulse.scale(force);
            
            Vector2f.add(missile.getVelocity(), impulse, missile.getVelocity());
        }
        
        // 生成震荡波视觉效果
        generateShockwaveVisual(ship, engine);
        
        Global.getLogger(this.getClass()).info("震荡波推开：影响了 " + targets.size() + " 枚导弹");
    }
    
    /**
     * 寻找最近的攻击目标
     */
    private CombatEntityAPI findNearestTarget(ShipAPI ship, CombatEngineAPI engine) {
        CombatEntityAPI nearestTarget = null;
        float nearestDistance = Float.MAX_VALUE;
        
        // 优先寻找导弹
        List<MissileAPI> missiles = getMissilesInRange(ship, SCAN_RANGE, engine);
        for (MissileAPI missile : missiles) {
            if (missile.getOwner() != ship.getOwner() && 
                !missile.isExpired() && 
                engine.isEntityInPlay(missile)) {
                
                float distance = Misc.getDistance(ship.getLocation(), missile.getLocation());
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestTarget = missile;
                }
            }
        }
        
        // 没有导弹时寻找战机
        if (nearestTarget == null) {
            for (ShipAPI fighter : engine.getShips()) {
                if (fighter.isFighter() && 
                    fighter.getOwner() != ship.getOwner() && 
                    fighter.isAlive()) {
                    
                    float distance = Misc.getDistance(ship.getLocation(), fighter.getLocation());
                    if (distance <= SCAN_RANGE && distance < nearestDistance) {
                        nearestDistance = distance;
                        nearestTarget = fighter;
                    }
                }
            }
        }
        
        return nearestTarget;
    }
    
    /**
     * 生成目标电弧
     */
    private void generateTargetedArc(ShipAPI ship, CombatEntityAPI target, CombatEngineAPI engine) {
        engine.spawnEmpArc(
            ship,                    // 来源舰船
            ship.getLocation(),      // 起始点
            ship,                    // 起始绑定实体
            target,                  // 目标
            DamageType.ENERGY,       // 伤害类型
            ARC_DAMAGE,              // 实体伤害
            ARC_EMP_DAMAGE,          // EMP伤害
            100000f,                 // 最大射程
            "tachyon_lance_emp_impact", // 音效
            12f,                     // 电弧宽度
            ARC_FRINGE_COLOR,        // 边缘颜色
            ARC_CORE_COLOR           // 核心颜色
        );
    }
    
    /**
     * 生成纯视觉电弧
     */
    private void generateVisualOnlyArc(ShipAPI ship, CombatEngineAPI engine) {
        // 生成随机方向的装饰电弧
        Vector2f randomPoint = MathUtils.getPointOnCircumference(
            ship.getLocation(), 
            SCAN_RANGE * 0.5f, 
            (float) (Math.random() * 360f)
        );
        
        engine.spawnEmpArcVisual(
            ship.getLocation(),      // 起始点
            ship,                    // 起始绑定实体
            randomPoint,             // 目标点
            null,                    // 目标实体
            8f,                      // 电弧宽度
            ARC_FRINGE_COLOR,        // 边缘颜色
            ARC_CORE_COLOR           // 核心颜色
        );
    }
    
    /**
     * 生成震荡波视觉效果
     */
    private void generateShockwaveVisual(ShipAPI ship, CombatEngineAPI engine) {
        // 播放音效
        Global.getSoundPlayer().playSound("system_emp_emitter_activate", 1f, 1f, 
            ship.getLocation(), ship.getVelocity());
        
        // 生成扩散电弧效果
        for (int i = 0; i < 8; i++) {
            float angle = i * 45f;
            Vector2f arcEnd = MathUtils.getPointOnCircumference(
                ship.getLocation(), 
                SCAN_RANGE * 0.3f, 
                angle
            );
            
            engine.spawnEmpArcVisual(
                ship.getLocation(),      // 起始点
                ship,                    // 起始绑定实体
                arcEnd,                  // 目标点
                null,                    // 目标实体
                10f,                     // 电弧宽度
                ARC_FRINGE_COLOR,        // 边缘颜色
                ARC_CORE_COLOR           // 核心颜色
            );
        }
    }
    
    /**
     * 触发扫描视觉效果
     */
    private void triggerScanEffect() {
        scanScale = 1.5f; // 触发缩放效果
        
        // 触发视觉插件的扫描波纹效果
        if (visualPlugin != null) {
            visualPlugin.triggerScanWave();
        }
    }
    
    /**
     * 更新视觉效果
     */
    private void updateVisualEffects(float amount, boolean isActive) {
        // 更新扫描旋转
        scanRotation += SCAN_ROTATION_SPEED * amount;
        if (scanRotation > 360f) {
            scanRotation -= 360f;
        }
        
        // 更新扫描缩放（逐渐回归正常）
        if (scanScale > 1f) {
            scanScale = Math.max(1f, scanScale - amount * 2f);
        }
        
        // 主动状态时更新雷达指针
        if (isActive) {
            radarPointerRotation += RADAR_POINTER_SPEED * amount;
            if (radarPointerRotation > 360f) {
                radarPointerRotation -= 360f;
            }
        }
    }
    
    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        unapplyEffects(id, stats);
    }
    
    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (index == 0) {
            return new StatusData("导弹防御系统激活", false);
        }
        if (index == 1) {
            return new StatusData("传感器范围 +" + (int)(25f * effectLevel) + "%", false);
        }
        return null;
    }
    
    @Override
    public String getDisplayNameOverride(State state, float effectLevel) {
        if (state == State.IDLE || state == State.COOLDOWN) {
            return "导弹防御雷达 - 被动扫描";
        }
        return "导弹防御雷达 - 主动攻击";
    }
} 