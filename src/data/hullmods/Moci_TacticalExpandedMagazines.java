package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

/**
 * 战术改装（火力组）：扩展弹仓。
 * <p>
 * 普通效果照搬原版扩展弹仓（magazines），仅供「航空中队管理」的战术改装使用。
 * 战术改装分防御组和火力组，每组最多一项，按 tags 判组（带 {@code moci_tactical_defense} 的是防御组，其余是火力组）。
 * 该船插在 CSV 中登记为 hidden + hiddenEverywhere，不会出现在正常改装、市场和图鉴里，
 * 只由 {@link Moci_TacticalHullmods#applySelections} 以普通船插写入战机联队的 variant，永远不会成为 S 插。
 * </p>
 */
public class Moci_TacticalExpandedMagazines extends BaseHullMod {

    /** 实弹与能量武器备弹量提升百分比。 */
    public static final float AMMO_BONUS = 50f;

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getBallisticAmmoBonus().modifyPercent(id, AMMO_BONUS);
        stats.getEnergyAmmoBonus().modifyPercent(id, AMMO_BONUS);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) AMMO_BONUS + "%";
        return null;
    }
}
