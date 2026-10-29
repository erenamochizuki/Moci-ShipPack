package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Own-variant pose overlay. The salvo coordinator is the sole clock owner;
 * arm/rifle advanceAfter hooks only read and apply this state. No render callback
 * advances this clock and no original Moci callback is called a second time.
 */
public final class FullSalvoPose {
    public static final String STATE_KEY = "xi.arsenal.fullSalvoPose";
    public static final float OPEN_SECONDS = 0.5f;
    public static final float CLOSE_SECONDS = 0.25f;
    public static final float LEFT_RELATIVE_DEGREES = 30f;
    public static final float RIGHT_RELATIVE_DEGREES = -30f;
    private static final float EPSILON = 0.00001f;

    private FullSalvoPose() { }

    /** Call once per ship-local simulation step, including the closing phase. */
    public static void setActive(ShipAPI ship, boolean active, float amount) {
        if (ship == null) return;
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null && engine.isPaused()) return;
        if (!Float.isFinite(amount) || amount < 0f) {
            throw new IllegalArgumentException("Full salvo pose requires finite nonnegative ship-local time");
        }
        if (!ship.isAlive() || ship.isHulk() || ship.wasRemoved()) {
            clear(ship);
            return;
        }
        Pose state = state(ship);
        if (state == null) {
            if (!active) return;
            state = new Pose(ship);
            ship.setCustomData(STATE_KEY, state);
        }
        state.active = active;
        state.opening = clamp(state.opening + (active ? amount / OPEN_SECONDS : -amount / CLOSE_SECONDS));
        // Make the current pose available to the original shield-follow listener
        // immediately; the per-weapon advanceAfter hooks reassert it after aim.
        for (WeaponAPI weapon : ship.getAllWeapons()) state.apply(weapon, smooth(state.opening));
        if (!active && state.opening <= EPSILON) {
            // Restore the captured local pose only once; native aim owns it again
            // from the next frame. Never touch body/head/shoulder/leg animation.
            for (WeaponAPI weapon : ship.getAllWeapons()) state.apply(weapon, 0f);
            clear(ship);
        }
    }

    public static boolean isActive(ShipAPI ship) {
        Pose state = state(ship);
        return state != null && state.active;
    }

    public static float opening(ShipAPI ship) {
        Pose state = state(ship);
        return state == null ? 0f : state.opening;
    }

    /** Readiness is the applied arm pose, rather than a second charge timer. */
    public static boolean isFullyOpen(ShipAPI ship) {
        Pose state = state(ship);
        return state != null && state.active && state.opening >= 1f - EPSILON;
    }

    /**
     * Xi's native charge is its opening animation. Called before that weapon's
     * native charge step, so the actual arm angles are applied before a first
     * projectile can be emitted. Absolute progress cannot advance a clock twice.
     */
    static void followNativeOpening(ShipAPI ship, float progress) {
        if (!Float.isFinite(progress) || progress < 0f)
            throw new IllegalArgumentException("Native opening progress must be finite and nonnegative");
        setActive(ship, true, 0f);
        Pose state = state(ship);
        if (state == null) return;
        state.opening = Math.max(state.opening, clamp(progress));
        for (WeaponAPI weapon : ship.getAllWeapons()) state.apply(weapon, smooth(state.opening));
    }

    /** The rifle remains blocked until the hands have returned to their normal pose. */
    public static boolean isBlockingRifle(ShipAPI ship) {
        Pose state = state(ship);
        return state != null && (state.active || state.opening > EPSILON);
    }

    /** Before the native trigger, without changing cooldown, ammo or weapon groups. */
    public static void beforeWeaponUpdate(WeaponAPI weapon) {
        if (weapon != null && isRightSource(weapon) && isBlockingRifle(weapon.getShip())) {
            weapon.setForceNoFireOneFrame(true);
        }
    }

    /** After that weapon's native aiming; this hook never owns a clock. */
    public static void applyWeaponPose(WeaponAPI weapon) {
        if (weapon == null) return;
        Pose state = state(weapon.getShip());
        if (state != null) state.apply(weapon, smooth(state.opening));
    }

    /** Remove only the state owned by this overlay, for death/uninstall cleanup. */
    public static void clear(ShipAPI ship) {
        if (ship != null && ship.getCustomData().get(STATE_KEY) instanceof Pose) {
            ship.removeCustomData(STATE_KEY);
        }
    }

    private static Pose state(ShipAPI ship) {
        if (ship == null) return null;
        Object found = ship.getCustomData().get(STATE_KEY);
        return found instanceof Pose ? (Pose) found : null;
    }

    private static boolean isRightSource(WeaponAPI weapon) {
        return weapon.getSlot() != null && "ARM_R_SOURCE".equals(weapon.getSlot().getId());
    }

    private static String armKey(WeaponAPI weapon) {
        if (weapon.getSlot() == null) return null;
        String slot = weapon.getSlot().getId();
        return "ARM_L".equals(slot) || "ARM_R".equals(slot) || "ARM_R_SOURCE".equals(slot) ? slot : null;
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static float smooth(float value) { return value * value * (3f - 2f * value); }
    private static float normalize(float degrees) {
        float value = degrees % 360f;
        return value < 0f ? value + 360f : value;
    }
    private static float shortest(float degrees) {
        float value = normalize(degrees);
        return value > 180f ? value - 360f : value;
    }

    private static final class Pose {
        private final ShipAPI ship;
        private final Map<String, Float> baseline = new LinkedHashMap<>();
        private float opening;
        private boolean active;

        private Pose(ShipAPI ship) {
            this.ship = ship;
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                String key = armKey(weapon);
                if (key != null) baseline.put(key, shortest(weapon.getCurrAngle() - ship.getFacing()));
            }
            // Right hand and carried rifle are one limb, even if the original
            // noHide callback had not run yet when the first charge began.
            Float sourceAngle = baseline.get("ARM_R_SOURCE");
            if (sourceAngle != null) baseline.put("ARM_R", sourceAngle);
        }

        private void apply(WeaponAPI weapon, float blend) {
            String key = armKey(weapon);
            Float from = baseline.get(key);
            if (from == null) return;
            float target = "ARM_L".equals(key) ? LEFT_RELATIVE_DEGREES : RIGHT_RELATIVE_DEGREES;
            float relative = from + shortest(target - from) * blend;
            weapon.setCurrAngle(normalize(ship.getFacing() + relative));
            if (isRightSource(weapon) && (active || opening > EPSILON)) {
                weapon.setForceNoFireOneFrame(true);
            }
        }
    }
}
