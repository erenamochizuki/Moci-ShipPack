package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

/**
 * 战术改装（防御组）：强化护盾。
 * <p>
 * 效果照搬原版强化护盾（hardenedshieldemitter），仅供「航空中队管理」的战术改装使用。
 * 战术改装分防御组和火力组，每组最多一项，按 tags 判组（带 {@code moci_tactical_defense} 的是防御组，其余是火力组）。
 * 该船插在 CSV 中登记为 hidden + hiddenEverywhere，不会出现在正常改装、市场和图鉴里，
 * 只由 {@link Moci_TacticalHullmods#applySelections} 以普通船插写入战机联队的 variant。
 * </p>
 */
public class Moci_TacticalHardenedShieldEmitter extends BaseHullMod {

    /** 护盾所受伤害降低比例。 */
    public static final float SHIELD_BONUS = 20f;
    /** 护盾被 EMP 电弧击穿概率倍率。 */
    public static final float PIERCE_MULT = 0.5f;

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getShieldDamageTakenMult().modifyMult(id, 1f - SHIELD_BONUS * 0.01f);
        stats.getDynamic().getStat(Stats.SHIELD_PIERCED_MULT).modifyMult(id, PIERCE_MULT);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) SHIELD_BONUS + "%";
        return null;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && ship.getShield() != null;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return "舰船没有护盾。";
    }
}
