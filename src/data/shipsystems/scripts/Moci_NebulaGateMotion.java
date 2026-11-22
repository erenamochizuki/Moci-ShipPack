package data.shipsystems.scripts;

/** Combat seconds; the gate plane is at -(tail + GAP) in the locked ship's frame. */
final class Moci_NebulaGateMotion {
    static final float SMOKE = .22f, LIGHTNING = .94f, OPEN = 1.42f, EXIT = 1.96f;
    static final float HANDOFF = 4.16f, CLOSED = 4.76f, END = 5.65f, GAP = 3f;

    static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    static float flash(float t) { return ease(t / .085f) * (1f - ease((t - .14f) / .46f)); }
    static float aperture(float t) {
        return ease((t - OPEN) / (EXIT - OPEN)) * (1f - ease((t - HANDOFF) / (CLOSED - HANDOFF)));
    }
    static float offset(float t, float nose, float tail, float radius) {
        // The leading tip starts behind the rear tangent, not behind the ellipse centre.
        return -(nose + tail + .295f * radius + 2f * GAP) * (1f - ease((t - EXIT) / (HANDOFF - EXIT)));
    }
}
