package data.hullmods;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.combat.entities.Ship.ShipAIWrapper;

import data.scripts.Moci_CollisionStateManager;
import data.scripts.util.Moci_AIRestoreUtil;
import data.scripts.util.Moci_TravelDriveControl;

/** 独立管理一次整备流程，不依赖舰船当前安装的 AI 持续运行。 */
public final class Moci_RefitSession {
    public static final String DATA_KEY = "Moci_RefitSession";
    public static final String LANDING_ABORT_REASON_KEY = "Moci_LandingAbortReason";
    private static final String LANDING_COLLISION_ID = "Moci_LandingAI";
    private static final String REPAIR_COLLISION_ID = "Moci_RefitSession";
    private static final float LAUNCH_FIRE_DELAY = 1.5f;
    // 降落检测按全局战斗模拟时间（秒）计时，受全局时间倍率影响，暂停不计时。
    // 同一帧多次检查不会重复累计时间；此处不按现实时间或单船时间倍率计时。
    // 启动宽限：从调用 beginLandingAnimation() 起，允许原生降落状态延迟出现的时间。
    // 原生实现通常立即置为降落状态；这里留 2 秒兼容余量。需要调整启动宽限时修改此值。
    private static final float LANDING_START_GRACE_SECONDS = 2f;
    // 整备移交宽限：从首次检测到动画完成起，等待降落 AI 调用 startRepair() 的最长时间。
    // 正常流程也可能跨两次 AI 更新；留 2 秒容纳较慢的调度，避免 0.5 秒过早中止。
    // 需要调整移交宽限时修改此值；该值不控制整备耗时，也不延长下方的动画总超时。
    private static final float LANDING_HANDOFF_GRACE_SECONDS = 2f;
    // 动画总超时：从触发原生动画起，等待动画完成的最长时间，与上述两个宽限分别判定。
    // 正常时间倍率下原生动画约 3 秒；这里给 6 秒。此处使用全局时间，单船时间减速需另行适配。
    private static final float LANDING_ANIMATION_TIMEOUT_SECONDS = 6f;

    private enum Stage { APPROACHING, REPAIRING, LAUNCHING, CLOSED }

    private final ShipAPI ship;
    private final ShipAIPlugin originalAI;
    private final ShipAIConfig originalConfig;
    private final SavedState savedShip;
    private final List<SavedState> savedModules = new ArrayList<SavedState>();
    private Moci_RepairBayScript.Moci_RepairBay bay;
    private Moci_RepairBayScript.Moci_RepairBayStatus reservedBayStatus;
    private ShipAIPlugin installedAI;
    private Moci_LandingAI controller;
    private Stage stage = Stage.APPROACHING;
    private boolean controlsChanged;
    private boolean visualStateChanged;
    private boolean landingAnimationStarted;
    private boolean landingAnimationObserved;
    private float landingAnimationStartedAt;
    private float landingAnimationFinishedAt = -1f;

    private Moci_RefitSession(ShipAPI ship) {
        this.ship = ship;
        originalAI = ship.getShipAI();
        originalConfig = originalAI != null ? originalAI.getConfig() : null;
        savedShip = new SavedState(ship);
        for (ShipAPI module : Moci_RepairBayScript.getRepairableParts(ship)) {
            if (module != ship) savedModules.add(new SavedState(module));
        }
        Moci_MobileSuitRepairTracker tracker = Moci_MobileSuitRepairTracker.get(ship);
        if (tracker != null) bay = tracker.getBay();
        reservedBayStatus = getBayStatus();
    }

    public static Moci_RefitSession get(ShipAPI ship) {
        if (ship == null) return null;
        Object existing = ship.getCustomData().get(DATA_KEY);
        return existing instanceof Moci_RefitSession ? (Moci_RefitSession) existing : null;
    }

    public static Moci_RefitSession getOrCreate(ShipAPI ship) {
        if (ship == null) return null;
        Moci_RefitSession session = get(ship);
        if (session == null || !session.isActive()) {
            session = new Moci_RefitSession(ship);
            ship.setCustomData(DATA_KEY, session);
        }
        return session;
    }

