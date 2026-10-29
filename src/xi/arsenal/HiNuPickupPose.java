package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/**
 * Late, angle-only pickup pose on the ordinary Hi-Nu's real bones.
 *
 * The normal held gun supplies a registered palm point at construction. A
 * shoulder rotation can put that point on a circle, never change its radius.
 * Reach solves the grip bearing and exposes the remaining position error to
 * the caller; it never translates or stretches an arm to fake contact.
 * The caller owns the free gun and its continuous contact/return handoff.
 *
 * apply samples the caller's clock, including ARM_R.advanceAfter. It neither
 * advances animation time nor invokes another native bone callback.
 */
public final class HiNuPickupPose implements AutoCloseable {
    public static final String KEY = "xi.arsenal.hinu.pickupPose";
    public static final float REACH_END = .45f, RETURN_START = .65f;
    public static final float TOUCH_FRACTION = .6f;
    public static final float CONTACT_TOLERANCE = 10f;
    private static final float DEFAULT_SECONDS = 1.25f;
    private static final String BITMAP_KEY = KEY + ".bitmapLease";
    private final Thread ownerThread = Thread.currentThread();
    private final ShipAPI ship;
    private final WeaponAPI arm, body;
    private final AngleLease armAngle, bodyAngle, shoulderAngle, heldAngle;
    private final Vector2f palmLocal, targetWorld = new Vector2f();
    private final float radius, seconds;
    private final boolean originalBitmapHidden;
    private boolean bitmapOwned, closed, targetSampled, returning;
    private float fraction, blend, bodyDegrees, armDegrees;
    private float reachRelative, returnRelative, targetError = Float.POSITIVE_INFINITY;
    private float radialError = Float.POSITIVE_INFINITY, angularVelocity;
    private float lastVelocityFraction = Float.NaN, lastVelocityAngle;
    private String beat = "REACH";

    public HiNuPickupPose(ShipAPI ship, WeaponAPI arm) { this(ship, arm, DEFAULT_SECONDS); }

    /** Duration is diagnostic only; the caller remains the sole owner of time. */
    public HiNuPickupPose(ShipAPI ship, WeaponAPI arm, float durationSeconds) {
        this.ship = Objects.requireNonNull(ship, "Pickup ship");
        this.arm = Objects.requireNonNull(arm, "Pickup right arm");
        if (arm.getShip() != ship || arm.getSlot() == null || !"ARM_R".equals(arm.getSlot().getId()))
            throw new IllegalArgumentException("Pickup pose requires this ship's actual ARM_R");
        if (!Float.isFinite(durationSeconds) || durationSeconds <= 0)
            throw new IllegalArgumentException("Invalid pickup duration");
        seconds = durationSeconds;
        body = Objects.requireNonNull(find("BODY"), "Pickup pose requires real BODY");
        WeaponAPI held = Objects.requireNonNull(find("ARM_R_SOURCE"), "Pickup pose requires its original held weapon");
        if (ship.getCustomData().containsKey(KEY)) throw new IllegalStateException("Pickup pose already owned");
        armAngle = new AngleLease(arm);
        bodyAngle = new AngleLease(body);
        WeaponAPI shoulder = find("SHOULDER_R");
        shoulderAngle = shoulder == null ? null : new AngleLease(shoulder);
        heldAngle = new AngleLease(held);
        // Capture normal carry registration once. Recomputing this during the
        // pose would make the hand target follow the free gun itself.
        palmLocal = new Vector2f(HiNuGripAnchors.palmLocal(arm, held));
        radius = palmLocal.length();
        if (!Float.isFinite(radius) || radius < .001f)
            throw new IllegalStateException("Pickup requires a finite, nonzero registered palm radius");
        reachRelative = returnRelative = armDegrees = armAngle.baseRelative;
        originalBitmapHidden = ship.isDoNotRenderSprite();
        ship.setCustomData(KEY, this);
    }

    private WeaponAPI find(String slot) {
        for (WeaponAPI weapon : ship.getAllWeapons())
            if (weapon.getSlot() != null && slot.equals(weapon.getSlot().getId())) return weapon;
        return null;
    }

