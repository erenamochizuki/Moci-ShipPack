package data.scripts.impl;

import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

public class Moci_FlanaganInstitute extends BaseIndustry {

    public static final String INDUSTRY_ID = "moci_flanagan_institute";

    @Override
    public void apply() {
        super.apply(true);
    }

    @Override
    public boolean isAvailableToBuild() {
        return false;
    }

    @Override
    public boolean showWhenUnavailable() {
        return false;
    }

    @Override
    public boolean canBeDisrupted() {
        return false;
    }

    @Override
    public boolean canInstallAICores() {
        return false;
    }

    @Override
    public boolean showShutDown() {
        return false;
    }

    @Override
    public boolean canShutDown() {
        return false;
    }

    @Override
    public boolean canImprove() {
        return false;
    }

    @Override
    protected void addRightAfterDescriptionSection(TooltipMakerAPI tooltip, IndustryTooltipMode mode) {
        if (mode == IndustryTooltipMode.NORMAL) {
            tooltip.addPara("允许在市场交互中消耗 Alpha Core，为玩家或舰队军官进行强化人培育。",
                    10f, Misc.getHighlightColor(), "Alpha Core", "强化人培育");
        }
    }
}
