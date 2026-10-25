package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

/**
 * 战术改装：导弹挂架。
 * <p>
 * 效果照搬原版扩展发射架（missleracks），仅供「航空中队管理」的战术改装三选一使用。
 * 该船插在 CSV 中登记为 hidden + hiddenEverywhere，不会出现在正常改装、市场和图鉴里，
 * 只由 {@link Moci_TacticalHullmods#applyToVariant} 写入战机联队的 variant。
 * </p>
 */
public class Moci_TacticalExpandedMissileRacks extends BaseHullMod {

    /** 导弹武器备弹量提升百分比。 */
    public static final float AMMO_BONUS = 100f;
    /** S 插对导弹射速的惩罚倍率。 */
    public static final float SMOD_ROF_MULT = 0.8f;

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getMissileAmmoBonus().modifyPercent(id, AMMO_BONUS);

        boolean sMod = isSMod(stats);
        if (sMod) {
            stats.getMissileRoFMult().modifyMult(id, SMOD_ROF_MULT);
        }
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) AMMO_BONUS + "%";
        return null;
    }

    @Override
    public String getSModDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) Math.round((1f - SMOD_ROF_MULT) * 100f) + "%";
        return null;
    }

    @Override
    public boolean isSModEffectAPenalty() {
        return true;
    }
}
