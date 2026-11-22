package data.campaign.fighter;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** One read-only pass over player ships; temporary old-price specs never enter the registry. */
final class Moci_FighterPricingReview {
    private final MutableCharacterStatsAPI playerStats;
    private final Map<String, FighterWingSpecAPI> legacyWings = new HashMap<>();
    private final Set<FleetMemberAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Consumer<String> notify;
    private boolean complete = true;

    private Moci_FighterPricingReview(SectorAPI sector, Consumer<String> notify) {
        playerStats = sector.getPlayerStats(); this.notify = notify;
    }

    static boolean check(SectorAPI sector, Consumer<String> notify) {
        Moci_FighterPricingReview audit = new Moci_FighterPricingReview(sector, notify);
        CampaignFleetAPI fleet = sector.getPlayerFleet();
        if (fleet == null || fleet.getFleetData() == null) return false;
        audit.inspect(fleet.getFleetData(), "");
        audit.inspectStored(fleet.getCargo(), "");
        if (sector.getEconomy() != null) for (MarketAPI market : sector.getEconomy().getMarketsCopy()) {
            try {
                for (SubmarketAPI sub : market.getSubmarketsCopy()) {
                    if (sub != null && Moci_FighterMarketGuard.isPlayerStorage(sub)) {
                        audit.inspectStored(sub.getCargoNullOk(), market.getName() + "仓库：");
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                audit.failed(market.getName(), ex);
            }
        }
        return audit.complete;
    }

    private void failed(String context, Throwable ex) {
        complete = false;
        Global.getLogger(Moci_FighterPricingReview.class).warn("Moci-AirSquadron|OP review|" + context, ex);
    }

    private void inspectStored(CargoAPI cargo, String location) {
        if (cargo != null) inspect(cargo.getMothballedShips(), location);
    }

    private void inspect(FleetDataAPI ships, String location) {
        if (ships == null) return;
        for (FleetMemberAPI member : ships.getMembersListCopy()) {
            if (member == null || !seen.add(member)) continue;
            String name = member.getShipName();
            String label = location + (name == null || name.isBlank() ? member.getId() : name);
            try {
                inspectVariant(member.getVariant(), label, Collections.newSetFromMap(new IdentityHashMap<>()));
            } catch (RuntimeException | LinkageError ex) {
                failed(label, ex);
            }
        }
    }

    private void inspectVariant(ShipVariantAPI variant, String label, Set<ShipVariantAPI> visited) {
        if (variant == null || variant.getHullSpec() == null || !visited.add(variant)) return;
        long increase = 0;
        List<String> bays = variant.getWings();
        for (int i = 0; i < bays.size(); i++) {
            String id = bays.get(i);
            if (!Moci_FighterLoadoutService.isCustomWingId(id) || variant.getHullSpec().isBuiltInWing(i)) continue;
            if (!legacyWings.containsKey(id)) legacyWings.put(id, Moci_FighterLoadoutService.legacyPricedWing(id));
            FighterWingSpecAPI old = legacyWings.get(id);
            if (old == null) continue;
            MutableShipStatsAPI opStats = variant.getStatsForOpCosts();
            FighterWingSpecAPI current = Moci_FighterLoadoutService.getWingSpec(id);
            increase += Math.round(current.getOpCost(opStats)) - Math.round(old.getOpCost(opStats));
        }
        if (increase > 0) {
            int remaining = variant.getUnusedOP(playerStats);
            if (remaining < 0 && remaining + increase >= 0) {
                notify.accept("航空中队管理：" + label + " 因联队新计价超出 " + -(long) remaining
                        + " OP，请复核母舰装配。已保留原装配，未扣货物。");
            }
        }
        Map<String, String> modules = variant.getStationModules();
        if (modules != null && !modules.isEmpty()) for (String slot : variant.getModuleSlots()) {
            inspectVariant(variant.getModuleVariant(slot), label + " / " + slot, visited);
        }
    }
}
