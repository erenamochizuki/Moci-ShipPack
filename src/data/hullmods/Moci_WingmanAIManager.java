package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;

import data.scripts.util.Moci_AIRestoreUtil;

/**
 * Moci_WingmanAIManager - 无人机智能AI管理器
 * 
 * 只作为AdvanceableListener使用，不作为HullMod使用
 * 每个无人机都有自己独立的实例
 */
public class Moci_WingmanAIManager extends BaseHullMod implements AdvanceableListener {

    private ShipAPI managedShip = null;
    private String lastAIType = "";
    private boolean lastLandingActive = false;

    public Moci_WingmanAIManager() {
    }

    public void setManagedShip(ShipAPI ship) {
        this.managedShip = ship;
    }

    @Override
    public void advance(float amount) {
        if (managedShip == null) return;
        
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;
        if (!managedShip.isAlive() || !engine.isEntityInPlay(managedShip)) {
            managedShip.removeListener(this);
            return;
        }

        advanceInternal(managedShip, amount);
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        // 不使用HullMod方式，留空
        // 所有逻辑都在advance()中通过Listener方式处理
    }

    private void advanceInternal(ShipAPI ship, float amount) {
        // 只处理标记为Zero无人机的舰船
        if (!isZeroWingman(ship)) {
            return;
        }

        String shipName = (ship.getName() != null) ? ship.getName() : ship.getHullSpec().getHullId();

        Moci_RefitSession refitSession = Moci_RefitSession.get(ship);
        if (refitSession != null) refitSession.advance();
        Moci_MobileSuitRepairTracker refitTracker = Moci_MobileSuitRepairTracker.get(ship);
        if (refitTracker != null && refitTracker.isActive()) return;

        // 检查AI是否丢失，如果丢失则恢复
        if (ship.getShipAI() == null) {
            Global.getLogger(this.getClass()).info(
                shipName + " [无人机AI管理器] AI丢失，正在恢复默认AI");
            ShipAIConfig fallbackConfig = new ShipAIConfig();
            fallbackConfig.alwaysStrafeOffensively = true;
            Moci_AIRestoreUtil.restoreDefaultAI(ship, fallbackConfig);
            ship.setCustomData("Moci_LandingAIActive", false);
            
            if (ship.getShipAI() != null) {
                Global.getLogger(this.getClass()).info(
                    shipName + " [无人机AI管理器] AI恢复成功: " + ship.getShipAI().getClass().getSimpleName());
            } else {
                Global.getLogger(this.getClass()).info(
                    shipName + " [无人机AI管理器] AI恢复失败，仍然为null");
            }
            return;
        }

        // 获取当前状态
        String currentAIType = ship.getShipAI().getClass().getSimpleName();
        boolean landingAIActive = ship.getCustomData().containsKey("Moci_LandingAIActive") &&
                (Boolean) ship.getCustomData().get("Moci_LandingAIActive");

        // 检测状态变化并记录
        if (!currentAIType.equals(lastAIType) || landingAIActive != lastLandingActive) {
            Global.getLogger(this.getClass()).info(
                shipName + " [状态变化] AI: " + lastAIType + " -> " + currentAIType + 
                ", 标记: " + lastLandingActive + " -> " + landingAIActive);
            lastAIType = currentAIType;
            lastLandingActive = landingAIActive;
        }

        // 检查是否真的是LandingAI（处理ShipAIWrapper包装的情况）
        boolean isLandingAI = isLandingAIActive(ship);

        // 如果标记说在着陆中，但AI确实不是LandingAI，说明着陆流程已结束
        if (landingAIActive && !isLandingAI) {
            Global.getLogger(this.getClass()).info(
                    shipName + " [无人机AI管理器] 着陆流程已结束，清理标记。当前AI: " + currentAIType);
            ship.setCustomData("Moci_LandingAIActive", false);
            landingAIActive = false;
        }
        
        // 检查AI是否被错误地设置为FighterAI，如果是则恢复原始AI
        if (currentAIType.equals("FighterAI") && !landingAIActive) {
            Global.getLogger(this.getClass()).info(
                    shipName + " [无人机AI管理器] 检测到FighterAI，恢复原始护卫舰AI");
            restoreOriginalAI(ship);
        }

        // 检查是否需要整备
        Moci_RepairBayScript.Moci_RepairBay bay = Moci_LandingSequence.findAutomaticRefitTarget(ship);

        if (bay != null && !landingAIActive && Moci_LandingAI.startLanding(ship, bay)) {
            Global.getLogger(this.getClass()).info(
                    shipName + " [无人机AI管理器] 切换到着陆AI，目标: " + bay.getShip().getName());
        }
    }

    /**
     * 检查是否真的在使用LandingAI（处理ShipAIWrapper包装的情况）
     */
    private boolean isLandingAIActive(ShipAPI ship) {
        if (ship.getShipAI() == null) return false;
        
        // 直接检查
        if (ship.getShipAI() instanceof Moci_LandingAI) {
            return true;
        }
        
        // 检查是否被ShipAIWrapper包装
        // 如果是ShipAIWrapper且标记为true，假设里面包装的是LandingAI
        String aiClassName = ship.getShipAI().getClass().getSimpleName();
        if (aiClassName.equals("ShipAIWrapper")) {
            // 检查标记来判断
            boolean marked = ship.getCustomData().containsKey("Moci_LandingAIActive") &&
                    (Boolean) ship.getCustomData().get("Moci_LandingAIActive");
            return marked;
        }
        
        return false;
    }

    /**
     * 恢复原始的护卫舰AI
     */
    private void restoreOriginalAI(ShipAPI ship) {
        Object originalAI = ship.getCustomData().get("Moci_Zero_OriginalAI");
        
        if (originalAI != null) {
            // 使用保存的原始AI
            Moci_AIRestoreUtil.restoreSavedOrDefaultAI(ship, originalAI, null);
        } else {
            // 如果没有保存，重新创建护卫舰AI
            ShipAIConfig config = new ShipAIConfig();
            config.alwaysStrafeOffensively = true;
            Moci_AIRestoreUtil.restoreDefaultAI(ship, config);
        }
    }

    private boolean isZeroWingman(ShipAPI ship) {
        Object data = ship.getCustomData().get("Moci_Zero_Wingman");
        return data instanceof Boolean && (Boolean) data;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return true;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return null;
    }

    @Override
    public boolean showInRefitScreenModPickerFor(ShipAPI ship) {
        return false;
    }
}
