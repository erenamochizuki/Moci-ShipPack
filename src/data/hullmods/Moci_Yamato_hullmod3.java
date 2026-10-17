package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import org.magiclib.util.MagicIncompatibleHullmods;

public class Moci_Yamato_hullmod3 extends BaseHullMod {
    private static final int EMP_DECREASE = 80;
    private static final int REPAIR = 80;

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        ShipVariantAPI variant = stats.getVariant();
        if(variant.hasHullMod(HullMods.FLUXBREAKERS)){
            MagicIncompatibleHullmods.removeHullmodWithWarning(variant, HullMods.FLUXBREAKERS, spec.getId());
        }if(variant.hasHullMod(HullMods.AUTOREPAIR)){
            MagicIncompatibleHullmods.removeHullmodWithWarning(variant, HullMods.AUTOREPAIR, spec.getId());
        }

        stats.getEmpDamageTakenMult().modifyMult(id, (100 - EMP_DECREASE) * 0.01f);
        stats.getCombatWeaponRepairTimeMult().modifyMult(id, (100 - REPAIR) * 0.01f);
        stats.getCombatEngineRepairTimeMult().modifyMult(id, (100 - REPAIR) * 0.01f);
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize, ShipAPI ship) {
        if(index==0){
            return EMP_DECREASE+"%";
        }
        if(index==1){
            return REPAIR+"%";
        }
        return null;
    }

}
