package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * 哈罗舰载AI系统船插。
 */
public class Moci_HaroAISystem extends Moci_ShipboardAISystemBase {

    public static final String HULLMOD_ID = "Moci_HaroAISystem";
    public static final String AI_TYPE_ID = "Moci_haro";
    private static final String TEXT_ID = "Moci_HaroAISystem";

    @Override
    protected String getHullmodId() {
        return HULLMOD_ID;
    }

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        enforceSingleAISystemInRefit(stats);
        ensureBuiltInInRefit(stats);
    }

    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        super.advanceInCampaign(member, amount);
        ensureBuiltInIfNotBuiltIn(member);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        syncIcon(ship);
        if (ship != null) {
            ensureBuiltInIfNotBuiltIn(ship.getFleetMember());
        }
        super.applyEffectsAfterShipCreation(ship, id);
    }

    @Override
    public boolean canBeAddedOrRemovedNow(ShipAPI ship, MarketAPI marketOrNull, CampaignUIAPI.CoreUITradeMode mode) {
        syncIcon(ship);
        if (ship != null) {
            ensureBuiltInIfNotBuiltIn(ship.getFleetMember());
        }
        return super.canBeAddedOrRemovedNow(ship, marketOrNull, mode);
    }

    private void ensureBuiltInIfNotBuiltIn(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null || member.getHullSpec() == null) {
            return;
        }
        if (member.getFleetData() == null || member.getFleetData().getFleet() == null
                || !member.getFleetData().getFleet().isPlayerFleet()) {
            return;
        }
        if (!member.getVariant().hasHullMod(HULLMOD_ID)) {
            return;
        }

        boolean builtInByHull = member.getHullSpec().getBuiltInMods() != null
                && member.getHullSpec().getBuiltInMods().contains(HULLMOD_ID);
        if (builtInByHull) {
            return;
        }

        boolean alreadyPerma = member.getVariant().getPermaMods() != null
                && member.getVariant().getPermaMods().contains(HULLMOD_ID);
        if (alreadyPerma) {
            return;
        }

        try {
            ShipVariantAPI variant = member.getVariant().clone();
            variant.setSource(VariantSource.REFIT);
            variant.setHullVariantId(Misc.genUID());
            member.setVariant(variant, false, false);

            variant.addPermaMod(HULLMOD_ID);
            variant.removeMod(HULLMOD_ID);

            if (Global.getSector() != null && Global.getSector().getPlayerFleet() != null) {
                Global.getSector().getPlayerFleet().forceSync();
            }
        } catch (Exception ignored) {
            // 船插功能容错，不影响主流程
        }
    }

    private void ensureBuiltInInRefit(MutableShipStatsAPI stats) {
        if (stats == null || stats.getVariant() == null || stats.getVariant().getHullSpec() == null) {
            return;
        }
        ShipVariantAPI variant = stats.getVariant();
        if (!variant.hasHullMod(HULLMOD_ID)) {
            return;
        }

        boolean builtInByHull = variant.getHullSpec().getBuiltInMods() != null
                && variant.getHullSpec().getBuiltInMods().contains(HULLMOD_ID);
        if (builtInByHull) {
            return;
        }

        boolean alreadyPerma = variant.getPermaMods() != null
                && variant.getPermaMods().contains(HULLMOD_ID);
        if (alreadyPerma) {
            variant.removeMod(HULLMOD_ID);
            return;
        }

        try {
            FleetMemberAPI member = stats.getFleetMember();
            if (member != null && member.getVariant() != null) {
                ShipVariantAPI v2 = member.getVariant().clone();
                v2.setSource(VariantSource.REFIT);
                v2.setHullVariantId(Misc.genUID());
                member.setVariant(v2, false, false);
                variant = stats.getVariant();
                if (variant == null) {
                    return;
                }
            }

            variant.addPermaMod(HULLMOD_ID);
            variant.removeMod(HULLMOD_ID);

            if (Global.getSector() != null && Global.getSector().getPlayerFleet() != null) {
                Global.getSector().getPlayerFleet().forceSync();
            }
        } catch (Exception ignored) {
            // 船插功能容错，不影响主流程
        }
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) {
            return Moci_TextLoader.getText(TEXT_ID, "description.param_ai_name");
        }
        if (index == 1) {
            return Moci_TextLoader.getText(TEXT_ID, "description.param_ai_category");
        }
        return null;
    }

    @Override
    public CargoStackAPI getRequiredItem() {
        // 安装时需要消耗哈罗AI核心（商品）
        return Global.getSettings().createCargoStack(CargoAPI.CargoItemType.RESOURCES, "Moci_AICore_Haro", null);
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship,
                                          float width, boolean isForModSpec) {
        syncIcon(ship);
        addShipboardAIDescriptionSection(tooltip, ship, AI_TYPE_ID);
    }

    private void syncIcon(ShipAPI ship) {
        // 船插图标保持固定，哈罗头像改为仅在 tooltip 和专用 UI 中展示。
    }
}


