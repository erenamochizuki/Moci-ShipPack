package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

/**
 * 实弹增幅器
 * 降低实弹武器射速，但提高伤害和弹丸速度
 * S内插时额外降低射速但提高射程
 */
public class Moci_BallisticAmplifier extends BaseHullMod {

    // 基础效果
    private static final float FIRE_RATE_MULT = 0.5f;      // 射速降低50%
    private static final float DAMAGE_MULT = 1.5f;         // 伤害提高50%
    private static final float PROJ_SPEED_MULT = 1.5f;     // 弹丸速度提高50%
    private static final float FLUX_GEN_MULT = 1.25f;       // 赋能产生提高50%
    private static final float RANGE_MULT = 20f;     // 提高20%射程
    
    // S内插额外效果
    private static final float SMOD_FIRE_RATE_MULT = 0.9f; // 额外降低10%射速
    private static final float SMOD_RANGE_MULT = 20f;     // 提高20%射程

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        boolean sMod = isSMod(stats);

        // 基础效果 - 只影响实弹武器
        stats.getBallisticRoFMult().modifyMult(id, FIRE_RATE_MULT);
        stats.getBallisticWeaponFluxCostMod().modifyMult(id, FLUX_GEN_MULT);
        stats.getBallisticWeaponDamageMult().modifyMult(id, DAMAGE_MULT);
        stats.getBallisticProjectileSpeedMult().modifyMult(id, PROJ_SPEED_MULT);

        // S内插额外效果
        if (sMod) {
            stats.getBallisticRoFMult().modifyMult(id + "_smod", SMOD_FIRE_RATE_MULT);
            stats.getBallisticWeaponRangeBonus().modifyPercent(id, SMOD_RANGE_MULT);
        }
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int)((1f - FIRE_RATE_MULT) * 100f) + "%";
        if (index == 1) return "" + (int)((FLUX_GEN_MULT - 1f) * 100f) + "%";
        if (index == 2) return "" + (int)((DAMAGE_MULT - 1f) * 100f) + "%";
        if (index == 3) return "" + (int)((PROJ_SPEED_MULT - 1f) * 100f) + "%";
        return null;
    }

    @Override
    public String getSModDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int)((1f - SMOD_FIRE_RATE_MULT) * 100f) + "%";
        if (index == 1) return "" + (int)( SMOD_RANGE_MULT ) + "%";
        return null;
    }
}
