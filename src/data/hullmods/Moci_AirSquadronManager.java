package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
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
                && ship.getMutableStats().getNumFighterBays().getModifiedValue() >= 1f;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        String carrierReason = ship == null ? null : Moci_FighterLoadoutEligibility.getCarrierLockReason(ship.getVariant());
        if (carrierReason != null) return carrierReason;
        return "需要至少一个可用的舰载机机库";
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize,
            ShipAPI ship, float width, boolean isForModSpec) {
        Color highlight = Misc.getHighlightColor();
        float pad = 10f;

        tooltip.addPara("允许在情报界面的编队管理中调整母舰的舰载机联队装配。每个机库分别设置，不附加战斗属性。",
                pad, highlight, "编队管理", "母舰", "舰载机联队", "每个机库分别设置", "不附加战斗属性");

        tooltip.addSectionHeading("使用方法",
                new Color(150, 205, 255, 255), new Color(20, 80, 140, 150), Alignment.MID, pad);
        tooltip.addPara("在情报界面打开“编队管理”，选择“航空中队管理”；再选择母舰和要调整的机库联队，完成修改后点击“应用装配”。每支联队按一套武器配置结算。",
                pad, highlight, "编队管理", "航空中队管理", "母舰", "机库联队", "应用装配", "一套武器配置");

        tooltip.addSectionHeading("注意事项",
                new Color(255, 150, 150, 255), new Color(130, 0, 0, 150), Alignment.MID, pad);
        tooltip.addPara("安装无需分配军官，但母舰至少要有一个机库。自动化或无人舰船不能安装。",
                pad, highlight, "无需分配军官", "至少要有一个机库", "自动化或无人舰船不能安装");
        tooltip.addPara("卸下船插后，退出改装或市场界面时，系统会恢复联队原型，并把额外投入的武器退回舰队货舱。货舱中的自定义联队 LPC 保留原有装配。本船插不能永久内置。",
                pad, highlight, "恢复联队原型", "退回舰队货舱", "自定义联队 LPC", "保留原有装配", "不能永久内置");
    }
}
