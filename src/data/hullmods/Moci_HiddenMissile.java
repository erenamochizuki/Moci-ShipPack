package data.hullmods;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;

public class Moci_HiddenMissile extends BaseLogisticsHullMod {

    private void hideMissileSprites(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon != null && weapon.getMissileRenderData() != null) {
                for (MissileRenderDataAPI render : weapon.getMissileRenderData()) {
                    if (render != null && render.getSprite() != null) {
                        render.getSprite().setWidth(0);
                        render.getSprite().setHeight(0);
                    }
                }
            }
        }
    }
    
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null) return;

        hideMissileSprites(ship);
    }
    
} 