package com.fs.starfarer.api.impl.campaign.rulecmd;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.impl.Moci_GachaManager;
import data.scripts.util.Moci_TextLoader;

import java.util.List;
import java.util.Map;

public class Moci_GachaCommand extends BaseCommandPlugin {
    private static final String TEXT_ID = "Moci_CampaignText";

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params, Map<String, MemoryAPI> memoryMap) {
        CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
        if (!params.isEmpty()) {
            String k = params.get(0).getString(memoryMap);
            TextPanelAPI text = dialog.getTextPanel();
            if ("showGachaInfo".equals(k)) {
                if (!Global.getSector().getPlayerPerson().getMemoryWithoutUpdate().getBoolean("$Moci_GachaContacted")) {
                    text.addPara(text("gacha.command.info_first"));
                    Global.getSector().getPlayerPerson().getMemoryWithoutUpdate().set("$Moci_GachaContacted", true);
                } else {
                    text.addPara(text("gacha.command.info_repeat"));
                }
            } else if ("checkCommodity".equals(k)) {
                String commodityId = params.get(1).getString(memoryMap);
                int num = params.get(2).getInt(memoryMap);
                String optionId = params.get(3).getString(memoryMap);
                if (cargo.getCommodityQuantity(commodityId) < num) {
                    dialog.getOptionPanel().setEnabled(optionId, false);
                }
            } else if ("checkGacha".equals(k)) {
                String commodityId = params.get(1).getString(memoryMap);
                int num = params.get(2).getInt(memoryMap);
                float quantity = cargo.getCommodityQuantity(commodityId);
                Moci_GachaManager.gachaMemoryItem = commodityId;
                Moci_GachaManager.gachaMemoryItemNum = num;
                if (quantity < 100 * num) {
                    dialog.getOptionPanel().setEnabled("moci_gacha_gacha100", false);
                }
                if (quantity < 50 * num) {
                    dialog.getOptionPanel().setEnabled("moci_gacha_gacha50", false);
                }
                if (quantity < 20 * num) {
                    dialog.getOptionPanel().setEnabled("moci_gacha_gacha20", false);
                }
                if (quantity < 10 * num) {
                    dialog.getOptionPanel().setEnabled("moci_gacha_gacha10", false);
                }
                if (quantity < num) {
                    dialog.getOptionPanel().setEnabled("moci_gacha_gacha1", false);
                }
            } else if ("gacha".equals(k)) {
                if (Moci_GachaManager.gachaMemoryItem == null) {
                    text.addPara(text("gacha.command.error_missing_resource"));
                    return false;
                }
                int num = params.get(1).getInt(memoryMap);
                if (num == 0) {
                    text.addPara(text("gacha.command.error_zero_draws"));
                    return false;
                }
                Moci_GachaManager.getInstance().Gacha(cargo, text, num);
                cargo.removeCommodity(Moci_GachaManager.gachaMemoryItem, num * Moci_GachaManager.gachaMemoryItemNum);
                AddRemoveCommodity.addCommodityLossText(Moci_GachaManager.gachaMemoryItem, num * Moci_GachaManager.gachaMemoryItemNum, text);
            } else if ("DEV_Item".equals(k)) {
                cargo.addCommodity("Moci_Vorttear", 2000);
                cargo.addCommodity("Moci_Mournscale", 500);
                cargo.addCommodity("Moci_Dracaryx", 100);
                AddRemoveCommodity.addCommodityGainText("Moci_Vorttear",2000,text);
                AddRemoveCommodity.addCommodityGainText("Moci_Mournscale",500,text);
                AddRemoveCommodity.addCommodityGainText("Moci_Dracaryx",100,text);
            } else if ("showGachaShop".equals(k)) {
                Moci_GachaManager.getInstance().showGachaShop(dialog, cargo);
            } else if (k.equals("showNoSSRLimit")) { //如果需要显示抽卡保底 调用这个
                dialog.getTextPanel().addPara("*" + Moci_GachaManager.getInstance().getLimitText());
            }
        }

        return true;
    }

    private static String text(String key) {
        return Moci_TextLoader.getText(TEXT_ID, key);
    }
}
