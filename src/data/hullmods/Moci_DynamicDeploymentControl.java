package data.hullmods;

import com.fs.starfarer.api.Global;

/**
 * CombatSquad动态部署点系统控制接口
 * 
 * 提供简单的开关接口来控制动态部署点系统的启用/禁用
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_DynamicDeploymentControl {
    
    /**
     * 启用动态部署点系统
     * 
     * 使用方法：在控制台中调用 Moci_DynamicDeploymentControl.enable()
     */
    public static void enable() {
        Moci_DynamicDeploymentConfig.setDynamicDeployment(true);
        
        // 清空缓存以确保立即生效
        Moci_DeploymentCalculationCache.clearAllCache();
        
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("动态部署点系统已启用");
    }
    
    /**
     * 禁用动态部署点系统
     * 
     * 使用方法：在控制台中调用 Moci_DynamicDeploymentControl.disable()
     */
    public static void disable() {
        Moci_DynamicDeploymentConfig.setDynamicDeployment(false);
        
        // 清空缓存以确保立即生效
        Moci_DeploymentCalculationCache.clearAllCache();
        
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("动态部署点系统已禁用，将使用固定1.5倍惩罚");
    }
    
    /**
     * 检查动态部署点系统状态
     * 
     * 使用方法：在控制台中调用 Moci_DynamicDeploymentControl.status()
     */
    public static void status() {
        boolean enabled = Moci_DynamicDeploymentConfig.isDynamicDeploymentEnabled();
        
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("动态部署点系统状态: " + (enabled ? "启用" : "禁用"));
        
        if (enabled) {
            Global.getLogger(Moci_DynamicDeploymentControl.class)
                  .info("系统将根据僚机配置动态计算部署点消耗");
        } else {
            Global.getLogger(Moci_DynamicDeploymentControl.class)
                  .info("系统将始终使用固定的1.5倍部署点惩罚");
        }
        
        // 显示缓存统计
        Moci_DeploymentCalculationCache.CacheStatistics stats = 
            Moci_DeploymentCalculationCache.getStatistics();
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("缓存统计: " + stats.toString());
    }
    
    /**
     * 重置系统到默认状态
     * 
     * 使用方法：在控制台中调用 Moci_DynamicDeploymentControl.reset()
     */
    public static void reset() {
        Moci_DynamicDeploymentConfig.resetToDefaults();
        Moci_DeploymentCalculationCache.clearAllCache();
        Moci_DeploymentCalculationCache.resetStatistics();
        
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("动态部署点系统已重置为默认状态");
    }
    
    /**
     * 启用调试模式
     * 
     * 使用方法：在控制台中调用 Moci_DynamicDeploymentControl.enableDebug()
     */
    public static void enableDebug() {
        Moci_DynamicDeploymentConfig.setDetailedLogging(true);
        Moci_DynamicDeploymentConfig.setPerformanceMonitoring(true);
        
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("动态部署点系统调试模式已启用");
    }
    
    /**
     * 禁用调试模式
     * 
     * 使用方法：在控制台中调用 Moci_DynamicDeploymentControl.disableDebug()
     */
    public static void disableDebug() {
        Moci_DynamicDeploymentConfig.setDetailedLogging(false);
        Moci_DynamicDeploymentConfig.setPerformanceMonitoring(false);
        
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("动态部署点系统调试模式已禁用");
    }
    
    /**
     * 运行系统测试
     * 
     * 使用方法：在控制台中调用 Moci_DynamicDeploymentControl.test()
     */
    public static void test() {
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("开始运行动态部署点系统测试...");
        
        try {
            // 运行基础功能测试
            testBasicFunctionality();
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDeploymentControl.class)
                  .error("测试运行失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 基础功能测试
     */
    private static void testBasicFunctionality() {
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("执行基础功能测试...");
        
        // 测试配置系统
        float minContribution = Moci_DynamicDeploymentConfig.getMinWingmanContribution();
        if (minContribution <= 0) {
            Global.getLogger(Moci_DynamicDeploymentControl.class)
                  .error("配置系统测试失败: 最小贡献系数无效");
            return;
        }
        
        // 测试计算系统
        float result = Moci_DynamicDeploymentCalculator.calculateDeploymentMultiplier(null);
        if (!Moci_DynamicDeploymentConfig.isValidMultiplier(result)) {
            Global.getLogger(Moci_DynamicDeploymentControl.class)
                  .error("计算系统测试失败: 计算结果无效");
            return;
        }
        
        // 测试缓存系统
        Moci_DeploymentCalculationCache.CacheStatistics stats = 
            Moci_DeploymentCalculationCache.getStatistics();
        if (stats == null) {
            Global.getLogger(Moci_DynamicDeploymentControl.class)
                  .error("缓存系统测试失败: 无法获取统计信息");
            return;
        }
        
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("✓ 基础功能测试通过");
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("  - 配置系统: 正常");
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("  - 计算系统: 正常");
        Global.getLogger(Moci_DynamicDeploymentControl.class)
              .info("  - 缓存系统: 正常");
    }
}