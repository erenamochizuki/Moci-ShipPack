package data.hullmods.shipboardai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import data.scripts.util.Moci_TextLoader;

/**
 * 哈罗舰载AI系统船插。
 */
public class Moci_HaroAISystem extends Moci_InstallableAISystemBase {

    public static final String HULLMOD_ID = "Moci_HaroAISystem";
    public static final String AI_TYPE_ID = "Moci_haro";
    private static final String TEXT_ID = "Moci_HaroAISystem";

    @Override
    protected String getHullmodId() {
        return HULLMOD_ID;
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) {
            return Moci_TextLoader.getText(TEXT_ID, "description.param_ai_name");
        }
        if (index == 1) {
            return Moci_TextLoader.getText(TEXT_ID, "description.param_ai_category");
        }
        return null;
    }

    @Override
    public CargoStackAPI getRequiredItem() {
        // 安装时需要消耗哈罗AI核心（商品）
        return Global.getSettings().createCargoStack(CargoAPI.CargoItemType.RESOURCES, "Moci_AICore_Haro", null);
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship,
                                          float width, boolean isForModSpec) {
        if (showInstallationHint(tooltip, ship)) return;
        addShipboardAIDescriptionSection(tooltip, ship, AI_TYPE_ID);
    }

}


