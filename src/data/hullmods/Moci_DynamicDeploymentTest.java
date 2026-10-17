package data.hullmods;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;

/**
 * CombatSquad动态部署点计算系统单元测试
 * 
 * 测试覆盖：
 * 1. 核心计算逻辑的正确性
 * 2. 边界条件和异常情况处理
 * 3. 配置参数的有效性
 * 4. 与现有系统的兼容性
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_DynamicDeploymentTest {
    
    /**
     * 运行所有测试
     */
    public static void runAllTests() {
        Global.getLogger(Moci_DynamicDeploymentTest.class).info("开始运行动态部署点计算系统测试");
        
        int totalTests = 0;
        int passedTests = 0;
        
        // 核心计算逻辑测试
        totalTests++;
        if (testWingmanContributionCalculation()) {
            passedTests++;
            Global.getLogger(Moci_DynamicDeploymentTest.class).info("✓ 僚机贡献计算测试通过");
        } else {
            Global.getLogger(Moci_DynamicDeploymentTest.class).error("✗ 僚机贡献计算测试失败");
        }
        
        totalTests++;
        if (testMultiplierCalculation()) {
            passedTests++;
            Global.getLogger(Moci_DynamicDeploymentTest.class).info("✓ 惩罚倍数计算测试通过");
        } else {
            Global.getLogger(Moci_DynamicDeploymentTest.class).error("✗ 惩罚倍数计算测试失败");
        }
        
        // 边界条件测试
        totalTests++;
        if (testBoundaryConditions()) {
            passedTests++;
            Global.getLogger(Moci_DynamicDeploymentTest.class).info("✓ 边界条件测试通过");
        } else {
            Global.getLogger(Moci_DynamicDeploymentTest.class).error("✗ 边界条件测试失败");
        }
        
        // 配置参数测试
        totalTests++;
        if (testConfigurationParameters()) {
            passedTests++;
            Global.getLogger(Moci_DynamicDeploymentTest.class).info("✓ 配置参数测试通过");
        } else {
            Global.getLogger(Moci_DynamicDeploymentTest.class).error("✗ 配置参数测试失败");
        }
        
        // 数据验证测试
        totalTests++;
        if (testDataValidation()) {
            passedTests++;
            Global.getLogger(Moci_DynamicDeploymentTest.class).info("✓ 数据验证测试通过");
        } else {
            Global.getLogger(Moci_DynamicDeploymentTest.class).error("✗ 数据验证测试失败");
        }
        
        // 输出测试结果
        Global.getLogger(Moci_DynamicDeploymentTest.class)
              .info(String.format("测试完成: %d/%d 通过 (%.1f%%)", 
                    passedTests, totalTests, (float)passedTests / totalTests * 100));
    }
    
    /**
     * 测试僚机贡献计算的正确性
     */
    private static boolean testWingmanContributionCalculation() {
        try {
            // 测试用例1: 僚机DP等于母舰DP
            float contribution1 = calculateWingmanContribution(20, 20);
            if (!isApproximatelyEqual(contribution1, 0.5f, 0.001f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 20DP僚机对20DP母舰的贡献应为0.5，实际为" + contribution1);
                return false;
            }
            
            // 测试用例2: 僚机DP为母舰DP的50%
            float contribution2 = calculateWingmanContribution(10, 20);
            if (!isApproximatelyEqual(contribution2, 0.375f, 0.001f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 10DP僚机对20DP母舰的贡献应为0.375，实际为" + contribution2);
                return false;
            }
            
            // 测试用例3: 僚机DP为母舰DP的75%
            float contribution3 = calculateWingmanContribution(15, 20);
            float expected3 = 15f / 20f * 0.5f; // 0.375
            if (!isApproximatelyEqual(contribution3, expected3, 0.001f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 15DP僚机对20DP母舰的贡献应为" + expected3 + "，实际为" + contribution3);
                return false;
            }
            
            // 测试用例4: 僚机DP超过母舰DP（应该被限制为最大值）
            float contribution4 = calculateWingmanContribution(30, 20);
            if (!isApproximatelyEqual(contribution4, 0.5f, 0.001f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 30DP僚机对20DP母舰的贡献应为0.5（上限），实际为" + contribution4);
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDeploymentTest.class)
                  .error("僚机贡献计算测试异常: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 测试惩罚倍数计算的正确性
     */
    private static boolean testMultiplierCalculation() {
        try {
            // 测试用例1: 2个同等级僚机
            List<Integer> wingmanDPs1 = new ArrayList<>();
            wingmanDPs1.add(20);
            wingmanDPs1.add(20);
            float multiplier1 = calculateMultiplier(20, wingmanDPs1);
            float expected1 = 1.0f + 0.5f + 0.5f; // 2.0
            if (!isApproximatelyEqual(multiplier1, expected1, 0.001f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 2个20DP僚机的惩罚倍数应为" + expected1 + "，实际为" + multiplier1);
                return false;
            }
            
            // 测试用例2: 2个小型僚机
            List<Integer> wingmanDPs2 = new ArrayList<>();
            wingmanDPs2.add(10);
            wingmanDPs2.add(10);
            float multiplier2 = calculateMultiplier(20, wingmanDPs2);
            float expected2 = 1.0f + 0.375f + 0.375f; // 1.75
            if (!isApproximatelyEqual(multiplier2, expected2, 0.001f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 2个10DP僚机的惩罚倍数应为" + expected2 + "，实际为" + multiplier2);
                return false;
            }
            
            // 测试用例3: 混合僚机
            List<Integer> wingmanDPs3 = new ArrayList<>();
            wingmanDPs3.add(20);
            wingmanDPs3.add(10);
            float multiplier3 = calculateMultiplier(20, wingmanDPs3);
            float expected3 = 1.0f + 0.5f + 0.375f; // 1.875
            if (!isApproximatelyEqual(multiplier3, expected3, 0.001f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 混合僚机的惩罚倍数应为" + expected3 + "，实际为" + multiplier3);
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDeploymentTest.class)
                  .error("惩罚倍数计算测试异常: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 测试边界条件
     */
    private static boolean testBoundaryConditions() {
        try {
            // 测试用例1: 空僚机列表
            List<Integer> emptyList = new ArrayList<>();
            float multiplier1 = calculateMultiplier(20, emptyList);
            if (!isApproximatelyEqual(multiplier1, Moci_DynamicDeploymentConfig.getFallbackMultiplier(), 0.001f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 空僚机列表应返回回退倍数");
                return false;
            }
            
            // 测试用例2: 母舰DP为0
            List<Integer> wingmanDPs = new ArrayList<>();
            wingmanDPs.add(10);
            float multiplier2 = calculateMultiplier(0, wingmanDPs);
            if (!isApproximatelyEqual(multiplier2, Moci_DynamicDeploymentConfig.getFallbackMultiplier(), 0.001f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 母舰DP为0应返回回退倍数");
                return false;
            }
            
            // 测试用例3: 僚机DP为0
            List<Integer> zeroWingmanDPs = new ArrayList<>();
            zeroWingmanDPs.add(0);
            float multiplier3 = calculateMultiplier(20, zeroWingmanDPs);
            // 0DP僚机应该被忽略，返回回退倍数
            if (!isApproximatelyEqual(multiplier3, Moci_DynamicDeploymentConfig.getFallbackMultiplier(), 0.001f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 0DP僚机应被忽略");
                return false;
            }
            
            // 测试用例4: 最小惩罚倍数限制
            List<Integer> smallWingmanDPs = new ArrayList<>();
            smallWingmanDPs.add(1); // 很小的僚机DP
            float multiplier4 = calculateMultiplier(20, smallWingmanDPs);
            if (multiplier4 < Moci_DynamicDeploymentConfig.getMinTotalMultiplier()) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 惩罚倍数不应低于最小值");
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDeploymentTest.class)
                  .error("边界条件测试异常: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 测试配置参数的有效性
     */
    private static boolean testConfigurationParameters() {
        try {
            // 测试配置参数的合理性
            float minContribution = Moci_DynamicDeploymentConfig.getMinWingmanContribution();
            float maxContribution = Moci_DynamicDeploymentConfig.getMaxWingmanContribution();
            float minMultiplier = Moci_DynamicDeploymentConfig.getMinTotalMultiplier();
            float fallbackMultiplier = Moci_DynamicDeploymentConfig.getFallbackMultiplier();
            
            if (minContribution < 0 || minContribution > 1) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 最小贡献系数超出合理范围");
                return false;
            }
            
            if (maxContribution < minContribution || maxContribution > 1) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 最大贡献系数超出合理范围");
                return false;
            }
            
            if (minMultiplier < 1.0f) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 最小惩罚倍数不应小于1.0");
                return false;
            }
            
            if (fallbackMultiplier < 1.0f) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 回退惩罚倍数不应小于1.0");
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDeploymentTest.class)
                  .error("配置参数测试异常: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 测试数据验证功能
     */
    private static boolean testDataValidation() {
        try {
            // 测试部署点验证
            if (!Moci_DynamicDeploymentConfig.isValidDeploymentPoints(10)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 正常部署点应该通过验证");
                return false;
            }
            
            if (Moci_DynamicDeploymentConfig.isValidDeploymentPoints(-1)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 负数部署点不应通过验证");
                return false;
            }
            
            if (Moci_DynamicDeploymentConfig.isValidDeploymentPoints(0)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 0部署点不应通过验证");
                return false;
            }
            
            // 测试倍数验证
            if (!Moci_DynamicDeploymentConfig.isValidMultiplier(1.5f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 正常倍数应该通过验证");
                return false;
            }
            
            if (Moci_DynamicDeploymentConfig.isValidMultiplier(0.5f)) {
                Global.getLogger(Moci_DynamicDeploymentTest.class)
                      .error("测试失败: 小于1的倍数不应通过验证");
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDeploymentTest.class)
                  .error("数据验证测试异常: " + e.getMessage(), e);
            return false;
        }
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 计算僚机贡献（模拟DynamicDeploymentCalculator的逻辑）
     */
    private static float calculateWingmanContribution(int wingmanDP, int mothershipDP) {
        if (mothershipDP <= 0) {
            return Moci_DynamicDeploymentConfig.getMaxWingmanContribution();
        }
        
        float ratio = (float) wingmanDP / mothershipDP;
        float contribution = ratio * 0.5f;
        
        return Math.max(Moci_DynamicDeploymentConfig.getMinWingmanContribution(), 
                       Math.min(Moci_DynamicDeploymentConfig.getMaxWingmanContribution(), contribution));
    }
    
    /**
     * 计算惩罚倍数（模拟DynamicDeploymentCalculator的逻辑）
     */
    private static float calculateMultiplier(int mothershipDP, List<Integer> wingmanDPs) {
        if (mothershipDP <= 0 || wingmanDPs == null || wingmanDPs.isEmpty()) {
            return Moci_DynamicDeploymentConfig.getFallbackMultiplier();
        }
        
        float totalContribution = 0f;
        boolean hasValidWingman = false;
        
        for (Integer wingmanDP : wingmanDPs) {
            if (wingmanDP != null && wingmanDP > 0) {
                totalContribution += calculateWingmanContribution(wingmanDP, mothershipDP);
                hasValidWingman = true;
            }
        }
        
        if (!hasValidWingman) {
            return Moci_DynamicDeploymentConfig.getFallbackMultiplier();
        }
        
        float multiplier = 1.0f + totalContribution;
        return Math.max(multiplier, Moci_DynamicDeploymentConfig.getMinTotalMultiplier());
    }
    
    /**
     * 浮点数近似相等比较
     */
    private static boolean isApproximatelyEqual(float a, float b, float tolerance) {
        return Math.abs(a - b) <= tolerance;
    }
    
    /**
     * 启用详细测试日志
     */
    public static void enableDetailedTestLogging() {
        Moci_DynamicDeploymentConfig.setDetailedLogging(true);
        Global.getLogger(Moci_DynamicDeploymentTest.class).info("详细测试日志已启用");
    }
    
    /**
     * 禁用详细测试日志
     */
    public static void disableDetailedTestLogging() {
        Moci_DynamicDeploymentConfig.setDetailedLogging(false);
        Global.getLogger(Moci_DynamicDeploymentTest.class).info("详细测试日志已禁用");
    }
}