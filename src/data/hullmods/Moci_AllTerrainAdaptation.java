package data.hullmods;

import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import data.scripts.util.Moci_TextLoader;

public class Moci_AllTerrainAdaptation extends BaseLogisticsHullMod {
    private static final String TEXT_ID = "Moci_allterrainadaptation";

    public static float CORONA_EFFECT_MULT = 0f; // 日冕效应乘数
    public static float GROUND_SUPPORT_BONUS = 250f; // 地面袭击强度加成

    /**
     * 在飞船创建前应用效果
     * @param hullSize 船体尺寸
     * @param stats 可变的飞船状态
     * @param id 插件ID
     */
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 减免日冕效应
        stats.getDynamic().getStat(Stats.CORONA_EFFECT_MULT).modifyMult(id, CORONA_EFFECT_MULT);
        
        // 提高地面袭击强度
        stats.getDynamic().getMod(Stats.FLEET_GROUND_SUPPORT).modifyFlat(id, GROUND_SUPPORT_BONUS);
    }
    
    /**
     * 在战斗中每帧更新
     * @param ship 飞船实例
     * @param amount 时间增量
     */
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null) return;
        
        // 获取当前结构完整度
        float hullPercent = ship.getHullLevel();
        
        // 如果当前CR低于结构完整度，则提升到结构完整度
        if (ship.getCurrentCR() < hullPercent) {
            ship.setCurrentCR(hullPercent);
        }
        
        // 隐藏导弹渲染
        hideMissiles(ship);
    }

    /**
     * 隐藏导弹渲染效果
     * @param ship 飞船实例
     */
    private void hideMissiles(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            // 检查武器是否为导弹武器且不是内置武器
            if (isMissileWeapon(weapon) && !weapon.getSlot().isBuiltIn()) {
                // 隐藏导弹渲染
                if (weapon.getMissileRenderData() != null && !weapon.getMissileRenderData().isEmpty()) {
                    for (MissileRenderDataAPI data : weapon.getMissileRenderData()) {
                        if (data.getSprite() != null) {
                            data.getSprite().setSize(0.0f, 0.0f);
                        }
                    }
                }
            }
        }
    }

    /**
     * 判断武器是否为导弹武器
     * @param weapon 武器实例
     * @return 是否为导弹武器
     */
    private boolean isMissileWeapon(WeaponAPI weapon) {
        if (weapon == null) return false;
        
        WeaponSpecAPI spec = weapon.getSpec();
        if (spec == null) return false;
        
        // 通过武器类型判断是否为导弹武器
        return spec.getType() == WeaponAPI.WeaponType.MISSILE;
    }

    /**
     * 获取描述参数
     * @param index 参数索引
     * @param hullSize 船体尺寸
     * @return 格式化后的参数值
     */
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int)GROUND_SUPPORT_BONUS; // 地面袭击强度加成
        if (index == 1) return Moci_TextLoader.getText(TEXT_ID, "description.param_cr");
        if (index == 2) return Moci_TextLoader.getText(TEXT_ID, "description.param_hull_integrity");
        if (index == 3) return Moci_TextLoader.getText(TEXT_ID, "description.param_missile_stealth");
        return null;
    }
} 
