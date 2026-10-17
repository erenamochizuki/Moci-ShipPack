package data.shipsystems.scripts;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

/**
 * 能量核心超频系统
 * 
 * 系统效果：
 * - 启动期间：能量武器射速+50%，弹速+50%，弹药回复速度+50%，幅能消耗-50%
 * - 结束后：3秒内缓慢获得节省的50%幅能（软幅能）
 * - 超出幅能上限时强制主动排幅，排幅期间继续获得幅能
 * - 安全协议超驰舰船：只获得射速、弹速和弹药回复加成，无幅能减免和强制排幅
 */
public class Moci_EnergyCoreOverclock extends BaseShipSystemScript {
    
    // 系统参数
    private static final float ENERGY_WEAPON_ROF_BONUS = 50f;      // 能量武器射速加成(%)
    private static final float ENERGY_WEAPON_SPEED_BONUS = 50f;    // 能量武器弹速加成(%)
    private static final float ENERGY_AMMO_REGEN_BONUS = 50f;      // 能量武器弹药回复速度加成(%)
    private static final float FLUX_RECOVERY_RATE = 0.3333f;       // 幅能恢复速率(每秒恢复总量的33.33%)
    
    // 状态追踪
    private static final String DATA_KEY = "Moci_EnergyCoreOverclock_Data";
    
