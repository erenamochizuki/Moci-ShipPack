package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

public class Moci_CombatSquadUI {
    
    private static final Color HEADER_COLOR = new Color(100, 165, 255);
    private static final Color TEXT_COLOR = Misc.getTextColor();
    private static final Color POSITIVE_COLOR = Misc.getPositiveHighlightColor();
    private static final Color NEGATIVE_COLOR = Misc.getNegativeHighlightColor();
    private static final Color HIGHLIGHT_COLOR = Misc.getHighlightColor();
    private static final Color GRAY_COLOR = Misc.getGrayColor();

    private static String text(String key, String fallback) {
        return Moci_CombatSquadTextRegistry.getText(key, fallback);
    }
    
    /**
     * 为船插添加CombatSquad机师系统的详细描述信息
     * 在船插的addPostDescriptionSection中调用此方法
     */
    public static void addCombatSquadDescription(TooltipMakerAPI tooltip, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 10f;
        float smallPad = 3f;
        
        // 获取机师系统状态
        boolean systemEnabled = Moci_CombatSquadPilotIntegration.isPilotSystemEnabled();
        boolean useDynamicPilots = Moci_CombatSquadPilotConfig.useDynamicPilots();
        
        // 标题
        tooltip.addSectionHeading(text("hullmod_ui_heading", "=== CombatSquad 机师系统 ==="), Alignment.MID, pad);
        
        if (!systemEnabled) {
            tooltip.addPara(text("hullmod_ui_disabled", "机师系统当前已禁用。CombatSquad僚机将没有专属机师。"), 
                    pad, NEGATIVE_COLOR);
            tooltip.addPara(text("hullmod_ui_enable_hint", "可通过LunaLib设置或配置代码启用机师系统。"), 
                    smallPad, GRAY_COLOR);
            return;
        }
        
        // 系统模式说明
        if (useDynamicPilots) {
            tooltip.addPara(text("hullmod_ui_mode", "当前模式: %s"), pad, POSITIVE_COLOR,
                    text("hullmod_ui_mode_dynamic_highlight", "动态机师模式"));
            tooltip.addPara(text("hullmod_ui_mode_dynamic_line1", "- 机师会在战斗中获得经验并升级"), smallPad, TEXT_COLOR);
            tooltip.addPara(text("hullmod_ui_mode_dynamic_line2", "- 指挥官技能影响整个编队表现"), smallPad, TEXT_COLOR);
        } else {
            tooltip.addPara(text("hullmod_ui_mode", "当前模式: %s"), pad, HIGHLIGHT_COLOR,
                    text("hullmod_ui_mode_fixed_highlight", "固定军官模式"));
            tooltip.addPara(text("hullmod_ui_mode_fixed_line1", "- 使用预设的固定军官配置"), smallPad, TEXT_COLOR);
    }
    
        // 如果有舰船实例，显示详细信息
        if (ship != null && ship.getCaptain() != null && !ship.getCaptain().isDefault()) {
            addSquadDetailedInfo(tooltip, ship, useDynamicPilots, pad, smallPad);
        } else {
            tooltip.addPara("", pad);
            tooltip.addPara(text("hullmod_ui_need_commander", "需要为舰船分配指挥官才能建立机师编队。"), 
                    smallPad, GRAY_COLOR);
        }
        
        // 系统说明
        addSystemExplanation(tooltip, pad, smallPad);
    }
    