    public void apply(float fraction, Vector2f gripWorld) {
        requireThread();
        if (closed) return;
        if (!Float.isFinite(fraction) || gripWorld == null || !Float.isFinite(gripWorld.x) || !Float.isFinite(gripWorld.y))
            throw new IllegalArgumentException("Invalid pickup pose sample");
        if (ship.getCustomData().get(KEY) != this) throw new IllegalStateException("Pickup pose ownership changed");
        this.fraction = clamp01(fraction);
        targetWorld.set(gripWorld);
        targetSampled = true;
        float facing = ship.getFacing();
        Vector2f origin = armOrigin();
        float dx = gripWorld.x - origin.x, dy = gripWorld.y - origin.y;
        float distance = (float)Math.hypot(dx, dy);
        radialError = Math.abs(distance - radius);
        // Shortest equivalent rotation bounds each turn to half a circle;
        // no fixed ship-relative quadrant can point the palm past the grip.
        if (distance > .001f && this.fraction <= RETURN_START) {
            float angle = HiNuGripAnchors.solveArmAngle(origin, palmLocal, gripWorld);
            reachRelative += shortest(angle - facing - reachRelative);
        }
        if (this.fraction < RETURN_START) returning = false;
        else if (!returning) {
            // Once the gun is hand-constrained its grip is an output, not a
            // new aiming input. Freeze the contact angle for a stable return.
            returnRelative = reachRelative;
            returning = true;
        }

        if (this.fraction < REACH_END) {
            beat = "REACH";
            blend = smoother(this.fraction / REACH_END);
            // reachRelative is unwrapped from the entry angle; retain that
            // branch as a drifting grip crosses +/-180 instead of flipping.
            armDegrees = armAngle.baseRelative + (reachRelative - armAngle.baseRelative) * blend;
        } else if (this.fraction <= RETURN_START) {
            beat = "CONTACT";
            blend = 1;
            armDegrees = reachRelative;
        } else {
            beat = "RETURN";
            float home = smoother((this.fraction - RETURN_START) / (1 - RETURN_START));
            blend = 1 - home;
            armDegrees = returnRelative + shortest(armAngle.baseRelative - returnRelative) * home;
        }
        claimBitmap();
        bodyDegrees = RifleFiringPose.BODY_DEGREES * blend;
        float torso = facing + bodyAngle.baseRelative + bodyDegrees;
        float rightArm = facing + armDegrees;
        bodyAngle.write(torso);
        armAngle.write(rightArm);
        // Retain the normal gun/arm angular registration, including specs
        // whose initial source angle differs from the empty arm angle.
        heldAngle.write(rightArm + shortest(heldAngle.baseRelative - armAngle.baseRelative));
        if (shoulderAngle != null) {
            float desired = torso + shortest(rightArm - torso) * .70f;
            float rest = facing + shoulderAngle.baseRelative;
            shoulderAngle.write(rest + shortest(desired - rest) * blend);
        }
        targetError = Vector2f.sub(handWorld(), gripWorld, null).length();
        sampleVelocity();
    }

    /** Current registered grasp point; returns an independent vector. */
    public Vector2f handWorld() {
        requireThread();
        return HiNuGripAnchors.handWorld(arm, palmLocal);
    }

    /** Existing native decorative offset is read, never written. */
    public Vector2f armOrigin() {
        requireThread();
        return HiNuGripAnchors.renderOrigin(arm);
    }

    public float targetError() { requireThread(); return targetError; }
    public float contactError() { return targetError(); }
    public float radialError() { requireThread(); return radialError; }
    public float requiredApproachDistance() { return radialError(); }
    public float reachRadius() { requireThread(); return radius; }
    public float carryAngle() { requireThread(); return ship.getFacing() + heldAngle.baseRelative; }
    public boolean reachReady() {
        requireThread();
        return !closed && targetSampled && fraction >= REACH_END && fraction <= RETURN_START
                && targetError <= CONTACT_TOLERANCE;
    }

