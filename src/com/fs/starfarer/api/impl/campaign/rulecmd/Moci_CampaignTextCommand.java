package com.fs.starfarer.api.impl.campaign.rulecmd;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.AddBarEvent;
import data.scripts.impl.Moci_YurikaStoryManager;
import data.scripts.util.Moci_TextLoader;

import java.awt.Color;
import java.util.List;
import java.util.Map;

public class Moci_CampaignTextCommand extends BaseCommandPlugin {
    public static final String TEXT_ID = "Moci_CampaignText";

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params,
                           Map<String, MemoryAPI> memoryMap) {
        if (dialog == null || params == null || params.size() < 1) {
            return false;
        }

        String command = params.get(0).getString(memoryMap);
        if ("addText".equals(command)) {
            return addText(dialog, memoryMap, params);
        }
        if ("addBarEvent".equals(command)) {
            return addBarEvent(dialog, memoryMap, params);
        }
        if ("devAffinityText".equals(command)) {
            return addDevAffinityText(dialog, memoryMap, params);
        }
        return false;
    }

    public static String text(String key, String fallback) {
        return Moci_TextLoader.getText(TEXT_ID, key, fallback);
    }

    public static String format(String key, String fallback, Map<String, String> replacements) {
        String value = text(key, fallback);
        if (replacements == null) {
            return value;
        }
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return value;
    }

    private static boolean addText(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap,
                                   List<Misc.Token> params) {
        if (dialog.getTextPanel() == null || params.size() < 2) {
            return false;
        }
        String key = params.get(1).getString(memoryMap);
        String colorId = params.size() > 2 ? params.get(2).getString(memoryMap) : "text";
        dialog.getTextPanel().addPara(text(key, "[missing text: " + key + "]"), colorFor(colorId));
        return true;
    }

    private static boolean addBarEvent(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap,
                                       List<Misc.Token> params) {
        if (params.size() < 4 || dialog.getInteractionTarget() == null) {
            return false;
        }
        MarketAPI market = dialog.getInteractionTarget().getMarket();
        if (market == null) {
            return false;
        }
        String optionId = params.get(1).getString(memoryMap);
        String optionKey = params.get(2).getString(memoryMap);
        String promptKey = params.get(3).getString(memoryMap);
        String optionText = text(optionKey, "[missing text: " + optionKey + "]");
        String promptText = text(promptKey, "[missing text: " + promptKey + "]");
        AddBarEvent.TempBarEvents events = AddBarEvent.getTempEvents(market);
        AddBarEvent.BarEventData data = new AddBarEvent.BarEventData(optionId, optionText, promptText);
        if (params.size() > 4 && "highlight".equals(params.get(4).getString(memoryMap))) {
            data.optionColor = Misc.getHighlightColor();
        }
        events.events.put(optionId, data);
        return true;
    }

    private static boolean addDevAffinityText(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap,
                                              List<Misc.Token> params) {
        if (dialog.getTextPanel() == null || params.size() < 3) {
            return false;
        }
        String key = params.get(1).getString(memoryMap);
        String amount = params.get(2).getString(memoryMap);
        String affinity = String.valueOf((int) Moci_YurikaStoryManager.getAffinity());
        dialog.getTextPanel().addPara(format(key, "测试：已增加 {amount} 点好感，当前好感为 {affinity}。",
                        Moci_TextLoader.mapOf("amount", amount, "affinity", affinity)),
                Misc.getTextColor(), Misc.getHighlightColor(), amount, affinity);
        return true;
    }

    private static Color colorFor(String colorId) {
        if ("marketFlavorTextColor".equals(colorId)) {
            return Misc.getGrayColor();
        }
        if ("highlightColor".equals(colorId) || "highlight".equals(colorId)) {
            return Misc.getHighlightColor();
        }
        if ("negative".equals(colorId)) {
            return Misc.getNegativeHighlightColor();
        }
        return Misc.getTextColor();
    }
}
