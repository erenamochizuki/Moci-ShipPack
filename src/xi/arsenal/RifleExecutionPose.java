package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * DSK execution-only owner of the already authored rifle-sniper skeleton action.
 * The rifle controller remains the sole clock, input, ammo, damage and fire owner.
 * All skeleton writes and restoration stay inside RifleFiringPose's own lease.
 */
public final class RifleExecutionPose implements AutoCloseable {
    public static final String KEY = "xi.arsenal.rifle.executionPose";
    public static final float RECOVER_SECONDS = DskRifleCurveV2.RECOVERY;
    public enum Stage { PRESENTING, FIRING, RECOVER }

    private static final String RIG_KEY = RifleFiringPose.KEY + ".releaseRig";
    private static final String LEG_KEY = RifleFiringPose.KEY + ".standingLeg";
    private final Thread owner = Thread.currentThread();
    private final ShipAPI ship;
    private final WeaponAPI pulse;
    private final float power, presentSeconds;
    private final AutoCloseable lease;
    private final Object rigIdentity;
    private Object poseIdentity, standingIdentity;
    private Stage stage = Stage.PRESENTING;
    private float presentElapsed, firingElapsed, recoveryElapsed, nativeAim;
    private boolean firstLight, recovered, interrupted, closed;

    public RifleExecutionPose(ShipAPI ship, WeaponAPI visiblePulse, float presentationPower) {
        this.ship = Objects.requireNonNull(ship, "Execution ship");
        pulse = Objects.requireNonNull(visiblePulse, "Execution visible rifle");
        if (!DskRifleCurveV2.isDsk(ship))
            throw new IllegalArgumentException("Execution sniper pose is restricted to Moci_DSK_105");
        if (pulse.getShip() != ship || pulse.getSlot() == null ||
                !"ARM_R_SOURCE".equals(pulse.getSlot().getId()) || pulse.isBeam() ||
                !BeamRiflePalette.isOwnRifle(pulse.getId()))
            throw new IllegalArgumentException("Execution requires this DSK's real visible pulse rifle");
        if (!Float.isFinite(presentationPower) || presentationPower < 0)
            throw new IllegalArgumentException("Invalid execution presentation power");
        power = Math.min(1, presentationPower);
        presentSeconds = DskRifleCurveV2.toPresentation(power).total();
        nativeAim = pulse.getCurrAngle();
        if (ship.getCustomData().containsKey(KEY))
            throw new IllegalStateException("Execution pose already owned");
        String block = RifleFiringPose.blockedReason(ship);
        if (!block.isEmpty()) throw new IllegalStateException(block);
        // attach rejects existing pose/standing/rig owners. Never clear them here.
        lease = RifleFiringPose.attach(ship, new RifleReleaseRig() {
            public ShipAPI ship() { return RifleExecutionPose.this.ship; }
            public WeaponAPI source() { return pulse; }
            public float presentationPower() { return power; }
            public int standingLegFrame() { return 3; }
            public boolean preserveNativeLegs() { return true; }
        });
        rigIdentity = ship.getCustomData().get(RIG_KEY);
        ship.setCustomData(KEY, this);
        try {
            RifleFiringPose.advance(ship, RifleFiringPose.Stage.PRESENTING, 0);
            poseIdentity = ship.getCustomData().get(RifleFiringPose.KEY);
            standingIdentity = ship.getCustomData().get(LEG_KEY);
            if (poseIdentity == null)
                throw new IllegalStateException("Execution sniper pose did not acquire its skeleton");
        } catch (RuntimeException | Error failure) {
            try { lease.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            if (ship.getCustomData().get(KEY) == this) ship.removeCustomData(KEY);
            throw failure;
        }
    }

    /** dt has already been converted to this ship's local combat time by the caller. */
    public void advance(float dt, Stage wanted) {
        requireOwner();
        Objects.requireNonNull(wanted, "Execution pose stage");
        if (!Float.isFinite(dt) || dt < 0) throw new IllegalArgumentException("Invalid execution pose step");
        if (closed || recovered) return;
        requireOwnership();
        if (stage == Stage.RECOVER && wanted != Stage.RECOVER)
            throw new IllegalStateException("Recovering execution pose cannot restart");
        if (firstLight && wanted == Stage.PRESENTING)
            throw new IllegalStateException("A fired execution pose cannot present again");
        if (wanted == Stage.FIRING && !firstLight)
            throw new IllegalStateException("Execution firing pose requires actual native first light");
        if (!ownPosePresent()) { markInterrupted(); return; }
        stage = wanted;
        switch (wanted) {
            case PRESENTING -> {
                presentElapsed = Math.min(presentSeconds, presentElapsed + dt);
                RifleFiringPose.advance(ship, RifleFiringPose.Stage.PRESENTING, dt);
            }
            case FIRING -> {
                firingElapsed += dt;
                RifleFiringPose.advance(ship, RifleFiringPose.Stage.FIRING, dt);
            }
            case RECOVER -> {
                recoveryElapsed = Math.min(RECOVER_SECONDS, recoveryElapsed + dt);
                // Published DSK closing captures current body/raise/blend on entry.
                // It works for early cancellation as well as completed native firing.
                RifleFiringPose.advance(ship, RifleFiringPose.Stage.CLOSING, dt);
            }
        }
        requireNoForeignPose();
        if (!ownPosePresent()) {
            if (wanted == Stage.RECOVER) recovered = true;
            else markInterrupted();
        }
    }

    /** Native confirmation calls this once; repeats do not restart recoil. */
    public void onNativeFirstLight() {
        requireOwner();
        if (closed) throw new IllegalStateException("Execution pose is closed");
        if (firstLight) return;
        requireOwnership();
        if (!readyForBeam())
            throw new IllegalStateException("Execution beam illuminated before its sniper pose settled");
        RifleFiringPose.onNativeFirstLight(ship);
        firstLight = true;
        stage = Stage.FIRING;
        firingElapsed = 0;
    }

    public boolean readyForBeam() {
        requireOwner();
        return !closed && !interrupted && !firstLight && stage == Stage.PRESENTING &&
                ownsRig() && ownPosePresent() && !foreignStanding() &&
                ship.getCustomData().get(KEY) == this && RifleFiringPose.blockedReason(ship).isEmpty() &&
                RifleFiringPose.readyForBeam(ship);
    }

    /** Pure angular result. The caller aligns the visible rifle, then calls applyAll. */
    public float aimAngle(float unmodifiedNativeAim) {
        requireOwner();
        if (!Float.isFinite(unmodifiedNativeAim)) throw new IllegalArgumentException("Invalid execution aim");
        nativeAim = unmodifiedNativeAim;
        if (closed || recovered || interrupted) return unmodifiedNativeAim;
        requireOwnership();
        return RifleFiringPose.gunAimAngle(pulse, unmodifiedNativeAim);
    }

    /** Late native-bone reconciliation only. This method never advances a clock. */
    public void applyAll() {
        requireOwner();
        if (closed || recovered || interrupted) return;
        requireOwnership();
        if (!ownPosePresent()) { markInterrupted(); return; }
        RifleFiringPose.applyAll(ship);
        requireNoForeignPose();
        if (!ownPosePresent()) markInterrupted();
    }

    public boolean recovered() { requireOwner(); return recovered; }

    public String blockedReason() {
        requireOwner();
        if (closed) return "处决姿态已关闭";
        if (!ownsRig() || ship.getCustomData().get(KEY) != this) return "处决姿态租约已被替换";
        if (foreignPose() || foreignStanding()) return "其他动作已替换处决骨架";
        if (interrupted) return "原处决骨架已中止";
        return RifleFiringPose.blockedReason(ship);
    }

    public Map<String, Object> status() {
        requireOwner();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", stage.name()); result.put("closed", closed);
        result.put("firstLight", firstLight); result.put("firstLightCount", firstLight ? 1 : 0);
        result.put("presentingElapsed", presentElapsed); result.put("presentationSeconds", presentSeconds);
        result.put("presentationPower", power); result.put("firingElapsed", firingElapsed);
        result.put("recoveryElapsed", recoveryElapsed); result.put("recoverySeconds", RECOVER_SECONDS);
        result.put("readyForBeam", readyForBeam()); result.put("recovered", recovered);
        result.put("interrupted", interrupted); result.put("ownsRig", ownsRig());
        result.put("blockedReason", blockedReason()); result.put("nativeAim", nativeAim);
        result.put("curve", "PUBLISHED_DSK_SNIPER_V2"); result.put("standingFrame", 3);
        result.put("clockPolicy", "CALLER_LOCAL_TIME_ONCE");
        result.put("limbPolicy", "RifleFiringPose owns skeleton writes and reversible BODY-pivot orbits");
        result.put("riflePose", ownPosePresent() ? RifleFiringPose.status(ship) : Map.of("active", false));
        return Collections.unmodifiableMap(result);
    }

    @Override public void close() {
        requireOwner();
        if (closed) return;
        // A legitimate replacement changes its rig identity too; our handle's
        // close is then a no-op for that foreign rig. A mismatched raw pose swap
        // under our still-live rig cannot be cleared safely through the API.
        if (ownsRig() && (foreignPose() || foreignStanding()))
            throw new IllegalStateException("Execution cleanup refused to clear a foreign skeleton");
        try { lease.close(); }
        catch (Exception failure) { throw new IllegalStateException("Execution pose cleanup failed", failure); }
        if (ship.getCustomData().get(KEY) == this) ship.removeCustomData(KEY);
        closed = true;
    }

    private boolean ownsRig() { return ship.getCustomData().get(RIG_KEY) == rigIdentity; }
    private boolean ownPosePresent() { return poseIdentity != null && ship.getCustomData().get(RifleFiringPose.KEY) == poseIdentity; }
    private boolean foreignPose() { Object current = ship.getCustomData().get(RifleFiringPose.KEY); return current != null && current != poseIdentity; }
    private boolean foreignStanding() { Object current = ship.getCustomData().get(LEG_KEY); return current != null && current != standingIdentity; }
    private void requireNoForeignPose() {
        if (foreignPose() || foreignStanding())
            throw new IllegalStateException("Execution skeleton ownership changed");
    }
    private void requireOwnership() {
        if (!ownsRig() || ship.getCustomData().get(KEY) != this)
            throw new IllegalStateException("Execution pose lease ownership changed");
        requireNoForeignPose();
    }
    private void markInterrupted() { interrupted = true; stage = Stage.RECOVER; recovered = true; }
    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Execution pose belongs to its native battle thread");
    }
}
