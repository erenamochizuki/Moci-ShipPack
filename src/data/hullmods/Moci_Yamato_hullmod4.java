package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.WeaponBaseRangeModifier;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.ids.Tags;

import java.util.HashSet;
import java.util.Set;

public class Moci_Yamato_hullmod4 extends BaseHullMod {
    private static final int CR_LOSS = 100;
    private static final int PEAK_CR = 100;
    // 小型武器OP减免
    public static final float COST_REDUCTION = 10;
    // 最小射程保证
    public static final float MIN_WEAPON_RANGE = 1000f;

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getCRLossPerSecondPercent().modifyPercent(id, CR_LOSS);
        stats.getPeakCRDuration().modifyPercent(id, PEAK_CR);
        
        // 小型武器OP减免
        stats.getDynamic().getMod(Stats.SMALL_BALLISTIC_MOD).modifyFlat(id, -COST_REDUCTION);
        stats.getDynamic().getMod(Stats.SMALL_ENERGY_MOD).modifyFlat(id, -COST_REDUCTION);
        
        if (stats.getVariant() != null) {
            Set<String> remove = new HashSet<>();
            for (String hullModId : stats.getVariant().getPermaMods()) {
                if (Global.getSettings().getHullModSpec(hullModId).hasTag(Tags.HULLMOD_DMOD)) {
                    remove.add(hullModId);
                }
            }
            if (!remove.isEmpty()) {
                for (String hullModId : remove) {
                    stats.getVariant().removePermaMod(hullModId);
                }
            }
        }
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 添加射程修正监听器
        ship.addListener(new YamatoRangeModifier());
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize, ShipAPI ship) {
        if (index == 0) {
            return CR_LOSS + "%";
        }
        if (index == 1) {
            return PEAK_CR + "%";
        }
        if (index == 2) {
            return "" + (int) COST_REDUCTION;
        }
        if (index == 3) {
            return "" + (int) MIN_WEAPON_RANGE;
        }
        if (index == 4) {
            return "" + (int) MIN_WEAPON_RANGE;
        }
        return null;
    }

    @Override
    public boolean affectsOPCosts() {
        return true;
    }

    /**
     * 射程修正监听器类
     */
    public static class YamatoRangeModifier implements WeaponBaseRangeModifier {
        
        @Override
        public float getWeaponBaseRangePercentMod(ShipAPI ship, WeaponAPI weapon) {
            return 0f; // 不使用百分比修正
        }
        
        @Override
        public float getWeaponBaseRangeMultMod(ShipAPI ship, WeaponAPI weapon) {
            return 1f; // 不使用乘数修正
        }
        
        @Override
        public float getWeaponBaseRangeFlatMod(ShipAPI ship, WeaponAPI weapon) {
            // 获取武器基础射程
            float baseRange = weapon.getSpec().getMaxRange();
            
            // 如果基础射程低于1000，返回差值
            if (baseRange < MIN_WEAPON_RANGE) {
                return MIN_WEAPON_RANGE - baseRange;
            }
            
            // 否则不修改射程
            return 0f;
        }
    }
}
