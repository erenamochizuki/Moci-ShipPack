package data.hullmods;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import org.magiclib.util.MagicIncompatibleHullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import data.scripts.util.Moci_TextLoader;

public class Moci_DamperArmor extends BaseHullMod {

    private static final String TEXT_ID = "Moci_damped_armor";
    private static final Color DAMPER_COLOR = new Color(200, 0, 0, 150);
    private static final Color DAMPER_UNDER_COLOR = new Color(255, 190, 60, 200);
    private static final float FLUX_PER_SECOND_FRIGATE = 150f;
    private static final float FLUX_PER_SECOND_DESTROYER = 300f;
    private static final float FLUX_PER_SECOND_CRUISER = 600f;
    private static final float FLUX_PER_SECOND_CAPITAL_SHIP = 1200f;
    private static final Set<String> UNAPPLICABLE = new HashSet<>();
    private static final String buffId = "Moci_DamperArmor_Hullmod";
    private static final float MASS_MULT = 2f;
    
    // 数据键定义
    private static final String JITTER_ACTIVE_KEY = "moci_damper_jitter_active";
    private static final String ORIGINAL_MASS_KEY = "moci_damper_original_mass";

    static {
    UNAPPLICABLE.add("frontshield");
    UNAPPLICABLE.add("shield_shunt");
    }


    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 禁用飞行甲板
        int bays = (int) stats.getNumFighterBays().getBaseValue();
        stats.getNumFighterBays().modifyFlat(buffId, -bays);

        //// 武器射程
        //stats.getBallisticWeaponRangeBonus().modifyMult(buffId, 0.1f);
        //stats.getEnergyWeaponRangeBonus().modifyMult(buffId, 0.1f);
//
        //// 射速
        //stats.getBallisticRoFMult().modifyMult(buffId, 0.2f);
        //stats.getEnergyRoFMult().modifyMult(buffId, 0.2f);
        //stats.getMissileRoFMult().modifyMult(buffId, 0.2f);
//
        //// 导弹伤害
        //stats.getMissileWeaponDamageMult().modifyMult(buffId, 0.1f);
        //// 导弹飞行速度×0.1
        //stats.getMissileMaxSpeedBonus().modifyMult(buffId, 0.1f);
//
        //// 幅能产生
        //stats.getBallisticWeaponFluxCostMod().modifyMult(buffId, 2f);
        //stats.getEnergyWeaponFluxCostMod().modifyMult(buffId, 2f);

        // 过载时间提高100%
        stats.getOverloadTimeMod().modifyMult(buffId, 2f);

        for (String hullmMod : UNAPPLICABLE) {
            if (stats.getVariant().hasHullMod(hullmMod)) {
                MagicIncompatibleHullmods.removeHullmodWithWarning(stats.getVariant(), hullmMod, spec.getId());
            }
        }
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 强制禁用护盾
        ship.setShield(ShieldType.NONE, 0f, 1f, 1f);

    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (!ship.isAlive()) return;

        // 处理质量调整 - 每个舰船独立存储原始质量
        Float originalMass = (Float) ship.getCustomData().get(ORIGINAL_MASS_KEY);
        if (originalMass == null) {
            originalMass = ship.getMass();
            ship.setCustomData(ORIGINAL_MASS_KEY, originalMass);
            ship.setMass(originalMass * MASS_MULT);
        }

        // 每帧禁用非System武器
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!weapon.getSpec().getTags().contains("System")) {
                weapon.setForceNoFireOneFrame(true);
            }
        }

        // 生成硬幅能
        float fluxPerSecond = getFluxByHullSize(ship.getHullSize());
        ship.getFluxTracker().increaseFlux(fluxPerSecond * amount, true);

        // 获取状态
        boolean isOverloaded = ship.getFluxTracker().isOverloaded();
        boolean isVenting = ship.getFluxTracker().isVenting();
        boolean shouldDisableEffect = isOverloaded || isVenting;

        // 获取当前舰船的抖动状态
        Boolean jitterActive = (Boolean) ship.getCustomData().get(JITTER_ACTIVE_KEY);
        if (jitterActive == null) {
            jitterActive = true;
            ship.setCustomData(JITTER_ACTIVE_KEY, jitterActive);
        }

        // 动态调整减伤效果
        if (shouldDisableEffect) {
            // 移除减伤效果
            ship.getMutableStats().getHullDamageTakenMult().unmodify(buffId);
            ship.getMutableStats().getArmorDamageTakenMult().unmodify(buffId);
            ship.getMutableStats().getEmpDamageTakenMult().unmodify(buffId);
            ship.setCustomData(JITTER_ACTIVE_KEY, false);
            jitterActive = false;
        } else {
            // 重新应用减伤效果
            ship.getMutableStats().getHullDamageTakenMult().modifyMult(buffId, 0.5f);
            ship.getMutableStats().getArmorDamageTakenMult().modifyMult(buffId, 0.5f);
            ship.getMutableStats().getEmpDamageTakenMult().modifyMult(buffId, 0.5f);
            ship.setCustomData(JITTER_ACTIVE_KEY, true);
            jitterActive = true;
        }

        // 渲染特效
        if (jitterActive) {
            ship.setJitterUnder(ship, DAMPER_UNDER_COLOR, 1.5f, 25, 0f, 10f);
            ship.setJitter(ship, DAMPER_COLOR, 0.5f, 2, 0, 5);
        } else {
            return;
        }
    }

    private float getFluxByHullSize(HullSize size) {
        switch (size) {
            case FRIGATE:
                return FLUX_PER_SECOND_FRIGATE;
            case DESTROYER:
                return FLUX_PER_SECOND_DESTROYER;
            case CRUISER:
                return FLUX_PER_SECOND_CRUISER;
            case CAPITAL_SHIP:
                return FLUX_PER_SECOND_CAPITAL_SHIP;
            default:
                return 100f;
        }
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        // 检查护盾和船插
            if (ship.getVariant().getHullSpec().getShieldType() == ShieldType.NONE &&
                    !ship.getVariant().hasHullMod("frontshield")) return false;
            if (ship.getVariant().hasHullMod("shield_shunt")) return false;
            if (ship.getVariant().hasHullMod("frontshield")) return false;
            if (ship.getVariant().hasHullMod("Moci_damped_armor")) return true;
            return ship != null && ship.getShield() != null;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship.getVariant().hasHullMod("shield_shunt"))
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_shield_shunt");
        if (ship.getVariant().hasHullMod("frontshield"))
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_front_shield");
        return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_no_shield");
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        switch (index) {
            case 0:
                return "" + (int)Math.round(FLUX_PER_SECOND_FRIGATE);
            case 1:
                return "" + (int)Math.round(FLUX_PER_SECOND_DESTROYER);
            case 2:
                return "" + (int)Math.round(FLUX_PER_SECOND_CRUISER);
            case 3:
                return "" + (int)Math.round(FLUX_PER_SECOND_CAPITAL_SHIP);
            case 4:
                return "50%";
            case 5:
                return "100%";
            default:
                return null;
        }
    }
}
