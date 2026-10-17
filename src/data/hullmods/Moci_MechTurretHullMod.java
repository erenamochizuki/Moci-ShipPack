package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import data.scripts.ships.ai.Moci_MechTurretAI;
import data.scripts.util.Moci_TextLoader;

public class Moci_MechTurretHullMod extends BaseHullMod {
    private static final String TEXT_ID = "Moci_MechTurretHullMod";

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        Moci_MechTurretAI.getMechDockListener(ship);
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        Moci_MechTurretAI.advanceCompatibilityShell(ship, amount);
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null &&
                ship.getVariant() != null &&
                ship.getVariant().hasHullMod("Moci_MobileSuits");
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_only_ms");
    }

    @Override
    public boolean showInRefitScreenModPickerFor(ShipAPI ship) {
        return isApplicableToShip(ship);
    }
}
