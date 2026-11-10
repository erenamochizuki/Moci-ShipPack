package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.campaign.fighter.Moci_FighterLoadoutEligibility;

/** Enables carrier-bay loadout management; deliberately grants no combat modifiers. */
public class Moci_AirSquadronManager extends BaseHullMod {
    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && ship.getMutableStats() != null
                && Moci_FighterLoadoutEligibility.getCarrierLockReason(ship.getVariant()) == null
                && editableBays(ship) >= 1;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        String carrierReason = ship == null ? null : Moci_FighterLoadoutEligibility.getCarrierLockReason(ship.getVariant());
        if (carrierReason != null) return carrierReason;
        if (ship != null && ship.getMutableStats() != null
                && ship.getMutableStats().getNumFighterBays().getModifiedValue() >= 1f) {
            return "船体内置联队不能改装，需要至少一个非内置机库";
        }
        return "需要至少一个可用的舰载机机库";
    }

    /**
     * 可编辑的机库数。船体内置联队的机位由引擎锁定：HullVariantSpec.setWingId 会把内置机位换回船体自带联队，
     * 改装界面同步变体时也会重置，所以这些机库只显示、不计入。判断方式与 Moci_FighterLoadoutService.collectBays 一致。
     */
    private static int editableBays(ShipAPI ship) {
        int bays = (int) ship.getMutableStats().getNumFighterBays().getModifiedValue();
        ShipVariantAPI variant = ship.getVariant();
        ShipHullSpecAPI hull = variant == null ? null : variant.getHullSpec();
        int editable = 0;
        for (int i = 0; i < bays; i++) {
            if (hull == null || !hull.isBuiltInWing(i)) editable++;
        }
        return editable;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize,
            ShipAPI ship, float width, boolean isForModSpec) {
        Color highlight = Misc.getHighlightColor();
        float pad = 10f;

        // 引擎按字符串匹配高亮，且要求高亮词两侧是空白、ASCII 标点或段落首尾；紧贴汉字或中文标点
        // （“”，。；）的词不会变色。所以高亮词一律写成 " %s "，与原版中文说明的「提高 %s 装甲值」一致。
        tooltip.addPara("允许在情报界面的 %s 中调整 %s 的 %s 装配。 %s ， %s 。",
                pad, highlight, "编队管理", "母舰", "舰载机联队", "每个机库分别设置", "不附加战斗属性");

        tooltip.addSectionHeading("使用方法",
                new Color(150, 205, 255, 255), new Color(20, 80, 140, 150), Alignment.MID, pad);
        tooltip.addPara("在情报界面打开 %s ，选择 %s ；再选择 %s 和要调整的 %s ，完成修改后点击 %s 。每支联队按 %s 结算。",
                pad, highlight, "编队管理", "航空中队管理", "母舰", "机库联队", "应用装配", "一套武器配置");

        tooltip.addSectionHeading("注意事项",
                new Color(255, 150, 150, 255), new Color(130, 0, 0, 150), Alignment.MID, pad);
        tooltip.addPara("安装 %s ，但母舰 %s 。 %s 。",
                pad, highlight, "无需分配军官", "至少要有一个非内置机库", "自动化舰船不能安装");
        tooltip.addPara("卸下船插后，退出改装或市场界面时，系统会 %s ，并把额外投入的武器 %s 。货舱中的 %s %s 。本船插 %s 。",
                pad, highlight, "恢复联队原型", "退回舰队货舱", "自定义联队 LPC", "保留原有装配", "不能永久内置");
    }
}
