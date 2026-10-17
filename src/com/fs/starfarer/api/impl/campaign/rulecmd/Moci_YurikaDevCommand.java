package com.fs.starfarer.api.impl.campaign.rulecmd;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.missions.BarCMD;
import com.fs.starfarer.api.util.Misc;
import data.scripts.impl.Moci_YurikaStoryManager;
import data.scripts.util.Moci_TextLoader;

import java.util.List;
import java.util.Map;

public class Moci_YurikaDevCommand extends BaseCommandPlugin {
    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params,
                           Map<String, MemoryAPI> memoryMap) {
        if (dialog == null || memoryMap == null || params == null || params.isEmpty()) {
            return false;
        }

        String command = params.get(0).getString(memoryMap);
        if (!"addAffinityAndOpen".equals(command) && !"addAffinity".equals(command)) {
            return false;
        }

        int amount = params.size() > 1 ? params.get(1).getInt(memoryMap) : 15;
        Moci_YurikaStoryManager.ensureLoaded();
        Moci_YurikaStoryManager.addAffinity(amount);

        if (dialog.getTextPanel() != null) {
            dialog.getTextPanel().addPara(Moci_TextLoader.getTextWithReplacements(
                            Moci_CampaignTextCommand.TEXT_ID,
                            "bar.dev_affinity_result",
                            Moci_TextLoader.mapOf("amount", String.valueOf(amount),
                                    "affinity", String.valueOf((int) Moci_YurikaStoryManager.getAffinity()))),
                    Misc.getTextColor(),
                    Misc.getHighlightColor(),
                    String.valueOf(amount),
                    String.valueOf((int) Moci_YurikaStoryManager.getAffinity())
            );
        }

        if ("addAffinityAndOpen".equals(command)) {
            return Moci_ShowYurikaBarEventCmd.openBarEvent(dialog, memoryMap);
        }

        Object stored = getEntityMemory(memoryMap).get("$BarCMD");
        if (stored instanceof BarCMD) {
            BarCMD cmd = (BarCMD) stored;
            dialog.setPlugin(cmd);
            cmd.returningFromEvent(false);
        }

        return true;
    }
}
