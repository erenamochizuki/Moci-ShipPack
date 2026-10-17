package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

import java.awt.Color;

/**
 * 广域作战雷达船插
 * 
 * 效果：
 * - 战斗视野+50%
 * - 舰载机交战距离+100%
 * - 禁用零幅能加速
 * - 禁用护盾
 * - 排幅速度×0（完全禁止排幅）
 */
public class Moci_WideAreaRadar extends BaseHullMod {
    private static final String TEXT_ID = "Moci_WideAreaRadar";

    // 战斗视野加成
    public static final float SIGHT_RADIUS_BONUS = 50f;

    // 舰载机交战距离加成
    public static final float FIGHTER_RANGE_BONUS = 100f;

    // 排幅速度乘数
    public static final float VENT_RATE_MULT = 0f;

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 增加战斗视野
        stats.getSightRadiusMod().modifyPercent(id, SIGHT_RADIUS_BONUS);

        // 增加舰载机交战距离 
        stats.getFighterWingRange().modifyPercent(id, FIGHTER_RANGE_BONUS);

        // 禁用零幅能加速 
        stats.getZeroFluxSpeedBoost().modifyMult(id, 0f);

        // 禁用强制排幅
        stats.getVentRateMult().modifyMult(id, VENT_RATE_MULT);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 禁用护盾
        ship.setShield(ShieldType.NONE, 0f, 1f, 1f);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0)
            return (int) SIGHT_RADIUS_BONUS + "%";
        if (index == 1)
            return (int) FIGHTER_RANGE_BONUS + "%";
        return null;
    }

    @Override
    public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width,
            boolean isForModSpec) {
        float pad = 3f;
        float opad = 10f;
        Color h = Misc.getHighlightColor();
        Color good = Misc.getPositiveHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();

        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.intro"), pad);
        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.effect_heading"), com.fs.starfarer.api.ui.Alignment.MID, pad);
        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.sight_bonus",
                Moci_TextLoader.mapOf("sight_bonus", String.valueOf((int) SIGHT_RADIUS_BONUS))),
                pad, good,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.sight_bonus_highlights",
                        Moci_TextLoader.mapOf("sight_bonus", String.valueOf((int) SIGHT_RADIUS_BONUS))).toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.fighter_bonus",
                Moci_TextLoader.mapOf("fighter_bonus", String.valueOf((int) FIGHTER_RANGE_BONUS))),
                pad, good,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.fighter_bonus_highlights",
                        Moci_TextLoader.mapOf("fighter_bonus", String.valueOf((int) FIGHTER_RANGE_BONUS))).toArray(new String[0]));

        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.limit_heading"),
                new Color(255, 150, 150, 255), // 文字颜色
                new Color(200, 0, 0, 150), // 背景颜色
                Alignment.MID, // 文字位置
                opad); // 与其他元素之间的间距
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.limit_intro"), pad);
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.disable_zero_flux"), pad, bad,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.disable_zero_flux_highlights").toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.disable_shield"), pad, bad,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.disable_shield_highlights").toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.disable_vent"), pad, bad,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.disable_vent_highlights").toArray(new String[0]));
    }
}
