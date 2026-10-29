package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import java.awt.Color;
import java.util.Map;
import org.lwjgl.util.vector.Vector2f;

/** Native collision already applied damage; this callback adds only Unicorn-coloured impact light and sparks. */
public final class BeamRifleOnHit implements OnHitEffectPlugin {
    @Override public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point,
            boolean shieldHit, ApplyDamageResultAPI result, CombatEngineAPI engine) {
        if (Boolean.TRUE.equals(projectile.getCustomData().get("xi.arsenal.rifle.hitVisual"))) return;
        projectile.setCustomData("xi.arsenal.rifle.hitVisual", Boolean.TRUE);
        BeamRiflePalette palette = BeamRiflePalette.forProjectile(projectile.getProjectileSpecId());
        BeamRifleVisuals.increment(engine, "nativeOnHitVisuals");
        if (shieldHit) BeamRifleVisuals.increment(engine, "nativeShieldHits");
        BeamRifleVisuals.event(engine, "hits", Map.of("engineTime", engine.getTotalElapsedTime(false), "shieldHit", shieldHit,
                "x", point.x, "y", point.y, "projectileSpecId", projectile.getProjectileSpecId()));
        Vector2f inherited = target == null ? new Vector2f() : new Vector2f(target.getVelocity());
        engine.addHitParticle(point, inherited, 100f, 1f, 0.2f, new Color(255, 255, 255, 230));
        engine.addSmoothParticle(point, inherited, 150f, 0.8f, 0.3f, BeamRiflePalette.alpha(palette.primary, 160));
        for (int i = 0; i < 12; i++) {
            double angle = Math.random() * Math.PI * 2;
            float speed = 40f + (float) Math.random() * 150f;
            Vector2f velocity = new Vector2f(inherited.x + (float) Math.cos(angle) * speed,
                    inherited.y + (float) Math.sin(angle) * speed);
            engine.addSmoothParticle(point, velocity, 5f + (float) Math.random() * 5f,
                    1f, 0.4f + (float) Math.random() * 0.3f,
                    BeamRiflePalette.alpha(i % 3 == 0 ? palette.primary : palette.spark, 230));
        }
    }
}
