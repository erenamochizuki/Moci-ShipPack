package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

public class Moci_PhaseSubLaunch extends BaseHullMod {
    public static final String HULLMOD_ID = "Moci_PhaseSubLaunch";
    private static final String TEXT_ID = HULLMOD_ID;
    private static final String TARGET_HULL_ID = "Moci_TDD_1";

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) {
            return false;
        }
        return TARGET_HULL_ID.equals(ship.getHullSpec().getBaseHullId());
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_reason");
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 10f;

        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.title"), Alignment.MID, pad);
        tooltip.addPara(
                Moci_TextLoader.getText(TEXT_ID, "description.effect_1"),
                4f,
                Misc.getHighlightColor(),
                Moci_TextLoader.getHighlights(TEXT_ID, "description.effect_1_highlights").toArray(new String[0])
        );
    }
}
