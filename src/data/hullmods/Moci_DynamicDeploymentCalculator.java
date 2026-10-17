package data.hullmods;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * CombatSquad动态部署点计算器
 * 
 * 根据实际指派的僚机舰船类型和数量，动态计算母舰的部署点消耗倍数。
 * 系统设计为完全向后兼容，在无自定义僚机时回退到原有的固定惩罚机制。
 * 
 * 核心算法：
 * - 僚机贡献系数 = max(0.375, min(0.5, 僚机DP/母舰DP * 0.5))
 * - 总惩罚倍数 = 1.0 + sum(所有僚机贡献系数)
 * - 最小惩罚倍数 = 1.5 (兼容现有系统)
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_DynamicDeploymentCalculator {
    
    // 配置参数（从配置管理器获取）
    private static float getMinWingmanContribution() {
        return Moci_DynamicDeploymentConfig.getMinWingmanContribution();
    }
    
    private static float getMaxWingmanContribution() {
        return Moci_DynamicDeploymentConfig.getMaxWingmanContribution();
    }
    
    private static float getMinTotalMultiplier() {
        return Moci_DynamicDeploymentConfig.getMinTotalMultiplier();
    }
    
    private static float getFallbackMultiplier() {
        return Moci_DynamicDeploymentConfig.getFallbackMultiplier();
    }
    
    /**
     * 计算舰船的动态部署点消耗倍数
     * 
     * @param ship 目标舰船
     * @return 部署点消耗倍数
     */
    public static float calculateDeploymentMultiplier(ShipAPI ship) {
        if (ship == null) {
            return getFallbackMultiplier();
        }
        
        // 检查动态部署点系统是否启用
        if (!Moci_DynamicDeploymentConfig.isDynamicDeploymentEnabled()) {
            return getFallbackMultiplier();
        }
        
        // 尝试从缓存获取结果
        Float cachedResult = Moci_DeploymentCalculationCache.getCachedMultiplier(ship);
        if (cachedResult != null) {
            return cachedResult;
        }
        
        try {
            long startTime = System.nanoTime();
            float result = calculateDeploymentMultiplierInternal(ship);
            
            // 缓存计算结果
            Moci_DynamicDeploymentCalculator.CalculationDetails details = getCalculationDetails(ship);
            Moci_DeploymentCalculationCache.cacheResult(ship, result, details);
            
            // 性能监控
            if (Moci_DynamicDeploymentConfig.isPerformanceMonitoringEnabled()) {
                long duration = System.nanoTime() - startTime;
                Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                      .info(String.format("动态部署点计算耗时: %.2f微秒", duration / 1000.0));
            }
            
            // 详细日志
            if (Moci_DynamicDeploymentConfig.isDetailedLoggingEnabled()) {
                Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                      .info(String.format("舰船[%s]动态部署点计算结果: %.2f", 
                            ship.getHullSpec().getHullId(), result));
            }
            
            return result;
            
        } catch (Exception e) {
            // 记录错误并回退到固定惩罚
            Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                  .error("动态部署点计算失败，回退到固定惩罚: " + e.getMessage(), e);
            return getFallbackMultiplier();
        }
    }
    
    /**
     * 内部计算方法，包含主要逻辑
     */
    private static float calculateDeploymentMultiplierInternal(ShipAPI ship) {
        // 详细日志记录
        boolean detailedLogging = Moci_DynamicDeploymentConfig.isDetailedLoggingEnabled();
        
        if (detailedLogging) {
            Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                  .info("开始计算舰船[" + ship.getHullSpec().getHullId() + "]的动态部署点");
        }
        
        // 1. 获取母舰部署点
        int mothershipDP = Moci_DeploymentInfoProvider.getShipBaseDeploymentPoints(ship);
        if (mothershipDP <= 0) {
            if (detailedLogging) {
                Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                      .warn("无法获取母舰部署点，回退到固定惩罚");
            }
            return getFallbackMultiplier(); // 无法获取母舰DP时回退
        }
        
        if (detailedLogging) {
            Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                  .info("母舰部署点: " + mothershipDP);
        }
        
        // 2. 获取僚机部署点列表
        List<Integer> wingmanDPs = getWingmanDeploymentPoints(ship);
        if (wingmanDPs == null || wingmanDPs.isEmpty()) {
            if (detailedLogging) {
                Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                      .info("无僚机配置，使用固定惩罚");
            }
            return getFallbackMultiplier(); // 无僚机时使用固定惩罚
        }
        
        if (detailedLogging) {
            Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                  .info("僚机部署点列表: " + wingmanDPs);
        }
        
        // 3. 验证数据有效性
        if (!validateDeploymentData(mothershipDP, wingmanDPs)) {
            if (detailedLogging) {
                Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                      .warn("数据验证失败，回退到固定惩罚");
            }
            return getFallbackMultiplier();
        }
        
        // 4. 计算总贡献系数
        float totalContribution = 0f;
        for (int i = 0; i < wingmanDPs.size(); i++) {
            Integer wingmanDP = wingmanDPs.get(i);
            if (wingmanDP != null && wingmanDP > 0) {
                float contribution = getWingmanContribution(wingmanDP, mothershipDP);
                totalContribution += contribution;
                
                if (detailedLogging) {
                    Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                          .info(String.format("僚机%d: %dDP → %.3f贡献", i + 1, wingmanDP, contribution));
                }
            }
        }
        
        if (detailedLogging) {
            Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                  .info("总贡献系数: " + totalContribution);
        }
        
        // 5. 返回总惩罚倍数，确保不低于最小值
        float multiplier = 1.0f + totalContribution;
        float finalMultiplier = Math.max(multiplier, getMinTotalMultiplier());
        
        if (detailedLogging) {
            Global.getLogger(Moci_DynamicDeploymentCalculator.class)
                  .info(String.format("最终惩罚倍数: %.3f (原始: %.3f, 最小值: %.3f)", 
                        finalMultiplier, multiplier, getMinTotalMultiplier()));
        }
        
        return finalMultiplier;
    }
    
    /**
     * 计算单个僚机的贡献系数
     * 
     * @param wingmanDP 僚机部署点
     * @param mothershipDP 母舰部署点
     * @return 僚机贡献系数
     */
    private static float getWingmanContribution(int wingmanDP, int mothershipDP) {
        if (mothershipDP <= 0) {
            return getMaxWingmanContribution(); // 默认最大贡献
        }
        
        // 计算比例贡献: max(0.375, min(0.5, wingmanDP/mothershipDP * 0.5))
        float ratio = (float) wingmanDP / mothershipDP;
        float contribution = ratio * 0.5f;
        
        return Math.max(getMinWingmanContribution(), Math.min(getMaxWingmanContribution(), contribution));
    }
    
    /**
     * 获取僚机部署点列表
     */
    private static List<Integer> getWingmanDeploymentPoints(ShipAPI ship) {
        PersonAPI captain = ship.getCaptain();
        if (captain != null && !captain.isDefault()) {
            // 1. 优先从自定义僚机系统获取
            List<Integer> customDPs = Moci_DeploymentInfoProvider.getCustomWingmanDeploymentPoints(captain.getId());
            if (customDPs != null && !customDPs.isEmpty()) {
                return customDPs;
            }
        }
        
        // 2. 回退到默认配置
        String hullId = ship.getHullSpec().getDParentHullId();
        if (hullId == null) {
            hullId = ship.getHullSpec().getHullId();
        }
        
        return Moci_DeploymentInfoProvider.getDefaultWingmanDeploymentPoints(hullId);
    }
    
    /**
     * 验证部署点数据的有效性
     */
    private static boolean validateDeploymentData(int mothershipDP, List<Integer> wingmanDPs) {
        if (mothershipDP <= 0) {
            return false;
        }
        
        if (wingmanDPs == null || wingmanDPs.isEmpty()) {
            return false;
        }
        
        // 验证僚机部署点的合理性
        for (Integer wingmanDP : wingmanDPs) {
            if (wingmanDP == null || wingmanDP < 0) {
                return false;
            }
            // 可以添加更多验证规则，比如最大DP限制等
        }
        
        return true;
    }
    
    /**
     * 获取计算详情（用于调试和UI显示）
     * 
     * @param ship 目标舰船
     * @return 计算详情对象
     */
    public static CalculationDetails getCalculationDetails(ShipAPI ship) {
        if (ship == null) {
            return new CalculationDetails(getFallbackMultiplier(), new ArrayList<Float>(), 0, new ArrayList<Integer>());
        }
        
        // 尝试从缓存获取详情
        CalculationDetails cachedDetails = Moci_DeploymentCalculationCache.getCachedDetails(ship);
        if (cachedDetails != null) {
            return cachedDetails;
        }
        
        try {
            int mothershipDP = Moci_DeploymentInfoProvider.getShipBaseDeploymentPoints(ship);
            List<Integer> wingmanDPs = getWingmanDeploymentPoints(ship);
            List<Float> contributions = new ArrayList<>();
            
            if (wingmanDPs != null && !wingmanDPs.isEmpty() && mothershipDP > 0) {
                for (Integer wingmanDP : wingmanDPs) {
                    if (wingmanDP != null && wingmanDP > 0) {
                        contributions.add(getWingmanContribution(wingmanDP, mothershipDP));
                    }
                }
            }
            
            // 注意：这里不能调用calculateDeploymentMultiplier，会导致无限递归
            // 直接计算倍数
            float multiplier;
            if (wingmanDPs == null || wingmanDPs.isEmpty() || mothershipDP <= 0) {
                multiplier = getFallbackMultiplier();
            } else {
                float totalContribution = 0f;
                for (Float contribution : contributions) {
                    if (contribution != null) {
                        totalContribution += contribution;
                    }
                }
                multiplier = Math.max(1.0f + totalContribution, getMinTotalMultiplier());
            }
            
            return new CalculationDetails(multiplier, contributions, mothershipDP, wingmanDPs);
            
        } catch (Exception e) {
            return new CalculationDetails(getFallbackMultiplier(), new ArrayList<Float>(), 0, new ArrayList<Integer>());
        }
    }
    
    /**
     * 计算详情数据类
     */
    public static class CalculationDetails {
        public final float multiplier;
        public final List<Float> wingmanContributions;
        public final int mothershipDP;
        public final List<Integer> wingmanDPs;
        
        public CalculationDetails(float multiplier, List<Float> wingmanContributions, 
                                int mothershipDP, List<Integer> wingmanDPs) {
            this.multiplier = multiplier;
            this.wingmanContributions = wingmanContributions != null ? wingmanContributions : new ArrayList<Float>();
            this.mothershipDP = mothershipDP;
            this.wingmanDPs = wingmanDPs != null ? wingmanDPs : new ArrayList<Integer>();
        }
        
        /**
         * 是否使用了动态计算
         */
        public boolean isDynamicCalculation() {
            return !wingmanContributions.isEmpty() && mothershipDP > 0;
        }
        
        /**
         * 获取总贡献系数
         */
        public float getTotalContribution() {
            float total = 0f;
            for (Float contribution : wingmanContributions) {
                if (contribution != null) {
                    total += contribution;
                }
            }
            return total;
        }
    }
}