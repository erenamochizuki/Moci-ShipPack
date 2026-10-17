package data.hullmods;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;

import data.hullmods.Moci_CombatSquadOfficierManager.SquadMember;

/**
 * CombatSquad部署点信息提供器
 * 
 * 统一管理从各种来源获取部署点信息的逻辑，包括：
 * 1. 自定义僚机舰船的部署点信息
 * 2. 默认配置的部署点信息
 * 3. 母舰的基础部署点信息
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_DeploymentInfoProvider {
    
    // 从配置管理器获取默认值
    private static int getDefaultWingmanDP() {
        return Moci_DynamicDeploymentConfig.getDefaultWingmanDP();
    }
    
    private static int getDefaultMothershipDP() {
        return Moci_DynamicDeploymentConfig.getDefaultMothershipDP();
    }
    
    /**
     * 获取自定义僚机的部署点列表
     * 
     * @param commanderId 指挥官ID
     * @return 部署点列表，如果无自定义配置则返回null
     */
    public static List<Integer> getCustomWingmanDeploymentPoints(String commanderId) {
        if (commanderId == null || commanderId.isEmpty()) {
            return null;
        }
        
        try {
            List<Integer> deploymentPoints = new ArrayList<>();
            int squadSize = Moci_PilotManager.getSquadSize(commanderId);
            
            if (squadSize <= 0) {
                return null; // 无编队时返回null
            }
            
            boolean hasCustomShips = false;
            
            for (int i = 0; i < squadSize; i++) {
                Moci_CustomWingmanManager.CustomWingmanShip customShip = 
                    Moci_CustomWingmanManager.getCustomWingmanShip(commanderId, i);
                
                if (customShip != null) {
                    deploymentPoints.add(resolveCustomDeploymentPoints(customShip));
                    hasCustomShips = true;
                } else {
                    // 如果某个位置没有自定义舰船，使用默认值
                    deploymentPoints.add(getDefaultWingmanDP());
                }
            }
            
            // 只有当至少有一个自定义舰船时才返回结果
            return hasCustomShips ? deploymentPoints : null;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DeploymentInfoProvider.class)
                  .error("获取自定义僚机部署点失败 [commanderId=" + commanderId + "]: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 获取默认配置的僚机部署点列表
     * 
     * @param hullId 船体ID
     * @return 部署点列表
     */
    public static List<Integer> getDefaultWingmanDeploymentPoints(String hullId) {
        if (hullId == null || hullId.isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            List<Integer> deploymentPoints = new ArrayList<>();
            List<SquadMember> config = Moci_CombatSquadOfficierManager.getSquadConfigFromFile(hullId);
            
            if (config != null) {
                for (SquadMember member : config) {
                    if (member != null && member.variantId != null) {
                        // 从变体ID获取部署点信息
                        int dp = getDeploymentPointsFromVariant(member.variantId);
                        
                        // 根据数量添加多个相同的部署点
                        for (int i = 0; i < member.count; i++) {
                            deploymentPoints.add(dp);
                        }
                    }
                }
            }
            
            return deploymentPoints;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DeploymentInfoProvider.class)
                  .error("获取默认僚机部署点失败 [hullId=" + hullId + "]: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 从变体ID获取部署点信息
     * 
     * @param variantId 变体ID
     * @return 部署点数量
     */
    private static int getDeploymentPointsFromVariant(String variantId) {
        if (variantId == null || variantId.isEmpty()) {
            return getDefaultWingmanDP();
        }
        
        try {
            ShipVariantAPI variant = Global.getSettings().getVariant(variantId);
            int dp = getDeploymentPointsFromVariant(variant);
            if (dp > 0) {
                return dp;
            }
            if (variant != null && variant.getHullSpec() != null) {
                float suppliesToRecover = variant.getHullSpec().getSuppliesToRecover();
                return Math.max(1, (int)Math.round(suppliesToRecover));
            }
        } catch (Exception e) {
            Global.getLogger(Moci_DeploymentInfoProvider.class)
                  .warn("从变体获取部署点失败 [" + variantId + "]: " + e.getMessage());
        }
        
        return getDefaultWingmanDP(); // 默认值
    }

    private static int getDeploymentPointsFromVariant(ShipVariantAPI variant) {
        if (variant == null) {
            return -1;
        }

        try {
            FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variant);
            if (member != null) {
                return Math.max(1, (int)Math.round(member.getDeploymentPointsCost()));
            }
        } catch (Exception e) {
            Global.getLogger(Moci_DeploymentInfoProvider.class)
                  .warn("通过 FleetMember 计算部署点失败: " + e.getMessage());
        }

        return -1;
    }

    private static int resolveCustomDeploymentPoints(Moci_CustomWingmanManager.CustomWingmanShip customShip) {
        if (customShip == null) {
            return getDefaultWingmanDP();
        }

        int dp = getDeploymentPointsFromVariant(customShip.variantConfig);
        if (dp > 0) {
            return dp;
        }

        return Math.max(1, customShip.deploymentPoints);
    }
    
    /**
     * 获取舰船的基础部署点
     * 
     * @param ship 目标舰船
     * @return 基础部署点数量
     */
    public static int getShipBaseDeploymentPoints(ShipAPI ship) {
        if (ship == null) {
            return getDefaultMothershipDP();
        }
        
        try {
            if (ship.getFleetMember() != null) {
                return Math.max(1, (int)Math.round(ship.getFleetMember().getDeploymentPointsCost()));
            }
            float fallback = ship.getHullSpec() != null ? ship.getHullSpec().getSuppliesToRecover() : getDefaultMothershipDP();
            return Math.max(1, (int)Math.round(fallback));
        } catch (Exception e) {
            Global.getLogger(Moci_DeploymentInfoProvider.class)
                  .error("获取舰船基础部署点失败 [ship=" + 
                        (ship != null && ship.getHullSpec() != null ? ship.getHullSpec().getHullId() : "null") + "]: " + e.getMessage(), e);
            float fallback = ship.getHullSpec() != null ? ship.getHullSpec().getSuppliesToRecover() : getDefaultMothershipDP();
            return Math.max(1, (int)Math.round(fallback));
        }
    }
    
    /**
     * 验证部署点数据的合理性
     * 
     * @param deploymentPoints 部署点数量
     * @return 是否合理
     */
    public static boolean isValidDeploymentPoints(int deploymentPoints) {
        // 使用配置管理器的验证方法
        return Moci_DynamicDeploymentConfig.isValidDeploymentPoints(deploymentPoints);
    }
    
    /**
     * 获取调试信息
     * 
     * @param ship 目标舰船
     * @return 调试信息字符串
     */
    public static String getDebugInfo(ShipAPI ship) {
        if (ship == null) {
            return "Ship is null";
        }
        
        StringBuilder debug = new StringBuilder();
        debug.append("Ship: ").append(ship.getHullSpec().getHullId()).append("\n");
        debug.append("Base DP: ").append(getShipBaseDeploymentPoints(ship)).append("\n");
        
        // 自定义僚机信息
        if (ship.getCaptain() != null && !ship.getCaptain().isDefault()) {
            String commanderId = ship.getCaptain().getId();
            List<Integer> customDPs = getCustomWingmanDeploymentPoints(commanderId);
            if (customDPs != null) {
                debug.append("Custom Wingman DPs: ").append(customDPs).append("\n");
            }
        }
        
        // 默认配置信息
        String hullId = ship.getHullSpec().getDParentHullId();
        if (hullId == null) {
            hullId = ship.getHullSpec().getHullId();
        }
        List<Integer> defaultDPs = getDefaultWingmanDeploymentPoints(hullId);
        debug.append("Default Wingman DPs: ").append(defaultDPs).append("\n");
        
        return debug.toString();
    }
}
