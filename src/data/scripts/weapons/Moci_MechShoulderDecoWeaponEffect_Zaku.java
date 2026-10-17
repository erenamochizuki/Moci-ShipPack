package data.scripts.weapons;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Zaku版机甲肩膀装饰武器效果
 * 继承基础类，增加护盾动画控制功能
 * 支持多个槽位的护盾关联：
 * - SHOULDER_R → SHIELD_RS
 * - SHOULDER_L → SHIELD_LS  
 * - ARM_R → SHIELD_R
 * - ARM_L → SHIELD_L
 */
public class Moci_MechShoulderDecoWeaponEffect_Zaku extends Moci_MechShoulderDecoWeaponEffect {
    
    // 槽位到护盾模块的映射关系
    private static final Map<String, String> SLOT_TO_SHIELD_MAP = new HashMap<>();
    static {
        SLOT_TO_SHIELD_MAP.put("SHOULDER_R", "SHIELD_RS");
        SLOT_TO_SHIELD_MAP.put("SHOULDER_L", "SHIELD_LS");
    }
    
    private ShipAPI shield = null; // 关联的护盾模块
    private String currentSlotId = null; // 当前武器槽位ID

    @Override
    protected void onInitialized(WeaponAPI weapon) {
        // 获取当前武器的槽位ID
        currentSlotId = weapon.getSlot().getId();
        
        // 根据槽位ID获取对应的护盾模块ID
        String shieldId = SLOT_TO_SHIELD_MAP.get(currentSlotId);
        
        if (shieldId != null) {
            // 查找对应的护盾模块
            ShipAPI ship = weapon.getShip();
            
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getStationSlot() != null && module.getStationSlot().getId().equals(shieldId)) {
                    shield = module;
                    break;
                }
            }
            
            // 调试信息
            if (shield != null) {
                // 成功找到护盾模块
            } else {
                // 未找到对应的护盾模块，可能该舰船没有这个模块
            }
        }
    }

    @Override
    protected void onAdvance(WeaponAPI weapon, float amount, CombatEngineAPI engine) {
        // 控制护盾动画 - 基于护盾存活状态
        if (weapon.getAnimation() != null && shield != null) {
            if (shield.isAlive()) {
                weapon.getAnimation().setFrame(1); // 护盾存活为第1帧
            } else {
                weapon.getAnimation().setFrame(0); // 护盾死亡为第0帧
            }
        }
    }
    
    /**
     * 添加新的槽位映射关系
     * @param slotId 武器槽位ID
     * @param shieldId 对应的护盾模块ID
     */
    public static void addSlotMapping(String slotId, String shieldId) {
        SLOT_TO_SHIELD_MAP.put(slotId, shieldId);
    }
} 