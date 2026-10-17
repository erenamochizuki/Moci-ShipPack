package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import org.apache.log4j.Logger;

/**
 * 维修龙门架舰队效果船插
 */
public class Moci_OpenMaintenanceGantryFleetEffect extends BaseHullMod {
    private static final Logger log = Global.getLogger(Moci_OpenMaintenanceGantryFleetEffect.class);
    public static final int MAX_FLEET_GANTRY = 3; // 最大叠加数量


    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        FleetMemberAPI ship = stats.getFleetMember();
        if (ship != null && ship.getFleetData() != null && ship.getFleetData().getFleet() != null) {
            // 检查是否为玩家舰队
            if (!isPlayerFleet(ship.getFleetData().getFleet())) {
                return;
            }

            // 计算舰队中有效的维修龙门架船插数量
            int gantryCount = countActiveGantries(ship.getFleetData().getFleet());

            // 限制最大效果，防止过度叠加
            gantryCount = Math.min(gantryCount, MAX_FLEET_GANTRY);

            // 计算舰队效果强度
            float fleetRepairBonus = Moci_OpenMaintenanceGantry.REPAIR_BONUS * gantryCount;
            float fleetCRBonus = Moci_OpenMaintenanceGantry.CR_RECOVERY_BONUS * gantryCount;
            float fleetSupplyBonus = Moci_OpenMaintenanceGantry.SUPPLY_REDUCTION * gantryCount;

            // 限制最大效果，防止过度叠加
            fleetRepairBonus = Math.min(fleetRepairBonus, 0.6f); // 最大60%维修加成
            fleetCRBonus = Math.min(fleetCRBonus, 0.6f); // 最大60%CR恢复加成
            fleetSupplyBonus = Math.min(fleetSupplyBonus, 0.3f); // 最大30%补给节省

            // 应用舰队效果
            stats.getCombatEngineRepairTimeMult().modifyMult(id, 1f - fleetRepairBonus);
            stats.getCombatWeaponRepairTimeMult().modifyMult(id, 1f - fleetRepairBonus);
            stats.getBaseCRRecoveryRatePercentPerDay().modifyPercent(id, fleetCRBonus * 100f);
            stats.getSuppliesPerMonth().modifyMult(id, 1f - fleetSupplyBonus);

            // 日志输出，用于调试
            // if (ship.getId().equals(Global.getSector().getPlayerFleet().getFlagship().getId())) {
            //     log.info(String.format("维修龙门架舰队效果应用 - 船只: %s, 龙门架数量: %d, 维修加成: %.2f, CR加成: %.2f, 补给节省: %.2f",
            //             ship.getShipName(), gantryCount, fleetRepairBonus, fleetCRBonus, fleetSupplyBonus));
            // }
        }
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 检查是否在玩家舰队中
        if (ship != null && ship.getFleetMemberId() != null) {
            // 使用安全的方式检查船只是否在玩家舰队中
            boolean inPlayerFleet = false;
            CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
            if (playerFleet != null && playerFleet.getFleetData() != null) {
                for (FleetMemberAPI member : playerFleet.getFleetData().getMembersListCopy()) {
                    if (member.getId().equals(ship.getFleetMemberId())) {
                        inPlayerFleet = true;
                        break;
                    }
                }
            }

            if (!inPlayerFleet) {
                // 如果不在玩家舰队中，移除此船插
                if (ship.getVariant() != null) {
                    ship.getVariant().removePermaMod("Moci_open_maintenance_gantry_fleet_effect");
                    ship.getVariant().removeMod("Moci_open_maintenance_gantry_fleet_effect");

                    // 日志输出
                    // log.info(String.format("维修龙门架舰队效果移除 - 船只: %s 不在玩家舰队中",
                    //         ship.getName()));
                }
            }
        }
    }

    /**
     * 计算舰队中有效的维修龙门架数量
     */
    private int countActiveGantries(CampaignFleetAPI fleet) {
        if (fleet == null)
            return 0;

        int count = 0;
        for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
            if (!member.isMothballed() && member.getVariant() != null &&
                    member.getVariant().hasHullMod("Moci_open_maintenance_gantry")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 检查是否为玩家舰队
     */
    private boolean isPlayerFleet(CampaignFleetAPI fleet) {
        return fleet != null && fleet.equals(Global.getSector().getPlayerFleet());
    }


}