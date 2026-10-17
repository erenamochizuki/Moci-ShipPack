package data.hullmods;

import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.ui.TooltipMakerAPI;

import data.scripts.util.Moci_TextLoader;

/**
 * SYSTEM99 - 自动化驾驶系统
 * 功能：
 * 1. 减少1点最小船员需求
 * 2. 自动分配AI驾驶员
 */
public class Moci_System99 extends Moci_ShipboardAISystemBase {

    public static final String HULLMOD_ID = "Moci_System99";
    public static final String AI_TYPE_ID = "Moci_system99";
    private static final String TEXT_ID = HULLMOD_ID;

    @Override
    protected String getHullmodId() {
        return HULLMOD_ID;
    }

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        enforceSingleAISystemInRefit(stats);
        // 减少1点最小船员需求
        stats.getMinCrewMod().modifyFlat(id, -1f);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        syncIcon(ship);
        super.applyEffectsAfterShipCreation(ship, id);
    }

    @Override
    public boolean canBeAddedOrRemovedNow(ShipAPI ship, MarketAPI marketOrNull, CampaignUIAPI.CoreUITradeMode mode) {
        syncIcon(ship);
        return super.canBeAddedOrRemovedNow(ship, marketOrNull, mode);
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        super.advanceInCombat(ship, amount);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) {
            return "1";
        }
        if (index == 1) {
            return Moci_TextLoader.getText(TEXT_ID, "description.param_system_name");
        }
        return super.getDescriptionParam(index, hullSize);
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship,
                                          float width, boolean isForModSpec) {
        syncIcon(ship);
        addShipboardAIDescriptionSection(tooltip, ship, AI_TYPE_ID);
    }

    private void syncIcon(ShipAPI ship) {
        // 船插图标保持固定，99 系统头像改为仅在 tooltip 和专用 UI 中展示。
    }
}

