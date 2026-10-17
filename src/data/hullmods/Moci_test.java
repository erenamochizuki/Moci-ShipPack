package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 测试船插 - 打印舰船规格中的所有标签
 */
public class Moci_test extends BaseHullMod {

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 打印舰船ID
        Global.getLogger(this.getClass()).info("舰船ID: " + ship.getHullSpec().getHullId());
        
        // 打印舰船规格中的所有标签
        if (ship.getHullSpec().getTags() != null && !ship.getHullSpec().getTags().isEmpty()) {
            Global.getLogger(this.getClass()).info("舰船规格标签:");
            for (String tag : ship.getHullSpec().getTags()) {
                Global.getLogger(this.getClass()).info("  - " + tag);
            }
        } else {
            Global.getLogger(this.getClass()).info("舰船规格中没有标签");
        }
        
        // 额外打印舰船变体标签（如果有的话）
        if (ship.getVariant() != null && ship.getVariant().getTags() != null && !ship.getVariant().getTags().isEmpty()) {
            Global.getLogger(this.getClass()).info("舰船变体标签:");
            for (String tag : ship.getVariant().getTags()) {
                Global.getLogger(this.getClass()).info("  - " + tag);
            }
        } else {
            Global.getLogger(this.getClass()).info("舰船变体中没有标签");
        }
    }
}