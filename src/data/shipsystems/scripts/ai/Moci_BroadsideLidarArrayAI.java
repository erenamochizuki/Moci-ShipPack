package data.shipsystems.scripts.ai;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;

import data.shipsystems.scripts.Moci_BroadsideLidarArrayStats;

/**
 * 侧弦雷达阵列AI - 专为侧舷火力设计的雷达阵列系统AI
 * 与原版雷达阵列不同，此AI会考虑侧舷武器的覆盖范围，而非仅考虑船首朝向
 */
public class Moci_BroadsideLidarArrayAI implements ShipSystemAIScript {
    private ShipAPI ship;
    private ShipSystemAPI system;
    private ShipwideAIFlags flags;
    private CombatEngineAPI engine;

    private final IntervalUtil tracker = new IntervalUtil(0.5f, 1f);
    private float broadsidesEffectiveness = 0f;
    private boolean leftSideStronger = false;
    private boolean rightSideStronger = false;
    private static final float OPTIMAL_ANGLE_BUFFER = 45f; // 最佳侧弦攻击角度缓冲范围(度)
    
    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.flags = flags;
        this.engine = engine;
        
        // 初始化分析舰船的侧弦武器配置
        analyzeShipWeapons();
    }
    
    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (engine.isPaused() || ship == null || system == null) return;
        
        // 如果系统不可用或正在充能或冷却中，则跳过
        if (!system.isActive() && (system.getCooldownRemaining() > 0 || system.getAmmo() == 0)) return;
        
        // 在固定间隔内评估是否应该激活系统
        tracker.advance(amount);
        if (!tracker.intervalElapsed()) return;

        // 评估侧弦武器的有效性和当前战场情况
        evaluateBroadsideEffectiveness();
        
        // 如果效率达到阈值，激活系统
        if (broadsidesEffectiveness == 1) {
            // 如果需要，调整舰船朝向以使侧弦武器能发挥最大效果
            adjustShipFacing();
            ship.useSystem();
        }
    }
    
    /**
     * 分析舰船的武器配置，确定哪一侧的火力更强
     */
    private void analyzeShipWeapons() {
        float leftFirepower = 0f;
        float rightFirepower = 0f;
        
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            // 跳过装饰性武器和导弹武器
            if (weapon.isDecorative() || weapon.getType() == WeaponAPI.WeaponType.MISSILE) continue;
            
            // 获取武器在舰船坐标系中的角度
            float weaponAngle = weapon.getSlot().getAngle();
            float relativeAngle = MathUtils.getShortestRotation(0, weaponAngle);
            
            // 根据武器角度和DPS判断左右侧火力
            float dps = weapon.getDerivedStats().getDps();
            float arc = weapon.getArc();
            
            // 计算武器是否主要朝向左侧(-90度附近)或右侧(90度附近)
            if (relativeAngle < -30f && relativeAngle > -150f) {
                leftFirepower += dps * (1f - Math.abs(relativeAngle + 90f) / 90f) * (arc / 360f);
            } else if (relativeAngle > 30f && relativeAngle < 150f) {
                rightFirepower += dps * (1f - Math.abs(relativeAngle - 90f) / 90f) * (arc / 360f);
            }
        }
        
        // 确定哪一侧的火力更强
        leftSideStronger = leftFirepower > rightFirepower;
        rightSideStronger = rightFirepower > leftFirepower;
    }
    
    /**
     * 评估当前侧弦武器的效能
     * 简化后的逻辑：
     * 1. 检查目标是否在舰船火力最强一侧的方向（带缓冲范围）
     * 2. 检查目标是否在系统加成后的武器射程内
     */
    private void evaluateBroadsideEffectiveness() {
        // 重置有效性
        broadsidesEffectiveness = 0f;
        
        // 获取目标
        ShipAPI target = findBestTarget();
        if (target == null) return;
        
        // 获取目标相对于舰船的方向
        Vector2f toTarget = Vector2f.sub(target.getLocation(), ship.getLocation(), null);
        float angleToTarget = VectorUtils.getFacing(toTarget);
        float relativeAngle = MathUtils.getShortestRotation(ship.getFacing(), angleToTarget);
        
        // 根据火力更强的一侧确定最佳角度
        float optimalAngle;
        if (leftSideStronger) {
            // 如果左侧火力更强，最佳角度是-90度
            optimalAngle = -90f;
        } else if (rightSideStronger) {
            // 如果右侧火力更强，最佳角度是90度
            optimalAngle = 90f;
        } else {
            // 如果两侧火力相当，选择当前更接近的一侧
            optimalAngle = (relativeAngle > 0) ? 90f : -90f;
        }
        
        // 计算目标角度与最佳角度的差距
        float angleDifference = Math.abs(MathUtils.getShortestRotation(relativeAngle, optimalAngle));
        
        // 检查目标是否在理想方向的缓冲范围内
        boolean isInOptimalAngle = angleDifference <= OPTIMAL_ANGLE_BUFFER;
        
        // 获取系统加成后的最大射程
        float maxRange = getMaxBroadsideRange();
        
        // 检查目标是否在射程内
        boolean isInRange = MathUtils.getDistanceSquared(ship.getLocation(), target.getLocation()) <= maxRange * maxRange;
        
        // 如果目标在理想的角度范围内且在射程内，则认为系统有效
        if (isInOptimalAngle && isInRange) {
            // 角度差计算有效性
            broadsidesEffectiveness = 1f;
        }
        else {
            broadsidesEffectiveness = 0f;
        }
    }
    
    /**
     * 计算系统加成后的最大侧弦武器射程
     */
    private float getMaxBroadsideRange() {
        float maxBaseRange = 0; // 默认基础射程
        
        // 寻找侧弦武器中射程最远的
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.isDecorative() || weapon.getType() == WeaponAPI.WeaponType.MISSILE) continue;
            
            // 获取武器在舰船坐标系中的角度
            float weaponAngle = weapon.getSlot().getAngle();
            float relativeAngle = MathUtils.getShortestRotation(0, weaponAngle);
            
            // 检查是否为侧弦武器
            boolean isSideWeapon = (relativeAngle < -30f && relativeAngle > -150f) || 
                                   (relativeAngle > 30f && relativeAngle < 150f);
            
            if (isSideWeapon && weapon.getRange() > maxBaseRange) {
                maxBaseRange = weapon.getRange();
            }
        }
        
        // 应用系统射程加成(来自Moci_BroadsideLidarArrayStats)
        return maxBaseRange * (1f + Moci_BroadsideLidarArrayStats.RANGE_BONUS / 100f);
    }
    
    /**
     * 找出最佳目标
     */
    private ShipAPI findBestTarget() {
        ShipAPI target = ship.getShipTarget();
        
        // 如果没有指定目标，寻找最近的敌舰
        if (target == null || !target.isAlive()) {
            target = AIUtils.getNearestEnemy(ship);
        }
        
        return target;
    }

    
    /**
     * 调整舰船朝向以使侧弦武器能发挥最大效果
     */
    private void adjustShipFacing() {
        ShipAPI target = findBestTarget();
        if (target == null) return;
        
        // 获取目标相对于舰船的方向
        Vector2f toTarget = Vector2f.sub(target.getLocation(), ship.getLocation(), null);
        float angleToTarget = VectorUtils.getFacing(toTarget);
        
        // 根据更强的侧弦方向决定最佳朝向
        float bestFacing;
        if (leftSideStronger) {
            // 如果左侧更强，舰船应该右侧面向目标
            bestFacing = angleToTarget + 90f;
        } else if (rightSideStronger) {
            // 如果右侧更强，舰船应该左侧面向目标
            bestFacing = angleToTarget - 90f;
        } else {
            // 如果两侧实力相当，选择当前更接近目标的一侧
            float relativeAngle = MathUtils.getShortestRotation(ship.getFacing(), angleToTarget);
            if (relativeAngle > 0) {
                bestFacing = angleToTarget - 90f;
            } else {
                bestFacing = angleToTarget + 90f;
            }
        }
        
        // 标记为转向目标
        ship.getAIFlags().setFlag(ShipwideAIFlags.AIFlags.MANEUVER_TARGET, 1f, target);
        ship.getAIFlags().setFlag(ShipwideAIFlags.AIFlags.TURN_QUICKLY, 1f);
        
        // 尝试减速
        ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        
        // 设置所需的朝向（通过直接发送转向命令实现）
        float angleDiff = MathUtils.getShortestRotation(ship.getFacing(), bestFacing);
        if (angleDiff > 0) {
            ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
        } else {
            ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
        }
    }
} 
