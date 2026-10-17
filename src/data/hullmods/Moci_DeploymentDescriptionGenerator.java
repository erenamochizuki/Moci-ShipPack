package data.hullmods;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * CombatSquad部署点描述生成器
 * 
 * 负责生成动态部署点计算的详细UI描述，包括：
 * 1. 基础部署点惩罚信息
 * 2. 僚机贡献详情
 * 3. 计算过程说明
 * 4. 异常情况提示
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_DeploymentDescriptionGenerator {
    private static final String TEXT_ID = "Moci_CombatSquadDeployment";
    
    /**
     * 添加动态部署点描述到tooltip
     * 
     * @param tooltip 目标tooltip
     * @param ship 舰船对象
     * @param width 宽度
     * @param isForModSpec 是否为改装规格
     */
    public static void addDynamicDeploymentDescription(TooltipMakerAPI tooltip, ShipAPI ship, 
                                                     float width, boolean isForModSpec) {
        if (tooltip == null) {
            return;
        }
        
        float pad = 10f;
        Color highlight = Misc.getHighlightColor();
        Color good = Misc.getPositiveHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();
        
        try {
            // 检查动态部署点系统是否启用
            if (!Moci_DynamicDeploymentConfig.isDynamicDeploymentEnabled()) {
                // 系统禁用时始终显示固定描述
                addFixedPenaltyDescription(tooltip, pad, bad);
                return;
            }
            
            // 检查是否有自定义僚机配置
            boolean hasCustomWingman = hasCustomWingmanConfiguration(ship);
            
            if (hasCustomWingman) {
                // 有自定义僚机时显示详细计算
                Moci_DynamicDeploymentCalculator.CalculationDetails details = 
                    Moci_CombatSquadV2.getDeploymentCalculationDetails(ship);
                
                if (details != null && details.isDynamicCalculation()) {
                    addDynamicCalculationDescription(tooltip, details, pad, highlight, bad);
                } else {
                    // 回退到固定描述
                    addFixedPenaltyDescription(tooltip, pad, bad);
                }
            } else {
                // 无自定义僚机时显示标准描述
                addFixedPenaltyDescription(tooltip, pad, bad);
            }
            
        } catch (Exception e) {
            // 异常情况下显示固定惩罚
            addFixedPenaltyDescription(tooltip, pad, bad);
            addErrorDescription(tooltip, pad, bad);
        }
    }
    
    /**
     * 添加动态计算描述
     */
    private static void addDynamicCalculationDescription(TooltipMakerAPI tooltip, 
                                                       Moci_DynamicDeploymentCalculator.CalculationDetails details,
                                                       float pad, Color highlight, Color bad) {
        // 主要描述 - 直接显示计算结果，不显示"根据僚机配置"的冗余信息
        float multiplier = details.multiplier;
        int penaltyPercent = (int)((multiplier - 1) * 100);
        
        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.fixed_penalty",
                        Moci_TextLoader.mapOf("penalty", String.valueOf(penaltyPercent))),
                pad, bad,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.fixed_penalty_highlights",
                        Moci_TextLoader.mapOf("penalty", String.valueOf(penaltyPercent))).toArray(new String[0]));
        
        // 添加详细计算信息
        if (!details.wingmanDPs.isEmpty()) {
            addCalculationDetails(tooltip, details, pad * 0.5f, highlight);
        }
        
        // 添加计算说明
        addCalculationExplanation(tooltip, pad * 0.5f, highlight);
    }
    
    /**
     * 添加固定惩罚描述
     */
    private static void addFixedPenaltyDescription(TooltipMakerAPI tooltip, float pad, Color bad) {
        int penaltyPercent = (int)((Moci_CombatSquadV2.DEPLOYMENT_COST_MULT - 1) * 100);
        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.fixed_penalty",
                        Moci_TextLoader.mapOf("penalty", String.valueOf(penaltyPercent))),
                pad, bad,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.fixed_penalty_highlights",
                        Moci_TextLoader.mapOf("penalty", String.valueOf(penaltyPercent))).toArray(new String[0]));
    }
    
    /**
     * 添加计算详情
     */
    private static void addCalculationDetails(TooltipMakerAPI tooltip, 
                                            Moci_DynamicDeploymentCalculator.CalculationDetails details,
                                            float pad, Color highlight) {
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.details_heading"), pad, highlight);
        
        StringBuilder detailsText = new StringBuilder();
        List<Integer> wingmanDPs = details.wingmanDPs;
        List<Float> contributions = details.wingmanContributions;
        
        for (int i = 0; i < Math.min(wingmanDPs.size(), contributions.size()); i++) {
            int wingmanDP = wingmanDPs.get(i);
            float contribution = contributions.get(i);
            int contributionPercent = (int)(contribution * 100);
            
            detailsText.append(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.details_line",
                    Moci_TextLoader.mapOf("index", String.valueOf(i + 1),
                            "dp", String.valueOf(wingmanDP),
                            "contribution", String.valueOf(contributionPercent))));
            
            if (i < Math.min(wingmanDPs.size(), contributions.size()) - 1) {
                detailsText.append("\n");
            }
        }
        
        if (detailsText.length() > 0) {
            tooltip.addPara(detailsText.toString(), pad, highlight);
        }
        
        // 显示总计
        float totalContribution = details.getTotalContribution();
        int totalPercent = (int)(totalContribution * 100);
        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.details_total",
                        Moci_TextLoader.mapOf("total", String.valueOf(totalPercent))),
                pad, highlight,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.details_total_highlights",
                        Moci_TextLoader.mapOf("total", String.valueOf(totalPercent))).toArray(new String[0]));
    }
    
    /**
     * 添加计算说明
     */
    private static void addCalculationExplanation(TooltipMakerAPI tooltip, float pad, Color highlight) {
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.explanation"),
                pad, highlight,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.explanation_highlights").toArray(new String[0]));
    }
    
    /**
     * 添加错误描述
     */
    private static void addErrorDescription(TooltipMakerAPI tooltip, float pad, Color bad) {
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.error"), pad, bad);
    }
    
    /**
     * 生成简化的部署点描述（用于简短显示）
     * 
     * @param ship 舰船对象
     * @return 描述字符串
     */
    public static String generateShortDescription(ShipAPI ship) {
        try {
            float multiplier = Moci_CombatSquadV2.getCurrentDeploymentMultiplier(ship);
            int penaltyPercent = (int)((multiplier - 1) * 100);
            return "+" + penaltyPercent + "%";
            
        } catch (Exception e) {
            int defaultPercent = (int)((Moci_CombatSquadV2.DEPLOYMENT_COST_MULT - 1) * 100);
            return "+" + defaultPercent + "%";
        }
    }
    
    /**
     * 生成详细的计算过程字符串（用于调试）
     * 
     * @param ship 舰船对象
     * @return 详细计算过程
     */
    public static String generateDetailedCalculation(ShipAPI ship) {
        if (ship == null) {
            return "Ship is null";
        }
        
        try {
            Moci_DynamicDeploymentCalculator.CalculationDetails details = 
                Moci_CombatSquadV2.getDeploymentCalculationDetails(ship);
            
            if (details == null) {
                return "No calculation details available";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("=== 动态部署点计算详情 ===\n");
            result.append("母舰部署点: ").append(details.mothershipDP).append("\n");
            result.append("最终倍数: ").append(String.format("%.2f", details.multiplier)).append("\n");
            
            if (details.isDynamicCalculation()) {
                result.append("使用动态计算\n");
                result.append("僚机详情:\n");
                
                for (int i = 0; i < Math.min(details.wingmanDPs.size(), details.wingmanContributions.size()); i++) {
                    int wingmanDP = details.wingmanDPs.get(i);
                    float contribution = details.wingmanContributions.get(i);
                    result.append(String.format("  僚机%d: %dDP → %.3f贡献\n", 
                        i + 1, wingmanDP, contribution));
                }
                
                result.append("总贡献: ").append(String.format("%.3f", details.getTotalContribution())).append("\n");
            } else {
                result.append("使用固定惩罚\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            return Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.detail_fail",
                    Moci_TextLoader.mapOf("message", e.getMessage()));
        }
    }
    
    /**
     * 检查舰船是否有自定义僚机配置
     * 
     * @param ship 舰船对象
     * @return 是否有自定义僚机配置
     */
    private static boolean hasCustomWingmanConfiguration(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        
        try {
            // 检查是否有军官
            if (ship.getCaptain() == null || ship.getCaptain().isDefault()) {
                return false;
            }
            
            String commanderId = ship.getCaptain().getId();
            
            // 检查是否有自定义僚机配置
            int squadSize = Moci_PilotManager.getSquadSize(commanderId);
            if (squadSize <= 0) {
                return false;
            }
            
            // 检查是否至少有一个自定义僚机
            for (int i = 0; i < squadSize; i++) {
                Moci_CustomWingmanManager.CustomWingmanShip customShip = 
                    Moci_CustomWingmanManager.getCustomWingmanShip(commanderId, i);
                if (customShip != null) {
                    return true; // 找到至少一个自定义僚机
                }
            }
            
            return false; // 没有找到自定义僚机
            
        } catch (Exception e) {
            // 检查失败时认为没有自定义配置
            return false;
        }
    }
    
    /**
     * 验证描述生成的输入参数
     */
    private static boolean validateInputs(TooltipMakerAPI tooltip, ShipAPI ship) {
        return tooltip != null && ship != null;
    }
}