    public Map<String, Object> status() {
        requireThread();
        Vector2f hand = handWorld();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", !closed); result.put("fraction", fraction); result.put("blend", blend);
        result.put("beat", beat); result.put("touchFraction", TOUCH_FRACTION);
        result.put("bodyLeftDegrees", bodyDegrees); result.put("rightArmRelativeDegrees", armDegrees);
        result.put("rightArmAngularVelocity", angularVelocity); result.put("velocityUnits", "degrees/second; caller clock");
        result.put("palmLocalForward", palmLocal.x); result.put("palmLocalLeft", palmLocal.y);
        result.put("handX", hand.x); result.put("handY", hand.y);
        result.put("gripX", targetWorld.x); result.put("gripY", targetWorld.y);
        result.put("targetError", targetError); result.put("radialError", radialError);
        result.put("reachRadius", radius); result.put("reachReady", reachReady());
        result.put("motionCurve", "QUINTIC_SMOOTHERSTEP: zero endpoint velocity and acceleration");
        result.put("bodyFrameCount", body.getAnimation() == null ? 0 : body.getAnimation().getNumFrames());
        result.put("bodyFramePolicy", "UNCHANGED: ordinary Hi-Nu BODY is a single authored bitmap");
        result.put("limbPolicy", "JOINT_ANGLES_ONLY: no location, slot, size, center or render-offset writes");
        result.put("bitmapLease", bitmapOwned); result.put("closed", closed);
        return Collections.unmodifiableMap(result);
    }

    private void claimBitmap() {
        if (blend <= 0 || bitmapOwned) return;
        if (ship.getCustomData().containsKey(BITMAP_KEY)) throw new IllegalStateException("Pickup bitmap already owned");
        ship.setCustomData(BITMAP_KEY, this);
        ship.setDoNotRenderSprite(true);
        bitmapOwned = true;
    }

    private void sampleVelocity() {
        // A late reapply after native callbacks must not double the clock.
        if (!Float.isNaN(lastVelocityFraction) && fraction > lastVelocityFraction + .000001f)
            angularVelocity = shortest(armDegrees - lastVelocityAngle) / ((fraction - lastVelocityFraction) * seconds);
        if (Float.isNaN(lastVelocityFraction) || fraction != lastVelocityFraction) {
            lastVelocityFraction = fraction;
            lastVelocityAngle = armDegrees;
        }
        if (fraction <= 0 || fraction >= 1) angularVelocity = 0;
    }

    @Override public void close() {
        requireThread();
        if (closed) return;
        closed = true;
        if (ship.getCustomData().get(KEY) == this) {
            armAngle.restore(); bodyAngle.restore();
            if (shoulderAngle != null) shoulderAngle.restore();
            heldAngle.restore();
            ship.removeCustomData(KEY);
        }
        if (bitmapOwned && ship.getCustomData().get(BITMAP_KEY) == this) {
            if (ship.isDoNotRenderSprite()) ship.setDoNotRenderSprite(originalBitmapHidden);
            ship.removeCustomData(BITMAP_KEY);
        }
        bitmapOwned = false; blend = 0; bodyDegrees = 0; angularVelocity = 0;
    }

    private void requireThread() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Pickup pose belongs to its battle thread");
    }
    static float smoother(float value) {
        float x = clamp01(value);
        return x * x * x * (x * (x * 6 - 15) + 10);
    }
    private static float clamp01(float value) { return Math.max(0, Math.min(1, value)); }
    private static float shortest(float degrees) { return ((degrees % 360) + 540) % 360 - 180; }
    private final class AngleLease {
        final WeaponAPI weapon;
        final float baseRelative;
        float written;
        boolean changed;
        AngleLease(WeaponAPI weapon) { this.weapon = weapon; baseRelative = shortest(weapon.getCurrAngle() - ship.getFacing()); }
        void write(float value) { weapon.setCurrAngle(value); written = weapon.getCurrAngle(); changed = true; }
        void restore() {
            if (changed && Math.abs(shortest(weapon.getCurrAngle() - written)) < .01f)
                weapon.setCurrAngle(ship.getFacing() + baseRelative);
            changed = false;
        }
    }
}
