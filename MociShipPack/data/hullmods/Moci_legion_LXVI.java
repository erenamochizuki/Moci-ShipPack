package data.hullmods;

import com.fs.starfarer.api.combat.*;

public class Moci_legion_LXVI extends BaseHullMod {
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getEmpDamageTakenMult().modifyMult(id,0);
        stats.getKineticArmorDamageTakenMult().modifyMult(id,2f);
        stats.getHighExplosiveDamageTakenMult().modifyMult(id,0.5f);
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if(index==0){
            return 100+"%";
        }
        return null;
    }
}
