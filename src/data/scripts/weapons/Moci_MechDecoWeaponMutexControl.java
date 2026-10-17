package data.scripts.weapons;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 机甲装饰武器互斥控制工具类
 * 用于协调装饰武器脚本和机体联动控制器（如Moci_MechBodyController_RX93）之间的控制权
 * 
 * 工作原理：
 * 1. 机体联动控制器在光剑挥动时设置全局标记
 * 2. 装饰武器脚本检查标记，光剑挥动时暂停控制
 * 3. 避免装饰武器脚本和光剑动画同时控制手臂角度
 * 
 * @author Moci
 */
public class Moci_MechDecoWeaponMutexControl {
    
    // 全局标记键
    private static final String BLADE_SWING_ACTIVE_LEFT_KEY = "MOCI_BLADE_SWING_ACTIVE_LEFT";
    private static final String BLADE_SWING_ACTIVE_RIGHT_KEY = "MOCI_BLADE_SWING_ACTIVE_RIGHT";
    
    /**
     * 检查光剑是否正在挥动
     * 
     * @param weapon 装饰武器
     * @return true = 光剑正在挥动，装饰武器应暂停控制
     */
    public static boolean isBladeSwingActive(WeaponAPI weapon) {
        if (weapon == null) return false;
        
        ShipAPI ship = weapon.getShip();
        if (ship == null) return false;
        
        // 根据槽位ID判断左右手
        String slotId = weapon.getSlot().getId();
        String key = getKeyForSlot(slotId);
        
        if (key == null) return false;
        
        // 检查全局标记
        Boolean active = (Boolean) ship.getCustomData().get(key);
        return active != null && active;
    }
    
    /**
     * 根据槽位ID获取对应的标记键
     * 
     * @param slotId 槽位ID（如ARM_L, ARM_R等）
     * @return 标记键，如果无法判断则返回null
     */
    private static String getKeyForSlot(String slotId) {
        if (slotId == null) return null;
        
        // 检查槽位ID是否包含_L或_R
        if (slotId.contains("_L")) {
            return BLADE_SWING_ACTIVE_LEFT_KEY;
        } else if (slotId.contains("_R")) {
            return BLADE_SWING_ACTIVE_RIGHT_KEY;
        }
        
        // 无法判断左右手
        return null;
    }
    
    /**
     * 同步装饰武器角度到SOURCE武器
     * 光剑挥动时使用此方法，只同步角度不控制
     * 
     * @param decorativeWeapon 装饰武器
     * @param sourceWeapon SOURCE武器
     */
    public static void syncAngleOnly(WeaponAPI decorativeWeapon, WeaponAPI sourceWeapon) {
        if (decorativeWeapon == null || sourceWeapon == null) return;
        decorativeWeapon.setCurrAngle(sourceWeapon.getCurrAngle());
    }
}
