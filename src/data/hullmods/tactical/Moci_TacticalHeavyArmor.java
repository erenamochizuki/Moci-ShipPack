package data.hullmods.tactical;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

/**
 * 战术改装（防御组）：重型装甲；战机与护卫舰都获得 150 装甲。
 * <p>
 * 战术改装只由 {@link Moci_TacticalHullmods#applySelections} 以普通船插写入战机联队的 variant，
 * 永远不会成为 S 插，因此这里没有 S 插效果。
 * </p>
 */
public class Moci_TacticalHeavyArmor extends BaseHullMod implements Moci_TacticalHullmods.ParamTone {
    public static final float FIGHTER_ARMOR_BONUS = 150;
    public static final float MOBILITY_PENALTY = 15f;

    @Override public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        float bonus = switch (hullSize) {
            case FIGHTER, FRIGATE -> FIGHTER_ARMOR_BONUS;
            case DESTROYER -> 300f;
            case CRUISER -> 400f;
            case CAPITAL_SHIP -> 500f;
            default -> 0f;
        };
        stats.getArmorBonus().modifyFlat(id, bonus);
        if (hullSize == HullSize.FIGHTER) {
            float mult = 1f - MOBILITY_PENALTY * 0.01f;
            stats.getMaxSpeed().modifyMult(id, mult);
            stats.getAcceleration().modifyMult(id, mult);
            stats.getDeceleration().modifyMult(id, mult);
            stats.getMaxTurnRate().modifyMult(id, mult);
            stats.getTurnAcceleration().modifyMult(id, mult);
        }
    }
    @Override public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) FIGHTER_ARMOR_BONUS;
        if (index == 1) return (int) MOBILITY_PENALTY + "%";
        return null;
    }
    /** 1：速度与机动性降低。 */
    @Override public boolean isNegativeParam(int index) {
        return index == 1;
    }
}
