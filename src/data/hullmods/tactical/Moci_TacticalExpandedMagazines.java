package data.hullmods.tactical;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/**
 * 战术改装（火力组）：扩展弹仓。
 * <p>
 * 普通效果照搬原版扩展弹仓（magazines），仅供「航空中队管理」的战术改装使用。
 * 战术改装分防御组和火力组，每组最多一项，按 tags 判组（带 {@code moci_tactical_defense} 的是防御组，其余是火力组）。
 * 该船插在 CSV 中登记为 hidden + hiddenEverywhere，不会出现在正常改装、市场和图鉴里，
 * 只由 {@link Moci_TacticalHullmods#applySelections} 以普通船插写入战机联队的 variant，永远不会成为 S 插。
 * </p>
 */
public class Moci_TacticalExpandedMagazines extends BaseHullMod implements Moci_TacticalHullmods.ParamTone {

    /** 实弹与能量武器备弹量提升百分比。 */
    public static final float AMMO_BONUS = 50f;
    public static final float TURN_PENALTY = 10f;
    public static final float RECOIL_PENALTY = 10f;
    private static final String TURN_APPLIED = "moci_tactical_expanded_magazines_turn_applied";

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getBallisticAmmoBonus().modifyPercent(id, AMMO_BONUS);
        stats.getEnergyAmmoBonus().modifyPercent(id, AMMO_BONUS);
        if (hullSize == HullSize.FIGHTER) stats.getRecoilPerShotMult().modifyMult(id, 1f + RECOIL_PENALTY * 0.01f);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (!ship.isFighter() || Boolean.TRUE.equals(ship.getCustomData().get(TURN_APPLIED))) return;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.isDecorative() || (weapon.getType() != WeaponAPI.WeaponType.BALLISTIC
                    && weapon.getType() != WeaponAPI.WeaponType.ENERGY)) continue;
            // The engine reads the local base rate each frame, then applies mount and beam modifiers.
            weapon.ensureClonedSpec();
            WeaponSpecAPI local = weapon.getSpec();
            if (local != null && local != weapon.getOriginalSpec()) {
                local.setTurnRate(local.getTurnRate() * (1f - TURN_PENALTY * 0.01f));
            }
        }
        ship.setCustomData(TURN_APPLIED, true);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) AMMO_BONUS + "%";
        if (index == 1) return (int) TURN_PENALTY + "%";
        if (index == 2) return (int) RECOIL_PENALTY + "%";
        return null;
    }

    /** 1：转动速度降低；2：后坐力增加。 */
    @Override
    public boolean isNegativeParam(int index) {
        return index == 1 || index == 2;
    }
}
