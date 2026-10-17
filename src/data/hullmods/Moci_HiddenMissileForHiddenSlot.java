package data.hullmods;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;

/**
 * 隐藏装备在DECORATIVE槽位上的武器的导弹贴图
 */
public class Moci_HiddenMissileForHiddenSlot extends BaseLogisticsHullMod {

    /**
     * 隐藏Hidden槽位武器的导弹贴图
     */
    private void hideDecoSlotMissileSprites(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon == null) continue;
            
            // 只处理装备在Hidden槽位的武器
            if (weapon.getSlot() != null && 
                weapon.getSlot().isHidden() &&
                weapon.getMissileRenderData() != null) {
                
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

        hideDecoSlotMissileSprites(ship);
    }
    
}
