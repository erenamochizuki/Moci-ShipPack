package data.shipsystems.scripts.ai;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

/**
 * 横向推进系统2 - AI控制脚本
 * 
 * 基于左/右加速命令的版本
 * AI会根据威胁位置智能选择左/右推进方向
 * 
 * @author Moci
 */
public class Moci_LateralThrustAI2 implements ShipSystemAIScript {
    
    private ShipAPI ship;
    private CombatEngineAPI engine;
    private ShipSystemAPI system;
    private boolean phaseCloak = false;
    
    // 系统ID
    private static final String SYSTEM_ID = "Moci_LateralThrustStats";
    private static final String RX104FF_SYSTEM_ID = "Moci_RX104FFFlightArmor";
    
    private IntervalUtil tracker = new IntervalUtil(0.5f, 0.5f);
    private float lastActivationTime = 0f;
    
    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.engine = engine;
        
        // 通过检查系统ID来判断槽位
        if (system != null && isSupportedSystem(system.getId())) {
            this.system = system;
        } else if (ship.getPhaseCloak() != null && isSupportedSystem(ship.getPhaseCloak().getId())) {
            this.system = ship.getPhaseCloak();
            this.phaseCloak = true;
        }
    }

    private boolean isSupportedSystem(String id) {
        return SYSTEM_ID.equals(id) || RX104FF_SYSTEM_ID.equals(id);
    }
    
    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        
        if (system == null) {
            return;
        }
        
        // 系统已激活或冷却中
        if (system.isActive() || system.isCoolingDown()) {
            return;
        }
        
        tracker.advance(amount);
        if (!tracker.intervalElapsed()) {
            return;
        }
        
        // 冷却缓冲
        float currentTime = engine.getTotalElapsedTime(false);
        if (currentTime - lastActivationTime < 2f) {
            return;
        }
        
        // 基础检查
        if (ship.isHulk() || ship.getEngineController().isFlamedOut()) {
            return;
        }
        
        // 威胁检测：检测真正会命中的敌方弹丸，并计算威胁方向
        int nearbyThreats = 0;
        Vector2f avgThreatDir = new Vector2f(0f, 0f);
        
        for (DamagingProjectileAPI proj : engine.getProjectiles()) {
            // 跳过友军
            if (proj.getOwner() == ship.getOwner()) {
                continue;
            }
            
            // 跳过已命中的
            if (proj.didDamage() || proj.isFading()) {
                continue;
            }
            
            // 精确距离检查
            Vector2f nearestPointOnShip = CollisionUtils.getNearestPointOnBounds(proj.getLocation(), ship);
            float distance;
            if (nearestPointOnShip != null) {
                distance = MathUtils.getDistance(nearestPointOnShip, proj.getLocation());
            } else {
                distance = MathUtils.getDistance(ship.getLocation(), proj.getLocation()) - ship.getCollisionRadius();
            }
            distance = Math.max(0f, distance);
            
            // 只检测500范围内的弹丸
            if (distance > 500f) {
                continue;
            }
            
            // 关键：判断弹丸是否会命中飞船
            if (willProjectileHitShip(proj, ship)) {
                nearbyThreats++;
                
                // 累加威胁方向
                Vector2f toProj = Vector2f.sub(proj.getLocation(), ship.getLocation(), null);
                avgThreatDir.x += toProj.x;
                avgThreatDir.y += toProj.y;
            }
        }
        
        // 如果有足够威胁就激活，并设置推进方向
        if (nearbyThreats >= 2) {
            // 计算平均威胁方向
            avgThreatDir.x /= nearbyThreats;
            avgThreatDir.y /= nearbyThreats;
            
            // 决定向左还是向右推进
            decideStrafeDirection(avgThreatDir);
            
            // 激活系统
            activateSystem();
            lastActivationTime = currentTime;
        }
    }
    
    /**
     * 根据威胁方向决定向左还是向右推进
     * 
     * @param threatDir 威胁方向向量（从飞船指向威胁）
     */
    private void decideStrafeDirection(Vector2f threatDir) {
        float shipFacing = ship.getFacing();
        
        // 计算威胁相对于船头的角度
        float threatAngle = (float) Math.toDegrees(Math.atan2(threatDir.y, threatDir.x));
        float relativeAngle = MathUtils.getShortestRotation(shipFacing, threatAngle);
        
        // 如果威胁在左侧，向右推进（远离威胁）
        // 如果威胁在右侧，向左推进（远离威胁）
        if (relativeAngle < 0) {
            // 威胁在左侧 → 向右推进（远离威胁）
            ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
        } else {
            // 威胁在右侧 → 向左推进（远离威胁）
            ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
        }
    }
    
    /**
     * 激活系统
     */
    private void activateSystem() {
        if (phaseCloak) {
            ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0);
        } else {
            ship.useSystem();
        }
    }
    
    /**
     * 判断弹丸是否会命中飞船
     * 使用弹道预测算法
     * 
     * @param proj 弹丸
     * @param ship 飞船
     * @return 是否会命中
     */
    private boolean willProjectileHitShip(DamagingProjectileAPI proj, ShipAPI ship) {
        // 获取弹丸和飞船的位置
        Vector2f projLoc = proj.getLocation();
        Vector2f shipLoc = ship.getLocation();
        
        // 获取弹丸速度向量
        Vector2f projVel = proj.getVelocity();
        if (projVel == null || (projVel.x == 0 && projVel.y == 0)) {
            // 静止弹丸（如地雷），只要在范围内就算威胁
            return true;
        }
        
        // 计算弹丸到飞船的向量
        Vector2f toShip = Vector2f.sub(shipLoc, projLoc, null);
        
        // 计算弹丸速度方向与到飞船方向的夹角
        float angleToShip = (float) Math.toDegrees(Math.atan2(toShip.y, toShip.x));
        float projAngle = (float) Math.toDegrees(Math.atan2(projVel.y, projVel.x));
        float angleDiff = Math.abs(MathUtils.getShortestRotation(projAngle, angleToShip));
        
        // 如果夹角大于45度，说明弹丸不是朝着飞船飞的
        if (angleDiff > 45f) {
            return false;
        }
        
        // 计算弹丸轨迹与飞船的最近距离
        float projSpeed = projVel.length();
        if (projSpeed < 1f) {
            return true; // 慢速弹丸，保守判断
        }
        
        // 归一化速度向量
        Vector2f projDir = new Vector2f(projVel.x / projSpeed, projVel.y / projSpeed);
        
        // 计算投影长度
        float projection = toShip.x * projDir.x + toShip.y * projDir.y;
        
        // 如果投影为负，说明飞船在弹丸后方
        if (projection < 0) {
            return false;
        }
        
        // 计算最近点
        Vector2f closestPoint = new Vector2f(
            projLoc.x + projDir.x * projection,
            projLoc.y + projDir.y * projection
        );
        
        // 计算最近距离
        float closestDistance = MathUtils.getDistance(closestPoint, shipLoc);
        
        // 考虑飞船碰撞半径，加上一定的安全余量
        float safetyMargin = ship.getCollisionRadius() * 1.2f;
        
        return closestDistance < safetyMargin;
    }
}
