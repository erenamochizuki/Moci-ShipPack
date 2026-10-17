package data.shipsystems.scripts.ai;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

import data.shipsystems.scripts.Moci_Great_Determination;

public class Moci_Great_Determination_AI implements ShipSystemAIScript {
    private ShipAPI ship;
    private ShipSystemAPI system;
    private static final float HULL_THRESHOLD = 0.2f; // 触发系统的生命值阈值
    private float checkTimer = 0f;
    
    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        
        // 确保初始化时添加被动触发监听器
        Moci_Great_Determination.addSystemListener(ship);
    }
    
    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (system == null || ship == null) return;
        
        // 如果系统已经激活，我们不需要再次激活它
        if (system.isActive()) return;
        
        // 检查系统可用性
        if (system.isOutOfAmmo() || system.getAmmo() <= 0 || system.getCooldownRemaining() > 0) {
            return;
        }
        
        checkTimer += amount;
        
        // 每1秒检查一次是否需要激活系统
        if (checkTimer >= 1f) {
            checkTimer = 0f;
            
            // 当生命值低于阈值且系统可用时激活系统
            if (ship.getHullLevel() <= HULL_THRESHOLD) {
                ship.useSystem();
            }
        }
    }
} 