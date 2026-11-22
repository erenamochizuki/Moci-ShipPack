package data.scripts.weapons.render;

/** Accretion art direction in world units. Independent of zoom, age and gameplay mass. */
final class Moci_BlackHoleDiskProfile {
    private Moci_BlackHoleDiskProfile() {}

    static float growth(float radius) {
        // Launch radius is 10; taper smoothly through both the collapse and no-collapse ranges.
        float excess = Math.max(0f, radius - 10f);
        return excess / (excess + 52f);
    }

    static float cloudRadius(float radius, float growth) {
        return radius * (3.4f + 3.2f * growth);
    }

    static float brightness(float growth) {
        return 0.30f + 1.02f * growth;
    }

    static float angularSpeed(float growth) {
        float remaining = 1f - growth;
        return 0.18f + 1.72f * remaining * remaining;
    }
}
