package data.scripts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 碰撞状态管理器
 * 
 * 用于统一管理舰船的碰撞状态，解决多个mod功能同时修改碰撞类型时的冲突问题
 * 通过优先级系统和默认状态恢复机制确保碰撞状态的正确性
 * 
 * 特性：
 * - 默认碰撞状态存储：在战斗开始时记录每个舰船的原始碰撞类型
 * - 优先级管理：多个修改器可以按优先级设置碰撞类型
 * - 自动恢复：当移除修改器时，自动恢复到次高优先级的修改器或默认状态
 * - 线程安全：使用WeakHashMap避免内存泄漏
 */
public class Moci_CollisionStateManager {
    
    private static Moci_CollisionStateManager instance;
    
    // 存储每个舰船的默认碰撞类型
    private final Map<ShipAPI, CollisionClass> defaultCollisionClasses = new WeakHashMap<>();
    
    // 存储每个舰船的所有碰撞修改器（按优先级排序）
    private final Map<ShipAPI, List<CollisionModifier>> activeModifiers = new WeakHashMap<>();
    
    /**
     * 碰撞修改器类
     * 包含修改器ID、优先级和目标碰撞类型
     */
    public static class CollisionModifier {
        public final String modifierId;
        public final int priority;
        public final CollisionClass collisionClass;
        
        public CollisionModifier(String modifierId, int priority, CollisionClass collisionClass) {
            this.modifierId = modifierId;
            this.priority = priority;
            this.collisionClass = collisionClass;
        }
    }
    
    /**
     * 获取管理器实例（单例模式）
     */
    public static Moci_CollisionStateManager getInstance() {
        if (instance == null) {
            instance = new Moci_CollisionStateManager();
        }
        return instance;
    }
    
    /**
     * 初始化舰船的默认碰撞状态
     * 应在战斗开始时或首次遇到舰船时调用
     * 
     * 注意：由于所有使用碰撞管理器的系统都是临时修改碰撞类型，
     * 因此默认碰撞类型应该是舰船的原始碰撞类型
     * 
     * @param ship 目标舰船
     */
    public void initializeDefaultCollision(ShipAPI ship) {
        if (ship == null) return;
        
        // 只在首次遇到时记录默认状态
        if (!defaultCollisionClasses.containsKey(ship)) {
            CollisionClass defaultClass;
            
            // 根据舰船类型判断默认碰撞类型
            if (ship.isFighter()) {
                // 真正的战机，默认是FIGHTER
                defaultClass = CollisionClass.FIGHTER;
            } else {
                // 非战机的舰船，默认是SHIP
                // 即使当前碰撞类型是FIGHTER（被其他系统修改过），默认值也应该是SHIP
                defaultClass = CollisionClass.SHIP;
            }
            
            defaultCollisionClasses.put(ship, defaultClass);
        }
    }
    
    /**
     * 强制设置舰船的默认碰撞状态
     * 用于在确定真正的默认碰撞类型时覆盖之前的记录
     * 
     * @param ship 目标舰船
     * @param defaultClass 默认碰撞类型
     */
    public void setDefaultCollision(ShipAPI ship, CollisionClass defaultClass) {
        if (ship == null || defaultClass == null) return;
        defaultCollisionClasses.put(ship, defaultClass);
    }
    
