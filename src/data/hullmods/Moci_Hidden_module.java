package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 隐藏模块船插
 * 
 * 功能：将舰船的所有子模块隐藏到屏幕外并设置为无碰撞
 * 用途：配合Zero浮游炮系统，隐藏原始模块，通过复制模块装配来部署浮游炮
 */
public class Moci_Hidden_module extends BaseHullMod {

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null) {
            return;
        }
        
        // 遍历所有子模块
        for (ShipAPI child : ship.getChildModulesCopy()) {
            if (child != null) {
                // 将模块移动到屏幕外
                child.getLocation().set(0f, -1000000f);
                // 设置为无碰撞
                child.setCollisionClass(CollisionClass.NONE);
            }
        }
    }
}
