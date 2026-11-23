package data.shipsystems.scripts;

/** Combat seconds; the gate plane is at -(tail + GAP) in the locked ship's frame. */
final class Moci_NebulaGateMotion {
    static final float SMOKE = .22f, LIGHTNING = .94f, OPEN = 1.42f, EXIT = 1.96f;
    static final float HANDOFF = 4.16f, CLOSED = 4.76f, END = 5.65f, GAP = 3f;
    static final float FLASH_DELAY = .12f, FOLD_RISE = .10f, FOLD_RELEASE = .14f, FOLD_END = .28f;

    static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    static float fold(float battleTime) {
        return ease(battleTime/FOLD_RISE) * (1f-ease((battleTime-FOLD_RELEASE)/(FOLD_END-FOLD_RELEASE)));
    }
    static float flashAge(float battleTime,float openingRate) { return (battleTime-FLASH_DELAY)*openingRate; }
    static float flash(float sinceFlash) { return ease(sinceFlash / .085f) * (1f - ease((sinceFlash - .14f) / .46f)); }
    static float aperture(float t) {
        return ease((t - OPEN) / (EXIT - OPEN)) * (1f - ease((t - HANDOFF) / (CLOSED - HANDOFF)));
    }
    static float offset(float t, float nose, float tail, float radius) {
        // The leading tip starts behind the rear tangent, not behind the ellipse centre.
        return -(nose + tail + .295f * radius + 2f * GAP) * (1f - ease((t - EXIT) / (HANDOFF - EXIT)));
    }
}
