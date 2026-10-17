package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 弹丸收束工具类
 * 
 * 功能：让弹丸以可控的幅度向目标点收束，而不是完全追踪
 * 
 * 使用方法：
 * 1. 在武器的onFire中调用 addConvergingProjectile() 注册弹丸
 * 2. 在战斗插件的advance中调用 updateProjectiles() 更新所有弹丸
 * 
 * 特点：
 * - 简单的弹道收束，不是复杂的追踪
 * - 收束强度可调（0.0-1.0）
 * - 自动清理已消失的弹丸
 */
public class Moci_ProjectileConvergenceUtil {
    
    // 存储所有需要收束的弹丸数据
    private static final Map<DamagingProjectileAPI, ConvergenceData> convergingProjectiles = new HashMap<>();
    private static CombatEngineAPI activeEngine = null;
    
    /**
     * 添加需要收束的弹丸
     * 
     * @param projectile 弹丸对象
     * @param targetPoint 目标收束点（通常是武器最大射程的朝向点）
     * @param convergenceStrength 收束强度（0.0-1.0，默认0.5）
     *                           0.0 = 不收束（直线飞行）
     *                           0.5 = 中等收束
     *                           1.0 = 强力收束
     */
    public static void addConvergingProjectile(DamagingProjectileAPI projectile, Vector2f targetPoint, float convergenceStrength) {
        CombatEngineAPI currentEngine = Global.getCombatEngine();
        if (currentEngine != null && currentEngine != activeEngine) {
            clear();
            activeEngine = currentEngine;
        }
        convergingProjectiles.put(projectile, new ConvergenceData(projectile, targetPoint, convergenceStrength));
    }
    
    /**
     * 更新所有收束弹丸（在战斗插件的advance中调用）
     * 
     * @param amount 时间增量
     * @param engine 战斗引擎
     */
    public static void updateProjectiles(float amount, CombatEngineAPI engine) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        if (engine != activeEngine) {
            clear();
            activeEngine = engine;
        }
        
        // 更新所有弹丸
        for (Map.Entry<DamagingProjectileAPI, ConvergenceData> entry : convergingProjectiles.entrySet()) {
            DamagingProjectileAPI proj = entry.getKey();
            ConvergenceData data = entry.getValue();
            
            // 检查弹丸是否还存在
            if (!engine.isEntityInPlay(proj)) {
                continue;
            }
            
            // 计算当前朝向到目标点的角度差
            float currentAngle = proj.getFacing();
            float targetAngle = VectorUtils.getAngle(proj.getLocation(), data.targetPoint);
            float angleDiff = MathUtils.getShortestRotation(currentAngle, targetAngle);
            
            // 根据收束强度计算实际转向角度
            float rotationAmount = angleDiff * data.convergenceStrength * amount * 2f;
            
            // 限制每帧最大转向角度（避免过度转向）
            float maxRotationPerFrame = 5f; // 每帧最大5度
            rotationAmount = MathUtils.clamp(rotationAmount, -maxRotationPerFrame, maxRotationPerFrame);
            
            // 应用转向
            if (Math.abs(rotationAmount) > 0.01f) {
                // 旋转速度向量
                VectorUtils.rotate(proj.getVelocity(), rotationAmount, proj.getVelocity());
                // 更新朝向
                proj.setFacing(currentAngle + rotationAmount);
            }
        }
        
        // 清理已消失的弹丸
        Iterator<Map.Entry<DamagingProjectileAPI, ConvergenceData>> iterator = convergingProjectiles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<DamagingProjectileAPI, ConvergenceData> entry = iterator.next();
            if (!engine.isEntityInPlay(entry.getKey())) {
                iterator.remove();
            }
        }
    }
    
    /**
     * 清空所有弹丸数据（战斗结束时调用）
     */
    public static void clear() {
        convergingProjectiles.clear();
        activeEngine = null;
    }
    
    /**
     * 获取当前追踪的弹丸数量（用于调试）
     */
    public static int getTrackedProjectileCount() {
        return convergingProjectiles.size();
    }
    
    /**
     * 弹丸收束数据类
     */
    private static class ConvergenceData {
        final Vector2f targetPoint;
        final float convergenceStrength;
        
        ConvergenceData(DamagingProjectileAPI projectile, Vector2f targetPoint, float convergenceStrength) {
            this.targetPoint = new Vector2f(targetPoint); // 复制一份，避免引用问题
            this.convergenceStrength = MathUtils.clamp(convergenceStrength, 0f, 1f);
        }
    }
}
