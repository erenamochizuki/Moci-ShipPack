package data.hullmods;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * Moci动态数据同步器
 * 
 * 负责实时同步CombatSquad机师数据，解决多波次部署时数据不一致的问题。
 * 支持舰船级别的精确数据同步，确保每个舰船实例都能获取正确的机师数据。
 * 
 * 核心功能：
 * 1. 舰船级别的动态数据同步
 * 2. 同步状态管理和缓存
 * 3. 批量数据操作优化
 * 4. 同步失败的重试机制
 * 5. 数据完整性验证
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_DynamicDataSynchronizer {
    
    // 同步状态标记键前缀
    private static final String SYNC_MARKER_KEY = "moci_pilot_sync_marker_";
    
    // 同步重试计数器
    private static final Map<String, Integer> SYNC_RETRY_COUNT = new ConcurrentHashMap<>();
    
    // 最大重试次数
    private static final int MAX_RETRY_COUNT = 3;
    
    // 同步操作统计
    private static int totalSyncOperations = 0;
    private static int successfulSyncs = 0;
    private static int failedSyncs = 0;
    
    /**
     * 为特定舰船实例同步机师数据
     * 
     * 这是主要的同步入口点，会检查同步状态、执行数据同步、处理失败重试
     * 
     * @param ship 需要同步数据的舰船
     * @return 如果同步成功返回true，否则返回false
     */
    public static boolean syncPilotDataForShip(ShipAPI ship) {
        if (ship == null) {
            Global.getLogger(Moci_DynamicDataSynchronizer.class).warn("尝试为null舰船同步数据");
            return false;
        }
        
        totalSyncOperations++;
        
        try {
            PersonAPI captain = ship.getCaptain();
            if (captain == null || captain.isDefault()) {
                Global.getLogger(Moci_DynamicDataSynchronizer.class).debug(
                    "舰船无指挥官，跳过数据同步: " + ship.getName());
                return false;
            }
            
            String commanderId = captain.getId();
            String instanceId = Moci_ShipInstanceManager.getInstanceId(ship);
            
            // 如果没有实例ID，先生成一个
            if (instanceId == null) {
                instanceId = Moci_ShipInstanceManager.generateInstanceId(ship);
                if (instanceId == null) {
                    Global.getLogger(Moci_DynamicDataSynchronizer.class).error(
                        "无法为舰船生成实例ID: " + ship.getName());
                    failedSyncs++;
                    return false;
                }
            }
            
            // 检查是否已同步且数据完整
            if (isAlreadySynced(instanceId) && validateSyncedData(instanceId)) {
                Global.getLogger(Moci_DynamicDataSynchronizer.class).debug(
                    "舰船数据已同步且完整: " + instanceId);
                return true;
            }
            
            // 执行数据同步
            boolean success = performDataSync(commanderId, instanceId);
            
            if (success) {
                // 标记为已同步
                markAsSynced(instanceId);
                // 清除重试计数
                SYNC_RETRY_COUNT.remove(instanceId);
                successfulSyncs++;
                
            } else {
                // 处理同步失败
                handleSyncFailure(instanceId);
                failedSyncs++;
            }
            
            return success;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDataSynchronizer.class).error(
                "舰船数据同步异常: " + e.getMessage());
            e.printStackTrace();
            failedSyncs++;
            return false;
        }
    }
    
    /**
     * 批量同步多个舰船的数据
     * 
     * @param ships 需要同步的舰船数组
     * @return 成功同步的舰船数量
     */
    public static int batchSyncPilotData(ShipAPI[] ships) {
        if (ships == null || ships.length == 0) {
            return 0;
        }
        
        int successCount = 0;
        
        for (ShipAPI ship : ships) {
            if (syncPilotDataForShip(ship)) {
                successCount++;
            }
        }
        
        return successCount;
    }
    
    /**
     * 强制重新同步舰船数据
     * 
     * 忽略已同步标记，强制重新执行数据同步
     * 
     * @param ship 需要重新同步的舰船
     * @return 如果同步成功返回true，否则返回false
     */
    public static boolean forceSyncPilotData(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        
        String instanceId = Moci_ShipInstanceManager.getInstanceId(ship);
        if (instanceId != null) {
            // 清除同步标记，强制重新同步
            clearSyncMarker(instanceId);
            SYNC_RETRY_COUNT.remove(instanceId);
        }
        
        return syncPilotDataForShip(ship);
    }
    
    /**
     * 执行实际的数据同步操作
     * 
     * @param commanderId 指挥官ID
     * @param instanceId 舰船实例ID
     * @return 如果同步成功返回true，否则返回false
     */
    private static boolean performDataSync(String commanderId, String instanceId) {
        try {
            // 获取编队规模
            int squadSize = Moci_PilotManager.getSquadSize(commanderId);
            if (squadSize <= 0) {
                Global.getLogger(Moci_DynamicDataSynchronizer.class).debug(
                    "指挥官无编队数据: " + commanderId);
                return false;
            }
            
            Global.getLogger(Moci_DynamicDataSynchronizer.class).debug(
                "开始同步编队数据: 指挥官=" + commanderId + ", 实例=" + instanceId + ", 规模=" + squadSize);
            
            // 同步编队级别数据
            String squadName = Moci_PilotManager.getSquadName(commanderId, true);
            if (squadName != null) {
                Global.getCombatEngine().getCustomData().put(
                    "moci_combatSquad_squadName_" + instanceId, squadName);
            }
            Global.getCombatEngine().getCustomData().put(
                "moci_combatSquad_squadSize_" + instanceId, squadSize);
            
            // 同步每个机师的数据
            int syncedPilots = 0;
            for (int i = 0; i < squadSize; i++) {
                if (syncSinglePilotData(commanderId, instanceId, i)) {
                    syncedPilots++;
                }
            }
            
            // 验证同步结果
            boolean success = (syncedPilots == squadSize);
            
            if (!success) {
                Global.getLogger(Moci_DynamicDataSynchronizer.class).debug(
                    "编队数据同步不完整: " + syncedPilots + "/" + squadSize + " 机师同步成功");
            }
            
            return success;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDataSynchronizer.class).error(
                "数据同步执行异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 同步单个机师的数据
     * 
     * @param commanderId 指挥官ID
     * @param instanceId 舰船实例ID
     * @param pilotIndex 机师索引
     * @return 如果同步成功返回true，否则返回false
     */
    private static boolean syncSinglePilotData(String commanderId, String instanceId, int pilotIndex) {
        try {
            // 从持久化存储获取机师数据
            PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, true);
            String callsign = Moci_PilotManager.getCallsign(commanderId, pilotIndex, true);
            float experience = Moci_PilotManager.getExperience(commanderId, pilotIndex, true);
            boolean assigned = Moci_PilotManager.isPilotAssigned(commanderId, pilotIndex, true);
            
            if (pilot == null) {
                Global.getLogger(Moci_DynamicDataSynchronizer.class).debug(
                    "机师数据不存在: 指挥官=" + commanderId + ", 索引=" + pilotIndex);
                return false;
            }
            
            // 同步到战斗引擎临时存储
            String pilotKey = "moci_combatSquad_pilot_" + pilotIndex + "_" + instanceId;
            String callsignKey = "moci_combatSquad_callsign_" + pilotIndex + "_" + instanceId;
            String expKey = "moci_combatSquad_exp_" + pilotIndex + "_" + instanceId;
            String assignedKey = "moci_combatSquad_assigned_" + pilotIndex + "_" + instanceId;
            
            Global.getCombatEngine().getCustomData().put(pilotKey, pilot);
            Global.getCombatEngine().getCustomData().put(callsignKey, callsign);
            Global.getCombatEngine().getCustomData().put(expKey, experience);
            Global.getCombatEngine().getCustomData().put(assignedKey, assigned);
            
            Global.getLogger(Moci_DynamicDataSynchronizer.class).debug(
                "机师数据同步完成: " + pilot.getNameString() + " (呼号: " + callsign + 
                ", 经验: " + experience + ", 实例: " + instanceId + ")");
            
            return true;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDataSynchronizer.class).error(
                "单个机师数据同步异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 检查舰船实例是否已同步
     * 
     * @param instanceId 舰船实例ID
     * @return 如果已同步返回true，否则返回false
     */
    private static boolean isAlreadySynced(String instanceId) {
        String syncMarker = SYNC_MARKER_KEY + instanceId;
        return Global.getCombatEngine().getCustomData().containsKey(syncMarker);
    }
    
    /**
     * 标记舰船实例为已同步
     * 
     * @param instanceId 舰船实例ID
     */
    private static void markAsSynced(String instanceId) {
        String syncMarker = SYNC_MARKER_KEY + instanceId;
        Global.getCombatEngine().getCustomData().put(syncMarker, System.currentTimeMillis());
    }
    
    /**
     * 清除同步标记
     * 
     * @param instanceId 舰船实例ID
     */
    private static void clearSyncMarker(String instanceId) {
        String syncMarker = SYNC_MARKER_KEY + instanceId;
        Global.getCombatEngine().getCustomData().remove(syncMarker);
    }
    
    /**
     * 验证已同步数据的完整性
     * 
     * @param instanceId 舰船实例ID
     * @return 如果数据完整返回true，否则返回false
     */
    private static boolean validateSyncedData(String instanceId) {
        try {
            // 检查编队规模数据
            Object sizeObj = Global.getCombatEngine().getCustomData().get(
                "moci_combatSquad_squadSize_" + instanceId);
            if (!(sizeObj instanceof Integer)) {
                return false;
            }
            
            int squadSize = (Integer) sizeObj;
            if (squadSize <= 0) {
                return false;
            }
            
            // 检查每个机师数据的存在性
            for (int i = 0; i < squadSize; i++) {
                String pilotKey = "moci_combatSquad_pilot_" + i + "_" + instanceId;
                if (!Global.getCombatEngine().getCustomData().containsKey(pilotKey)) {
                    Global.getLogger(Moci_DynamicDataSynchronizer.class).debug(
                        "机师数据缺失: " + pilotKey);
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDataSynchronizer.class).error(
                "数据完整性验证异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 处理同步失败
     * 
     * @param instanceId 舰船实例ID
     */
    private static void handleSyncFailure(String instanceId) {
        Integer retryCountObj = SYNC_RETRY_COUNT.get(instanceId);
        int retryCount = (retryCountObj != null) ? retryCountObj : 0;
        retryCount++;
        SYNC_RETRY_COUNT.put(instanceId, retryCount);
        
        if (retryCount >= MAX_RETRY_COUNT) {
            Global.getLogger(Moci_DynamicDataSynchronizer.class).error(
                "舰船数据同步失败，已达最大重试次数: " + instanceId + " (重试次数: " + retryCount + ")");
        }
    }
    
    /**
     * 清理指定舰船实例的同步数据
     * 
     * @param instanceId 舰船实例ID
     * @return 如果清理成功返回true，否则返回false
     */
    public static boolean cleanupSyncData(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return false;
        }
        
        try {
            // 清除同步标记
            clearSyncMarker(instanceId);
            
            // 清除重试计数
            SYNC_RETRY_COUNT.remove(instanceId);
            
            // 清除编队级别数据
            Global.getCombatEngine().getCustomData().remove("moci_combatSquad_squadName_" + instanceId);
            Global.getCombatEngine().getCustomData().remove("moci_combatSquad_squadSize_" + instanceId);
            
            // 清除机师数据（假设最多10个机师）
            for (int i = 0; i < 10; i++) {
                Global.getCombatEngine().getCustomData().remove("moci_combatSquad_pilot_" + i + "_" + instanceId);
                Global.getCombatEngine().getCustomData().remove("moci_combatSquad_callsign_" + i + "_" + instanceId);
                Global.getCombatEngine().getCustomData().remove("moci_combatSquad_exp_" + i + "_" + instanceId);
                Global.getCombatEngine().getCustomData().remove("moci_combatSquad_assigned_" + i + "_" + instanceId);
            }
            
            return true;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDataSynchronizer.class).error(
                "清理同步数据异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取同步器统计信息
     * 
     * @return 包含统计信息的字符串
     */
    public static String getStatistics() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== 动态数据同步器统计 ===\n");
        stats.append("总同步操作: ").append(totalSyncOperations).append("\n");
        stats.append("成功同步: ").append(successfulSyncs).append("\n");
        stats.append("失败同步: ").append(failedSyncs).append("\n");
        
        if (totalSyncOperations > 0) {
            float successRate = (float) successfulSyncs / totalSyncOperations * 100;
            stats.append("成功率: ").append(String.format("%.1f%%", successRate)).append("\n");
        }
        
        stats.append("待重试实例: ").append(SYNC_RETRY_COUNT.size()).append("\n");
        
        if (!SYNC_RETRY_COUNT.isEmpty()) {
            stats.append("重试详情:\n");
            for (Map.Entry<String, Integer> entry : SYNC_RETRY_COUNT.entrySet()) {
                stats.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" 次\n");
            }
        }
        
        return stats.toString();
    }
    
    /**
     * 重置同步器统计信息
     */
    public static void resetStatistics() {
        totalSyncOperations = 0;
        successfulSyncs = 0;
        failedSyncs = 0;
        SYNC_RETRY_COUNT.clear();
        
    }
}