    public boolean isActive() {
        return stage != Stage.CLOSED;
    }

    void beginApproach(Moci_RepairBayScript.Moci_RepairBay target, Moci_LandingAI landingAI) {
        bay = target;
        reservedBayStatus = getBayStatus();
        controller = landingAI;
        stage = Stage.APPROACHING;
        Moci_MobileSuitRepairTracker.getOrCreate(ship).beginApproach(target.getShip(), target);
        ship.setCustomData("Moci_LandingAIActive", true);
    }

    /** ShipAPI 可能包装自定义 AI，因此保存实际安装的包装对象，用于判断 AI 是否被替换。 */
    void bindInstalledAI() {
        installedAI = ship.getShipAI();
    }

    public ShipAIConfig getRestoreAIConfig() {
        return originalConfig;
    }

    /** 即使导航 AI 已被移除或替换，船体改造仍会调用此方法推进并清理整备流程。 */
    public void advance() {
        if (!isActive()) return;
        if (!ship.isAlive() || ship.isHulk()
                || (Global.getCombatEngine() != null && !Global.getCombatEngine().isEntityInPlay(ship))) {
            abort(false);
            return;
        }
        if (stage == Stage.LAUNCHING) return;
        if (installedAI != null && ship.getShipAI() != installedAI) {
            // 保留玩家手动控制，或其他功能替换的新 AI，不用旧 AI 覆盖它们。
            if (landingAnimationStarted) {
                ship.setCustomData(LANDING_ABORT_REASON_KEY,
                        ship.getShipAI() == null ? "manual_control_takeover" : "landing_ai_replaced");
            }
            abort(false);
            return;
        }
        if (installedAI == null && controller != null && ship.getShipAI() == null) {
            if (landingAnimationStarted) ship.setCustomData(LANDING_ABORT_REASON_KEY, "manual_control_takeover");
            abort(false);
            return;
        }
        boolean carrierValid = bay != null && bay.getShip() != null && bay.getShip().isAlive()
                && !bay.getShip().isHulk()
                && (Global.getCombatEngine() == null || Global.getCombatEngine().isEntityInPlay(bay.getShip()));
        Moci_RepairBayScript.Moci_RepairBayStatus status = getBayStatus();
        if (status == null || status != reservedBayStatus) {
            // 自定义数据中的登记被删除后，旧监听器仍可能继续存在。
            // 只释放最初预约的泊位，避免误释放替换脚本管理的泊位。
            abort(true);
        } else if (!carrierValid) {
            if (stage == Stage.REPAIRING) {
                Moci_MobileSuitRepairTracker.getOrCreate(ship).markEmergencyRelease();
                status.forceFinishRepair();
            } else {
                abort(true);
            }
        }
        if (isActive()) advanceLandingAnimation();
    }

    /** 导航 AI 停止更新时，船体改造仍会推进此检测，避免舰船永久卡在降落状态。 */
    private void advanceLandingAnimation() {
        if (stage != Stage.APPROACHING || !landingAnimationStarted) return;
        float elapsed = Math.max(0f, combatTime() - landingAnimationStartedAt);
        if (ship.isLanding()) {
            landingAnimationObserved = true;
            if (ship.isFinishedLanding()) {
                if (landingAnimationFinishedAt < 0f) {
                    landingAnimationFinishedAt = combatTime();
                } else if (combatTime() - landingAnimationFinishedAt >= LANDING_HANDOFF_GRACE_SECONDS) {
                    // AI 虽仍安装但已停止推进时，也要释放动画结束后仍未进入整备的舰船。
                    abortLandingAnimation("native_landing_handoff_timeout");
                }
                return;
            }
        } else if (landingAnimationObserved || elapsed >= LANDING_START_GRACE_SECONDS) {
            // 曾经进入降落状态后又消失，视为中途被打断，直接中止；启动宽限只用于从未进入的情况。
            abortLandingAnimation(landingAnimationObserved
                    ? "native_landing_interrupted" : "native_landing_not_started");
            return;
        }
        if (elapsed >= LANDING_ANIMATION_TIMEOUT_SECONDS) {
            abortLandingAnimation("native_landing_timeout");
        }
    }

