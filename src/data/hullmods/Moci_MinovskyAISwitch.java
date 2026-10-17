package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import data.scripts.util.Moci_TextLoader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 米诺夫斯基战术系统AI开关
 * 功能：控制米诺夫斯基战术系统的AI是否自动激活
 * 注意：这是一个空船插，仅用于标记，不提供任何实际效果
 */
public class Moci_MinovskyAISwitch extends BaseHullMod {
    private static final String TEXT_ID = "Moci_MinovskyAISwitch";

    // AI模式循环（当前模式 -> 下一模式）
    private static final Map<String, String> AI_MODE_CYCLE = new HashMap<>();
    static {
        AI_MODE_CYCLE.put("enabled", "disabled");  // 启用 -> 禁用
        AI_MODE_CYCLE.put("disabled", "enabled");  // 禁用 -> 启用
    }
    
    // 记录每个variant的船插状态
    private static final Map<ShipVariantAPI, Set<String>> modRecorder = new HashMap<>();

    /**
     * 舰船创建前处理
     * 检查是否需要切换AI状态
     */
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        if (stats.getEntity() == null) return;
        
        ShipVariantAPI variant = stats.getVariant();
        if (variant == null) return;

        // 检查是否需要切换AI模式（当没有任何模式hullmod存在时触发）
        boolean switchMode = true;
        
        for (String mode : AI_MODE_CYCLE.values()) {
            if (variant.getHullMods().contains("Moci_minovsky_ai_" + mode)) {
                switchMode = false;
                break;
            }
        }

        if (switchMode) {
            // 需要切换模式
            String newMode = "enabled"; // 默认模式
            
            // 检查记录中的上一个状态
            if (modRecorder.containsKey(variant)) {
                // 找出被移除的AI模式船插
                for (String modId : modRecorder.get(variant)) {
                    if (modId.startsWith("Moci_minovsky_ai_")) {
                        // 提取模式名称
                        String lastMode = modId.replace("Moci_minovsky_ai_", "");
                        if (AI_MODE_CYCLE.containsKey(lastMode)) {
                            // 切换到下一个模式
                            newMode = AI_MODE_CYCLE.get(lastMode);
                            break;
                        }
                    }
                }
            }
            
            // 添加对应模式的hullmod
            variant.addMod("Moci_minovsky_ai_" + newMode);
        }
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 保存当前variant的船插状态
        ShipVariantAPI variant = ship.getVariant();
        if (variant != null) {
            modRecorder.put(variant, new HashSet<>(variant.getHullMods()));
        }
    }

    @Override
    public int getDisplayCategoryIndex() {
        return 2;
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        return Moci_TextLoader.getText(TEXT_ID, "description.hidden_marker");
    }
}
