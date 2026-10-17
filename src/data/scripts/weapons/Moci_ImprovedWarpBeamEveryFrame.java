package data.scripts.weapons;

import org.lazywizard.lazylib.MathUtils;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 当武器开火时防止舰船AI后退，解决牵引过程中AI判断危险而后退的问题。
 * 这确保了牵引效果能够持续发挥作用，而不会因为AI的保守行为而被打断。
 * 
 * 增强功能：
 * - 标准模式：使用AI标志防止后退（原有功能）
 * - 冲锋模式：近距离时强制朝目标冲锋（类似RX-93光剑）
 */
public class Moci_ImprovedWarpBeamEveryFrame implements EveryFrameWeaponEffectPlugin {

    // AI行为控制参数
    private static final float DO_NOT_BACK_OFF_DURATION = 2.0f;  // 禁止后退的持续时间(秒)
    private static final float PURSUE_DURATION = 1.5f;          // 追击标志持续时间(秒)
    
    // 冲锋模式参数
    private static final boolean ENABLE_CHARGE_MODE = true;     // 是否启用冲锋模式
    private static final float CHARGE_DISTANCE = 600f;          // 冲锋触发距离

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused()) return;
        
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) return;
        
        if (ship.getShipAI() == null) return;
        
        boolean isWeaponFiring = weapon.isFiring();
        
        if (isWeaponFiring) {
            // 武器开火时的AI行为控制
            if (ENABLE_CHARGE_MODE) {
                // 冲锋模式：近距离时强制冲锋
                applyChargeMode(engine, ship, weapon);
            } else {
                // 标准模式：只使用AI标志
                applyAggressiveAIBehavior(ship);
            }
        }
    }

    /**
     * 应用攻击性AI行为（标准模式）
     * @param ship 需要控制的舰船
     */
    private void applyAggressiveAIBehavior(ShipAPI ship) {
        // 设置不后退标志，确保舰船保持攻击姿态
        ship.getAIFlags().setFlag(AIFlags.DO_NOT_BACK_OFF, DO_NOT_BACK_OFF_DURATION);
        
        // 如果舰船有明确目标，设置追击标志增强攻击意图
        if (ship.getShipTarget() != null && ship.getShipTarget().isAlive()) {
            ship.getAIFlags().setFlag(AIFlags.PURSUING, PURSUE_DURATION);
        }
        
        // 取消任何现有的后退标志
        ship.getAIFlags().unsetFlag(AIFlags.BACK_OFF);
        ship.getAIFlags().unsetFlag(AIFlags.BACKING_OFF);
    }
    
    /**
     * 应用冲锋模式（类似RX-93光剑）
     * 近距离时强制朝武器方向冲锋，阻止减速和后退
     * @param engine 战斗引擎
     * @param ship 舰船
     * @param weapon 武器
     */
    private void applyChargeMode(CombatEngineAPI engine, ShipAPI ship, WeaponAPI weapon) {
        // 先应用标准AI行为（防止后退标志）
        applyAggressiveAIBehavior(ship);
        
        // 检查是否应该强制冲锋
        if (!ship.getAIFlags().hasFlag(AIFlags.BACKING_OFF) &&
            !ship.getAIFlags().hasFlag(AIFlags.DO_NOT_PURSUE)) {
            
            // 检查是否有目标且距离在冲锋范围内
            if (ship.getShipTarget() != null && 
                ship.getShipTarget().isAlive() &&
                MathUtils.getDistanceSquared(ship.getLocation(), ship.getShipTarget().getLocation()) < CHARGE_DISTANCE * CHARGE_DISTANCE) {
                
                // 阻止减速指令（防止后退）
                ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
                
                // 强制朝武器指向的方向前进
                engine.headInDirectionWithoutTurning(
                    ship,
                    weapon.getCurrAngle(),  // 朝向武器指向的方向
                    ship.getMaxSpeed()      // 使用最大速度
                );
            }
        }
    }
} 