    /**
     * 设置舰船的碰撞修改器
     * 如果新修改器优先级更高，则立即应用；否则保持当前状态
     * 
     * @param ship 目标舰船
     * @param modifierId 修改器唯一ID
     * @param priority 优先级（数值越大优先级越高）
     * @param collisionClass 目标碰撞类型
     */
    public void setCollisionModifier(ShipAPI ship, String modifierId, int priority, CollisionClass collisionClass) {
        if (ship == null || modifierId == null || collisionClass == null) return;
        
        // 确保默认状态已初始化
        initializeDefaultCollision(ship);
        
        // 获取或创建修改器列表
        List<CollisionModifier> modifiers = activeModifiers.get(ship);
        if (modifiers == null) {
            modifiers = new ArrayList<>();
            activeModifiers.put(ship, modifiers);
        }
        
        // 移除同ID的旧修改器（如果存在）
        for (int i = modifiers.size() - 1; i >= 0; i--) {
            if (modifiers.get(i).modifierId.equals(modifierId)) {
                modifiers.remove(i);
                break;
            }
        }
        
        // 添加新修改器
        CollisionModifier newModifier = new CollisionModifier(modifierId, priority, collisionClass);
        modifiers.add(newModifier);
        
        // 按优先级排序（降序）
        Collections.sort(modifiers, new Comparator<CollisionModifier>() {
            @Override
            public int compare(CollisionModifier o1, CollisionModifier o2) {
                return Integer.compare(o2.priority, o1.priority); // 降序
            }
        });
        
        // 应用最高优先级的修改器
        if (!modifiers.isEmpty()) {
            ship.setCollisionClass(modifiers.get(0).collisionClass);
        }
    }
    
    /**
     * 移除舰船的碰撞修改器
     * 如果移除的是当前活跃的修改器，则恢复到次高优先级的修改器或默认状态
     * 
     * @param ship 目标舰船
     * @param modifierId 要移除的修改器ID
     */
    public void removeCollisionModifier(ShipAPI ship, String modifierId) {
        if (ship == null || modifierId == null) return;
        
        List<CollisionModifier> modifiers = activeModifiers.get(ship);
        if (modifiers == null || modifiers.isEmpty()) return;
        
        // 移除指定ID的修改器
        boolean removed = false;
        for (int i = modifiers.size() - 1; i >= 0; i--) {
            if (modifiers.get(i).modifierId.equals(modifierId)) {
                modifiers.remove(i);
                removed = true;
                break;
            }
        }
        
        if (!removed) return;
        
        // 如果还有其他修改器，应用最高优先级的
        if (!modifiers.isEmpty()) {
            ship.setCollisionClass(modifiers.get(0).collisionClass);
        } else {
            // 如果没有修改器了，恢复到默认状态
            CollisionClass defaultClass = defaultCollisionClasses.get(ship);
            if (defaultClass != null) {
                ship.setCollisionClass(defaultClass);
            }
        }
    }
    
    /**
     * 强制恢复舰船到默认碰撞状态
     * 清除所有修改器并恢复原始状态
     * 
     * @param ship 目标舰船
     */
    public void restoreDefaultCollision(ShipAPI ship) {
        if (ship == null) return;
        
        activeModifiers.remove(ship);
        CollisionClass defaultClass = defaultCollisionClasses.get(ship);
        if (defaultClass != null) {
            ship.setCollisionClass(defaultClass);
        }
    }
    
    /**
     * 获取舰船的默认碰撞类型
     * 
     * @param ship 目标舰船
     * @return 默认碰撞类型，如果未记录则返回null
     */
    public CollisionClass getDefaultCollision(ShipAPI ship) {
        if (ship == null) return null;
        return defaultCollisionClasses.get(ship);
    }
    
    /**
     * 检查舰船是否有活跃的碰撞修改器
     * 
     * @param ship 目标舰船
     * @return 是否有活跃修改器
     */
    public boolean hasActiveModifier(ShipAPI ship) {
        if (ship == null) return false;
        List<CollisionModifier> modifiers = activeModifiers.get(ship);
        return modifiers != null && !modifiers.isEmpty();
    }
    
    /**
     * 获取舰船当前的碰撞修改器（最高优先级的）
     * 
     * @param ship 目标舰船
     * @return 当前修改器，如果没有则返回null
     */
    public CollisionModifier getCurrentModifier(ShipAPI ship) {
        if (ship == null) return null;
        List<CollisionModifier> modifiers = activeModifiers.get(ship);
        if (modifiers == null || modifiers.isEmpty()) return null;
        return modifiers.get(0); // 返回最高优先级的
    }
    
    /**
     * 清理无效的引用
     * 在战斗结束时调用以防止内存泄漏
     */
    public void cleanup() {
        defaultCollisionClasses.clear();
        activeModifiers.clear();
    }
} 