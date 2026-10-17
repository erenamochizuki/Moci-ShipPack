package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;

public class Moci_ChainLightningBranching extends BaseHullMod {

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null || ship.getVariant() == null) {
            return false;
        }
        return ship.getVariant().hasHullMod(Moci_ChainLightningCapacitor.ID) &&
                !ship.getVariant().hasHullMod(Moci_ChainLightningCapacitor.OVERLOAD_ID);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null || ship.getVariant() == null ||
                !ship.getVariant().hasHullMod(Moci_ChainLightningCapacitor.ID)) {
            return "需要先安装连锁雷暴电容器";
        }
        if (ship.getVariant().hasHullMod(Moci_ChainLightningCapacitor.OVERLOAD_ID)) {
            return "只能选择一种雷暴调谐";
        }
        return null;
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) {
            return "" + Moci_ChainLightningCapacitor.BRANCHING_BOUNCE_BONUS;
        }
        if (index == 1) {
            return "" + (int) (Moci_ChainLightningCapacitor.BRANCHING_DAMAGE_DECAY * 100f) + "%";
        }
        if (index == 2) {
            return "" + (int) Moci_ChainLightningCapacitor.BRANCHING_DAMAGE_THRESHOLD_BONUS;
        }
        return null;
    }
}
