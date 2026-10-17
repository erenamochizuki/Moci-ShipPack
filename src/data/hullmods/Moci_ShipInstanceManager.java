package data.hullmods;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * Moci舰船实例管理器
 * 
 * 负责为每个CombatSquad舰船实例生成唯一标识，解决多波次部署时
 * 相同舰船类型共享数据导致的机师数据混乱问题。
 * 
 * 核心功能：
 * 1. 为每个舰船实例生成唯一ID
 * 2. 管理实例映射表和生命周期
 * 3. 提供实例计数和查询功能
 * 4. 支持实例数据的清理和回收
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_ShipInstanceManager {
    
    // 舰船实例映射表：舰船ID -> 实例ID
    private static final Map<String, String> SHIP_INSTANCE_MAP = new ConcurrentHashMap<>();
    
    // 指挥官实例计数表：指挥官ID_船体ID -> 实例数量
    private static final Map<String, Integer> INSTANCE_COUNTER = new ConcurrentHashMap<>();

    // 实例反向映射表：实例ID -> 舰船ID（用于清理）
    private static final Map<String, String> REVERSE_INSTANCE_MAP = new ConcurrentHashMap<>();

    // 每个实例对应 counterKey，用于清理时递减
    private static final Map<String, String> INSTANCE_COUNTER_KEYS = new ConcurrentHashMap<>();
    
    // 实例生成时间戳（用于调试和监控）
    private static final Map<String, Long> INSTANCE_TIMESTAMPS = new ConcurrentHashMap<>();
    
    /**
     * 为舰船生成唯一实例ID
     * 
     * ID格式: {commanderId}_{hullId}_{instanceIndex}
     * 例如: officer_123_moci_ms_0, officer_123_moci_ms_1
     * 
     * @param ship 需要生成实例ID的舰船
     * @return 唯一实例ID，如果舰船无指挥官则返回null
     */
    public static String generateInstanceId(ShipAPI ship) {
        if (ship == null) {
            Global.getLogger(Moci_ShipInstanceManager.class).warn("尝试为null舰船生成实例ID");
            return null;
        }
        
        PersonAPI captain = ship.getCaptain();
        if (captain == null || captain.isDefault()) {
            Global.getLogger(Moci_ShipInstanceManager.class).debug(
                "舰船无指挥官，跳过实例ID生成: " + ship.getName());
            return null;
        }
        
        String commanderId = captain.getId();
        String hullId = getEffectiveHullId(ship);
        String shipId = ship.getId();
        
        // 检查是否已经生成过实例ID
        if (SHIP_INSTANCE_MAP.containsKey(shipId)) {
            String existingId = SHIP_INSTANCE_MAP.get(shipId);
            Global.getLogger(Moci_ShipInstanceManager.class).debug(
                "舰船已有实例ID: " + existingId);
            return existingId;
        }
        
        // 生成新的实例ID
        String counterKey = commanderId + "_" + hullId;
        Integer instanceIndexObj = INSTANCE_COUNTER.get(counterKey);
        int instanceIndex = (instanceIndexObj != null) ? instanceIndexObj : 0;
        String instanceId = commanderId + "_" + hullId + "_" + instanceIndex;
        
        // 更新映射表
        SHIP_INSTANCE_MAP.put(shipId, instanceId);
        REVERSE_INSTANCE_MAP.put(instanceId, shipId);
        INSTANCE_COUNTER.put(counterKey, instanceIndex + 1);
        INSTANCE_COUNTER_KEYS.put(instanceId, counterKey);
        INSTANCE_TIMESTAMPS.put(instanceId, System.currentTimeMillis());
        
        return instanceId;
    }
    
    /**
     * 获取舰船的实例ID
     * 
     * @param ship 目标舰船
     * @return 实例ID，如果不存在则返回null
     */
    public static String getInstanceId(ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        
        String shipId = ship.getId();
        return SHIP_INSTANCE_MAP.get(shipId);
    }
    
    /**
     * 检查舰船是否已有实例ID
     * 
     * @param ship 目标舰船
     * @return 如果已有实例ID返回true，否则返回false
     */
    public static boolean hasInstanceId(ShipAPI ship) {
        return getInstanceId(ship) != null;
    }
    
    /**
     * 获取指挥官在特定船体类型上的实例数量
     * 
     * @param commanderId 指挥官ID
     * @param hullId 船体ID
     * @return 实例数量
     */
    public static int getInstanceCount(String commanderId, String hullId) {
        if (commanderId == null || hullId == null) {
            return 0;
        }
        
        String counterKey = commanderId + "_" + hullId;
        Integer countObj = INSTANCE_COUNTER.get(counterKey);
        return (countObj != null) ? countObj : 0;
    }
    
    /**
     * 清理指定舰船的实例数据
     * 
     * @param ship 要清理的舰船
     * @return 如果成功清理返回true，否则返回false
     */
    public static boolean cleanupShipInstance(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        
        String shipId = ship.getId();
        String instanceId = SHIP_INSTANCE_MAP.get(shipId);
        
        if (instanceId == null) {
            return false; // 没有实例数据需要清理
        }
        
        // 清理映射表
        SHIP_INSTANCE_MAP.remove(shipId);
        REVERSE_INSTANCE_MAP.remove(instanceId);
        INSTANCE_TIMESTAMPS.remove(instanceId);

        String counterKey = INSTANCE_COUNTER_KEYS.remove(instanceId);
        if (counterKey != null) {
            Integer count = INSTANCE_COUNTER.get(counterKey);
            if (count != null && count > 1) {
                INSTANCE_COUNTER.put(counterKey, count - 1);
            } else {
                INSTANCE_COUNTER.remove(counterKey);
            }
        }
        
        return true;
    }
    
    /**
     * 清理所有过期的实例数据
     * 
     * @param maxAgeMs 最大存活时间（毫秒）
     * @return 清理的实例数量
     */
    public static int cleanupExpiredInstances(long maxAgeMs) {
        long currentTime = System.currentTimeMillis();
        int cleanedCount = 0;
        
        // 查找过期实例
        Map<String, Long> expiredInstances = new HashMap<>();
        for (Map.Entry<String, Long> entry : INSTANCE_TIMESTAMPS.entrySet()) {
            if (currentTime - entry.getValue() > maxAgeMs) {
                expiredInstances.put(entry.getKey(), entry.getValue());
            }
        }
        
        // 清理过期实例
        for (String instanceId : expiredInstances.keySet()) {
            String shipId = REVERSE_INSTANCE_MAP.get(instanceId);
            if (shipId != null) {
                SHIP_INSTANCE_MAP.remove(shipId);
            }
            REVERSE_INSTANCE_MAP.remove(instanceId);
            String counterKey = INSTANCE_COUNTER_KEYS.remove(instanceId);
            if (counterKey != null) {
                Integer count = INSTANCE_COUNTER.get(counterKey);
                if (count != null && count > 1) {
                    INSTANCE_COUNTER.put(counterKey, count - 1);
                } else {
                    INSTANCE_COUNTER.remove(counterKey);
                }
            }
            INSTANCE_TIMESTAMPS.remove(instanceId);
            cleanedCount++;
        }
        
        return cleanedCount;
    }
    
    /**
     * 获取有效的船体ID
     * 
     * 优先使用getDParentHullId()以支持损坏版本的船只，
     * 如果不可用则使用getHullId()
     * 
     * @param ship 目标舰船
     * @return 有效的船体ID
     */
    private static String getEffectiveHullId(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) {
            return "unknown";
        }
        
        String hullId = ship.getHullSpec().getDParentHullId();
        if (hullId == null || hullId.isEmpty()) {
            hullId = ship.getHullSpec().getHullId();
        }
        
        return hullId != null ? hullId : "unknown";
    }
    
    /**
     * 获取实例管理器的统计信息
     * 
     * @return 包含统计信息的字符串
     */
    public static String getStatistics() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== 舰船实例管理器统计 ===\n");
        stats.append("活跃实例数量: ").append(SHIP_INSTANCE_MAP.size()).append("\n");
        stats.append("指挥官计数器: ").append(INSTANCE_COUNTER.size()).append("\n");
        stats.append("时间戳记录: ").append(INSTANCE_TIMESTAMPS.size()).append("\n");
        
        // 按指挥官分组显示实例数量
        if (!INSTANCE_COUNTER.isEmpty()) {
            stats.append("指挥官实例分布:\n");
            for (Map.Entry<String, Integer> entry : INSTANCE_COUNTER.entrySet()) {
                stats.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        
        return stats.toString();
    }
    
    /**
     * 重置所有实例数据（主要用于测试）
     * 
     * 警告：此方法会清除所有实例数据，仅在测试或调试时使用
     */
    public static void resetAllInstances() {
        SHIP_INSTANCE_MAP.clear();
        INSTANCE_COUNTER.clear();
        REVERSE_INSTANCE_MAP.clear();
        INSTANCE_COUNTER_KEYS.clear();
        INSTANCE_TIMESTAMPS.clear();
        
        Global.getLogger(Moci_ShipInstanceManager.class).warn(
            "重置所有舰船实例数据 - 仅用于测试目的");
    }
    
    /**
     * 检查实例管理器的内部一致性
     * 
     * @return 如果数据一致返回true，否则返回false
     */
    public static boolean validateConsistency() {
        boolean isConsistent = true;
        
        // 检查正向和反向映射的一致性
        for (Map.Entry<String, String> entry : SHIP_INSTANCE_MAP.entrySet()) {
            String shipId = entry.getKey();
            String instanceId = entry.getValue();
            
            String reverseShipId = REVERSE_INSTANCE_MAP.get(instanceId);
            if (reverseShipId == null || !reverseShipId.equals(shipId)) {
                Global.getLogger(Moci_ShipInstanceManager.class).error(
                    "映射不一致: " + shipId + " -> " + instanceId);
                isConsistent = false;
            }
        }
        
        // 检查时间戳记录的完整性
        for (String instanceId : REVERSE_INSTANCE_MAP.keySet()) {
            if (!INSTANCE_TIMESTAMPS.containsKey(instanceId)) {
                Global.getLogger(Moci_ShipInstanceManager.class).warn(
                    "缺少时间戳记录: " + instanceId);
            }
        }
        
        return isConsistent;
    }
}
