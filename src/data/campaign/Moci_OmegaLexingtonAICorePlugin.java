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
 * 欧米茄·列克星敦AI核心插件实现
 * 一个极其强大的11级AI核心，拥有鲁莽的作战风格
 */
public class Moci_OmegaLexingtonAICorePlugin extends BaseAICoreOfficerPluginImpl implements AICoreOfficerPlugin {

    @Override
    public PersonAPI createPerson(String aiCoreId, String factionId, Random random) {
        PersonAPI person = Global.getFactory().createPerson();
        person.setFaction(factionId);
        person.setAICoreId(aiCoreId);
        
        CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(aiCoreId);
        
        boolean isOmegaLexington = "Moci_AICore_Omega_Lexington".equals(aiCoreId);
        
        int points = 0;
        float mult = 1f;
        
        if (isOmegaLexington) {
            // 设置头像
            person.setPortraitSprite("graphics/portraits/Moci_omega_lexington_portrait.png");
            person.setName(new FullName(spec.getName(), "", FullName.Gender.ANY));
            person.setId("Moci_Omega_Lexington");
            person.getName().setFirst("欧米茄·Lex");
            
            // 设置等级和技能
            person.getStats().setLevel(11);
            
            // 进攻型技能 - 满级配置
            person.getStats().setSkillLevel(Skills.HELMSMANSHIP, 2);
            person.getStats().setSkillLevel(Skills.COMBAT_ENDURANCE, 2);
            person.getStats().setSkillLevel(Skills.IMPACT_MITIGATION, 2);
            person.getStats().setSkillLevel(Skills.DAMAGE_CONTROL, 2);
            person.getStats().setSkillLevel(Skills.FIELD_MODULATION, 2);
            person.getStats().setSkillLevel(Skills.TARGET_ANALYSIS, 2);
            person.getStats().setSkillLevel(Skills.BALLISTIC_MASTERY, 2);
            person.getStats().setSkillLevel(Skills.SYSTEMS_EXPERTISE, 2);
            person.getStats().setSkillLevel(Skills.MISSILE_SPECIALIZATION, 2);
            person.getStats().setSkillLevel(Skills.GUNNERY_IMPLANTS, 2);
            person.getStats().setSkillLevel(Skills.ENERGY_WEAPON_MASTERY, 1);
            
            // 设置个性和军衔 - 鲁莽型
            person.setPersonality(Personalities.RECKLESS);
            person.setRankId(Ranks.SPACE_ADMIRAL);
            person.setPostId(null);
            
            person.getStats().setSkipRefresh(true);
            
            points = 0;
            mult = 4f; // 超高效率倍数
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
        
        tooltip.addSectionHeading("鲁莽 — 欧米茄·列克星敦", text, bg, Alignment.MID, 20.0f);
        tooltip.addPara("欧米茄·列克星敦是一个极其强大的AI核心，拥有超凡的战术能力。" +
                "它采用极度激进的作战风格，毫不犹豫地投入战斗，追求压倒性的火力优势。" +
                "这个AI核心精通各类武器系统，能够最大化发挥舰船的进攻潜力。", 10.0f);
    }
}
