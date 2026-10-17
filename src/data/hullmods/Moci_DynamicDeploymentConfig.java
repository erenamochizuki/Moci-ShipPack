package data.hullmods;

import com.fs.starfarer.api.Global;

/**
 * CombatSquad动态部署点配置管理器
 * 
 * 管理动态部署点计算系统的所有可配置参数，包括：
 * 1. 僚机贡献系数的范围
 * 2. 最小和回退惩罚倍数
 * 3. 调试和日志选项
 * 4. 为LunaLib集成预留接口
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_DynamicDeploymentConfig {
    
    // ===========================================
    // 核心计算参数
    // ===========================================
    
    /**
     * 最小僚机贡献系数
     * 即使僚机部署点很低，也至少贡献这个比例的惩罚
     */
    public static final float MIN_WINGMAN_CONTRIBUTION = 0.375f;
    
    /**
     * 最大僚机贡献系数
     * 僚机部署点等于或超过母舰时的贡献系数
     */
    public static final float MAX_WINGMAN_CONTRIBUTION = 0.5f;
    
    /**
     * 最小总惩罚倍数
     * 确保动态计算的结果不会低于这个值，保持与原系统的兼容性
     */
    public static final float MIN_TOTAL_MULTIPLIER = 1.5f;
    
    /**
     * 默认回退惩罚倍数
     * 当动态计算失败或无法获取数据时使用的固定惩罚
     */
    public static final float FALLBACK_MULTIPLIER = 1.5f;
    
    // ===========================================
    // 数据获取参数
    // ===========================================
    
    /**
     * 默认僚机部署点
     * 当无法从变体获取部署点信息时使用的默认值
     */
    public static final int DEFAULT_WINGMAN_DP = 10;
    
    /**
     * 默认母舰部署点
     * 当无法获取母舰部署点时使用的默认值
     */
    public static final int DEFAULT_MOTHERSHIP_DP = 20;
    
    /**
     * 最大合理部署点
     * 用于验证部署点数据的合理性上限
     */
    public static final int MAX_REASONABLE_DP = 1000;
    
    // ===========================================
    // 调试和日志参数
    // ===========================================
    
    /**
     * 是否启用详细日志
     * 开启后会记录详细的计算过程和调试信息
     */
    private static boolean enableDetailedLogging = false;
    
    /**
     * 是否启用性能监控
     * 开启后会记录计算耗时和性能数据
     */
    private static boolean enablePerformanceMonitoring = false;
    
    /**
     * 是否启用数据验证
     * 开启后会进行更严格的数据验证
     */
    private static boolean enableDataValidation = true;
    
    /**
     * 是否启用动态部署点系统
     * 关闭后将始终使用固定的1.5倍惩罚
     */
    private static boolean enableDynamicDeployment = true;
    
    // ===========================================
    // 配置访问方法
    // ===========================================
    
    /**
     * 获取最小僚机贡献系数
     */
    public static float getMinWingmanContribution() {
        return MIN_WINGMAN_CONTRIBUTION;
    }
    
    /**
     * 获取最大僚机贡献系数
     */
    public static float getMaxWingmanContribution() {
        return MAX_WINGMAN_CONTRIBUTION;
    }
    
    /**
     * 获取最小总惩罚倍数
     */
    public static float getMinTotalMultiplier() {
        return MIN_TOTAL_MULTIPLIER;
    }
    
    /**
     * 获取回退惩罚倍数
     */
    public static float getFallbackMultiplier() {
        return FALLBACK_MULTIPLIER;
    }
    
    /**
     * 获取默认僚机部署点
     */
    public static int getDefaultWingmanDP() {
        return DEFAULT_WINGMAN_DP;
    }
    
    /**
     * 获取默认母舰部署点
     */
    public static int getDefaultMothershipDP() {
        return DEFAULT_MOTHERSHIP_DP;
    }
    
    /**
     * 获取最大合理部署点
     */
    public static int getMaxReasonableDP() {
        return MAX_REASONABLE_DP;
    }
    
    // ===========================================
    // 调试选项访问方法
    // ===========================================
    
    /**
     * 是否启用详细日志
     */
    public static boolean isDetailedLoggingEnabled() {
        return enableDetailedLogging;
    }
    
    /**
     * 设置详细日志开关
     */
    public static void setDetailedLogging(boolean enabled) {
        enableDetailedLogging = enabled;
        if (enabled) {
            Global.getLogger(Moci_DynamicDeploymentConfig.class)
                  .info("动态部署点详细日志已启用");
        }
    }
    
    /**
     * 是否启用性能监控
     */
    public static boolean isPerformanceMonitoringEnabled() {
        return enablePerformanceMonitoring;
    }
    
    /**
     * 设置性能监控开关
     */
    public static void setPerformanceMonitoring(boolean enabled) {
        enablePerformanceMonitoring = enabled;
        if (enabled) {
            Global.getLogger(Moci_DynamicDeploymentConfig.class)
                  .info("动态部署点性能监控已启用");
        }
    }
    
    /**
     * 是否启用数据验证
     */
    public static boolean isDataValidationEnabled() {
        return enableDataValidation;
    }
    
    /**
     * 设置数据验证开关
     */
    public static void setDataValidation(boolean enabled) {
        enableDataValidation = enabled;
    }
    
    /**
     * 是否启用动态部署点系统
     */
    public static boolean isDynamicDeploymentEnabled() {
        return enableDynamicDeployment;
    }
    
    /**
     * 设置动态部署点系统开关
     */
    public static void setDynamicDeployment(boolean enabled) {
        enableDynamicDeployment = enabled;
        if (enabled) {
            Global.getLogger(Moci_DynamicDeploymentConfig.class)
                  .info("动态部署点系统已启用");
        } else {
            Global.getLogger(Moci_DynamicDeploymentConfig.class)
                  .info("动态部署点系统已禁用，将使用固定1.5倍惩罚");
        }
    }
    
    // ===========================================
    // 参数验证方法
    // ===========================================
    
    /**
     * 验证僚机贡献系数是否在合理范围内
     */
    public static boolean isValidWingmanContribution(float contribution) {
        return contribution >= 0f && contribution <= 1.0f;
    }
    
    /**
     * 验证部署点倍数是否合理
     */
    public static boolean isValidMultiplier(float multiplier) {
        return multiplier >= 1.0f && multiplier <= 10.0f; // 设置一个合理的上限
    }
    
    /**
     * 验证部署点数量是否合理
     */
    public static boolean isValidDeploymentPoints(int deploymentPoints) {
        return deploymentPoints > 0 && deploymentPoints <= MAX_REASONABLE_DP;
    }
    
    // ===========================================
    // LunaLib集成预留接口
    // ===========================================
    
    /**
     * 从LunaLib加载配置（预留接口）
     * 
     * 注意：当前版本暂未实现LunaLib集成，此方法为后续扩展预留
     */
    public static void loadFromLunaLib() {
        // TODO: 实现LunaLib配置加载
        // 可以在这里添加从LunaLib读取配置的逻辑
        
        try {
            // 示例代码（需要LunaLib支持）:
            // LunaSettings settings = LunaSettings.getSettings();
            // enableDetailedLogging = settings.getBoolean("moci_combatSquad_detailedLogging");
            // enablePerformanceMonitoring = settings.getBoolean("moci_combatSquad_performanceMonitoring");
            
        } catch (Exception e) {
            Global.getLogger(Moci_DynamicDeploymentConfig.class)
                  .warn("从LunaLib加载配置失败: " + e.getMessage());
        }
    }
    
    /**
     * 保存配置到LunaLib（预留接口）
     */
    public static void saveToLunaLib() {
        // TODO: 实现LunaLib配置保存
        // 可以在这里添加向LunaLib保存配置的逻辑
    }
    
    // ===========================================
    // 配置重置和默认值
    // ===========================================
    
    /**
     * 重置所有配置到默认值
     */
    public static void resetToDefaults() {
        enableDetailedLogging = false;
        enablePerformanceMonitoring = false;
        enableDataValidation = true;
        enableDynamicDeployment = true;
        
        Global.getLogger(Moci_DynamicDeploymentConfig.class)
              .info("动态部署点配置已重置为默认值");
    }
    
    /**
     * 获取配置摘要信息
     */
    public static String getConfigSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== 动态部署点配置摘要 ===\n");
        summary.append("最小僚机贡献: ").append(MIN_WINGMAN_CONTRIBUTION).append("\n");
        summary.append("最大僚机贡献: ").append(MAX_WINGMAN_CONTRIBUTION).append("\n");
        summary.append("最小总惩罚: ").append(MIN_TOTAL_MULTIPLIER).append("\n");
        summary.append("回退惩罚: ").append(FALLBACK_MULTIPLIER).append("\n");
        summary.append("动态部署点: ").append(enableDynamicDeployment ? "启用" : "禁用").append("\n");
        summary.append("详细日志: ").append(enableDetailedLogging ? "启用" : "禁用").append("\n");
        summary.append("性能监控: ").append(enablePerformanceMonitoring ? "启用" : "禁用").append("\n");
        summary.append("数据验证: ").append(enableDataValidation ? "启用" : "禁用").append("\n");
        return summary.toString();
    }
}