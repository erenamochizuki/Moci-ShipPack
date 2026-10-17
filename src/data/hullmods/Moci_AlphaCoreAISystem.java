package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

import java.util.Arrays;
import java.util.List;

public class Moci_AlphaCoreAISystem extends Moci_RandomAICoreSystemBase {

    public static final String HULLMOD_ID = "Moci_AlphaCoreAISystem";
    private static final String TEXT_ID = HULLMOD_ID;
    public static final String AI_TYPE_ID = "Moci_ARP_AlphaRandom";
    private static final List<String> AI_POOL = Arrays.asList(
            "Moci_ARP_Takao",
            "Moci_ARP_Kongou",
            "Moci_ARP_Haruna"
    );

    @Override
    protected String getHullmodId() {
        return HULLMOD_ID;
    }

    @Override
    protected String getPlaceholderAiTypeId() {
        return AI_TYPE_ID;
    }

    @Override
    protected List<String> getAiPool() {
        return AI_POOL;
    }

    @Override
    protected String getRequiredCoreCommodityId() {
        return Commodities.ALPHA_CORE;
    }

    @Override
    public boolean shouldAddDescriptionToTooltip(ShipAPI.HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship,
                                          float width, boolean isForModSpec) {
        syncIcon(ship);
        boolean installed = ship != null
                && ship.getVariant() != null
                && ship.getVariant().hasHullMod(HULLMOD_ID);

        if (!installed) {
            String coreName = Global.getSettings().getCommoditySpec(Commodities.ALPHA_CORE).getName();
            String highlighted = Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.core_cost",
                    Moci_TextLoader.mapOf("core_name", coreName));
            tooltip.addPara(
                    Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.install",
                            Moci_TextLoader.mapOf("core_cost", highlighted)),
                    10f, Misc.getHighlightColor(),
                    Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.install_highlights",
                            Moci_TextLoader.mapOf("core_cost", highlighted)).toArray(new String[0]));
            return;
        }

        String fallbackName = Moci_TextLoader.getText(TEXT_ID, "description.ai_fallback_name", AI_TYPE_ID);
        String aiName = escapeTooltipFormat(getShipboardAIControlDisplayName(ship, AI_TYPE_ID, fallbackName));
        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.control",
                        Moci_TextLoader.mapOf("ai_name", aiName)),
                10f, Misc.getHighlightColor(),
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.control_highlights",
                        Moci_TextLoader.mapOf("ai_name", aiName)).toArray(new String[0]));
        addShipboardAIDescriptionSection(tooltip, ship, AI_TYPE_ID);
    }
}