    /**
     * 添加编队详细信息
     */
    private static void addSquadDetailedInfo(TooltipMakerAPI tooltip, ShipAPI ship, 
                                           boolean useDynamicPilots, float pad, float smallPad) {
        PersonAPI commander = ship.getCaptain();
        String commanderId = commander.getId();
        boolean persistent = Global.getSector() != null;
        
        if (useDynamicPilots) {
            // 动态机师模式的详细信息
            int squadSize = Moci_PilotManager.getCurrentSquadSize(commanderId, persistent);
            if (squadSize > 0) {
                String squadName = Moci_PilotManager.getSquadName(commanderId, persistent);
                String commanderRating = Moci_CommanderEffects.getCommanderRating(commander);
                
                tooltip.addPara("", pad);
                tooltip.addPara(text("hullmod_ui_squad_info", "编队信息:"), smallPad, HEADER_COLOR);
                tooltip.addPara(text("hullmod_ui_squad_name", "编队名称: %s"), smallPad, 
                        new Color[]{POSITIVE_COLOR}, squadName);
                tooltip.addPara(text("hullmod_ui_commander", "指挥官: %s (%s)"), smallPad, 
                        new Color[]{HIGHLIGHT_COLOR, POSITIVE_COLOR}, 
                        commander.getNameString(), commanderRating);
                tooltip.addPara(text("hullmod_ui_squad_size", "编队规模: %s 机"), smallPad, 
                        HIGHLIGHT_COLOR, String.valueOf(squadSize));
                
                // 显示机师统计
                addPilotStatistics(tooltip, commanderId, squadSize, persistent, smallPad);
            } else {
                tooltip.addPara("", pad);
                tooltip.addPara(text("hullmod_ui_squad_unformed", "尚未建立机师编队。战斗开始时将自动创建。"), 
                        smallPad, GRAY_COLOR);
            }
        } else {
            // 固定军官模式的说明
            tooltip.addPara("", pad);
            tooltip.addPara(text("hullmod_ui_fixed_hint", "使用固定军官配置，CombatSquad僚机将配备标准军官。"), 
                    smallPad, TEXT_COLOR);
        }
    }
    
    /**
     * 添加机师统计信息
     */
    private static void addPilotStatistics(TooltipMakerAPI tooltip, String commanderId, 
                                         int squadSize, boolean persistent, float smallPad) {
        float totalExperience = 0f;
        int totalLevel = 0;
        int elitePilots = 0;
        
        for (int i = 0; i < squadSize; i++) {
            PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, i, persistent);
            if (pilot != null) {
                totalLevel += pilot.getStats().getLevel();
                totalExperience += Moci_PilotManager.getExperience(commanderId, i, persistent);
                if (pilot.getStats().getLevel() >= 5) {
                    elitePilots++;
                }
            }
        }
        
        float avgLevel = squadSize > 0 ? (float) totalLevel / squadSize : 0f;
        
