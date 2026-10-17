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

/** Owns a refit operation independently of the AI currently installed on the ship. */
public final class Moci_RefitSession {
    public static final String DATA_KEY = "Moci_RefitSession";
    private static final String LANDING_COLLISION_ID = "Moci_LandingAI";
    private static final String REPAIR_COLLISION_ID = "Moci_RefitSession";
    private static final float LAUNCH_FIRE_DELAY = 1.5f;

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

    /** ShipAPI may wrap custom AIs; retain the actual installed wrapper identity. */
    void bindInstalledAI() {
        installedAI = ship.getShipAI();
    }

    public ShipAIConfig getRestoreAIConfig() {
        return originalConfig;
    }

    /** Invoked by the hullmod even after the navigation AI was removed/replaced. */
    public void advance() {
        if (!isActive()) return;
        if (!ship.isAlive() || ship.isHulk()
                || (Global.getCombatEngine() != null && !Global.getCombatEngine().isEntityInPlay(ship))) {
            abort(false);
            return;
        }
        if (stage == Stage.LAUNCHING) return;
        if (installedAI != null && ship.getShipAI() != installedAI) {
            // Preserve manual control and replacement AIs supplied by another feature.
            abort(false);
            return;
        }
        if (installedAI == null && controller != null && ship.getShipAI() == null) {
            abort(false);
            return;
        }
        boolean carrierValid = bay != null && bay.getShip() != null && bay.getShip().isAlive()
                && !bay.getShip().isHulk()
                && (Global.getCombatEngine() == null || Global.getCombatEngine().isEntityInPlay(bay.getShip()));
        Moci_RepairBayScript.Moci_RepairBayStatus status = getBayStatus();
        if (status == null || status != reservedBayStatus) {
            // The old listener can still exist after its custom-data registration is removed.
            // Release the originally reserved status, never a replacement script's berth.
            abort(true);
        } else if (!carrierValid) {
            if (stage == Stage.REPAIRING) {
                Moci_MobileSuitRepairTracker.getOrCreate(ship).markEmergencyRelease();
                status.forceFinishRepair();
            } else {
                abort(true);
            }
        }
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
        visualStateChanged = true;
        ship.beginLandingAnimation(bay.getShip());
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

    /** Starts one launch; the repair bay's observer calls finishLaunch after animation completion. */
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
        // A manually removed/replaced AI is never overwritten while releasing the berth.
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
        ship.turnOffTravelDrive();
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
