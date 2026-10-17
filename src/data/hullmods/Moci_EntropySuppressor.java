package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

import java.awt.*;

public class Moci_EntropySuppressor extends BaseHullMod {
    private static final String TEXT_ID = "Moci_entropy_suppressor";
    
    private static final float WEAPON_RECOVERY_BONUS = 0.99f; // 武器恢复速度提高
    private static final float ENGINE_RECOVERY_BONUS = 0.99f; // 引擎恢复速度提高
    private static final float ARMOR_MULT = 0.8f; // 护甲降低30%
    private static final float FLUX_CAPACITY_MULT = 0.8f; // 辐能上限降低30%

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 提高武器和引擎恢复速度
        stats.getCombatWeaponRepairTimeMult().modifyMult(id, 1f - WEAPON_RECOVERY_BONUS);
        stats.getCombatEngineRepairTimeMult().modifyMult(id, 1f - ENGINE_RECOVERY_BONUS);
        
        // 降低护甲和辐能上限
        stats.getArmorBonus().modifyMult(id, ARMOR_MULT);
        stats.getFluxCapacity().modifyMult(id, FLUX_CAPACITY_MULT);
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        // 定义间距常量
        float pad = 3f;   // 小间距

        // 获取高亮颜色
        Color h = Misc.getHighlightColor();
        // 获取负面效果颜色
        Color bad = Misc.getNegativeHighlightColor();

        // 添加详细描述内容

        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.repair"), pad, h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.repair_highlights").toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.armor_penalty"), pad, bad,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.armor_penalty_highlights").toArray(new String[0]));
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.flux_penalty"), pad, bad,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.flux_penalty_highlights").toArray(new String[0]));

    }
}
