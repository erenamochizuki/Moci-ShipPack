package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.magiclib.util.MagicIncompatibleHullmods;
import data.scripts.util.Moci_TextLoader;

import java.awt.Color;

/**
 * 装甲转结构系统
 * 将舰船的装甲值降低为0，并按舰级将装甲转换为结构值。
 */
public class Moci_ArmorToHull extends BaseHullMod {
    private static final String TEXT_ID = "Moci_armor_to_hull";
    
    // 不同舰级的装甲转结构比例
    private static final float CAPITAL_ARMOR_TO_HULL_RATIO = 25f;
    private static final float CRUISER_ARMOR_TO_HULL_RATIO = 20f;
    private static final float DESTROYER_ARMOR_TO_HULL_RATIO = 15f;
    private static final float FRIGATE_ARMOR_TO_HULL_RATIO = 10f;

    // 受损装甲时的转换比例
    private static final float CAPITAL_ARMOR_TO_HULL_RATIO_DAMAGED = 20f;
    private static final float CRUISER_ARMOR_TO_HULL_RATIO_DAMAGED = 16f;
    private static final float DESTROYER_ARMOR_TO_HULL_RATIO_DAMAGED = 12f;
    private static final float FRIGATE_ARMOR_TO_HULL_RATIO_DAMAGED = 8f;
    
    // 装甲削减倍率（直接设为0）
    private static final float ARMOR_MULT = 0f;

    private float getArmorToHullRatio(HullSize hullSize, boolean hasDamagedArmor) {
        switch (hullSize) {
            case CAPITAL_SHIP:
                return hasDamagedArmor ? CAPITAL_ARMOR_TO_HULL_RATIO_DAMAGED : CAPITAL_ARMOR_TO_HULL_RATIO;
            case CRUISER:
                return hasDamagedArmor ? CRUISER_ARMOR_TO_HULL_RATIO_DAMAGED : CRUISER_ARMOR_TO_HULL_RATIO;
            case DESTROYER:
                return hasDamagedArmor ? DESTROYER_ARMOR_TO_HULL_RATIO_DAMAGED : DESTROYER_ARMOR_TO_HULL_RATIO;
            case FRIGATE:
            case FIGHTER:
            default:
                return hasDamagedArmor ? FRIGATE_ARMOR_TO_HULL_RATIO_DAMAGED : FRIGATE_ARMOR_TO_HULL_RATIO;
        }
    }

    private String getArmorToHullRatioSummary(boolean hasDamagedArmor) {
        float frigateRatio = getArmorToHullRatio(HullSize.FRIGATE, hasDamagedArmor);
        float destroyerRatio = getArmorToHullRatio(HullSize.DESTROYER, hasDamagedArmor);
        float cruiserRatio = getArmorToHullRatio(HullSize.CRUISER, hasDamagedArmor);
        float capitalRatio = getArmorToHullRatio(HullSize.CAPITAL_SHIP, hasDamagedArmor);
        return String.format("%.0f/%.0f/%.0f/%.0f", frigateRatio, destroyerRatio, cruiserRatio, capitalRatio);
    }
    
    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        ShipVariantAPI variant = stats.getVariant();
        
        // 检查并移除冲突的船插（使用MagicLib自动移除）
        if (variant.hasHullMod("armoredweapons")) {
            MagicIncompatibleHullmods.removeHullmodWithWarning(variant, "armoredweapons", spec.getId());
        }
        if (variant.hasHullMod("heavyarmor")) {
            MagicIncompatibleHullmods.removeHullmodWithWarning(variant, "heavyarmor", spec.getId());
        }
        
        // 获取舰船规格中的基础装甲值
        float baseArmor = variant.getHullSpec().getArmorRating();
        
        // 检查是否有受损装甲，决定转换比例
        boolean hasDamagedArmor = variant.hasHullMod("comp_armor");
        float ratio = getArmorToHullRatio(hullSize, hasDamagedArmor);
        
        // 计算转换后的结构加成
        float hullBonus = baseArmor * ratio;
        
        // 将装甲值降低为0
        stats.getArmorBonus().modifyMult(id, ARMOR_MULT);
        
        // 增加结构值
        stats.getHullBonus().modifyFlat(id, hullBonus);
    }
    
    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return !ship.getVariant().hasHullMod("ablative_armor") &&
               !ship.getVariant().hasHullMod("armoredweapons") &&
               !ship.getVariant().hasHullMod("heavyarmor") &&
               !ship.getVariant().hasHullMod("Moci_LivingShip");
    }

    @Override
    public CargoStackAPI getRequiredItem() {
        return Global.getSettings().createCargoStack(CargoAPI.CargoItemType.SPECIAL,
                new SpecialItemData("Moci_vortexcore_Inactivation", null), null);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        // 返回提示信息告诉玩家为什么不能安装
        if (ship.getVariant().hasHullMod("ablative_armor")) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_ablative");
        }
        if (ship.getVariant().hasHullMod("Moci_LivingShip")) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_living");
        }
        if (ship.getVariant().hasHullMod("heavyarmor")) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_heavy");
        }
        if (ship.getVariant().hasHullMod("armoredweapons")) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_armoredweapons");
        }
        return null;
    }
    
    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, 
                                         ShipAPI ship, float width, boolean isForModSpec) {
        if (ship == null) return;
        
        float pad = 6f;
        Color highlight = Misc.getHighlightColor();
        Color warning = Misc.getTextColor();
        
        // 添加小标题
        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.current_heading"), 
            Misc.getBasePlayerColor(), 
            Misc.getDarkPlayerColor(), 
            com.fs.starfarer.api.ui.Alignment.MID, 
            pad);
        
        // 获取原始装甲值
        float baseArmor = ship.getHullSpec().getArmorRating();
        
        // 检查是否有受损装甲
        boolean hasDamagedArmor = ship.getVariant().hasHullMod("comp_armor");
        float ratio = getArmorToHullRatio(ship.getHullSize(), hasDamagedArmor);
        float convertedHull = baseArmor * ratio;
        
        // 添加红色警告
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.remove_armor"), pad, 
            Misc.getNegativeHighlightColor(),
            Moci_TextLoader.getHighlights(TEXT_ID, "description.remove_armor_highlights").toArray(new String[0]));
        
        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.hull_bonus",
                Moci_TextLoader.mapOf("converted_hull", String.format("%.0f", convertedHull))), pad, 
            highlight, 
            Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.hull_bonus_highlights",
                    Moci_TextLoader.mapOf("converted_hull", String.format("%.0f", convertedHull))).toArray(new String[0]));
        
        if (hasDamagedArmor) {
            tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.ratio_damaged",
                    Moci_TextLoader.mapOf("ratio_summary", getArmorToHullRatioSummary(true))), pad,
                warning, 
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.ratio_damaged_highlights",
                        Moci_TextLoader.mapOf("ratio_summary", getArmorToHullRatioSummary(true))).toArray(new String[0]));
        } else {
            tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.ratio_normal",
                    Moci_TextLoader.mapOf("ratio_summary", getArmorToHullRatioSummary(false))), pad,
                highlight, 
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.ratio_normal_highlights",
                        Moci_TextLoader.mapOf("ratio_summary", getArmorToHullRatioSummary(false))).toArray(new String[0]));
        }
    }
    
    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return Moci_TextLoader.getText(TEXT_ID, "description.param_source");
        if (index == 1) return Moci_TextLoader.getText(TEXT_ID, "description.param_purpose");
        return null;
    }
}
