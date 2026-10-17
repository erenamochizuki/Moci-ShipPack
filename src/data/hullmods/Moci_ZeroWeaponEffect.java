package data.hullmods;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.WeaponBaseRangeModifier;

public class Moci_ZeroWeaponEffect extends BaseHullMod {
    // 最小射程保证
    public static final float MIN_WEAPON_RANGE = 650f;
    private static final float WEAPON_BONUS = 50f;

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getBallisticRoFMult().modifyPercent(id, WEAPON_BONUS); // 实弹武器射速加成
        stats.getEnergyRoFMult().modifyPercent(id, WEAPON_BONUS); // 能量武器射速加成
        stats.getMissileRoFMult().modifyPercent(id, WEAPON_BONUS);      // 导弹射速加成
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        // 参数0: 射速加成百分比
        if (index == 0) return "" + (int)WEAPON_BONUS + "%";
        // 参数1: 最小射程（原始值）
        if (index == 1) return "" + (int)MIN_WEAPON_RANGE;
        // 参数2: 最小射程（提升后的值，与参数1相同）
        if (index == 2) return "" + (int)MIN_WEAPON_RANGE;
        return null;
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        // 每帧设置碰撞类型为战机
        // 但如果这个船是另一个船的模块，则不设置（由其他代码处理）
        if (ship != null && ship.isAlive() && ship.getParentStation() == null) {
            ship.setCollisionClass(CollisionClass.FIGHTER);
        }
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 添加射程修正监听器
        ship.addListener(new YamatoRangeModifier());
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
