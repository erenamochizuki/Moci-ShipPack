package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.FighterOPCostModifier;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

public class Moci_OpenMaintenanceGantry extends BaseHullMod implements FighterOPCostModifier {
    private static final String TEXT_ID = "Moci_open_maintenance_gantry";

    /** 维修速度提升百分比 (20%) */
    public static final float REPAIR_BONUS = 0.20f;
    
    /** CR恢复速率提升百分比 (20%) */
    public static final float CR_RECOVERY_BONUS = 0.20f;
    
    /** 补给消耗降低百分比 (10%) */
    public static final float SUPPLY_REDUCTION = 0.10f;
    
    /** 战斗机OP成本增加数值 */
    private static final int FIGHTER_OP_INCREASE = 5;

    @Override
    public boolean affectsOPCosts() {
        return true;
    }

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 添加战斗机OP成本修改器监听器
        stats.addListener(this);
    }

    @Override
    public int getFighterOPCost(MutableShipStatsAPI stats, FighterWingSpecAPI fighter, int currCost) {
        if (currCost <= 0) {
            return currCost + 5; // 0成本战斗机增加5点
        } else if (currCost == 1) {
            return currCost + 4; // 1成本战斗机增加4点
        } else if (currCost == 2) {
            return currCost + 3; // 2成本战斗机增加3点
        } else if (currCost == 3) {
            return currCost + 2; // 3成本战斗机增加2点
        } else if (currCost == 4) {
            return currCost + 1; // 4成本战斗机增加1点
        } else {
            return currCost; // 5成本及以上战斗机不增加
        }
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) return "" + (int)(REPAIR_BONUS * 100) + "%"; // 维修速度提升
        if (index == 1) return "" + (int)(CR_RECOVERY_BONUS * 100) + "%"; // CR恢复提升
        if (index == 2) return "" + (int)(SUPPLY_REDUCTION * 100) + "%"; // 补给消耗降低
        if (index == 3) return "" + FIGHTER_OP_INCREASE; // 战斗机OP成本增加
        return null;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 3f;
        float opad = 10f;
        Color good = Misc.getPositiveHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();

        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.limit_heading"), com.fs.starfarer.api.ui.Alignment.MID, opad);

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.main",
                        Moci_TextLoader.mapOf(
                                "repair_bonus", String.valueOf((int) (REPAIR_BONUS * 100)),
                                "cr_recovery_bonus", String.valueOf((int) (CR_RECOVERY_BONUS * 100)),
                                "supply_reduction", String.valueOf((int) (SUPPLY_REDUCTION * 100)),
                                "fighter_op", String.valueOf(FIGHTER_OP_INCREASE))),
                pad, new Color[]{good, good, good, bad},
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.main_highlights",
                        Moci_TextLoader.mapOf(
                                "repair_bonus", String.valueOf((int) (REPAIR_BONUS * 100)),
                                "cr_recovery_bonus", String.valueOf((int) (CR_RECOVERY_BONUS * 100)),
                                "supply_reduction", String.valueOf((int) (SUPPLY_REDUCTION * 100)),
                                "fighter_op", String.valueOf(FIGHTER_OP_INCREASE))).toArray(new String[0]));
        tooltip.addPara(
                Moci_TextLoader.getText(TEXT_ID, "description.stack_limit"),
                pad, Misc.getHighlightColor(),
                Moci_TextLoader.getHighlights(TEXT_ID, "description.stack_limit_highlights").toArray(new String[0]));

    }
}
