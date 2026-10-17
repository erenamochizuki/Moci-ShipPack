package data.hullmods;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.util.Misc;

/**
 * CombatSquad自定义僚机舰船管理器
 * 
 * 负责管理机师的自定义舰船指派，包括：
 * 1. 舰船指派和验证
 * 2. 自定义配置的持久化存储
 * 3. 可指派舰船的筛选
 * 4. 配置的查询和重置
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_CustomWingmanManager {
    
    // 数据键值常量
    private static final String CUSTOM_SHIP_HULL = "moci_combatSquad_customShip_hull_";
    private static final String CUSTOM_SHIP_VARIANT = "moci_combatSquad_customShip_variant_";
    private static final String CUSTOM_SHIP_VARIANT_OBJ = "moci_combatSquad_customShip_variantObj_";
    private static final String CUSTOM_SHIP_NAME = "moci_combatSquad_customShip_name_";
    private static final String CUSTOM_SHIP_DP = "moci_combatSquad_customShip_dp_";
    
    // 存储从舰队中移除的舰船，用于后续归还 - 基于Nexerelin的安全模式
    private static final String REMOVED_SHIP_KEY = "moci_combatSquad_removedShip_";
    
    /**
     * 生成舰船存储键值
     * @param commanderId 指挥官ID
     * @param pilotIndex 机师索引
     * @return 存储键值
     */
    private static String getShipStorageKey(String commanderId, int pilotIndex) {
        return REMOVED_SHIP_KEY + commanderId + "_" + pilotIndex;
    }
    
    /**
     * 自定义僚机舰船配置数据类
     */
    public static class CustomWingmanShip {
        public String hullId;
        public String variantId;
        public String displayName;
        public int deploymentPoints;
        public com.fs.starfarer.api.combat.ShipVariantAPI variantConfig; // 完整的变体配置
        
        public CustomWingmanShip(String hullId, String variantId, String displayName, int deploymentPoints, 
                               com.fs.starfarer.api.combat.ShipVariantAPI variantConfig) {
            this.hullId = hullId;
            this.variantId = variantId;
            this.displayName = displayName;
            this.deploymentPoints = deploymentPoints;
            this.variantConfig = variantConfig;
        }
        
        // 兼容性构造函数
        public CustomWingmanShip(String hullId, String variantId, String displayName, int deploymentPoints) {
            this(hullId, variantId, displayName, deploymentPoints, null);
        }
    }
    
    // ======================== 键值生成方法 ========================
    
    private static String getHullKey(String commanderId, int pilotIndex) {
        return CUSTOM_SHIP_HULL + pilotIndex + "_" + commanderId;
    }
    
    private static String getVariantKey(String commanderId, int pilotIndex) {
        return CUSTOM_SHIP_VARIANT + pilotIndex + "_" + commanderId;
    }
    
    private static String getVariantObjKey(String commanderId, int pilotIndex) {
        return CUSTOM_SHIP_VARIANT_OBJ + pilotIndex + "_" + commanderId;
    }
    
    private static String getNameKey(String commanderId, int pilotIndex) {
        return CUSTOM_SHIP_NAME + pilotIndex + "_" + commanderId;
    }
    
    private static String getDpKey(String commanderId, int pilotIndex) {
        return CUSTOM_SHIP_DP + pilotIndex + "_" + commanderId;
    }
    
    // ======================== 核心功能方法 ========================
    
    /**
     * 为机师指派自定义舰船
     * 
     * @param commanderId 指挥官ID
     * @param pilotIndex 机师索引
     * @param ship 要指派的舰船
     * @return 是否指派成功
     */
    public static boolean assignCustomShip(String commanderId, int pilotIndex, FleetMemberAPI ship) {
        if (commanderId == null || ship == null) {
            Global.getLogger(Moci_CustomWingmanManager.class).warn("指派参数无效");
            return false;
        }
        
        // 1. 获取指挥官舰船进行验证
        FleetMemberAPI commanderShip = getCommanderShip(commanderId);
        if (commanderShip == null) {
            Global.getLogger(Moci_CustomWingmanManager.class).warn("未找到指挥官舰船: " + commanderId);
            return false;
        }
        
        // 2. 验证舰船是否符合条件
        if (!isValidWingmanShip(ship, commanderShip)) {
            Global.getLogger(Moci_CustomWingmanManager.class).warn(
                "舰船不符合僚机条件: " + ship.getHullSpec().getHullNameWithDashClass()
            );
            return false;
        }
        
        // 3. 归还旧舰船到舰队（如果存在）
        returnOldShipToFleet(commanderId, pilotIndex);
        
        // 4. 保存新的自定义配置
        saveCustomShipConfig(commanderId, pilotIndex, ship);
        
        // 5. 安全地从舰队中移除新指派的舰船并存储
        removeAndStoreShipFromFleet(commanderId, pilotIndex, ship);
        
        return true;
    }
    
    /**
     * 获取机师的自定义舰船配置
     * 
     * @param commanderId 指挥官ID
     * @param pilotIndex 机师索引
     * @return 自定义舰船配置，如果没有则返回null
     */
    public static CustomWingmanShip getCustomWingmanShip(String commanderId, int pilotIndex) {
        if (commanderId == null) return null;
        
        String hullKey = getHullKey(commanderId, pilotIndex);
        String variantKey = getVariantKey(commanderId, pilotIndex);
        String variantObjKey = getVariantObjKey(commanderId, pilotIndex);
        String nameKey = getNameKey(commanderId, pilotIndex);
        String dpKey = getDpKey(commanderId, pilotIndex);
        
        String hullId = (String) Global.getSector().getPersistentData().get(hullKey);
        String variantId = (String) Global.getSector().getPersistentData().get(variantKey);
        String displayName = (String) Global.getSector().getPersistentData().get(nameKey);
        Object dpObj = Global.getSector().getPersistentData().get(dpKey);
        Object variantObj = Global.getSector().getPersistentData().get(variantObjKey);
        
        if (hullId != null && variantId != null && displayName != null && dpObj instanceof Integer) {
            com.fs.starfarer.api.combat.ShipVariantAPI variantConfig = null;
            if (variantObj instanceof com.fs.starfarer.api.combat.ShipVariantAPI) {
                variantConfig = (com.fs.starfarer.api.combat.ShipVariantAPI) variantObj;
            }
            return new CustomWingmanShip(hullId, variantId, displayName, (Integer) dpObj, variantConfig);
        }
        
        return null;
    }
    
    /**
     * 获取存储的自定义舰船对象（用于战斗部署）
     * 
     * @param commanderId 指挥官ID
     * @param pilotIndex 机师索引
     * @return 存储的舰船对象，如果没有则返回null
     */
    public static FleetMemberAPI getStoredCustomShip(String commanderId, int pilotIndex) {
        if (commanderId == null) return null;
        
        String storageKey = getShipStorageKey(commanderId, pilotIndex);
        Object storedObj = Global.getSector().getPersistentData().get(storageKey);
        
        if (storedObj instanceof FleetMemberAPI) {
            FleetMemberAPI storedShip = (FleetMemberAPI) storedObj;
            // 找到存储的自定义舰船
            return storedShip;
        }
        
        return null;
    }
    
    /**
     * 获取可指派给指定指挥官的舰船列表
     * 
     * @param commanderId 指挥官ID
     * @return 可指派的舰船列表
     */
    public static List<FleetMemberAPI> getAssignableShips(String commanderId) {
        List<FleetMemberAPI> assignableShips = new ArrayList<>();
        
        if (commanderId == null) return assignableShips;
        
        FleetMemberAPI commanderShip = getCommanderShip(commanderId);
        if (commanderShip == null) return assignableShips;
        
        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet == null) return assignableShips;
        
        for (FleetMemberAPI ship : playerFleet.getFleetData().getMembersListCopy()) {
            if (isValidWingmanShip(ship, commanderShip)) {
                assignableShips.add(ship);
            }
        }
        
        return assignableShips;
    }
    
    /**
     * 重置机师为默认舰船配置 - 使用安全模式
     * 
     * @param commanderId 指挥官ID
     * @param pilotIndex 机师索引
     */
    public static void resetToDefault(String commanderId, int pilotIndex) {
        if (commanderId == null) return;
        
        // 使用新的安全模式归还舰船
        returnOldShipToFleet(commanderId, pilotIndex);
        
        // 清除配置数据
        String hullKey = getHullKey(commanderId, pilotIndex);
        String variantKey = getVariantKey(commanderId, pilotIndex);
        String nameKey = getNameKey(commanderId, pilotIndex);
        String dpKey = getDpKey(commanderId, pilotIndex);
        
        Global.getSector().getPersistentData().remove(hullKey);
        Global.getSector().getPersistentData().remove(variantKey);
        Global.getSector().getPersistentData().remove(nameKey);
        Global.getSector().getPersistentData().remove(dpKey);
        
    }
    
    /**
     * 检查机师是否有自定义舰船配置
     * 
     * @param commanderId 指挥官ID
     * @param pilotIndex 机师索引
     * @return 是否有自定义配置
     */
    public static boolean hasCustomShip(String commanderId, int pilotIndex) {
        return getCustomWingmanShip(commanderId, pilotIndex) != null;
    }
    
    // ======================== 验证和辅助方法 ========================
    
    /**
     * 验证舰船是否可以作为僚机
     * 
     * @param candidate 候选舰船
     * @param commanderShip 指挥官舰船
     * @return 是否符合僚机条件
     */
    public static boolean isValidWingmanShip(FleetMemberAPI candidate, FleetMemberAPI commanderShip) {
        if (candidate == null || commanderShip == null) return false;
        
        // 1. 不能是指挥官舰船本身
        if (candidate == commanderShip) return false;
        
        // 2. 必须是护卫舰
        if (candidate.getHullSpec().getHullSize() != ShipAPI.HullSize.FRIGATE) return false;
        
        // 3. 原始部署点必须小于等于指挥官舰船
        if (candidate.getHullSpec().getSuppliesToRecover() > commanderShip.getHullSpec().getSuppliesToRecover()) return false;
        
        // 4. 舰船必须可用（未封存、未损毁）
        if (candidate.isMothballed()) return false;
        
        // 5. 不能是其他CombatSquad的队长机
        if (candidate.getVariant().hasHullMod("Moci_CombatSquad")) return false;
        
        // 6. 不能有军官（除了默认军官）
        if (hasOfficer(candidate)) return false;
        
        // 7. 排除一些特殊舰船类型 - 使用D-hull兼容的方式
        String hullId = candidate.getHullSpec().getDParentHullId();
        if (hullId == null) {
            hullId = candidate.getHullSpec().getHullId();
        }
        if (hullId.contains("_Station") || hullId.contains("_Base")) return false;
        
        // 8. 检查制造商类型限制
        if (!isValidManufacturer(candidate)) return false;
        
        return true;
    }
    
    /**
     * 检查舰船是否有军官（除了默认军官）
     * 
     * @param ship 舰船
     * @return 是否有军官
     */
    private static boolean hasOfficer(FleetMemberAPI ship) {
        if (ship == null) return false;
        
        try {
            PersonAPI captain = ship.getCaptain();
            // 如果没有船长，或者是默认军官，则认为没有军官
            if (captain == null || captain.isDefault()) {
                return false;
            }
            
            // 如果有非默认军官，则不能被指派
            return true;
            
        } catch (Exception e) {
            // 防异常，出错时认为有军官（安全起见）
            Global.getLogger(Moci_CustomWingmanManager.class).warn(
                "检查舰船军官时发生错误: " + e.getMessage()
            );
            return true;
        }
    }
    
    /**
     * 检查舰船的制造商是否符合要求
     * 
     * @param ship 舰船
     * @return 是否符合制造商要求
     */
    private static boolean isValidManufacturer(FleetMemberAPI ship) {
        if (ship == null || ship.getHullSpec() == null) return false;
        
        try {
            // 检查制造商字段
            String manufacturer = ship.getHullSpec().getManufacturer();
            if (manufacturer != null) {
                // 优先使用配置加载器
                if (Moci_CombatSquadConfigLoader.isConfigsLoaded()) {
                    return Moci_CombatSquadConfigLoader.isManufacturerAllowed(manufacturer);
                }
                
                // 后备方案:使用硬编码的制造商列表
                String[] allowedManufacturers = {
                    // 中文制造商
                    "地球联邦军",
                    "地球联邦军(特装)",
                    "阿纳海姆电子",
                    "阿纳海姆电子(特装)",
                    "吉翁尼克重工",
                    "吉翁尼克重工(特装)",
                    "阿克西斯",
                    "阿克西斯(特装)",
                    // 英文制造商
                    "Earth Federation Forces",
                    "Earth Federation Forces(Special)",
                    "Anaheim Electronics",
                    "Anaheim Electronics(Special)",
                    "Gironic Heavy Industries",
                    "Gironic Heavy Industries(Special)",
                    "Axis",
                    "Axis(Special)",
                    // 其他制造商
                    "Arma Armatura",
                    "骤雨军事公司"
                };
                
                for (String allowed : allowedManufacturers) {
                    if (allowed.equals(manufacturer)) {
                        return true;
                    }
                }
            }
            
            // 如果制造商不匹配，返回false
            return false;
            
        } catch (Exception e) {
            // 防null异常，出错时返回false
            Global.getLogger(Moci_CustomWingmanManager.class).warn(
                "检查舰船制造商时发生错误: " + e.getMessage()
            );
            return false;
        }
    }
    
    /**
     * 根据指挥官ID获取其指挥的舰船
     * 
     * @param commanderId 指挥官ID
     * @return 指挥官舰船，如果未找到则返回null
     */
    private static FleetMemberAPI getCommanderShip(String commanderId) {
        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet == null) return null;
        
        for (FleetMemberAPI ship : playerFleet.getFleetData().getMembersListCopy()) {
            PersonAPI captain = ship.getCaptain();
            if (captain != null && commanderId.equals(captain.getId())) {
                return ship;
            }
        }
        
        return null;
    }
    
    /**
     * 保存自定义舰船配置到持久化数据
     * 
     * @param commanderId 指挥官ID
     * @param pilotIndex 机师索引
     * @param ship 舰船
     */
    private static void saveCustomShipConfig(String commanderId, int pilotIndex, FleetMemberAPI ship) {
        String hullKey = getHullKey(commanderId, pilotIndex);
        String variantKey = getVariantKey(commanderId, pilotIndex);
        String variantObjKey = getVariantObjKey(commanderId, pilotIndex);
        String nameKey = getNameKey(commanderId, pilotIndex);
        String dpKey = getDpKey(commanderId, pilotIndex);
        
        // 使用D-hull兼容的方式获取Hull ID
        String hullId = ship.getHullSpec().getDParentHullId();
        if (hullId == null) {
            hullId = ship.getHullSpec().getHullId();
        }
        String variantId = ship.getVariant().getHullVariantId(); // 保存原始变体ID，即使是临时的
        String displayName = ship.getHullSpec().getHullNameWithDashClass();
        int deploymentPoints = (int) ship.getHullSpec().getSuppliesToRecover(); // 使用原始DP
        
        // 保存完整的变体配置对象
        com.fs.starfarer.api.combat.ShipVariantAPI variantConfig = ship.getVariant();
        
        Global.getSector().getPersistentData().put(hullKey, hullId);
        Global.getSector().getPersistentData().put(variantKey, variantId);
        Global.getSector().getPersistentData().put(variantObjKey, variantConfig); // 保存完整变体配置
        Global.getSector().getPersistentData().put(nameKey, displayName);
        Global.getSector().getPersistentData().put(dpKey, deploymentPoints);
        
        // 保存完整舰船配置完成
    }
    
    /**
     * 清理指挥官的所有自定义舰船配置 - 使用安全模式
     * 
     * @param commanderId 指挥官ID
     */
    public static void cleanupCustomShipConfigs(String commanderId) {
        if (commanderId == null) return;
        
        int squadSize = Moci_PilotManager.getSquadSize(commanderId);
        
        for (int i = 0; i < squadSize; i++) {
            resetToDefault(commanderId, i);
        }
        
    }
    
    /**
     * 紧急清理所有存储的舰船 - 用于系统维护
     * 当检测到数据不一致时调用此方法
     */
    public static void emergencyCleanupAllStoredShips() {
        try {
            CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
            if (playerFleet == null) return;
            
            int restoredCount = 0;
            List<String> keysToRemove = new ArrayList<>();
            
            // 遍历所有持久化数据，查找存储的舰船
            for (String key : Global.getSector().getPersistentData().keySet()) {
                if (key.startsWith(REMOVED_SHIP_KEY)) {
                    Object storedObj = Global.getSector().getPersistentData().get(key);
                    if (storedObj instanceof FleetMemberAPI) {
                        FleetMemberAPI storedShip = (FleetMemberAPI) storedObj;
                        
                        // 检查舰船是否已经在舰队中
                        boolean alreadyInFleet = false;
                        for (FleetMemberAPI existingShip : playerFleet.getFleetData().getMembersListCopy()) {
                            if (existingShip == storedShip) {
                                alreadyInFleet = true;
                                break;
                            }
                        }
                        
                        if (!alreadyInFleet) {
                            // 移除零船员船插 - 恢复原始状态
                            removeZeroCrewHullmod(storedShip);
                            
                            playerFleet.getFleetData().addFleetMember(storedShip);
                            restoredCount++;
                        }
                        
                        // 清除存储记录
                        keysToRemove.add(key);
                    }
                }
            }

            for (String removeKey : keysToRemove) {
                Global.getSector().getPersistentData().remove(removeKey);
            }
            
            if (restoredCount > 0) {
                playerFleet.getFleetData().setSyncNeeded();
                playerFleet.getFleetData().syncIfNeeded();
            }
            
        } catch (Exception e) {
            Global.getLogger(Moci_CustomWingmanManager.class).error(
                "紧急清理过程中发生错误: " + e.getMessage(), e
            );
        }
    }
    
    /**
     * 获取舰船的简短显示名称
     * 
     * @param ship 舰船
     * @return 显示名称
     */
    public static String getShipDisplayName(FleetMemberAPI ship) {
        if (ship == null) return Moci_CombatSquadTextRegistry.getText("ship_name_unknown", "未知舰船");
        
        String baseName = ship.getHullSpec().getHullName();
        String className = ship.getHullSpec().getDesignation();
        
        if (className != null && !className.isEmpty()) {
            return baseName + " (" + className + ")";
        } else {
            return baseName;
        }
    }
    
    /**
     * 获取舰船尺寸的显示名称
     * 
     * @param hullSize 舰船尺寸
     * @return 显示名称
     */
    private static String getHullSizeDisplayName(ShipAPI.HullSize hullSize) {
        switch (hullSize) {
            case FRIGATE:
                return Moci_CombatSquadTextRegistry.getText("ship_size_frigate", "护卫舰");
            case DESTROYER:
                return Moci_CombatSquadTextRegistry.getText("ship_size_destroyer", "驱逐舰");
            case CRUISER:
                return Moci_CombatSquadTextRegistry.getText("ship_size_cruiser", "巡洋舰");
            case CAPITAL_SHIP:
                return Moci_CombatSquadTextRegistry.getText("ship_size_capital", "主力舰");
            case FIGHTER:
                return Moci_CombatSquadTextRegistry.getText("ship_size_fighter", "战机");
            default:
                return Moci_CombatSquadTextRegistry.getText("ship_size_unknown", "未知");
        }
    }
    
    /**
     * 获取舰船的详细信息字符串
     * 
     * @param ship 舰船
     * @return 详细信息
     */
    public static String getShipDetailInfo(FleetMemberAPI ship) {
        if (ship == null) return Moci_CombatSquadTextRegistry.getText("ship_detail_none", "无信息");
        
        return Moci_CombatSquadTextRegistry.format(
                "ship_detail_format",
                "{name} | 部署点:{dp} | {size}级",
                "name", getShipDisplayName(ship),
                "dp", String.valueOf((int) ship.getDeploymentPointsCost()),
                "size", getHullSizeDisplayName(ship.getHullSpec().getHullSize()));
    }
    
    // ======================== 安全舰船管理方法（基于Nexerelin模式）========================
    
    /**
     * 安全地从舰队中移除舰船并存储 - 基于Nexerelin的安全模式
     * 
     * @param commanderId 指挥官ID
     * @param pilotIndex 机师索引
     * @param ship 要移除的舰船
     */
    private static void removeAndStoreShipFromFleet(String commanderId, int pilotIndex, FleetMemberAPI ship) {
        if (ship == null) return;
        
        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet != null) {
            // 添加Moci_ZeroCrew船插 - 使用类似Moci_bounty_plugin的安全方式
            addZeroCrewHullmod(ship);
            
            // 存储原始舰船对象到持久化数据
            String storageKey = getShipStorageKey(commanderId, pilotIndex);
            Global.getSector().getPersistentData().put(storageKey, ship);
            
            // 从舰队中移除舰船
            playerFleet.getFleetData().removeFleetMember(ship);
            
            // 确保数据同步 - 学习Nexerelin的做法
            playerFleet.getFleetData().setSyncNeeded();
            playerFleet.getFleetData().syncIfNeeded();
            
        }
    }
    
    /**
     * 归还旧舰船到舰队 - 基于Nexerelin的安全模式
     * 
     * @param commanderId 指挥官ID
     * @param pilotIndex 机师索引
     */
    private static void returnOldShipToFleet(String commanderId, int pilotIndex) {
        String storageKey = getShipStorageKey(commanderId, pilotIndex);
        FleetMemberAPI storedShip = (FleetMemberAPI) Global.getSector().getPersistentData().get(storageKey);
        
        if (storedShip != null) {
            CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
            if (playerFleet != null) {
                // 移除Moci_ZeroCrew船插 - 恢复原始状态
                removeZeroCrewHullmod(storedShip);
                
                // 直接添加原始舰船对象回舰队 - 保持所有原始数据
                playerFleet.getFleetData().addFleetMember(storedShip);
                
                // 确保数据同步
                playerFleet.getFleetData().setSyncNeeded();
                playerFleet.getFleetData().syncIfNeeded();
                
                // 清除存储
                Global.getSector().getPersistentData().remove(storageKey);
                
            }
        }
    }
    
    /**
     * 将舰船归还到舰队（兼容旧版本的方法）
     * 
     * @param customShip 自定义舰船配置
     */
    @Deprecated
    private static void returnShipToFleet(CustomWingmanShip customShip) {
        if (customShip == null) return;
        
        try {
            CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
            if (playerFleet != null) {
                // 首先检查舰队中是否已经有相同的舰船
                boolean alreadyExists = false;
                for (FleetMemberAPI existingShip : playerFleet.getFleetData().getMembersListCopy()) {
                    // 使用D-hull兼容的方式比较Hull ID
                    String existingHullId = existingShip.getHullSpec().getDParentHullId();
                    if (existingHullId == null) {
                        existingHullId = existingShip.getHullSpec().getHullId();
                    }
                    
                    if (existingHullId.equals(customShip.hullId) && 
                        existingShip.getVariant().getHullVariantId().equals(customShip.variantId)) {
                        alreadyExists = true;
                        break;
                    }
                }
                
                if (!alreadyExists) {
                    // 重新创建舰船并添加到舰队
                    FleetMemberAPI restoredShip = Global.getFactory().createFleetMember(
                        FleetMemberType.SHIP, 
                        customShip.variantId
                    );
                    
                    if (restoredShip != null) {
                        playerFleet.getFleetData().addFleetMember(restoredShip);
                    } else {
                        Global.getLogger(Moci_CustomWingmanManager.class).warn(
                            "无法重新创建舰船: " + customShip.displayName
                        );
                    }
                }
            }
        } catch (Exception e) {
            Global.getLogger(Moci_CustomWingmanManager.class).error(
                "归还舰船到舰队时发生错误: " + e.getMessage()
            );
        }
    }
    
    // ======================== 系统状态检查和维护方法 ========================
    
    /**
     * 检查存储的舰船数据完整性
     * 
     * @return 检查报告
     */
    public static String checkStoredShipsIntegrity() {
        StringBuilder report = new StringBuilder();
        report.append("=== 自定义僚机舰船存储完整性检查 ===\n");
        
        int totalStored = 0;
        int validStored = 0;
        int invalidStored = 0;
        
        try {
            for (String key : Global.getSector().getPersistentData().keySet()) {
                if (key.startsWith(REMOVED_SHIP_KEY)) {
                    totalStored++;
                    Object storedObj = Global.getSector().getPersistentData().get(key);
                    
                    if (storedObj instanceof FleetMemberAPI) {
                        FleetMemberAPI storedShip = (FleetMemberAPI) storedObj;
                        if (storedShip.getHullSpec() != null && storedShip.getVariant() != null) {
                            validStored++;
                        } else {
                            invalidStored++;
                            report.append("发现损坏的存储舰船: ").append(key).append("\n");
                        }
                    } else {
                        invalidStored++;
                        report.append("发现无效的存储对象: ").append(key).append("\n");
                    }
                }
            }
            
            report.append("总存储数量: ").append(totalStored).append("\n");
            report.append("有效存储: ").append(validStored).append("\n");
            report.append("无效存储: ").append(invalidStored).append("\n");
            
            if (invalidStored > 0) {
                report.append("建议执行紧急清理操作\n");
            } else {
                report.append("存储数据完整性良好\n");
            }
            
        } catch (Exception e) {
            report.append("检查过程中发生错误: ").append(e.getMessage()).append("\n");
        }
        
        report.append("=== 检查完成 ===");
        return report.toString();
    }
    
    /**
     * 获取当前存储的舰船数量
     * 
     * @return 存储的舰船数量
     */
    public static int getStoredShipCount() {
        int count = 0;
        for (String key : Global.getSector().getPersistentData().keySet()) {
            if (key.startsWith(REMOVED_SHIP_KEY)) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * 检查特定机师是否有存储的舰船
     * 
     * @param commanderId 指挥官ID
     * @param pilotIndex 机师索引
     * @return 是否有存储的舰船
     */
    public static boolean hasStoredShip(String commanderId, int pilotIndex) {
        String storageKey = getShipStorageKey(commanderId, pilotIndex);
        return Global.getSector().getPersistentData().containsKey(storageKey);
    }
    
    /**
     * 获取存储舰船的详细信息
     * 
     * @param commanderId 指挥官ID
     * @param pilotIndex 机师索引
     * @return 舰船详细信息，如果没有存储舰船则返回null
     */
    public static String getStoredShipInfo(String commanderId, int pilotIndex) {
        String storageKey = getShipStorageKey(commanderId, pilotIndex);
        FleetMemberAPI storedShip = (FleetMemberAPI) Global.getSector().getPersistentData().get(storageKey);
        
        if (storedShip != null) {
            return getShipDetailInfo(storedShip);
        }
        
        return null;
    }
    
    // ======================== 船插管理方法（基于Moci_bounty_plugin模式）========================
    
    /**
     * 安全地为舰船添加Moci_ZeroCrew船插
     * 使用类似Moci_bounty_plugin中维修龙门架的安全方式
     * 
     * @param ship 要添加船插的舰船
     */
    private static void addZeroCrewHullmod(FleetMemberAPI ship) {
        if (ship == null || ship.getVariant() == null) return;
        
        try {
            // 检查是否已经有零船员船插
            if (ship.getVariant().hasHullMod("Moci_ZeroCrew")) {
                return;
            }
            
            // 克隆变体并设置为改装来源，解除装配保护 - 学习Moci_bounty_plugin的做法
            com.fs.starfarer.api.combat.ShipVariantAPI variant = ship.getVariant().clone();
            variant.setSource(com.fs.starfarer.api.loading.VariantSource.REFIT);
            variant.setHullVariantId(com.fs.starfarer.api.util.Misc.genUID());
            ship.setVariant(variant, false, false);
            
            // 添加零船员船插
            variant.addPermaMod("Moci_ZeroCrew");
            
        } catch (Exception e) {
            Global.getLogger(Moci_CustomWingmanManager.class).error(
                "为舰船添加零船员船插时发生错误: " + e.getMessage(), e
            );
        }
    }
    
    /**
     * 安全地从舰船移除Moci_ZeroCrew船插
     * 使用类似Moci_bounty_plugin中维修龙门架的安全方式
     * 
     * @param ship 要移除船插的舰船
     */
    private static void removeZeroCrewHullmod(FleetMemberAPI ship) {
        if (ship == null || ship.getVariant() == null) return;
        
        try {
            // 检查是否有零船员船插
            if (!ship.getVariant().hasHullMod("Moci_ZeroCrew")) {
                return;
            }
            
            // 克隆变体并设置为改装来源，解除装配保护 - 学习Moci_bounty_plugin的做法
            com.fs.starfarer.api.combat.ShipVariantAPI variant = ship.getVariant().clone();
            variant.setSource(com.fs.starfarer.api.loading.VariantSource.REFIT);
            variant.setHullVariantId(com.fs.starfarer.api.util.Misc.genUID());
            ship.setVariant(variant, false, false);
            
            // 移除零船员船插
            variant.removePermaMod("Moci_ZeroCrew");
            
        } catch (Exception e) {
            Global.getLogger(Moci_CustomWingmanManager.class).error(
                "从舰船移除零船员船插时发生错误: " + e.getMessage(), e
            );
        }
    }
    
    // ======================== 测试和调试方法 ========================
    
    /**
     * 测试船插管理功能 - 用于调试和验证
     * 
     * @param ship 测试舰船
     * @return 测试结果报告
     */
    public static String testHullmodManagement(FleetMemberAPI ship) {
        if (ship == null) return "测试失败：舰船为null";
        
        StringBuilder report = new StringBuilder();
        report.append("=== 船插管理功能测试 ===\n");
        report.append("测试舰船: ").append(ship.getHullSpec().getHullNameWithDashClass()).append("\n");
        
        try {
            // 测试添加船插
            boolean hadZeroCrewBefore = ship.getVariant().hasHullMod("Moci_ZeroCrew");
            report.append("添加前是否有零船员船插: ").append(hadZeroCrewBefore).append("\n");
            
            addZeroCrewHullmod(ship);
            
            boolean hasZeroCrewAfterAdd = ship.getVariant().hasHullMod("Moci_ZeroCrew");
            report.append("添加后是否有零船员船插: ").append(hasZeroCrewAfterAdd).append("\n");
            
            // 测试移除船插
            removeZeroCrewHullmod(ship);
            
            boolean hasZeroCrewAfterRemove = ship.getVariant().hasHullMod("Moci_ZeroCrew");
            report.append("移除后是否有零船员船插: ").append(hasZeroCrewAfterRemove).append("\n");
            
            // 验证结果
            if (!hadZeroCrewBefore && hasZeroCrewAfterAdd && !hasZeroCrewAfterRemove) {
                report.append("测试结果: ✅ 成功\n");
            } else {
                report.append("测试结果: ❌ 失败\n");
            }
            
        } catch (Exception e) {
            report.append("测试过程中发生错误: ").append(e.getMessage()).append("\n");
            report.append("测试结果: ❌ 异常\n");
        }
        
        report.append("=== 测试完成 ===");
        return report.toString();
    }
}
