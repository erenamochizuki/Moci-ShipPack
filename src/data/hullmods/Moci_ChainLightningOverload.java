package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;

public class Moci_ChainLightningOverload extends BaseHullMod {

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null || ship.getVariant() == null) {
            return false;
        }
        return ship.getVariant().hasHullMod(Moci_ChainLightningCapacitor.ID) &&
                !ship.getVariant().hasHullMod(Moci_ChainLightningCapacitor.BRANCHING_ID);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null || ship.getVariant() == null ||
                !ship.getVariant().hasHullMod(Moci_ChainLightningCapacitor.ID)) {
            return "需要先安装连锁雷暴电容器";
        }
        if (ship.getVariant().hasHullMod(Moci_ChainLightningCapacitor.BRANCHING_ID)) {
            return "只能选择一种雷暴调谐";
        }
        return null;
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) {
            return "" + (int) ((Moci_ChainLightningCapacitor.OVERLOAD_CHARGE_PER_SECOND_MULT - 1f) * 100f) + "%";
        }
        if (index == 1) {
            return "" + (int) ((Moci_ChainLightningCapacitor.OVERLOAD_DAMAGE_MULT - 1f) * 100f) + "%";
        }
        if (index == 2) {
            return "" + Moci_ChainLightningCapacitor.OVERLOAD_BOUNCE_PENALTY;
        }
        if (index == 3) {
            return "" + (int) Moci_ChainLightningCapacitor.OVERLOAD_DAMAGE_THRESHOLD_BONUS;
        }
        return null;
    }
}
