package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;

/**
 * 护盾模块控制器船插
 * 
 * 功能说明：
 * 这个船插用于控制机甲护盾模块的朝向，替代原本需要注册专用AI的方式。
 * 通过监听器模式，自动查找和控制护盾模块，让护盾模块跟随指定的控制武器角度。
 * 
 * 重要：此船插应安装在**母舰**上，不是护盾模块上！
 * 
 * 优势：
 * 1. 不占用护盾模块的AI，让护盾能使用原本的AI功能
 * 2. 不需要为每个护盾ID单独注册AI
 * 3. 更加灵活，可以通过船插参数配置控制逻辑
 */
public class Moci_ShieldModuleController extends BaseHullMod {
    
    // 护盾控制映射配置 - 护盾模块ID -> 控制武器ID
    private static final Map<String, String> SHIELD_CONTROL_MAP = new HashMap<>();
    static {
        // 可以在这里配置更多的护盾-武器映射关系
        SHIELD_CONTROL_MAP.put("SHIELD_L", "ARM_L");   // 左护盾跟随左臂
        SHIELD_CONTROL_MAP.put("SHIELD_R", "ARM_R");   // 右护盾跟随右臂  
        SHIELD_CONTROL_MAP.put("SHIELD_RS", "SHOULDER_R"); // RS护盾跟随肩膀武器
    }
    
    public static final String SHIELD_CONTROLLER_KEY = "Moci_ShieldModuleController_listener";
    
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (!ship.hasListenerOfClass(ShieldControllerListener.class)) {
            ship.addListener(new ShieldControllerListener(ship));
        }
    }
    
    public String getDescriptionParam(int index, MutableShipStatsAPI stats, String hullId) {
        return null;
    }
    
    /**
     * 护盾控制监听器
     * 负责实际的护盾控制逻辑
     */
    public static class ShieldControllerListener implements AdvanceableListener {
        private final ShipAPI ship;
        private boolean initialized = false;
        private Map<ShipAPI, WeaponAPI> shieldControlMap = new HashMap<>(); // 护盾模块 -> 控制武器
        
        public ShieldControllerListener(ShipAPI ship) {
            this.ship = ship;
        }
        
        @Override
        public void advance(float amount) {
            if (ship.isHulk()) {
                return;
            }
            
            // 延迟初始化，确保所有模块都已加载
            if (!initialized) {
                initializeShieldControlMap();
                initialized = true;
            }
            
            // 更新所有护盾模块的朝向
            for (Map.Entry<ShipAPI, WeaponAPI> entry : shieldControlMap.entrySet()) {
                ShipAPI shieldModule = entry.getKey();
                WeaponAPI controlWeapon = entry.getValue();
                
                // 检查护盾模块和控制武器是否都还存活/存在
                if (shieldModule != null && shieldModule.isAlive() && 
                    controlWeapon != null && !controlWeapon.isDisabled()) {
                    
                        shieldModule.ensureClonedStationSlotSpec();
                        float targetAngle = controlWeapon.getCurrAngle() - ship.getFacing();
                        // 规范化角度到[-180,180]范围
                        while (targetAngle > 180) targetAngle -= 360;
                        while (targetAngle < -180) targetAngle += 360;
                        shieldModule.getStationSlot().setAngle(targetAngle);
                }
            }
        }
        
        /**
         * 初始化护盾控制映射
         * 根据配置的映射关系，查找实际的护盾模块和控制武器
         */
        private void initializeShieldControlMap() {
            shieldControlMap.clear();
            
            // 获取所有子模块（护盾模块）
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getStationSlot() == null) continue;
                
                String shieldSlotId = module.getStationSlot().getId();
                String controlWeaponId = SHIELD_CONTROL_MAP.get(shieldSlotId);
                
                if (controlWeaponId != null) {
                    // 查找对应的控制武器
                    WeaponAPI controlWeapon = findWeaponById(controlWeaponId);
                    if (controlWeapon != null) {
                        shieldControlMap.put(module, controlWeapon);
                        // 可选：添加调试日志
                        // Global.getLogger(Moci_ShieldModuleController.class).info(
                        //     "护盾控制器：" + shieldSlotId + " -> " + controlWeaponId);
                    }
                }
            }
        }
        
        /**
         * 根据武器槽ID查找武器
         */
        private WeaponAPI findWeaponById(String weaponSlotId) {
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                if (weapon.getSlot() != null && weaponSlotId.equals(weapon.getSlot().getId())) {
                    return weapon;
                }
            }
            return null;
        }
    }
    

} 