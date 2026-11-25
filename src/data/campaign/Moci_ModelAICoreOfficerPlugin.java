package data.campaign;

import data.scripts.util.Moci_TextLoader;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.BaseAICoreOfficerPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import data.hullmods.shipboardai.Moci_ModelCore;
import data.hullmods.shipboardai.Moci_ShipboardAIConfigLoader;
import data.hullmods.shipboardai.Moci_ShipboardAIManager;

import java.util.Random;

public class Moci_ModelAICoreOfficerPlugin extends BaseAICoreOfficerPluginImpl {
    @Override
    public PersonAPI createPerson(String aiCoreId, String factionId, Random random) {
        Moci_ShipboardAIConfigLoader.AITypeDefinition type = Moci_ShipboardAIConfigLoader.getAiType(
                Moci_ModelCore.forItem(aiCoreId).baseType);
        PersonAPI person = Global.getFactory().createPerson();
        person.setFaction(factionId);
        person.setAICoreId(aiCoreId);
        person.setName(new FullName(type.name, "", FullName.Gender.ANY));
        person.setPortraitSprite(type.portrait);
        person.setPersonality(type.personality);
        person.setRankId(Ranks.SPACE_CAPTAIN);
        person.setPostId(null);
        person.getStats().setLevel(1);
        for (String skill : type.defaultSkills) person.getStats().setSkillLevel(skill, 1f);
        person.getMemoryWithoutUpdate().set(AUTOMATED_POINTS_MULT, 2.5f);
        return person;
    }

    @Override
    public void createPersonalitySection(PersonAPI person, TooltipMakerAPI tooltip) {
        tooltip.addPara(Moci_TextLoader.getText("code_Moci_ModelAICoreOfficerPlugin", "t001", "%s 的指挥风格：%s。通过对应船插内置后，可在舰载 AI 详情中培养或进化。"), 10f,
                com.fs.starfarer.api.util.Misc.getHighlightColor(), person.getNameString(),
                Moci_ShipboardAIManager.getPersonalityLabel(person.getPersonalityAPI().getId()));
    }
}
