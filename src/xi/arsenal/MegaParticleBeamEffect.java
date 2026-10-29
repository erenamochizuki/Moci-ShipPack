package xi.arsenal;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import java.awt.Color;
import org.lwjgl.util.vector.Vector2f;

/**
 * Sustained native beam visuals for this pack's mega-particle cannon.
 * Unicorn beam textures, colours and scrolling remain in the weapon spec.
 * The native beam tracker owns the full charge, sustained irradiation and fade;
 * this effect never deletes a beam, truncates its lifetime or applies damage.
 */
public final class MegaParticleBeamEffect implements BeamEffectPlugin {
    private static final Color PATH_COLOUR = new Color(120, 55, 118, 255);
    private static final Color HIT_COLOUR = new Color(165, 100, 255, 235);
    private static final int MAX_PATH_PARTICLES_PER_FRAME = 12;
    private static final float MAX_PATH_PARTICLES_PER_SECOND = 600f;
    private float nativeBaseWidth = -1f;
    private float particleCarry;
    private boolean nativeHitSeen;

    @Override public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine == null || engine.isPaused() || amount <= 0f || !Float.isFinite(amount)) return;
        if (beam.getWeapon() == null) return;
        ShipAPI ship = beam.getSource();
        if (ship == null || !ship.isAlive() || ship.isHulk()) return;
        if (nativeBaseWidth <= 0f) {
            if (beam.getWidth() <= 0f) return;
            nativeBaseWidth = beam.getWidth();
        }

        // Capture native initial width once. Never repeatedly multiply the last
        // rendered width, and never drive width toward zero during irradiation.
        beam.setWidth(nativeBaseWidth);
        if (beam.getDamage() != null) beam.getDamage().setForceHardFlux(true);

        float dx = beam.getTo().x - beam.getFrom().x;
        float dy = beam.getTo().y - beam.getFrom().y;
        float length = (float) Math.hypot(dx, dy);
        boolean active = beam.getBrightness() > 0.01f && length > 1f;
        if (!active) { particleCarry = 0f; nativeHitSeen = false; return; }

        if (!nativeHitSeen && beam.getDamageTarget() != null && beam.didDamageThisFrame()) {
            nativeHitSeen = true;
            engine.addHitParticle(new Vector2f(beam.getTo()), new Vector2f(), 150f,
                    1f, 0.25f, HIT_COLOUR);
        }
        if (!engine.getViewport().isNearViewport(beam.getFrom(), 400f)
                && !engine.getViewport().isNearViewport(beam.getTo(), 400f)) return;

        // Same purple/red Unicorn path-particle palette, sizes, inertia and drift.
        // A bounded rate keeps a 2.5-second beam safe for multiple simultaneous Xi.
        float rate = Math.min(MAX_PATH_PARTICLES_PER_SECOND,
                nativeBaseWidth * 0.5f * length * 0.05f * 0.7f);
        particleCarry = Math.min(MAX_PATH_PARTICLES_PER_FRAME, particleCarry + amount * rate);
        int count = Math.min(MAX_PATH_PARTICLES_PER_FRAME, (int) particleCarry);
        particleCarry -= count;
        for (int i = 0; i < count; i++) {
            float along = random(0f, 1f);
            double circle = Math.random() * Math.PI * 2;
            float radius = (float) Math.sqrt(Math.random()) * nativeBaseWidth * 0.5f;
            Vector2f point = new Vector2f(beam.getFrom().x + dx * along + (float) Math.cos(circle) * radius,
                    beam.getFrom().y + dy * along + (float) Math.sin(circle) * radius);
            if (!engine.getViewport().isNearViewport(point, 30f)) continue;
            double driftAngle = Math.random() * Math.PI * 2;
            float drift = (float) Math.sqrt(Math.random()) * 50f;
            Vector2f velocity = new Vector2f(ship.getVelocity().x * 0.5f + (float) Math.cos(driftAngle) * drift,
                    ship.getVelocity().y * 0.5f + (float) Math.sin(driftAngle) * drift);
            engine.addSmoothParticle(point, velocity, random(5f, 10f),
                    Math.min(1f, beam.getBrightness()), random(0.4f, 0.9f), PATH_COLOUR);
        }
    }

    private static float random(float min, float max) { return min + (float) Math.random() * (max - min); }
}
