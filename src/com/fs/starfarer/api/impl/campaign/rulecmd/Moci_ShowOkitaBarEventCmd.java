package com.fs.starfarer.api.impl.campaign.rulecmd;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.intel.bar.BarEventDialogPlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.missions.BarCMD;
import com.fs.starfarer.api.util.Misc;
import data.scripts.campaign.bar.Moci_OkitaBarEvent;
import data.scripts.impl.Moci_TulipColonyManager;

import java.util.List;
import java.util.Map;

public class Moci_ShowOkitaBarEventCmd extends BaseCommandPlugin {
    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params,
                           Map<String, MemoryAPI> memoryMap) {
        return openBarEvent(dialog, memoryMap);
    }

    static boolean openBarEvent(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        if (dialog == null || memoryMap == null) {
            return false;
        }

        Moci_TulipColonyManager.initializeTulipColony();

        Moci_OkitaBarEvent event = new Moci_OkitaBarEvent();
        Object stored = getEntityMemory(memoryMap).get("$BarCMD");
        BarCMD cmd = stored instanceof BarCMD ? (BarCMD) stored : null;
        if (cmd == null && dialog.getPlugin() instanceof BarCMD) {
            cmd = (BarCMD) dialog.getPlugin();
        }
        if (cmd == null) {
            return false;
        }

        // When this command is fired from a bar option, the active dialog plugin has already
        // been switched back to the rule-based plugin. Restoring to that plugin would leave the
        // UI showing bar options whose clicks no longer go through BarCMD.
        BarEventDialogPlugin plugin = new BarEventDialogPlugin(cmd, cmd, event, memoryMap);
        dialog.setPlugin(plugin);
        plugin.init(dialog);
        return true;
    }
}
