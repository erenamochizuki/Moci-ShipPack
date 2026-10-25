package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

/**
 * 战术改装：弹匣扩容。
 * <p>
 * 效果照搬原版扩展弹仓（magazines），仅供「航空中队管理」的战术改装三选一使用。
 * 该船插在 CSV 中登记为 hidden + hiddenEverywhere，不会出现在正常改装、市场和图鉴里，
 * 只由 {@link Moci_TacticalHullmods#applyToVariant} 写入战机联队的 variant。
 * </p>
 */
public class Moci_TacticalExpandedMagazines extends BaseHullMod {

    /** 实弹与能量武器备弹量提升百分比。 */
    public static final float AMMO_BONUS = 50f;
    /** S 插额外提供的备弹回复速率提升百分比。 */
    public static final float SMOD_REGEN_BONUS = 50f;

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getBallisticAmmoBonus().modifyPercent(id, AMMO_BONUS);
        stats.getEnergyAmmoBonus().modifyPercent(id, AMMO_BONUS);

        boolean sMod = isSMod(stats);
        if (sMod) {
            stats.getBallisticAmmoRegenMult().modifyPercent(id, SMOD_REGEN_BONUS);
            stats.getEnergyAmmoRegenMult().modifyPercent(id, SMOD_REGEN_BONUS);
        }
    }

    @Override
    public String getSModDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) SMOD_REGEN_BONUS + "%";
        return null;
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) AMMO_BONUS + "%";
        return null;
    }
}
