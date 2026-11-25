package data.hullmods.shipboardai;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

public abstract class Moci_InstallableAISystemBase extends Moci_ShipboardAISystemBase {
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        enforceSingleAISystemInRefit(stats);
    }

    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        ensureBuiltInIfInstalled(member);
        super.advanceInCampaign(member, amount);
    }

    private void ensureBuiltInIfInstalled(FleetMemberAPI member) {
        if (member == null || member.getFleetData() == null || member.getFleetData().getFleet() == null
                || !member.getFleetData().getFleet().isPlayerFleet()) return;
        ShipVariantAPI variant = member.getVariant();
        String hullmodId = getHullmodId();
        if (variant == null || !variant.hasHullMod(hullmodId) || Moci_ShipboardAISystemRegistry.isPermanent(variant, hullmodId)
                || !Moci_ShipboardAISystemRegistry.getConflictingHullmods(variant, hullmodId).isEmpty()) return;

        ShipVariantAPI installed = variant.clone();
        installed.setSource(VariantSource.REFIT);
        installed.setHullVariantId(Misc.genUID());
        installed.addPermaMod(hullmodId, false);
        member.setVariant(installed, false, false);
    }

    protected boolean showInstallationHint(TooltipMakerAPI tooltip, ShipAPI ship) {
        ShipVariantAPI variant = ship == null ? null : ship.getVariant();
        if (variant == null || !variant.hasHullMod(getHullmodId()) || Moci_ShipboardAISystemRegistry.isPermanent(variant, getHullmodId())) return false;
        tooltip.addPara(Moci_ShipboardAITextRegistry.getUiText("tooltip_install_pending", "尚未内置，可以撤销安装。内置后才会生成 AI 军官。"),
                Misc.getHighlightColor(), 8f);
        return true;
    }
}
