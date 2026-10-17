package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.apache.log4j.Logger;

import java.util.List;

/**
 * 武器组安全工具类
 * 通过临时全局插件绕过第0组限制，安全移除任何武器
 * 
 * 原理：
 * - 创建一个临时的全局战斗插件
 * - 在下一帧执行移除操作
 * - 执行完毕后立即移除插件
 * 
 * 优势：
 * - 绕过第0组限制（全局上下文无此限制）
 * - 时机正确（下一帧执行，武器组已初始化）
 * - 无资源泄漏（一次性插件）
 */
public class Moci_WeaponGroupSafetyUtil {
    
    private static final Logger log = Global.getLogger(Moci_WeaponGroupSafetyUtil.class);
    
    /**
     * 安全地从武器组中移除武器（通过临时全局插件）
     * 
     * @param engine 战斗引擎
     * @param ship 舰船
     * @param weapon 要移除的武器
     * @param disablePermanently 是否永久禁用武器
     */
    public static void safelyRemoveWeaponFromGroups(
            CombatEngineAPI engine, 
            ShipAPI ship, 
            WeaponAPI weapon,
            boolean disablePermanently) {
        
        if (engine == null || ship == null || weapon == null) {
            log.warn("无法移除武器：参数为null");
            return;
        }
        
        // 注册临时插件
        engine.addPlugin(new BaseEveryFrameCombatPlugin() {
            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                try {
                    // 执行移除操作
                    ship.removeWeaponFromGroups(weapon);
                    
                    // 禁用武器
                    if (disablePermanently) {
                        weapon.disable(true);
                    }
                    
                    log.info("成功移除武器：" + weapon.getId() + 
                            "，槽位：" + weapon.getSlot().getId());
                    
                } catch (Exception e) {
                    log.error("移除武器失败：" + weapon.getId(), e);
                } finally {
                    // 无论成功失败，都移除插件
                    engine.removePlugin(this);
                }
            }
        });
    }
    
    /**
     * 批量移除武器（通过临时全局插件）
     * 
     * @param engine 战斗引擎
     * @param ship 舰船
     * @param weapons 要移除的武器列表
     * @param disablePermanently 是否永久禁用武器
     */
    public static void safelyRemoveWeaponsFromGroups(
            CombatEngineAPI engine,
            ShipAPI ship,
            List<WeaponAPI> weapons,
            boolean disablePermanently) {
        
        if (engine == null || ship == null || weapons == null || weapons.isEmpty()) {
            log.warn("无法批量移除武器：参数为null或列表为空");
            return;
        }
        
        // 注册临时插件
        engine.addPlugin(new BaseEveryFrameCombatPlugin() {
            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                try {
                    int successCount = 0;
                    
                    for (WeaponAPI weapon : weapons) {
                        if (weapon != null) {
                            ship.removeWeaponFromGroups(weapon);
                            
                            if (disablePermanently) {
                                weapon.disable(true);
                            }
                            
                            successCount++;
                        }
                    }
                    
                    log.info("成功移除 " + successCount + " 个武器，舰船：" + ship.getName());
                    
                } catch (Exception e) {
                    log.error("批量移除武器失败", e);
                } finally {
                    // 无论成功失败，都移除插件
                    engine.removePlugin(this);
                }
            }
        });
    }
    
    /**
     * 根据槽位ID移除武器（通过临时全局插件）
     * 
     * @param engine 战斗引擎
     * @param ship 舰船
     * @param slotId 槽位ID
     * @param disablePermanently 是否永久禁用武器
     */
    public static void safelyRemoveWeaponBySlotId(
            CombatEngineAPI engine,
            ShipAPI ship,
            String slotId,
            boolean disablePermanently) {
        
        if (engine == null || ship == null || slotId == null) {
            log.warn("无法根据槽位移除武器：参数为null");
            return;
        }
        
        // 注册临时插件
        engine.addPlugin(new BaseEveryFrameCombatPlugin() {
            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                try {
                    WeaponAPI targetWeapon = null;
                    
                    // 查找目标武器
                    for (WeaponAPI weapon : ship.getAllWeapons()) {
                        if (slotId.equals(weapon.getSlot().getId())) {
                            targetWeapon = weapon;
                            break;
                        }
                    }
                    
                    // 移除武器
                    if (targetWeapon != null) {
                        ship.removeWeaponFromGroups(targetWeapon);
                        
                        if (disablePermanently) {
                            targetWeapon.disable(true);
                        }
                        
                        log.info("成功移除槽位武器：" + slotId);
                    } else {
                        log.warn("未找到槽位武器：" + slotId);
                    }
                    
                } catch (Exception e) {
                    log.error("根据槽位移除武器失败：" + slotId, e);
                } finally {
                    // 无论成功失败，都移除插件
                    engine.removePlugin(this);
                }
            }
        });
    }
    
    /**
     * 根据槽位ID前缀批量移除武器（通过临时全局插件）
     * 
     * @param engine 战斗引擎
     * @param ship 舰船
     * @param slotIdPrefix 槽位ID前缀（例如 "DECO_" 会匹配所有以此开头的槽位）
     * @param disablePermanently 是否永久禁用武器
     */
    public static void safelyRemoveWeaponsBySlotPrefix(
            CombatEngineAPI engine,
            ShipAPI ship,
            String slotIdPrefix,
            boolean disablePermanently) {
        
        if (engine == null || ship == null || slotIdPrefix == null) {
            log.warn("无法根据槽位前缀移除武器：参数为null");
            return;
        }
        
        // 注册临时插件
        engine.addPlugin(new BaseEveryFrameCombatPlugin() {
            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                try {
                    int successCount = 0;
                    
                    // 查找所有匹配前缀的武器
                    for (WeaponAPI weapon : ship.getAllWeapons()) {
                        String slotId = weapon.getSlot().getId();
                        if (slotId != null && slotId.startsWith(slotIdPrefix)) {
                            ship.removeWeaponFromGroups(weapon);
                            
                            if (disablePermanently) {
                                weapon.disable(true);
                            }
                            
                            successCount++;
                        }
                    }
                    
                    log.info("成功移除 " + successCount + " 个前缀为 '" + slotIdPrefix + "' 的武器");
                    
                } catch (Exception e) {
                    log.error("根据槽位前缀批量移除武器失败：" + slotIdPrefix, e);
                } finally {
                    // 无论成功失败，都移除插件
                    engine.removePlugin(this);
                }
            }
        });
    }
}
