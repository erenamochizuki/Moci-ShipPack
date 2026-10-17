package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.*;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

public class Moci_MobileArmour extends BaseLogisticsHullMod {
    private static final String TEXT_ID = "Moci_MobileArmour";
    // 效果参数
    public static final float SENSOR_PROFILE_MULT = 0.5f; // 传感器信号乘数
    public static final float VENT_RATE_MULT = 0.75f; // 强制排幅速度（25%惩罚）
    public static final float HARD_FLUX_DISSIPATION = 25f; // 耗散硬幅能能力25%
    public static final float WEAPON_ENGINE_DAMAGE_REDUCTION = 0.25f; // 武器引擎伤害减免25%
    public static final float EMP_DAMAGE_REDUCTION = 0.25f; // EMP伤害减免25%
    public static final float SIGHT_RANGE_BONUS = 100f; // 战场视野提高100%
    public static final float NO_OFFICER_CR_PENALTY = 0.15f; // 无军官CR惩罚
    public static final float NO_OFFICER_CREW_PENALTY = 30f; // 无军官船员需求惩罚
    
    // 模块幅能共享参数
    // public static final float FLUX_SHARE_RATE = 0.1f; // 幅能共享速度
    
    // 数据键定义
    private static final String NO_OFFICER_KEY = "moci_mobile_armor_no_officer";
    
    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 传感器信号减半
        stats.getSensorProfile().modifyMult(id, SENSOR_PROFILE_MULT);
        
        // 强制排幅速度惩罚25%
        stats.getVentRateMult().modifyMult(id, VENT_RATE_MULT);
        
        // 25%的耗散硬幅能能力
        stats.getHardFluxDissipationFraction().modifyFlat(id, HARD_FLUX_DISSIPATION / 100f);
        
        // 降低武器和引擎受到的伤害
        stats.getWeaponDamageTakenMult().modifyMult(id, 1f - WEAPON_ENGINE_DAMAGE_REDUCTION);
        stats.getEngineDamageTakenMult().modifyMult(id, 1f - WEAPON_ENGINE_DAMAGE_REDUCTION);
        
        // 降低EMP伤害
        stats.getEmpDamageTakenMult().modifyMult(id, 1f - EMP_DAMAGE_REDUCTION);
        
        // 战场视野提高100%
        stats.getSightRadiusMod().modifyPercent(id, SIGHT_RANGE_BONUS);
        
