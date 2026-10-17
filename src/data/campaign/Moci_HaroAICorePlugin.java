package data.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.AICoreOfficerPlugin;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.BaseAICoreOfficerPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;

import java.awt.Color;
import java.util.Random;

/**
 * 哈罗AI核心插件实现
 * 一个友好的AI核心，具有出色的战术协调能力
 */
public class Moci_HaroAICorePlugin extends BaseAICoreOfficerPluginImpl implements AICoreOfficerPlugin {

    @Override
    public PersonAPI createPerson(String aiCoreId, String factionId, Random random) {
        PersonAPI person = Global.getFactory().createPerson();
        person.setFaction(factionId);
        person.setAICoreId(aiCoreId);
        
        CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(aiCoreId);
        
        boolean isHaro = "Moci_AICore_Haro".equals(aiCoreId);
        
        int points = 0;
        float mult = 1f;
        
        if (isHaro) {
            // 设置头像
            person.setPortraitSprite("graphics/portraits/Moci_haro_Base.png");
            person.setName(new FullName(spec.getName(), "", FullName.Gender.ANY));
            person.setId("Moci_Haro");
            person.getName().setFirst("哈罗");
            
            // 设置等级和技能
            person.getStats().setLevel(7);
            person.getStats().setSkillLevel(Skills.HELMSMANSHIP, 2);
            person.getStats().setSkillLevel(Skills.COMBAT_ENDURANCE, 2);
            person.getStats().setSkillLevel(Skills.IMPACT_MITIGATION, 2);
            person.getStats().setSkillLevel(Skills.DAMAGE_CONTROL, 2);
            person.getStats().setSkillLevel(Skills.FIELD_MODULATION, 2);
            person.getStats().setSkillLevel(Skills.POINT_DEFENSE, 2);
            person.getStats().setSkillLevel(Skills.TARGET_ANALYSIS, 1);
            
            // 设置个性和军衔
            person.setPersonality(Personalities.STEADY);
            person.setRankId(Ranks.SPACE_CAPTAIN);
            person.setPostId(null);
            
            person.getStats().setSkipRefresh(true);
            
            points = 0;
            mult = 2.5f;
        }
        
        if (points != 0) {
            person.getMemoryWithoutUpdate().set(AUTOMATED_POINTS_VALUE, points);
        }
        person.getMemoryWithoutUpdate().set(AUTOMATED_POINTS_MULT, mult);
        
        return person;
    }

    @Override
    public void createPersonalitySection(PersonAPI person, TooltipMakerAPI tooltip) {
        Color text = person.getFaction().getBaseUIColor();
        Color bg = person.getFaction().getDarkUIColor();
        CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(person.getAICoreId());
        
        tooltip.addSectionHeading("哈罗", text, bg, Alignment.MID, 20.0f);
        tooltip.addPara("哈罗AI核心具有友好的个性和出色的战术协调能力。" +
                "它会冷静的执行任何命令，能够有效保护舰船并支援友军。", 10.0f);
    }
}
