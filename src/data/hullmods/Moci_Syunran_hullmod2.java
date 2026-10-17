package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import org.magiclib.util.MagicIncompatibleHullmods;

import data.scripts.util.Moci_TextLoader;

/**
 * 春兰号船插2 - 强化结构系统
 */
public class Moci_Syunran_hullmod2 extends BaseHullMod {
    private static final String TEXT_ID = "Moci_Syunran_hullmod2";
    
    // ReinforcedBulkheads效果
    public static final float REINFORCED_HULL_BONUS = 60f;
    
    // BlastDoors效果
    public static final float CASUALTY_REDUCTION = 80f;

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        ShipVariantAPI variant = stats.getVariant();
        
        // 检查并移除冲突的船插
        if (variant.hasHullMod(HullMods.REINFORCEDHULL)) {
            MagicIncompatibleHullmods.removeHullmodWithWarning(variant, HullMods.REINFORCEDHULL, spec.getId());
        }
        if (variant.hasHullMod(HullMods.BLAST_DOORS)) {
            MagicIncompatibleHullmods.removeHullmodWithWarning(variant, HullMods.BLAST_DOORS, spec.getId());
        }
        
        stats.getHullBonus().modifyPercent(id, REINFORCED_HULL_BONUS);
        stats.getDynamic().getMod(Stats.INDIVIDUAL_SHIP_RECOVERY_MOD).modifyFlat(id, 1000f);
        stats.getBreakProb().modifyMult(id, 0f);
        stats.getCrewLossMult().modifyMult(id, 1f - CASUALTY_REDUCTION * 0.01f);
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        // 参数0：船体值加成
        if (index == 0) return "" + (int)REINFORCED_HULL_BONUS  + "%";
        // 参数1：船员伤亡减少
        if (index == 1) return "" + (int)CASUALTY_REDUCTION + "%";

        if (index == 2) return Moci_TextLoader.getText(TEXT_ID, "description.param_reinforced_bulkheads");
        if (index == 3) return Moci_TextLoader.getText(TEXT_ID, "description.param_blast_doors");
        
        return null;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        // 可以应用到所有舰船
        return true;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        // 检查是否与冲突的船插同时存在
        if (ship != null && ship.getVariant() != null) {
            if (ship.getVariant().hasHullMod(HullMods.REINFORCEDHULL)) {
                return Moci_TextLoader.getText(TEXT_ID, "description.conflict_reinforced_hull");
            }
            if (ship.getVariant().hasHullMod(HullMods.BLAST_DOORS)) {
                return Moci_TextLoader.getText(TEXT_ID, "description.conflict_blast_doors");
            }
        }
        return null;
    }
}
