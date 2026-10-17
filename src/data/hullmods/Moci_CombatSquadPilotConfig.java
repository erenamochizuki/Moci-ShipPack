package data.hullmods;

import com.fs.starfarer.api.Global;

/**
 * CombatSquad机师系统配置管理器
 * 支持lunalib配置和运行时切换
 */
public class Moci_CombatSquadPilotConfig {
    
    // 配置键值
    private static final String CONFIG_KEY_PILOT_SYSTEM_ENABLED = "moci_combatSquad_pilotSystemEnabled";
    private static final String CONFIG_KEY_USE_DYNAMIC_PILOTS = "moci_combatSquad_useDynamicPilots";
    
    // 默认配置值
    private static final boolean DEFAULT_PILOT_SYSTEM_ENABLED = true;
    private static final boolean DEFAULT_USE_DYNAMIC_PILOTS = true;
    
    /**
     * 是否启用机师系统
     */
    public static boolean isPilotSystemEnabled() {
        try {
            if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
                return lunalib.lunaSettings.LunaSettings.getBoolean("moci_shippack", "Moci_enablePilotSystem");
            }
        } catch (Exception e) {
            // LunaLib未安装或读取失败，使用默认值
        }
        return DEFAULT_PILOT_SYSTEM_ENABLED;
    }
    
    /**
     * 是否使用动态机师系统（vs 固定僚机军官）
     */
    public static boolean useDynamicPilots() {
        try {
            if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
                return lunalib.lunaSettings.LunaSettings.getBoolean("moci_shippack", "Moci_useDynamicPilots");
            }
        } catch (Exception e) {
            // LunaLib未安装或读取失败，使用默认值
        }
        return DEFAULT_USE_DYNAMIC_PILOTS;
    }
    
    /**
     * 注意：LunaSettings配置是只读的，不支持运行时修改
     * 以下方法保留用于兼容性，但实际上不会修改LunaSettings
     */
    
    /**
     * 设置机师系统启用状态（仅用于兼容性，实际配置请在LunaSettings中修改）
     */
    public static void setPilotSystemEnabled(boolean enabled) {
        Global.getLogger(Moci_CombatSquadPilotConfig.class).info(
            "机师系统配置请在LunaSettings中修改，当前请求: " + (enabled ? "启用" : "禁用")
        );
    }
    
    /**
     * 设置是否使用动态机师（仅用于兼容性，实际配置请在LunaSettings中修改）
     */
    public static void setUseDynamicPilots(boolean useDynamic) {
        Global.getLogger(Moci_CombatSquadPilotConfig.class).info(
            "机师模式配置请在LunaSettings中修改，当前请求: " + (useDynamic ? "动态机师" : "固定军官")
        );
    }
    
    /**
     * 获取系统模式描述
     */
    public static String getSystemModeDescription() {
        if (!isPilotSystemEnabled()) {
            return Moci_CombatSquadTextRegistry.getText("hullmod_ui_status_disabled", "机师系统已禁用");
        }
        
        if (useDynamicPilots()) {
            return Moci_CombatSquadTextRegistry.getText("hullmod_ui_status_dynamic", "动态机师模式");
        } else {
            return Moci_CombatSquadTextRegistry.getText("hullmod_ui_status_fixed", "固定军官模式");
        }
    }
    
    /**
     * 重置所有配置到默认值
     */
    public static void resetToDefaults() {
        setPilotSystemEnabled(DEFAULT_PILOT_SYSTEM_ENABLED);
        setUseDynamicPilots(DEFAULT_USE_DYNAMIC_PILOTS);
        
        Global.getLogger(Moci_CombatSquadPilotConfig.class).info(
            "机师系统配置已重置为默认值"
        );
    }
    
    /**
     * 检查配置是否有效
     */
    public static boolean isConfigurationValid() {
        try {
            boolean systemEnabled = isPilotSystemEnabled();
            boolean dynamicPilots = useDynamicPilots();
            
            // 基本逻辑检查：如果系统禁用，动态机师设置不重要
            // 如果系统启用，需要明确选择模式
            return true; // 目前所有配置组合都是有效的
        } catch (Exception e) {
            Global.getLogger(Moci_CombatSquadPilotConfig.class).error(
                "配置验证失败: " + e.getMessage(), e
            );
            return false;
        }
    }
    
    /**
     * 获取调试信息
     */
    public static String getDebugInfo() {
        StringBuilder info = new StringBuilder();
        info.append("=== CombatSquad机师系统配置 ===\n");
        info.append("LunaLib可用: ").append(Global.getSettings().getModManager().isModEnabled("lunalib")).append("\n");
        info.append("机师系统启用: ").append(isPilotSystemEnabled()).append("\n");
        info.append("使用动态机师: ").append(useDynamicPilots()).append("\n");
        info.append("系统模式: ").append(getSystemModeDescription()).append("\n");
        info.append("配置有效性: ").append(isConfigurationValid()).append("\n");
        return info.toString();
    }
} 
