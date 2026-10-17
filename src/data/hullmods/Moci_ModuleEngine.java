package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import java.awt.Color;

public class Moci_ModuleEngine extends BaseHullMod {
    // ====================== 可配置参数 ======================
    // 引擎视觉效果参数
    private static final Color ENGINE_COLOR = new Color(255, 125, 25, 255);  // 主引擎颜色 (RGBA)
    private static final Color CONTRAIL_COLOR = new Color(50, 50, 50, 50); // 尾迹颜色
    private static final float COLOR_FADE_DURATION = 1f;                     // 颜色渐变持续时间（秒）
    
    // 子舰同步参数
    private static final float FLUX_ADJUST_RATE = 2f;         // 通量等级调整速率
    private static final float FLUX_MIN_LIMIT = -2f;          // 通量下限阈值
    private static final float FLUX_MAX_LIMIT = 2f;           // 通量上限阈值
    private static final String STAT_MOD_ID = "Moci_ModuleParts"; // 状态ID
    
    // 调试参数
    private static final boolean SHOW_ENGINE_DEBUG = false;   // 显示引擎调试信息

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        // 核心逻辑：更新引擎颜色和子舰行为
        //updateEngineColor(ship);
        syncChildBehavior(ship);
    }

    /**
     * 更新引擎颜色效果
     * @param ship 目标舰船
     */
//    private void updateEngineColor(ShipAPI ship) {
//        ShipEngineControllerAPI controller = ship.getEngineController();
//        if (controller != null) {
//            controller.fadeToOtherColor(
//                this,
//                ENGINE_COLOR,
//                CONTRAIL_COLOR,
//                COLOR_FADE_DURATION,
//                COLOR_FADE_DURATION
//            );
//        }
//    }

    /**
     * 同步子舰与母舰行为
     * @param ship 当前处理的子舰
     */
    private void syncChildBehavior(ShipAPI ship) {
        ShipAPI parent = ship.getParentStation();
        if (parent != null && parent.isAlive()) {
            syncEngineCommands(ship, parent);
            syncFlameoutState(ship, parent);
 //           adjustFluxLevels(ship, parent);
 //           syncFighterCommands(ship, parent);
        }
    }

    // ====================== 子舰行为同步 ======================

    private void syncEngineCommands(ShipAPI child, ShipAPI parent) {
        ShipEngineControllerAPI parentEngine = parent.getEngineController();
        if (parentEngine == null) return;

        if (parentEngine.isAccelerating()) {
            child.giveCommand(ShipCommand.ACCELERATE, null, 0);
        }
        if (parentEngine.isAcceleratingBackwards()) {
            child.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
        }
        if (parentEngine.isDecelerating()) {
            child.giveCommand(ShipCommand.DECELERATE, null, 0);
        }
        if (parentEngine.isStrafingLeft()) {
            child.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
        }
        if (parentEngine.isStrafingRight()) {
            child.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
        }
        if (parentEngine.isTurningLeft()) {
            child.giveCommand(ShipCommand.TURN_LEFT, null, 0);
        }
        if (parentEngine.isTurningRight()) {
            child.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
        }
    }

    /**
     * 同步引擎熄火状态
     */
    private void syncFlameoutState(ShipAPI child, ShipAPI parent) {
        ShipEngineControllerAPI parentEngine = parent.getEngineController();
        ShipEngineControllerAPI childEngine = child.getEngineController();
        
        if (parentEngine != null && childEngine != null 
            && (parentEngine.isFlamingOut() || parentEngine.isFlamedOut())
            && !childEngine.isFlamingOut() 
            && !childEngine.isFlamedOut()) {
            childEngine.forceFlameout(true);
        }
    }

//    /**
//     * 调整幅能等级参数
//     */
//    private void adjustFluxLevels(ShipAPI child, ShipAPI parent) {
//        // 获取时间增量并计算实际时间
//        float amount = getAdjustedTimeAmount();
//
//        // 获取当前状态修改器
//        MutableStat.StatMod stat = child.getMutableStats()
//            .getZeroFluxMinimumFluxLevel()
//            .getFlatStatMod(STAT_MOD_ID);
//
//        // 计算新的幅能等级
//        float currLevel = (stat != null) ? stat.getValue() : 0f;
//        if (shouldReduceFlux(parent)) {
//            currLevel = Math.max(FLUX_MIN_LIMIT, currLevel - amount * FLUX_ADJUST_RATE);
//        } else {
//            currLevel = Math.min(FLUX_MAX_LIMIT, currLevel + amount * FLUX_ADJUST_RATE);
//        }
//
//        // 应用新的幅能等级
//        child.getMutableStats()
//            .getZeroFluxMinimumFluxLevel()
//            .modifyFlat(STAT_MOD_ID, currLevel);
//    }

//   /**
//    * 同步舰载机指令（如果存在舰载机）
//    */
//   private void syncFighterCommands(ShipAPI child, ShipAPI parent) {
//       if (!child.hasLaunchBays()) return;
//
//       // 同步舰载机召回状态
//       if (child.isPullBackFighters() != parent.isPullBackFighters()) {
//           child.giveCommand(ShipCommand.PULL_BACK_FIGHTERS, null, 0);
//       }
//
//       // 同步舰载机目标
//       if (child.getAIFlags() != null) {
//           CombatEntityAPI target = resolveParentTarget(parent);
//           if (target != null) {
//               child.getAIFlags().setFlag(
//                   ShipwideAIFlags.AIFlags.CARRIER_FIGHTER_TARGET,
//                   1f,
//                   target
//               );
//           }
//       }
//   }

//    /**
//     * 获取经过调整的时间增量
//     */
//    private float getAdjustedTimeAmount() {
//        float amount = Global.getCombatEngine().getElapsedInLastFrame();
//        if (Global.getCombatEngine().isPaused()) amount = 0f;
//        return amount * Global.getCombatEngine().getTimeMult().getModifiedValue();
//    }
//
//    /**
//     * 判断是否需要降低幅能等级
//     */
//    private boolean shouldReduceFlux(ShipAPI parent) {
//        return parent.getFluxLevel() > parent.getMutableStats().getZeroFluxMinimumFluxLevel().getModifiedValue()
//            || (parent.getMutableStats().getZeroFluxMinimumFluxLevel().getModifiedValue() == 0f
//                && parent.getCurrFlux() > 0f);
//    }

//    /**
//     * 解析母舰的有效目标
//     */
//    private CombatEntityAPI resolveParentTarget(ShipAPI parent) {
//        if (Global.getCombatEngine().getPlayerShip() == parent
//            || parent.getAIFlags() == null) {
//            return parent.getShipTarget();
//        }
//        return (CombatEntityAPI) parent.getAIFlags()
//            .getCustom(ShipwideAIFlags.AIFlags.CARRIER_FIGHTER_TARGET);
//    }

    /**
     * 判断是否是移动指令
     */
    private boolean isMovementCommand(ShipCommand cmd) {
        return cmd == ShipCommand.ACCELERATE 
            || cmd == ShipCommand.ACCELERATE_BACKWARDS
            || cmd == ShipCommand.DECELERATE
            || cmd == ShipCommand.STRAFE_LEFT
            || cmd == ShipCommand.STRAFE_RIGHT
            || cmd == ShipCommand.TURN_LEFT
            || cmd == ShipCommand.TURN_RIGHT;
    }
}