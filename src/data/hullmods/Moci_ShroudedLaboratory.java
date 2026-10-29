package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;

public class Moci_ShroudedLaboratory extends BaseHullMod {

    public static final String ID = "Moci_legion_VIII_shrouded_lab";
    private static final float RANGE_BONUS = 20f;
    private static final float RATE_OF_FIRE_MULT = 1.2f;
    private static final float DAMAGE_MULT = 0.8f;
    private static final float ARMOR_MULT = 0.8f;

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getBallisticWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        stats.getEnergyWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        stats.getMissileWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        stats.getBallisticRoFMult().modifyMult(id, RATE_OF_FIRE_MULT);
        stats.getEnergyRoFMult().modifyMult(id, RATE_OF_FIRE_MULT);
        stats.getMissileRoFMult().modifyMult(id, RATE_OF_FIRE_MULT);
        stats.getBallisticWeaponDamageMult().modifyMult(id, DAMAGE_MULT);
        stats.getEnergyWeaponDamageMult().modifyMult(id, DAMAGE_MULT);
        stats.getMissileWeaponDamageMult().modifyMult(id, DAMAGE_MULT);
        stats.getArmorBonus().modifyMult(id, ARMOR_MULT);
    }

    public static boolean hasLaboratory(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ID);
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        return index >= 0 && index < 4 ? "20%" : null;
    }
}
