package com.fs.starfarer.api.impl.campaign.rulecmd;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.campaign.Moci_FlanaganInstituteDialogPlugin;
import data.scripts.impl.Moci_FlanaganInstitute;

import java.util.List;
import java.util.Map;

public class Moci_AugmentedHumanCommand extends BaseCommandPlugin {

    public static final String OPTION_ID = "moci_flanagan_institute";
    public static final String NERGAL_FACTION_ID = "Moci_NergalHeavyIndustries";
    public static final float REQUIRED_REPUTATION = 0.25f;

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params,
                           Map<String, MemoryAPI> memoryMap) {
        if (dialog == null || params == null || params.isEmpty()) {
            return false;
        }

        String command = params.get(0).getString(memoryMap);
        if ("canShow".equals(command)) {
            return hasFlanaganInstitute(dialog);
        }
        if ("checkAccess".equals(command)) {
            updateOptionState(dialog);
            return true;
        }
        if ("open".equals(command)) {
            if (getAccessUnavailableReason(dialog) != null) {
                return false;
            }
            Moci_FlanaganInstituteDialogPlugin plugin =
                    new Moci_FlanaganInstituteDialogPlugin(dialog.getPlugin(), memoryMap);
            dialog.setPlugin(plugin);
            plugin.init(dialog);
            return true;
        }

        return false;
    }

    private static boolean hasFlanaganInstitute(InteractionDialogAPI dialog) {
        MarketAPI market = getMarket(dialog);
        return market != null && market.hasIndustry(Moci_FlanaganInstitute.INDUSTRY_ID);
    }

    public static String getAccessUnavailableReason(InteractionDialogAPI dialog) {
        if (!hasFlanaganInstitute(dialog)) {
            return "此处没有弗拉纳冈机构。";
        }
        if (Global.getSector() == null || Global.getSector().getPlayerFaction() == null
                || Global.getSector().getPlayerFaction().getRelationship(NERGAL_FACTION_ID) < REQUIRED_REPUTATION) {
            return "需要与尼尔加的好感度至少达到 25。";
        }
        return null;
    }

    private static MarketAPI getMarket(InteractionDialogAPI dialog) {
        if (dialog == null || dialog.getInteractionTarget() == null) {
            return null;
        }
        return dialog.getInteractionTarget().getMarket();
    }

    private static void updateOptionState(InteractionDialogAPI dialog) {
        if (dialog.getOptionPanel() == null || !dialog.getOptionPanel().hasOption(OPTION_ID)) {
            return;
        }

        String unavailable = getAccessUnavailableReason(dialog);
        dialog.getOptionPanel().setEnabled(OPTION_ID, unavailable == null);
        dialog.getOptionPanel().setTooltip(OPTION_ID, unavailable == null
                ? "进入弗拉纳冈机构，使用强化人培育服务。" : unavailable);
    }
}