        // 检查军官状态，直接应用CR惩罚和船员需求惩罚
        if (stats.getEntity() instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) stats.getEntity();
            if (ship.getCaptain() == null || ship.getCaptain().isDefault()) {
                stats.getMaxCombatReadiness().modifyFlat(id + "_no_officer", -NO_OFFICER_CR_PENALTY);
                stats.getMinCrewMod().modifyMult(id + "_no_officer_crew", NO_OFFICER_CREW_PENALTY);
            }
        } else if (stats.getFleetMember() != null) {
            // 战役模式下检查舰队成员的军官
            if (stats.getFleetMember().getCaptain() == null || stats.getFleetMember().getCaptain().isDefault()) {
                stats.getMaxCombatReadiness().modifyFlat(id + "_no_officer", -NO_OFFICER_CR_PENALTY);
                stats.getMinCrewMod().modifyMult(id + "_no_officer_crew", NO_OFFICER_CREW_PENALTY);
            }
        }
    }

    private void hideWeaponCovers(ShipAPI ship) {
        if (ship.getLargeHardpointCover() != null) {
            ship.getLargeHardpointCover().setSize(0.0f, 0.0f);
        }
        if (ship.getMediumHardpointCover() != null) {
            ship.getMediumHardpointCover().setSize(0.0f, 0.0f);
        }
        if (ship.getSmallHardpointCover() != null) {
            ship.getSmallHardpointCover().setSize(0.0f, 0.0f);
        }
        if (ship.getLargeTurretCover() != null) {
            ship.getLargeTurretCover().setSize(0.0f, 0.0f);
        }
        if (ship.getMediumTurretCover() != null) {
            ship.getMediumTurretCover().setSize(0.0f, 0.0f);
        }
        if (ship.getSmallTurretCover() != null) {
            ship.getSmallTurretCover().setSize(0.0f, 0.0f);
        }

        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon != null && (weapon.getSlot().isTurret() || weapon.getSlot().isHardpoint())) {
                weapon.ensureClonedSpec();
            }
        }
    }
    
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 添加防殉爆和碰撞伤害监听器
        if (!ship.hasListenerOfClass(MobileArmorDamageListener.class)) {
            ship.addListener(new MobileArmorDamageListener());
        }
        
        // 移除模块幅能共享监听器（已被注释掉）
        // if (!ship.hasListenerOfClass(ModuleFluxSharingListener.class)) {
        //     ship.addListener(new ModuleFluxSharingListener(ship, id));
        // }
        
        // 移除无军官CR惩罚检查监听器（改为直接在applyEffectsBeforeShipCreation中处理）
        // if (!ship.hasListenerOfClass(NoOfficerCRPenaltyListener.class)) {
        //     ship.addListener(new NoOfficerCRPenaltyListener(ship, id));
        // }
    }
    
    /**
     * 防殉爆和碰撞伤害监听器
     */
    public static class MobileArmorDamageListener implements DamageTakenModifier {
        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
            if (!(target instanceof ShipAPI)) {
                return null;
            }
            
            // 处理殉爆伤害
            if (param instanceof DamagingProjectileAPI) {
                DamagingProjectileAPI projectile = (DamagingProjectileAPI) param;
                ShipAPI source = projectile.getSource();
                if (source != null) {
                    float explosionRadius = DamagingExplosionSpec.getShipExplosionRadius(source);
                    if ((projectile.getCollisionRadius() - explosionRadius) == 0) {
                        // 殉爆伤害减免
                        damage.getModifier().modifyMult(this.getClass().getName(), 0.5f);
                        return Moci_TextLoader.getText(TEXT_ID, "combat.explosion_protection");
                    }
                }
            }
            
            // 处理碰撞伤害 - 动能伤害且param为null才是碰撞伤害
            if (param == null && damage.getType() == DamageType.KINETIC) {
                // 碰撞伤害减免
                damage.getModifier().modifyMult(this.getClass().getName(), 0.5f);
                return Moci_TextLoader.getText(TEXT_ID, "combat.collision_protection");
            }
            
            return null;
        }
    }
    
    /**
     * 模块幅能共享监听器
     */
    /*
    public static class ModuleFluxSharingListener implements AdvanceableListener {
        private final ShipAPI ship;
        private final String id;
        private final IntervalUtil checkInterval = new IntervalUtil(0.1f, 0.1f);
        
        public ModuleFluxSharingListener(ShipAPI ship, String id) {
            this.ship = ship;
            this.id = id;
        }
        
        @Override
        public void advance(float amount) {
            if (!ship.isAlive() || ship.isHulk()) {
                ship.removeListener(this);
                return;
            }
            
            checkInterval.advance(amount);
            if (!checkInterval.intervalElapsed()) {
                return;
            }
            
            // 检查所有模块
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module != null && module.isAlive()) {
                    shareFluxWithModule(ship, module);
                }
            }
            
            // 如果是模块，与母舰共享
            if (ship.getParentStation() != null && ship.getParentStation().isAlive()) {
                shareFluxWithModule(ship.getParentStation(), ship);
            }
        }
        
        private void shareFluxWithModule(ShipAPI mainShip, ShipAPI module) {
            float mainFluxLevel = mainShip.getFluxLevel();
            float moduleFluxLevel = module.getFluxLevel();
            
            // 计算幅能等级差异
            float levelDifference = Math.abs(mainFluxLevel - moduleFluxLevel);
            
            // 如果差异很小，就不需要共享
            if (levelDifference < 0.05f) {
                return;
            }
            
            // 确定转移方向：从高的向低的转移
            boolean transferToModule = mainFluxLevel > moduleFluxLevel;
            
            // 计算转移量
            float transferAmount;
            if (transferToModule) {
                // 从主舰向模块转移
                float availableFlux = mainShip.getCurrFlux();
                float moduleCapacity = module.getMaxFlux() - module.getCurrFlux();
                float targetTransfer = levelDifference * Math.min(mainShip.getMaxFlux(), module.getMaxFlux()) * FLUX_SHARE_RATE;
                
                transferAmount = Math.min(targetTransfer, Math.min(availableFlux, moduleCapacity));
                
                if (transferAmount > 0) {
                    mainShip.getFluxTracker().decreaseFlux(transferAmount);
                    module.getFluxTracker().increaseFlux(transferAmount, false);
                }
            } else {
                // 从模块向主舰转移
                float availableFlux = module.getCurrFlux();
                float mainCapacity = mainShip.getMaxFlux() - mainShip.getCurrFlux();
                float targetTransfer = levelDifference * Math.min(mainShip.getMaxFlux(), module.getMaxFlux()) * FLUX_SHARE_RATE;
                
                transferAmount = Math.min(targetTransfer, Math.min(availableFlux, mainCapacity));
                
                if (transferAmount > 0) {
                    module.getFluxTracker().decreaseFlux(transferAmount);
                    mainShip.getFluxTracker().increaseFlux(transferAmount, false);
                }
            }
        }
    }
    */
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null) return;

        hideWeaponCovers(ship);
    }
    
    @Override
    public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }
    
    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 3f;
        Color h = Misc.getHighlightColor();
        Color good = Misc.getPositiveHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();

        tooltip.addPara(
                Moci_TextLoader.getText(TEXT_ID, "description.main"),
                pad,
                h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.main_highlights").toArray(new String[0])
        );

        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.defense_heading"),
                com.fs.starfarer.api.ui.Alignment.MID, pad);

        tooltip.addPara(
                Moci_TextLoader.getText(TEXT_ID, "description.defense_main"),
                pad,
                h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.defense_main_highlights").toArray(new String[0])
        );

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.weapon_engine_damage",
                        Moci_TextLoader.mapOf("weapon_engine_damage", String.valueOf((int) (WEAPON_ENGINE_DAMAGE_REDUCTION * 100f)))),
                pad,
                good,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.weapon_engine_damage_highlights",
                        Moci_TextLoader.mapOf("weapon_engine_damage", String.valueOf((int) (WEAPON_ENGINE_DAMAGE_REDUCTION * 100f)))).toArray(new String[0])
        );

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.emp_damage",
                        Moci_TextLoader.mapOf("emp_damage", String.valueOf((int) (EMP_DAMAGE_REDUCTION * 100f)))),
                pad,
                good,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.emp_damage_highlights",
                        Moci_TextLoader.mapOf("emp_damage", String.valueOf((int) (EMP_DAMAGE_REDUCTION * 100f)))).toArray(new String[0])
        );

        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.flux_heading"),
                com.fs.starfarer.api.ui.Alignment.MID, pad);

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.hard_flux",
                        Moci_TextLoader.mapOf("hard_flux", String.valueOf((int) HARD_FLUX_DISSIPATION))),
                pad,
                new Color[]{good, h},
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.hard_flux_highlights",
                        Moci_TextLoader.mapOf("hard_flux", String.valueOf((int) HARD_FLUX_DISSIPATION))).toArray(new String[0])
        );

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.vent_penalty",
                        Moci_TextLoader.mapOf("vent_penalty", String.valueOf((int) ((1f - VENT_RATE_MULT) * 100f)))),
                pad,
                bad,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.vent_penalty_highlights",
                        Moci_TextLoader.mapOf("vent_penalty", String.valueOf((int) ((1f - VENT_RATE_MULT) * 100f)))).toArray(new String[0])
        );

        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.sensor_heading"),
                com.fs.starfarer.api.ui.Alignment.MID, pad);

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.sensor_profile",
                        Moci_TextLoader.mapOf("sensor_profile", String.valueOf((int) ((1f - SENSOR_PROFILE_MULT) * 100f)))),
                pad,
                good,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.sensor_profile_highlights",
                        Moci_TextLoader.mapOf("sensor_profile", String.valueOf((int) ((1f - SENSOR_PROFILE_MULT) * 100f)))).toArray(new String[0])
        );

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.sight_range",
                        Moci_TextLoader.mapOf("sight_range", String.valueOf((int) SIGHT_RANGE_BONUS))),
                pad,
                good,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.sight_range_highlights",
                        Moci_TextLoader.mapOf("sight_range", String.valueOf((int) SIGHT_RANGE_BONUS))).toArray(new String[0])
        );

        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.traits_heading"),
                com.fs.starfarer.api.ui.Alignment.MID, pad);

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.no_officer_cr",
                        Moci_TextLoader.mapOf("no_officer_cr", String.valueOf((int) (NO_OFFICER_CR_PENALTY * 100f)))),
                pad,
                bad,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.no_officer_cr_highlights",
                        Moci_TextLoader.mapOf("no_officer_cr", String.valueOf((int) (NO_OFFICER_CR_PENALTY * 100f)))).toArray(new String[0])
        );

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.no_officer_crew",
                        Moci_TextLoader.mapOf("no_officer_crew", String.valueOf((int) (NO_OFFICER_CREW_PENALTY * 100f)))),
                pad,
                bad,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.no_officer_crew_highlights",
                        Moci_TextLoader.mapOf("no_officer_crew", String.valueOf((int) (NO_OFFICER_CREW_PENALTY * 100f)))).toArray(new String[0])
        );
    }
} 
