package data.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.AICoreAdminPlugin;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;

/**
 * 欧米茄·列克星敦AI核心管理员插件
 * 为AI核心作为星球总督时提供管理技能
 */
public class Moci_OmegaLexingtonAdminPlugin implements AICoreAdminPlugin {
    
    @Override
    public PersonAPI createPerson(String aiCoreId, String factionId, long seed) {
        PersonAPI person = Global.getFactory().createPerson();
        person.setFaction(factionId);
        person.setAICoreId(aiCoreId);
        
        // 获取商品名称
        String commodityName = Global.getSettings().getCommoditySpec(aiCoreId).getName();
        person.setName(new FullName(commodityName, "", FullName.Gender.ANY));
        person.setPortraitSprite("graphics/portraits/Moci_omega_lexington_portrait.png");
        
        // 设置职位
        person.setRankId(null);
        person.setPostId(Ranks.POST_ADMINISTRATOR);
        
        // 设置技能
        person.getStats().setSkillLevel(Skills.INDUSTRIAL_PLANNING, 1);
        person.getStats().setSkillLevel(Skills.HYPERCOGNITION, 1);
        person.getStats().setSkillLevel("Moci_OmegaAdministrationSkill", 1);
        
        return person;
    }
}
