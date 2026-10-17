package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipSystemAPI;

public class Moci_SignalDistortionAmplifier extends BaseHullMod {
    
    // 系统射程加成百分比
    private static final float SYSTEM_RANGE_BONUS = 200f;
    // 系统冷却时间减少百分比
    private static final float SYSTEM_COOLDOWN_BONUS = 50f;
    // 系统幅能消耗减少百分比
    private static final float SYSTEM_FLUX_COST_REDUCTION = 99f;

    private static final String LAST_SYSTEM_STATE_KEY = "moci_last_system_state";
    private static final String LAST_FLUX_KEY = "moci_last_flux";

    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 增加系统射程
        stats.getSystemRangeBonus().modifyPercent(id, SYSTEM_RANGE_BONUS);
        // 减少系统冷却时间
        stats.getSystemCooldownBonus().modifyPercent(id, -SYSTEM_COOLDOWN_BONUS);
    }
    
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null || !ship.isAlive() || ship.getSystem() == null) return;
        
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) return;
        
        ShipSystemAPI system = ship.getSystem();
        String shipId = ship.getId();
        
        // 获取上一帧的系统状态
        String lastStateKey = shipId + LAST_SYSTEM_STATE_KEY;
        String lastFluxKey = shipId + LAST_FLUX_KEY;
        
        ShipSystemAPI.SystemState lastState = (ShipSystemAPI.SystemState) engine.getCustomData().get(lastStateKey);
        Float lastFlux = (Float) engine.getCustomData().get(lastFluxKey);
        
        ShipSystemAPI.SystemState currentState = system.getState();
        float currentFlux = ship.getFluxTracker().getCurrFlux();
        
        // 检测系统激活（从IDLE变为IN状态）
        if (lastState == ShipSystemAPI.SystemState.IDLE && 
            currentState == ShipSystemAPI.SystemState.IN && 
            lastFlux != null) {
            
            // 计算系统消耗的幅能
            float fluxConsumed = currentFlux - lastFlux;
            if (fluxConsumed > 0) {
                // 减少50%的幅能消耗
                float fluxReduction = fluxConsumed * (SYSTEM_FLUX_COST_REDUCTION / 100f);
                ship.getFluxTracker().decreaseFlux(fluxReduction);
            }
        }
        
        // 保存当前状态供下一帧使用
        engine.getCustomData().put(lastStateKey, currentState);
        engine.getCustomData().put(lastFluxKey, currentFlux);
    }
    
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) SYSTEM_RANGE_BONUS + "%";  // 系统射程加成
        if (index == 1) return "" + (int) SYSTEM_COOLDOWN_BONUS + "%";  // 冷却时间减少
        if (index == 2) return "" + (int) SYSTEM_FLUX_COST_REDUCTION + "%";  // 幅能消耗减少
        
        return null;
    }
}
