package data.hullmods;

import java.util.Iterator;
import java.util.List;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

public class Moci_IntegratedPointDefenseAI extends BaseHullMod {

    public static float DAMAGE_BONUS = 50f;
    public static float RANGE_BONUS = 80f;

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 点防御系统无视干扰弹
        stats.getDynamic().getMod(Stats.PD_IGNORES_FLARES).modifyFlat(id, 1f);
        // 点防御系统获得最佳目标引导
        stats.getDynamic().getMod(Stats.PD_BEST_TARGET_LEADING).modifyFlat(id, 1f);
        // 对战机、导弹伤害加成
        stats.getDamageToMissiles().modifyPercent(id, DAMAGE_BONUS);
        stats.getDamageToFighters().modifyPercent(id, DAMAGE_BONUS);
        // 射程加成
        stats.getBallisticWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        stats.getEnergyWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
    }

    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        List weapons = ship.getAllWeapons();
        Iterator iter = weapons.iterator();
        while (iter.hasNext()) {
            WeaponAPI weapon = (WeaponAPI)iter.next();

            if (weapon.getType() != WeaponType.MISSILE) {
                weapon.setPD(true);  // 强制设置为点防御武器
                weapon.setPDAlso(true);  // 强制设置为点防御武器
            }
        }
    }

    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int)Math.round(DAMAGE_BONUS) + "%";
        return null;
    }
}