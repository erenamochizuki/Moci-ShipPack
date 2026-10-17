package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

public class Moci_HighEnergyFocus extends BaseHullMod {
    private static final String TEXT_ID = "Moci_high_energy_focus";
    
    private static final float ENERGY_DAMAGE_BONUS = 0.5f; // 能量武器伤害提高50%
    private static final float ENERGY_FLUX_MULT = 1.5f; // 能量武器辐能消耗提高50%

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 提高能量武器伤害
        stats.getEnergyWeaponDamageMult().modifyMult(id, 1f + ENERGY_DAMAGE_BONUS);
        // 提高能量武器辐能消耗
        stats.getEnergyWeaponFluxCostMod().modifyMult(id, ENERGY_FLUX_MULT);
    }


    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (!ship.hasListenerOfClass(HighEnergyFocusScript.class)) {
            ship.addListener(new HighEnergyFocusScript(ship));
        }
    }

    public static class HighEnergyFocusScript implements AdvanceableListener {
        private final ShipAPI ship;

        public HighEnergyFocusScript(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public void advance(float amount) {
            if (ship == null) return;
            
            ShipSystemAPI system = ship.getSystem();
            if (system != null) {
                // 强制禁用系统
                ship.setShipSystemDisabled(true);
            }
        }
    }


    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {

        float pad = 8f;
        float opad = 5f;

        Color h = Misc.getHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();

        // 添加详细描述内容
        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.warning_heading"),
                new Color(255,150,150,255), //文字颜色
                new Color(200,0,0,150), //背景颜色
                Alignment.MID, // 文字位置
                opad); // 与其他元素之间的间距

        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.damage_bonus",
                        Moci_TextLoader.mapOf("damage_bonus", String.valueOf((int)(ENERGY_DAMAGE_BONUS * 100)))),
                pad, h,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.damage_bonus_highlights",
                        Moci_TextLoader.mapOf("damage_bonus", String.valueOf((int)(ENERGY_DAMAGE_BONUS * 100)))).toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.flux_penalty",
                        Moci_TextLoader.mapOf("flux_penalty", String.valueOf((int)((ENERGY_FLUX_MULT - 1) * 100)))),
                pad, bad,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.flux_penalty_highlights",
                        Moci_TextLoader.mapOf("flux_penalty", String.valueOf((int)((ENERGY_FLUX_MULT - 1) * 100)))).toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.disable_system"), pad, bad,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.disable_system_highlights").toArray(new String[0]));


    }
}
