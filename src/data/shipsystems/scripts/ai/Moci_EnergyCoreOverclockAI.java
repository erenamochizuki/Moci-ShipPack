package data.shipsystems.scripts.ai;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 能量核心超频系统AI - 简化版
 * 
 * 使用策略：
 * - 当有能量武器准备开火且幅能较低时激活
 * - 避免在已有待恢复幅能时重复使用
 */
public class Moci_EnergyCoreOverclockAI implements ShipSystemAIScript {
    
    private ShipAPI ship;
    private CombatEngineAPI engine;
    private ShipSystemAPI system;
    
    // AI参数
    private static final float MAX_FLUX_LEVEL = 0.6f;             // 最大幅能水平
    private static final float MIN_ENERGY_WEAPONS = 2;            // 最少能量武器数量
    
    private final IntervalUtil tracker = new IntervalUtil(0.5f, 0.7f);
    
    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.engine = engine;
    }
    
    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (engine.isPaused()) {
            return;
        }
        
        tracker.advance(amount);
        if (!tracker.intervalElapsed()) {
            return;
        }
        
        // 检查系统是否可用
        if (!system.isActive() && !system.isCoolingDown() && !system.isChargedown()) {
            if (shouldActivateSystem(target)) {
                ship.useSystem();
            }
        }
    }
    
    /**
     * 判断是否应该激活系统
     */
    private boolean shouldActivateSystem(ShipAPI target) {
        // 检查幅能水平
        if (ship.getFluxTracker().getFluxLevel() > MAX_FLUX_LEVEL) {
            return false;
        }
        
        // 检查是否有足够的能量武器
        if (!hasMinimumEnergyWeapons()) {
            return false;
        }
        
        // 检查是否还有待恢复的幅能
        if (hasPendingFlux()) {
            return false;
        }
        
        // 检查是否有目标且在射程内
        if (target != null && hasReadyEnergyWeapons(target)) {
            return true;
        }
        
        // 如果没有目标，检查是否有其他敌舰在射程内
        return hasEnemiesInRange();
    }
    
    /**
     * 检查是否有足够的能量武器
     */
    private boolean hasMinimumEnergyWeapons() {
        int energyWeaponCount = 0;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getType() == WeaponAPI.WeaponType.ENERGY && 
                weapon.getSize() != WeaponAPI.WeaponSize.SMALL) {
                energyWeaponCount++;
            }
        }
        return energyWeaponCount >= MIN_ENERGY_WEAPONS;
    }
    
    /**
     * 检查是否有待恢复的幅能
     */
    private boolean hasPendingFlux() {
        Object data = ship.getCustomData().get("Moci_EnergyCoreOverclock_Data");
        if (data != null) {
            try {
                java.lang.reflect.Field field = data.getClass().getDeclaredField("pendingFlux");
                field.setAccessible(true);
                float pendingFlux = field.getFloat(data);
                return pendingFlux > ship.getFluxTracker().getMaxFlux() * 0.1f;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
    
    /**
     * 检查是否有准备开火的能量武器
     */
    private boolean hasReadyEnergyWeapons(ShipAPI target) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getType() != WeaponAPI.WeaponType.ENERGY) {
                continue;
            }
            
            if (weapon.getCooldownRemaining() > 0.5f) {
                continue;
            }
            
            // 简单的距离检查
            float distanceToTarget = Vector2f.sub(target.getLocation(), ship.getLocation(), null).length();
            if (distanceToTarget <= weapon.getRange()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查是否有敌舰在射程内
     */
    private boolean hasEnemiesInRange() {
        for (ShipAPI enemy : engine.getShips()) {
            if (enemy.getOwner() == ship.getOwner() || !enemy.isAlive()) {
                continue;
            }
            
            float distance = Vector2f.sub(enemy.getLocation(), ship.getLocation(), null).length();
            if (distance <= 1000f) { // 简单的距离检查
                return true;
            }
        }
        return false;
    }
} 