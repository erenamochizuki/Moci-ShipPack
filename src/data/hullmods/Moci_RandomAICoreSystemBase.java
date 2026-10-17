package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.List;
import java.util.Random;

/**
 * 消耗原版AI核心安装，并从候选列表中随机绑定舰载AI的通用船插基类。
 */
public abstract class Moci_RandomAICoreSystemBase extends Moci_ShipboardAISystemBase {

    private static final Random RANDOM = new Random();

    protected abstract String getHullmodId();

    protected abstract String getPlaceholderAiTypeId();

    protected abstract List<String> getAiPool();

    protected abstract String getRequiredCoreCommodityId();

    @Override
    protected boolean shouldHideAIDetailsBeforeAssignment() {
        return true;
    }

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        enforceSingleAISystemInRefit(stats);
        ensureBuiltInInRefit(stats);
        ensureRandomAssignment(stats == null ? null : stats.getFleetMember());
    }

    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        super.advanceInCampaign(member, amount);
        ensureBuiltInIfNotBuiltIn(member);
        ensureRandomAssignment(member);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (ship != null) {
            ensureBuiltInIfNotBuiltIn(ship.getFleetMember());
            ensureRandomAssignment(ship.getFleetMember());
        }
        syncIcon(ship);
        super.applyEffectsAfterShipCreation(ship, id);
    }

    @Override
    public boolean canBeAddedOrRemovedNow(ShipAPI ship, MarketAPI marketOrNull, CampaignUIAPI.CoreUITradeMode mode) {
        if (ship != null) {
            ensureBuiltInIfNotBuiltIn(ship.getFleetMember());
        }
        syncIcon(ship);
        return super.canBeAddedOrRemovedNow(ship, marketOrNull, mode);
    }

    @Override
    public CargoStackAPI getRequiredItem() {
        return Global.getSettings().createCargoStack(CargoAPI.CargoItemType.RESOURCES, getRequiredCoreCommodityId(), null);
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship,
                                          float width, boolean isForModSpec) {
        syncIcon(ship);
        addShipboardAIDescriptionSection(tooltip, ship, getPlaceholderAiTypeId());
    }

    protected void ensureRandomAssignment(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null || !member.getVariant().hasHullMod(getHullmodId())) {
            return;
        }

        List<String> pool = getAiPool();
        if (pool == null || pool.isEmpty()) {
            return;
        }

        Moci_ShipboardAIManager.ShipboardAIData data = Moci_ShipboardAIManager.getOrCreateData(member);
        if (data == null) {
            return;
        }

        if (data.currentAiTypeId != null && pool.contains(data.currentAiTypeId)) {
            return;
        }

        String selected = pickRandomAiType(pool);
        if (selected == null) {
            return;
        }
        Moci_ShipboardAIManager.assignAiType(member, selected, false);
    }

    protected String pickRandomAiType(List<String> pool) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        int start = RANDOM.nextInt(pool.size());
        for (int i = 0; i < pool.size(); i++) {
            String candidate = pool.get((start + i) % pool.size());
            if (Moci_ShipboardAIConfigLoader.getAiType(candidate) != null) {
                return candidate;
            }
        }
        return pool.get(0);
    }

    protected void syncIcon(ShipAPI ship) {
        // 船插图标保持为 hull_mods.csv 中定义的固定图标。
        // 角色头像只在 tooltip、AI 管理面板和自定义 UI 中展示，避免修改全局 spec 导致串图。
    }

    protected String getFallbackIcon() {
        try {
            return Global.getSettings().getCommoditySpec(getRequiredCoreCommodityId()).getIconName();
        } catch (Exception ex) {
            return "graphics/portraits/ai_pilot/Moci_system99.png";
        }
    }

    private void ensureBuiltInIfNotBuiltIn(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null || member.getHullSpec() == null) {
            return;
        }
        if (member.getFleetData() == null || member.getFleetData().getFleet() == null
                || !member.getFleetData().getFleet().isPlayerFleet()) {
            return;
        }
        if (!member.getVariant().hasHullMod(getHullmodId())) {
            return;
        }

        boolean builtInByHull = member.getHullSpec().getBuiltInMods() != null
                && member.getHullSpec().getBuiltInMods().contains(getHullmodId());
        if (builtInByHull) {
            return;
        }

        boolean alreadyPerma = member.getVariant().getPermaMods() != null
                && member.getVariant().getPermaMods().contains(getHullmodId());
        if (alreadyPerma) {
            return;
        }

        try {
            ShipVariantAPI variant = member.getVariant().clone();
            variant.setSource(VariantSource.REFIT);
            variant.setHullVariantId(Misc.genUID());
            member.setVariant(variant, false, false);

            variant.addPermaMod(getHullmodId());
            variant.removeMod(getHullmodId());

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
        if (!variant.hasHullMod(getHullmodId())) {
            return;
        }

        boolean builtInByHull = variant.getHullSpec().getBuiltInMods() != null
                && variant.getHullSpec().getBuiltInMods().contains(getHullmodId());
        if (builtInByHull) {
            return;
        }

        boolean alreadyPerma = variant.getPermaMods() != null
                && variant.getPermaMods().contains(getHullmodId());
        if (alreadyPerma) {
            variant.removeMod(getHullmodId());
            return;
        }

        try {
            FleetMemberAPI member = stats.getFleetMember();
            if (member != null && member.getVariant() != null) {
                ShipVariantAPI cloned = member.getVariant().clone();
                cloned.setSource(VariantSource.REFIT);
                cloned.setHullVariantId(Misc.genUID());
                member.setVariant(cloned, false, false);
                variant = stats.getVariant();
                if (variant == null) {
                    return;
                }
            }

            variant.addPermaMod(getHullmodId());
            variant.removeMod(getHullmodId());

            if (Global.getSector() != null && Global.getSector().getPlayerFleet() != null) {
                Global.getSector().getPlayerFleet().forceSync();
            }
        } catch (Exception ignored) {
            // 船插功能容错，不影响主流程
        }
    }
}
