package data.campaign.backgrounds;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionSpecAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import exerelin.campaign.backgrounds.BaseCharacterBackground;
import exerelin.utilities.NexFactionConfig;

/**
 * Moci新人类背景
 * 给予玩家Moci_PlayerSkill1技能
 */
public class Moci_NewtypeBackground extends BaseCharacterBackground {

    @Override
    public boolean shouldShowInSelection(FactionSpecAPI factionSpec, NexFactionConfig factionConfig) {
        // 总是显示这个背景选项
        return true;
    }

    @Override
    public void onNewGameAfterTimePass(FactionSpecAPI factionSpec, NexFactionConfig factionConfig) {
        // 在时间流逝后给玩家添加技能
        Global.getSector().getPlayerPerson().getStats().setSkillLevel("Moci_PlayerSkill1", 2);
        
        // 设置内存标记，表示玩家选择了这个背景
        Global.getSector().getMemoryWithoutUpdate().set("$moci_newtype_background", true);
    }

    @Override
    public void addTooltipForSelection(TooltipMakerAPI tooltip, FactionSpecAPI factionSpec, 
                                      NexFactionConfig factionConfig, Boolean expanded) {
        super.addTooltipForSelection(tooltip, factionSpec, factionConfig, expanded);

        tooltip.addSpacer(10f);
        tooltip.addPara("你天生拥有超越常人的感知能力和反应速度。", 0f, 
            Misc.getTextColor(), Misc.getHighlightColor());
        
        tooltip.addSpacer(10f);
        tooltip.addPara("获得技能: %s ", 0f,
            Misc.getTextColor(), Misc.getHighlightColor(), 
            "新人类");
    }

    @Override
    public void addTooltipForIntel(TooltipMakerAPI tooltip, FactionSpecAPI factionSpec, 
                                   NexFactionConfig factionConfig) {
        super.addTooltipForIntel(tooltip, factionSpec, factionConfig);

        Boolean hasBackground = (Boolean) Global.getSector().getMemoryWithoutUpdate().get("$moci_newtype_background");
        
        if (hasBackground != null && hasBackground) {
            tooltip.addSpacer(10f);
            tooltip.addPara("你的新人类能力让你在战场上拥有超乎寻常的洞察力。", 0f,
                Misc.getTextColor(), Misc.getHighlightColor());
        }
    }
}
