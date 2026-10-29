package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

/** 战术防御插件：重型装甲；战机与护卫舰都获得 150 装甲。 */
public class Moci_TacticalHeavyArmor extends BaseHullMod {
    public static final float FIGHTER_ARMOR_BONUS = 150f;
    public static final float SMOD_MANEUVER_PENALTY = 25f;

    @Override public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        float bonus = switch (hullSize) {
            case FIGHTER, FRIGATE -> FIGHTER_ARMOR_BONUS;
            case DESTROYER -> 300f;
            case CRUISER -> 400f;
            case CAPITAL_SHIP -> 500f;
            default -> 0f;
        };
        stats.getArmorBonus().modifyFlat(id, bonus);
        // 原版普通重甲没有机动惩罚；只有 S 插会施加此惩罚。
        if (isSMod(stats)) {
            float mult = 1f - SMOD_MANEUVER_PENALTY * 0.01f;
            stats.getAcceleration().modifyMult(id, mult);
            stats.getDeceleration().modifyMult(id, mult);
            stats.getTurnAcceleration().modifyMult(id, mult);
            stats.getMaxTurnRate().modifyMult(id, mult);
        }
    }
    @Override public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) FIGHTER_ARMOR_BONUS;
        return null;
    }
    @Override public String getSModDescriptionParam(int index, HullSize hullSize, ShipAPI ship) {
        return index == 0 ? (int) SMOD_MANEUVER_PENALTY + "%" : null;
    }
    @Override public boolean isSModEffectAPenalty() { return true; }
}
