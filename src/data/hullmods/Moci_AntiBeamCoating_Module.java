package data.hullmods;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;
import data.scripts.util.Moci_TextLoader;

/**
 * Moci_AntiBeamCoating_Module - 抗光束涂层（模块版）
 * 
 * 专门针对模块的抗光束涂层，提供：
 * - 光束伤害减免
 * - 日冕效应抵抗
 * - 禁止强制排幅（模块不应该有独立的幅能管理）
 * 
 * 其他保护功能已移至 Moci_ModuleCollisionSync 统一处理
 */
public class Moci_AntiBeamCoating_Module extends BaseLogisticsHullMod {
    private static final String TEXT_ID = "Moci_antibeamcoating_shield";

    public static float CORONA_EFFECT_REDUCE_MULT = 0f; // 日冕效应乘数
    public static float BEAM_DAMAGE_MULT = 0.5f; // 光束伤害降低

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 光束伤害减免
        stats.getBeamDamageTakenMult().modifyMult(id, 1 - BEAM_DAMAGE_MULT);
        stats.getBeamShieldDamageTakenMult().modifyMult(id, 1 - BEAM_DAMAGE_MULT);
        
        // 日冕效应抵抗
        stats.getDynamic().getStat(Stats.CORONA_EFFECT_MULT).modifyMult(id, CORONA_EFFECT_REDUCE_MULT);
        
        // 禁用强制排幅 - 使用和安超相同的方法
        stats.getVentRateMult().modifyMult(id, 0f);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) Math.round((1f - CORONA_EFFECT_REDUCE_MULT) * 100f) + "%"; // 日冕抵抗
        if (index == 1) return "" + (int) Math.round(BEAM_DAMAGE_MULT * 100f) + "%"; // 光束抵抗
        if (index == 2) return Moci_TextLoader.getText(TEXT_ID, "description.param_disable_vent"); // 禁用排幅说明
        return null;
    }
}