    private void abortLandingAnimation(String reason) {
        // 清理后保留本次中断原因，等下一次开始降落动画时再清除。
        ship.setCustomData(LANDING_ABORT_REASON_KEY, reason);
        abort(true);
    }

    private float combatTime() {
        return Global.getCombatEngine() != null
                ? Global.getCombatEngine().getTotalElapsedTime(false) : 0f;
    }

    boolean isLandingAnimationComplete() {
        return stage == Stage.APPROACHING && landingAnimationStarted
                && ship.isLanding() && ship.isFinishedLanding();
    }

    void maintainApproachCollision() {
        if (stage == Stage.APPROACHING) {
            Moci_CollisionStateManager.getInstance().setCollisionModifier(
                    ship, LANDING_COLLISION_ID, 300, CollisionClass.FIGHTER);
        }
    }

    void beginLandingAnimation() {
        if (!isActive() || landingAnimationStarted || bay == null) return;
        landingAnimationStarted = true;
        landingAnimationStartedAt = combatTime();
        ship.removeCustomData(LANDING_ABORT_REASON_KEY);
        visualStateChanged = true;
        ship.beginLandingAnimation(bay.getShip());
        landingAnimationObserved = ship.isLanding();
        Moci_RepairBayScript.markLandingAnimationStarted(ship, bay, true);
        for (SavedState saved : savedModules) {
            ShipAPI module = saved.ship;
            if (!Moci_RepairBayScript.isAttachedLivingPart(ship, module)) continue;
            module.beginLandingAnimation(bay.getShip());
            if (module.getVariant() != null
                    && module.getVariant().hasHullMod("Moci_module_collision_controller")) {
                Moci_ModuleCollisionSync.startRepairingHiding(module);
            }
        }
        lockLandingControls();
    }

    void lockLandingControls() {
        if (!isActive()) return;
        controlsChanged = true;
        ship.setControlsLocked(true);
        ship.setShipSystemDisabled(true);
        ship.setInvalidTransferCommandTarget(true);
    }

    public void enterRepair() {
        if (!isActive() || stage == Stage.LAUNCHING) return;
        Moci_MobileSuitRepairTracker tracker = Moci_MobileSuitRepairTracker.getOrCreate(ship);
        if (bay == null) bay = tracker.getBay();
        if (reservedBayStatus == null) reservedBayStatus = getBayStatus();
        stage = Stage.REPAIRING;
        visualStateChanged = true;
        tracker.beginRepair(bay != null ? bay.getShip() : tracker.getCarrier(), bay);
        Moci_RepairBayScript.clearLaunchAnimationMarkers(ship);
        maintainRepairState();
    }

    public void maintainRepairState() {
        if (stage != Stage.REPAIRING) return;
        lockLandingControls();
        ship.setDefenseDisabled(true);
        ship.setPhased(true);
        Moci_CollisionStateManager.getInstance().setCollisionModifier(
                ship, REPAIR_COLLISION_ID, 400, CollisionClass.NONE);
        if (ship.getSystem() != null && (ship.getSystem().isOn() || ship.getSystem().isActive()
                || ship.getSystem().isChargeup() || ship.getSystem().isChargedown())) {
            ship.getSystem().deactivate();
        }
    }

