package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import java.util.HashSet;
import java.util.Set;
import data.scripts.util.Moci_TextLoader;
import org.magiclib.util.MagicIncompatibleHullmods;

public class Moci_PhaseDrive extends BaseHullMod {
    private static final String TEXT_ID = "Moci_PhaseDrive";

    public static float PHASE_COOLDOWN_REDUCTION = 25f;

    private static final Set<String> BLOCKED_HULLMODS = new HashSet<>(1);

    static {
        BLOCKED_HULLMODS.add("adaptive_coils");
    }

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getPhaseCloakCooldownBonus().modifyMult(id, 1f - (PHASE_COOLDOWN_REDUCTION / 100f));
        if (stats == null || stats.getVariant() == null) {
            return;
        }

        for (String tmp : BLOCKED_HULLMODS) {
            if (stats.getVariant().getNonBuiltInHullmods().contains(tmp) && !stats.getVariant().getSMods().contains(tmp)) {
                MagicIncompatibleHullmods.removeHullmodWithWarning(stats.getVariant(), tmp, spec.getId());
            }
        }
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) {
            return "" + (int) Math.round(PHASE_COOLDOWN_REDUCTION) + "%";
        }
        return null;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if ((ship != null) && ship.getVariant().hasHullMod("adaptive_coils")) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_adaptive_coils");
        }
        return super.getUnapplicableReason(ship);
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if ((ship != null) && ship.getVariant().hasHullMod("adaptive_coils")) {
            return false;
        }
        return super.isApplicableToShip(ship);
    }
}

