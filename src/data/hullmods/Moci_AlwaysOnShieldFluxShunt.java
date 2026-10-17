package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipCommand;

public class Moci_AlwaysOnShieldFluxShunt extends BaseHullMod {

    public static final int HARD_FLUX_DISSIPATION_PERCENT = 100;
    public static final int SHIELD_UPKEEP_REDUCTION_PERCENT = 100;

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        if (!hasUsableShield(stats)) return;

        stats.getHardFluxDissipationFraction().modifyFlat(id, HARD_FLUX_DISSIPATION_PERCENT * 0.01f);
        stats.getShieldUpkeepMult().modifyMult(id, 1f - SHIELD_UPKEEP_REDUCTION_PERCENT * 0.01f);
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (!ship.isAlive() || ship.getShield() == null) return;

        ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK);
        ship.blockCommandForOneFrame(ShipCommand.VENT_FLUX);

        if (!ship.getFluxTracker().isOverloadedOrVenting() && !ship.getShield().isOn()) {
            ship.getShield().toggleOn();
        }
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return HARD_FLUX_DISSIPATION_PERCENT + "%";
        if (index == 1) return SHIELD_UPKEEP_REDUCTION_PERCENT + "%";
        return null;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && ship.getShield() != null;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null || ship.getShield() == null) {
            return "舰船没有护盾。";
        }
        return null;
    }

    private boolean hasUsableShield(MutableShipStatsAPI stats) {
        if (stats == null || stats.getVariant() == null || stats.getVariant().getHullSpec() == null) {
            return true;
        }

        if (stats.getVariant().hasHullMod("shield_shunt")) {
            return false;
        }

        return stats.getVariant().getHullSpec().getShieldType() != ShieldType.NONE
                || stats.getVariant().hasHullMod("frontshield");
    }
}