    /** 开始一次起飞；动画结束后，由整备湾的起飞观察器调用 finishLaunch 完成收尾。 */
    public void beginLaunch(ShipAIConfig fallbackConfig) {
        if (!isActive() || stage == Stage.LAUNCHING) return;
        stage = Stage.LAUNCHING;
        if (controller != null) controller.sessionFinished();
        Moci_MobileSuitRepairTracker tracker = Moci_MobileSuitRepairTracker.getOrCreate(ship);
        tracker.beginTakeoff(bay != null ? bay.getShip() : tracker.getCarrier(), bay);
        restoreOwnedState(false);
        clearActiveData();
        if (Global.getCombatEngine() != null && Global.getCombatEngine().getPlayerShip() == ship
                && bay != null && ship.getShipTarget() == bay.getShip()) {
            ship.setShipTarget(null);
        }
        ship.removeListenerOfClass(Moci_RepairBayScript.Moci_AnimationLanding.class);
        Moci_RepairBayScript.markLaunchAnimationStarted(ship, "refit_session_launch");
        ship.setAnimatedLaunch();
        for (SavedState saved : savedModules) {
            if (Moci_RepairBayScript.isAttachedLivingPart(ship, saved.ship)) saved.ship.setAnimatedLaunch();
        }
        Global.getSoundPlayer().playSound("fighter_takeoff", 1f, 1f, ship.getLocation(), new Vector2f());
        // 释放泊位时，不覆盖已被玩家移除或被其他功能替换的 AI。
        if (ship.getShipAI() != null && (installedAI == null || ship.getShipAI() == installedAI)) {
            restoreAI(fallbackConfig);
        }
        if (ship.getShipAI() != null) ship.getShipAI().setDoNotFireDelay(LAUNCH_FIRE_DELAY);
        tracker.markTakingOff();
    }

    public void finishLaunch() {
        if (stage != Stage.LAUNCHING) return;
        ship.removeListenerOfClass(Moci_RepairBayScript.Moci_AnimationLaunch.class);
        ship.removeListenerOfClass(Moci_RepairBayScript.Moci_LaunchAnimationObserver.class);
        restoreVisualState();
        releaseReservation();
        stage = Stage.CLOSED;
        Moci_MobileSuitRepairTracker.getOrCreate(ship).markCompleted();
        removeSessionReference();
    }

    public void abort() {
        abort(true);
    }

    public void abort(boolean restoreAI) {
        if (!isActive()) return;
        stage = Stage.CLOSED;
        if (controller != null) controller.sessionFinished();
        ship.removeListenerOfClass(Moci_RepairBayScript.Moci_AnimationLanding.class);
        ship.removeListenerOfClass(Moci_RepairBayScript.Moci_AnimationLaunch.class);
        ship.removeListenerOfClass(Moci_RepairBayScript.Moci_LaunchAnimationObserver.class);
        restoreOwnedState(true);
        releaseReservation();
        clearActiveData();
        clearAnimationData();
        if (restoreAI && installedAI != null && ship.getShipAI() == installedAI && ship.isAlive()) {
            restoreAI(originalConfig);
        }
        Moci_MobileSuitRepairTracker.getOrCreate(ship).markAborted();
        removeSessionReference();
    }

    private void restoreAI(ShipAIConfig fallbackConfig) {
        ShipAIPlugin unwrapped = originalAI;
        while (unwrapped instanceof ShipAIWrapper) {
            unwrapped = ((ShipAIWrapper) unwrapped).getAI();
        }
        ShipAIPlugin savedAI = unwrapped instanceof Moci_LandingAI ? null : originalAI;
        Moci_AIRestoreUtil.restoreSavedOrDefaultAI(ship, savedAI,
                fallbackConfig != null ? fallbackConfig : originalConfig);
    }

    private void restoreOwnedState(boolean abortAnimation) {
        if (abortAnimation && landingAnimationStarted) {
            if (ship.isLanding()) ship.abortLanding();
            for (SavedState saved : savedModules) {
                if (saved.ship.isLanding()) saved.ship.abortLanding();
            }
        }
        if (controlsChanged) {
            savedShip.restoreControlState();
            controlsChanged = false;
        }
        Moci_CollisionStateManager manager = Moci_CollisionStateManager.getInstance();
        manager.removeCollisionModifier(ship, REPAIR_COLLISION_ID);
        manager.removeCollisionModifier(ship, LANDING_COLLISION_ID);
        if (!manager.hasActiveModifier(ship)) ship.setCollisionClass(savedShip.collision);
        for (SavedState saved : savedModules) {
            if (!manager.hasActiveModifier(saved.ship)) saved.ship.setCollisionClass(saved.collision);
        }
        restoreVisualState();
        Moci_TravelDriveControl.stopTravelDrive(ship);
    }

