package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;

public class Moci_ModelAISystem extends Moci_InstallableAISystemBase {
    @Override
    protected String getHullmodId() { return spec.getId(); }

    @Override
    protected String getEffectModId() { return getHullmodId(); }

    @Override
    public CargoStackAPI getRequiredItem() {
        Moci_ModelCore model = Moci_ModelCore.forHullmod(getHullmodId());
        return Global.getSettings().createCargoStack(CargoAPI.CargoItemType.RESOURCES, model.coreId, null);
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship,
                                          float width, boolean isForModSpec) {
        if (!showInstallationHint(tooltip, ship)) {
            addShipboardAIDescriptionSection(tooltip, ship, Moci_ModelCore.forHullmod(getHullmodId()).baseType);
        }
    }
}
