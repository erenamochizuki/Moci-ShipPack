package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

import java.awt.*;
import java.util.Map;

public class Moci_CargoCarrierDeck extends BaseHullMod {
    private static final String TEXT_ID = "Moci_cargo_carrier_deck";

    private static final float REFIT_TIME_MULT = 1.5f; // 整备时间增加50%
    private static final float REARM_TIME_FRACTION = 0.4f; // 40%基础整备时间
    private static final float OP_PER_CARGO = 5f; // 每5点OP减少5%货舱
    private static final float MAX_CARGO_REDUCTION = 0.8f; // 最大减少80%

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 应用整备时间惩罚
        stats.getFighterRefitTimeMult().modifyMult(id, REFIT_TIME_MULT);

        // 应用重新起飞时间惩罚
        stats.getDynamic().getMod("fighter_rearm_time_extra_fraction_of_base_refit_time_mod").modifyFlat(id, REARM_TIME_FRACTION);

        // 计算货舱容量惩罚
        float opCost = 0f;
        for (String wingId : stats.getVariant().getFittedWings()) {
            FighterWingSpecAPI spec = Global.getSettings().getFighterWingSpec(wingId);
            opCost += spec.getOpCost(stats);
        }

        // 应用货舱容量惩罚
        float cargoReduction = Math.min(opCost / OP_PER_CARGO * 0.05f, MAX_CARGO_REDUCTION);
        stats.getCargoMod().modifyMult(id, 1f - cargoReduction);
    }

    @Override
    public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float opad = 10f;
        Color bad = Misc.getNegativeHighlightColor();
        Map<String, String> replacements = Moci_TextLoader.mapOf(
                "refit_time", String.valueOf(Math.round((REFIT_TIME_MULT - 1f) * 100)),
                "rearm_time", String.valueOf(Math.round(REARM_TIME_FRACTION * 100)),
                "op_per_cargo", String.valueOf((int) OP_PER_CARGO),
                "cargo_step", "5%",
                "max_reduction", String.valueOf(Math.round(MAX_CARGO_REDUCTION * 100))
        );

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.intro", replacements),
                opad,
                bad,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.intro_highlights", replacements).toArray(new String[0])
        );

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.rearm", replacements),
                opad,
                bad,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.rearm_highlights", replacements).toArray(new String[0])
        );

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.cargo_penalty", replacements),
                opad,
                bad,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.cargo_penalty_highlights", replacements).toArray(new String[0])
        );

        if (!isForModSpec && ship != null) {
            MutableShipStatsAPI stats = ship.getMutableStats();
            float opCost = 0f;
            for (String wingId : ship.getVariant().getFittedWings()) {
                FighterWingSpecAPI spec = Global.getSettings().getFighterWingSpec(wingId);
                opCost += spec.getOpCost(stats);
            }

            float cargoReduction = Math.min(opCost / OP_PER_CARGO * 0.05f, MAX_CARGO_REDUCTION);
            if (cargoReduction > 0) {
                Map<String, String> currentReplacements = Moci_TextLoader.mapOf(
                        "current_reduction", String.valueOf(Math.round(cargoReduction * 100))
                );
                tooltip.addPara(
                        Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.current_cargo", currentReplacements),
                        opad,
                        bad,
                        Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.current_cargo_highlights", currentReplacements).toArray(new String[0])
                );
            }
        }
    }
}