        tooltip.addPara(text("hullmod_ui_pilot_stats", "机师统计:"), smallPad, HEADER_COLOR);
        tooltip.addPara(text("hullmod_ui_avg_level", "平均等级: %s"), smallPad, 
                HIGHLIGHT_COLOR, String.format("%.1f", avgLevel));
        tooltip.addPara(text("hullmod_ui_elite_pilots", "精英机师: %s/%s"), smallPad, 
                elitePilots >= squadSize/2 ? POSITIVE_COLOR : TEXT_COLOR, 
                String.valueOf(elitePilots), String.valueOf(squadSize));
        tooltip.addPara(text("hullmod_ui_total_exp", "总经验值: %s"), smallPad, 
                GRAY_COLOR, String.format("%.0f", totalExperience));
    }
    
    /**
     * 添加系统说明
     */
    private static void addSystemExplanation(TooltipMakerAPI tooltip, float pad, float smallPad) {
        tooltip.addSectionHeading(text("hullmod_ui_system_explain", "系统说明"), Alignment.MID, smallPad);
        tooltip.addPara(text("hullmod_ui_explain_1", "- 可在LunaLib设置中切换机师模式"), smallPad, TEXT_COLOR);
        tooltip.addPara(text("hullmod_ui_explain_2", "- 详细编队管理请查看 %s"), smallPad,
                new Color[]{HIGHLIGHT_COLOR}, text("hullmod_ui_explain_2_highlight", "情报界面的CombatSquad管理"));
    }
    
    /**
     * 创建简化的编队信息工具提示（用于其他界面）
     */
    public static void addSquadTooltip(TooltipMakerAPI tooltip, FleetMemberAPI fleetMember) {
        if (fleetMember == null) return;
        
        PersonAPI commander = fleetMember.getCaptain();
        if (commander == null || commander.isDefault()) return;
        
        if (!Moci_CombatSquadPilotIntegration.isPilotSystemEnabled()) {
            tooltip.addPara(text("hullmod_ui_tooltip_disabled", "CombatSquad机师系统: %s"), 3f, NEGATIVE_COLOR,
                    text("hullmod_ui_tooltip_disabled_highlight", "已禁用"));
            return;
        }
        
        boolean persistent = Global.getSector() != null;
        String commanderId = commander.getId();
        int squadSize = Moci_PilotManager.getCurrentSquadSize(commanderId, persistent);
        
        if (squadSize <= 0) {
            tooltip.addPara(text("hullmod_ui_tooltip_unformed", "CombatSquad编队: %s"), 3f, GRAY_COLOR,
                    text("hullmod_ui_tooltip_unformed_highlight", "未建立"));
            return;
        }
        
        String squadName = Moci_PilotManager.getSquadName(commanderId, persistent);
        String commanderRating = Moci_CommanderEffects.getCommanderRating(commander);
        
        tooltip.addPara("", 5f);
        tooltip.addPara(text("hullmod_ui_tooltip_title", "CombatSquad编队: %s"), 3f, HEADER_COLOR, squadName);
        tooltip.addPara(text("hullmod_ui_tooltip_commander", "指挥官: %s (%s)"), 3f, TEXT_COLOR, 
                commander.getNameString(), commanderRating);
        tooltip.addPara(text("hullmod_ui_tooltip_size", "编队规模: %s 机"), 3f, TEXT_COLOR, String.valueOf(squadSize));
        
        // 机师简要信息
        StringBuilder pilotSummary = new StringBuilder(text("hullmod_ui_pilot_prefix", "机师: "));
        for (int i = 0; i < Math.min(squadSize, 3); i++) { // 最多显示3个
            PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, i, persistent);
            if (pilot != null) {
                String callsign = Moci_PilotManager.getCallsign(commanderId, i, persistent);
                int level = pilot.getStats().getLevel();
                if (i > 0) pilotSummary.append(", ");
                pilotSummary.append(callsign).append("(L").append(level).append(")");
            }
        }
        if (squadSize > 3) {
            pilotSummary.append("...");
        }
        
        tooltip.addPara(pilotSummary.toString(), 3f, TEXT_COLOR);
    }
    
    /**
     * 获取机师系统状态描述
     */
    public static String getSystemStatusDescription() {
        if (!Moci_CombatSquadPilotIntegration.isPilotSystemEnabled()) {
            return text("hullmod_ui_status_disabled", "机师系统已禁用");
        }
        
        if (Moci_CombatSquadPilotConfig.useDynamicPilots()) {
            return text("hullmod_ui_status_dynamic", "动态机师模式");
        } else {
            return text("hullmod_ui_status_fixed", "固定军官模式");
        }
    }
    
    /**
     * 获取编队简要统计信息
     */
    public static String getSquadBriefStats(FleetMemberAPI fleetMember) {
        if (fleetMember == null) return text("hullmod_ui_brief_no_ship", "无舰船信息");
        
        PersonAPI commander = fleetMember.getCaptain();
        if (commander == null || commander.isDefault()) {
            return text("hullmod_ui_brief_no_commander", "无指挥官");
        }
        
        if (!Moci_CombatSquadPilotIntegration.isPilotSystemEnabled()) {
            return text("hullmod_ui_status_disabled", "机师系统已禁用");
        }
        
        boolean persistent = Global.getSector() != null;
        String commanderId = commander.getId();
        int squadSize = Moci_PilotManager.getCurrentSquadSize(commanderId, persistent);
        
        if (squadSize <= 0) {
            return text("hullmod_ui_brief_unformed", "未建立编队");
        }
        
        String squadName = Moci_PilotManager.getSquadName(commanderId, persistent);
        return Moci_CombatSquadTextRegistry.formatTemplate(
                text("hullmod_ui_brief_format", "{name} ({count}机)"),
                Moci_CombatSquadTextRegistry.mapOf("name", squadName, "count", String.valueOf(squadSize)));
    }
} 