    // 视觉效果
    private static final Color FLUX_WARNING_COLOR = new Color(255, 100, 100, 150);
    
    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship = null;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        } else {
            return;
        }
        
        OverclockData data = getOverclockData(ship);
        
        // 根据系统状态应用不同效果
        if (state == State.ACTIVE || state == State.IN || state == State.OUT) {
            applyActiveEffects(stats, ship, data, effectLevel, id);
        } else {
            // 确保在非激活状态时移除所有buff
            stats.getEnergyRoFMult().unmodify(id + "_rof");
            stats.getEnergyProjectileSpeedMult().unmodify(id + "_speed");
            stats.getEnergyAmmoRegenMult().unmodify(id + "_ammo");
            stats.getEnergyWeaponFluxCostMod().unmodify(id + "_flux");
            
            // 处理幅能恢复（仅在冷却和空闲状态）
            if (state == State.COOLDOWN || state == State.IDLE) {
                handleFluxRecovery(ship, data, effectLevel);
            }
        }
        
        // 应用视觉效果
        applyVisualEffects(ship, state, effectLevel);
    }
    
    /**
     * 应用激活状态的效果
     */
    private void applyActiveEffects(MutableShipStatsAPI stats, ShipAPI ship, OverclockData data, float effectLevel, String id) {
        // 能量武器射速加成 - 使用独特ID避免冲突
        stats.getEnergyRoFMult().modifyPercent(id + "_rof", ENERGY_WEAPON_ROF_BONUS * effectLevel);
        // 能量武器弹速加成
        stats.getEnergyProjectileSpeedMult().modifyPercent(id + "_speed", ENERGY_WEAPON_SPEED_BONUS * effectLevel);
        // 能量武器弹药回复速度加成
        stats.getEnergyAmmoRegenMult().modifyPercent(id + "_ammo", ENERGY_AMMO_REGEN_BONUS * effectLevel);
        
        // 检查是否装备安全协议超驰
        boolean hasSafetyOverrides = ship.getVariant().hasHullMod("safetyoverrides");
        
        // 只有非安超舰船才能获得幅能消耗减免
        if (effectLevel >= 1f && !hasSafetyOverrides) {
            // 减少所有能量武器幅能消耗
            stats.getEnergyWeaponFluxCostMod().modifyMult(id + "_flux", 0.5f);
            trackFluxConsumption(ship, data);
        } else {
            // 确保安超舰船或未满充能时移除幅能消耗减免
            stats.getEnergyWeaponFluxCostMod().unmodify(id + "_flux");
        }
    }
    
    /**
     * 追踪幅能消耗
     */
    private void trackFluxConsumption(ShipAPI ship, OverclockData data) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getType() == WeaponAPI.WeaponType.ENERGY && weapon.isFiring()) {
                // 记录当前的幅能消耗（已经是减少50%后的值）
                float currentFluxCost = weapon.getFluxCostToFire();
                data.pendingFlux += currentFluxCost;
            }
        }
    }
    
    /**
     * 处理幅能恢复
     */
    private void handleFluxRecovery(ShipAPI ship, OverclockData data, float effectLevel) {
        // 检查是否装备安全协议超驰 - 安超舰船不应获得额外幅能
        boolean hasSafetyOverrides = ship.getVariant().hasHullMod("safetyoverrides");
        if (hasSafetyOverrides) {
            // 安超舰船直接清空待恢复幅能（如果意外有的话）
            data.pendingFlux = 0f;
            return;
        }
        
        if (data.pendingFlux <= 0f) {
            return;
        }
        
        float deltaTime = Global.getCombatEngine().getElapsedInLastFrame();
        float fluxToAdd = data.pendingFlux * FLUX_RECOVERY_RATE * deltaTime;
        
        // 添加软幅能
        float currentFlux = ship.getFluxTracker().getCurrFlux();
        float maxFlux = ship.getFluxTracker().getMaxFlux();
        
        ship.getFluxTracker().increaseFlux(fluxToAdd, false);
        data.pendingFlux -= fluxToAdd;
        
        // 检查是否需要强制排幅（此时已确认不是安超舰船）
        if (currentFlux + fluxToAdd >= maxFlux) {
            // 强制进入主动排幅状态
            ship.getFluxTracker().ventFlux();
        }
        
        // 确保pending flux不会变成负数
        if (data.pendingFlux < 0f) {
            data.pendingFlux = 0f;
        }
    }
    
    /**
     * 应用视觉效果
     */
    private void applyVisualEffects(ShipAPI ship, State state, float effectLevel) {
        
        OverclockData data = getOverclockData(ship);
        if (data.pendingFlux > 0f) {
            // 幅能恢复期间的警告效果
            float intensity = Math.min(1f, data.pendingFlux / ship.getFluxTracker().getMaxFlux());
            ship.setJitterUnder(this, FLUX_WARNING_COLOR, intensity * 0.5f, 2, 3f);
        }
    }
    
    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        // 移除所有效果，使用与apply中相同的ID
        stats.getEnergyRoFMult().unmodify(id + "_rof");
        stats.getEnergyProjectileSpeedMult().unmodify(id + "_speed");
        stats.getEnergyAmmoRegenMult().unmodify(id + "_ammo");
        stats.getEnergyWeaponFluxCostMod().unmodify(id + "_flux");
    }
    
    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (index == 0) {
            // 合并标题和效果到一行显示
            String effects = "射速+" + (int)(ENERGY_WEAPON_ROF_BONUS * effectLevel) + "% " +
                           "弹速+" + (int)(ENERGY_WEAPON_SPEED_BONUS * effectLevel) + "% " +
                           "弹药回复+" + (int)(ENERGY_AMMO_REGEN_BONUS * effectLevel) + "%";
            
            if (state == State.ACTIVE) {
                effects += " 幅能消耗-50%";
            }
            
            return new StatusData(effects, false);
        }
        return null;
    }
    
    /**
     * 获取或创建超频数据
     */
    private OverclockData getOverclockData(ShipAPI ship) {
        Object data = ship.getCustomData().get(DATA_KEY);
        if (data instanceof OverclockData) {
            return (OverclockData) data;
        } else {
            OverclockData newData = new OverclockData();
            ship.getCustomData().put(DATA_KEY, newData);
            return newData;
        }
    }
    
    /**
     * 超频数据类
     */
    private static class OverclockData {
        float pendingFlux = 0f;  // 待恢复的幅能值
    }
} 