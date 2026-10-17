package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * 先进甲板改造船插
 * 效果：
 * 1. 获得2个额外的战机甲板
 * 2. 战机遭到损坏时的整备率下降值为0f
 */
public class Moci_AdvancedFlightDeck extends BaseHullMod {
    private static final String TEXT_ID = "Moci_advanced_flight_deck";
    
    private static final float EXTRA_BAYS = 2f;
    
    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 增加2个战机甲板
        stats.getNumFighterBays().modifyFlat(id, EXTRA_BAYS);
        
        // 将战机整备率下降倍数设置为0，即战机损坏时整备率不下降
        stats.getDynamic().getStat(Stats.REPLACEMENT_RATE_DECREASE_MULT).modifyMult(id, 0f);
    }
    
    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        Color h = Misc.getHighlightColor();

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.extra_bays",
                        Moci_TextLoader.mapOf("extra_bays", String.valueOf((int) EXTRA_BAYS))),
                10f,
                h,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.extra_bays_highlights",
                        Moci_TextLoader.mapOf("extra_bays", String.valueOf((int) EXTRA_BAYS))).toArray(new String[0])
        );
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.replacement_protection"), 10f);
    }
} 
