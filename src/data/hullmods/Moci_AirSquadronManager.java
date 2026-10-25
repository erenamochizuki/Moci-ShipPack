package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
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
        tooltip.addPara("在情报界面的编队管理中选择“航空中队管理”，点击母舰即可逐机库调整适用联队的武器。无需分配军官，自动化或无人舰船无法安装。",
                10f, Misc.getHighlightColor(), "航空中队管理", "逐机库");
        tooltip.addPara("每支联队按一套武器结算。卸下本船插后，退出改装及市场界面时会安全恢复原型，额外投入的武器返还舰队货舱。",
                10f, Misc.getHighlightColor(), "一套武器", "恢复原型");
        tooltip.addPara("不附加战斗属性，不能永久内置。货舱中的自定义联队 LPC 保留原有装配。", 10f);
    }
}