    private void restoreVisualState() {
        if (!visualStateChanged) return;
        savedShip.restoreVisualState();
        for (SavedState saved : savedModules) saved.restoreVisualState();
    }

    private Moci_RepairBayScript.Moci_RepairBayStatus getBayStatus() {
        if (bay == null || bay.getShip() == null) return null;
        Moci_RepairBayScript script = Moci_RepairBayScript.getInstance(bay.getShip());
        return script != null ? script.getBay(bay) : null;
    }

    private void releaseReservation() {
        if (reservedBayStatus != null) {
            reservedBayStatus.releaseReservation(ship);
            reservedBayStatus = null;
        }
    }

    private void clearActiveData() {
        ship.setCustomData("Moci_LandingAIActive", false);
        ship.removeCustomData("Moci_ActiveRefitReason");
        ship.removeCustomData("Moci_LastLandingPosition");
        ship.removeCustomData("Moci_StuckTimer");
    }

    private void clearAnimationData() {
        Moci_RepairBayScript.clearLaunchAnimationMarkers(ship);
        ship.removeCustomData(Moci_RepairBayScript.LANDING_ANIM_STARTED_KEY);
        ship.removeCustomData(Moci_RepairBayScript.LANDING_ANIM_FINISHED_KEY);
        ship.removeCustomData(Moci_RepairBayScript.LANDING_ANIM_FINISH_REASON_KEY);
    }

    private void removeSessionReference() {
        if (get(ship) == this) ship.removeCustomData(DATA_KEY);
    }

    private static final class SavedState {
        final ShipAPI ship;
        final CollisionClass collision;
        final boolean controlsLocked;
        final boolean systemDisabled;
        final boolean defenseDisabled;
        final boolean invalidTransferTarget;
        final boolean phased;
        final float alpha;
        final float extraAlpha;
        final float extraAlpha2;
        final Object repairing;
        final Object hideTimer;
        final Object hideOriginalAlpha;

        SavedState(ShipAPI ship) {
            this.ship = ship;
            collision = ship.getCollisionClass();
            controlsLocked = ship.controlsLocked();
            systemDisabled = ship.isShipSystemDisabled();
            defenseDisabled = ship.isDefenseDisabled();
            invalidTransferTarget = ship.isInvalidTransferCommandTarget();
            phased = ship.isPhased();
            alpha = ship.getAlphaMult();
            extraAlpha = ship.getExtraAlphaMult();
            extraAlpha2 = ship.getExtraAlphaMult2();
            repairing = ship.getCustomData().get(Moci_ModuleCollisionSync.MODULE_REPAIRING_KEY);
            hideTimer = ship.getCustomData().get(Moci_ModuleCollisionSync.MODULE_HIDE_TIMER_KEY);
            hideOriginalAlpha = ship.getCustomData().get(Moci_ModuleCollisionSync.MODULE_HIDE_ALPHA_KEY);
        }

        void restoreControlState() {
            ship.setControlsLocked(controlsLocked);
            ship.setShipSystemDisabled(systemDisabled);
            ship.setDefenseDisabled(defenseDisabled);
            ship.setInvalidTransferCommandTarget(invalidTransferTarget);
            ship.setPhased(phased);
        }

        void restoreVisualState() {
            ship.setAlphaMult(alpha);
            ship.setExtraAlphaMult(extraAlpha);
            ship.setExtraAlphaMult2(extraAlpha2);
            restoreData(Moci_ModuleCollisionSync.MODULE_REPAIRING_KEY, repairing);
            restoreData(Moci_ModuleCollisionSync.MODULE_HIDE_TIMER_KEY, hideTimer);
            restoreData(Moci_ModuleCollisionSync.MODULE_HIDE_ALPHA_KEY, hideOriginalAlpha);
        }

        void restoreData(String key, Object value) {
            if (value == null) ship.removeCustomData(key);
            else ship.setCustomData(key, value);
        }
    }
}
