package com.fs.starfarer.api.impl.campaign.rulecmd;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.campaign.Moci_FlanaganInstituteDialogPlugin;
import data.scripts.impl.Moci_FlanaganInstitute;

import java.util.List;
import java.util.Map;

public class Moci_AugmentedHumanCommand extends BaseCommandPlugin {

    public static final String OPTION_ID = "moci_flanagan_augmented_human";

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
        if ("checkCost".equals(command)) {
            updateOptionState(dialog);
            return true;
        }
        if ("open".equals(command)) {
            if (!hasFlanaganInstitute(dialog)) {
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

        if (!Moci_FlanaganInstituteDialogPlugin.hasAlphaCore()) {
            dialog.getOptionPanel().setEnabled(OPTION_ID, false);
            dialog.getOptionPanel().setTooltip(OPTION_ID, "需要 1 个 Alpha Core。");
            return;
        }
        if (!Moci_FlanaganInstituteDialogPlugin.hasEligibleTarget()) {
            dialog.getOptionPanel().setEnabled(OPTION_ID, false);
            dialog.getOptionPanel().setTooltip(OPTION_ID, "没有可进行强化人培育的玩家或军官。");
            return;
        }

        dialog.getOptionPanel().setTooltip(OPTION_ID, "消耗 1 个 Alpha Core，为玩家或军官授予强化人技能。");
    }
}
