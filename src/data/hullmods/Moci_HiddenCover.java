package data.hullmods;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;

public class Moci_HiddenCover extends BaseLogisticsHullMod {

    private void hideWeaponCovers(ShipAPI ship) {
        if (ship.getLargeHardpointCover() != null) {
            ship.getLargeHardpointCover().setSize(0.0f, 0.0f);
        }
        if (ship.getMediumHardpointCover() != null) {
            ship.getMediumHardpointCover().setSize(0.0f, 0.0f);
        }
        if (ship.getSmallHardpointCover() != null) {
            ship.getSmallHardpointCover().setSize(0.0f, 0.0f);
        }
        if (ship.getLargeTurretCover() != null) {
            ship.getLargeTurretCover().setSize(0.0f, 0.0f);
        }
        if (ship.getMediumTurretCover() != null) {
            ship.getMediumTurretCover().setSize(0.0f, 0.0f);
        }
        if (ship.getSmallTurretCover() != null) {
            ship.getSmallTurretCover().setSize(0.0f, 0.0f);
        }
        
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon != null && (weapon.getSlot().isTurret() || weapon.getSlot().isHardpoint())) {
                weapon.ensureClonedSpec();
            }
        }
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null) return;

        hideWeaponCovers(ship);
    }
    
} 