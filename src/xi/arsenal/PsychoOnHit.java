package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import java.awt.Color;
import org.lwjgl.util.vector.Vector2f;

/** Native collision already applies the warhead damage; this callback is observation and light only. */
public final class PsychoOnHit implements OnHitEffectPlugin {
    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point,
                      boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        if (Boolean.TRUE.equals(projectile.getCustomData().get("xi.arsenal.psycho.hitObserved"))) return;
        projectile.setCustomData("xi.arsenal.psycho.hitObserved", Boolean.TRUE);
        PsychoTelemetry.increment(engine, "nativeHits");
        if (shieldHit) PsychoTelemetry.increment(engine, "nativeShieldHits");
        engine.addHitParticle(point, target == null ? new Vector2f() : target.getVelocity(),
                80f, 1f, 0.22f, new Color(140, 230, 255, 230));
    }
}
