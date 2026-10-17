/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import org.magiclib.util.MagicIncompatibleHullmods;

import data.scripts.util.Moci_TextLoader;

/**
 * 波动引擎
 */
public class Moci_Yamato_hullmod2 extends BaseHullMod {
    private static final String TEXT_ID = "Moci_Yamato_hullmod2";

    private static final int MANEUVER_INCREASE = 80;
    private static final int WEAPON_DAMAGE_INCREASE = 30;
    private static final int WEAPON_DAMAGE_INCREASE_S = 50;
    private static final int SUPPLY_PM = 20;
    
    // 允许安装的舰船ID
    private static final String ALLOWED_HULL_ID = "Moci_Yamato";

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getZeroFluxMinimumFluxLevel().modifyFlat(id, -10);

        stats.getAcceleration().modifyPercent(id, MANEUVER_INCREASE);
        stats.getDeceleration().modifyPercent(id, MANEUVER_INCREASE);
        //    stats.getMaxTurnRate().modifyPercent(id, MANEUVER_INCREASE);
        stats.getTurnAcceleration().modifyPercent(id, MANEUVER_INCREASE);
        stats.getMaxSpeed().modifyPercent(id, MANEUVER_INCREASE);
        
        if (!isSMod(stats)) {
            stats.getSuppliesPerMonth().modifyPercent(id, SUPPLY_PM);
            stats.getBallisticWeaponDamageMult().modifyPercent(id, WEAPON_DAMAGE_INCREASE);
            stats.getEnergyWeaponDamageMult().modifyPercent(id, WEAPON_DAMAGE_INCREASE);
            //    stats.getMissileWeaponDamageMult().modifyPercent(id, WEAPON_DAMAGE_INCREASE);
        } else {
            stats.getBallisticWeaponDamageMult().modifyPercent(id, WEAPON_DAMAGE_INCREASE_S);
            stats.getEnergyWeaponDamageMult().modifyPercent(id, WEAPON_DAMAGE_INCREASE_S);
            //    stats.getMissileWeaponDamageMult().modifyPercent(id, WEAPON_DAMAGE_INCREASE_S);
        }
        ShipVariantAPI variant = stats.getVariant();
        if (variant.hasHullMod(HullMods.AUXILIARY_THRUSTERS)) {
            MagicIncompatibleHullmods.removeHullmodWithWarning(stats.getVariant(), HullMods.AUXILIARY_THRUSTERS, spec.getId());
        }
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) {
            return MANEUVER_INCREASE + "%";
        }
        if (index == 1) {
            return WEAPON_DAMAGE_INCREASE + "%";
        }
        if (index == 2) {
            return SUPPLY_PM + "%";
        }
        return null;
    }

    @Override
    public String getSModDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) {
            return WEAPON_DAMAGE_INCREASE_S + "%";
        }
        return null;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        
        // 修复D-hull问题：获取基础船体ID
        String hullId = ship.getHullSpec().getDParentHullId();
        if (hullId == null) {
            hullId = ship.getHullSpec().getHullId();
        }
        
        // 检查是否为允许的舰船ID
        return ALLOWED_HULL_ID.equals(hullId);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        
        // 修复D-hull问题：获取基础船体ID
        String hullId = ship.getHullSpec().getDParentHullId();
        if (hullId == null) {
            hullId = ship.getHullSpec().getHullId();
        }
        
        if (!ALLOWED_HULL_ID.equals(hullId)) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_yamato_only");
        }
        
        return null;
    }

}
