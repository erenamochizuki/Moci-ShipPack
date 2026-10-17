/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package data.hullmods;

import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.util.IntervalUtil;
import org.lwjgl.util.vector.Vector2f;

/**
 * 活性再生装甲
 */
public class Moci_Yamato_hullmod1 extends BaseHullMod {

    private static final int NO_DAMAGE = 10;
    private static final int ARMOR_K_DANAGE_TAKEN = 100;
    private static final int SUPPLY_PM = 50;
    private static final int HEAL = 5;
    private static final int HEAL_S = 2;

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getKineticArmorDamageTakenMult().modifyPercent(id, ARMOR_K_DANAGE_TAKEN);
        if (!isSMod(stats)) {
            stats.getSuppliesPerMonth().modifyPercent(id, SUPPLY_PM);
        }
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        ship.addListener(new selfHealListener(ship, isSMod(ship) ? HEAL_S : HEAL));
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) {
            return "" + NO_DAMAGE;
        }
        if (index == 1) {
            return HEAL+"%";
        }
        if (index == 2) {
            return ARMOR_K_DANAGE_TAKEN+"%";
        }
        if (index == 3) {
            return SUPPLY_PM+"%";
        }
        return null;
    }

    @Override
    public String getSModDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) {
            return HEAL_S+"%";
        }
        return null;
    }

    private static class selfHealListener implements AdvanceableListener, DamageTakenModifier {

        private final ShipAPI ship;
        private final int heal;

        public selfHealListener(ShipAPI ship, int heal) {
            this.ship = ship;
            this.heal = heal;
        }
        private float damageTakenTimer = 0;
        private IntervalUtil interval = new IntervalUtil(1, 1);

        @Override
        public void advance(float amount) {
            if (damageTakenTimer < 10) {
                damageTakenTimer += amount;
                return;
            }
            interval.advance(amount);
            if (interval.intervalElapsed()) {
                float max = ship.getArmorGrid().getMaxArmorInCell();
                float h = max * heal * 0.01f;
                ArmorGridAPI grid = ship.getArmorGrid();
                for (int x = 0; x < grid.getGrid().length; x++) {
                    for (int y = 0; y < grid.getGrid()[0].length; y++) {
                        float v = grid.getArmorValue(x, y);
                        v = Math.min(max, v + h);
                        grid.setArmorValue(x, y, v);
                    }
                }
            }
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
            damageTakenTimer = 0;
            return null;
        }

    }
}
