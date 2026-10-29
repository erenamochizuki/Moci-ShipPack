package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.awt.Color;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;

/** Unicorn-inspired muzzle glow for a real native MovingRay; never spawns an extra damaging shot. */
public final class BeamRifleOnFire implements OnFireEffectPlugin {
    @Override public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (Boolean.TRUE.equals(projectile.getCustomData().get("xi.arsenal.rifle.fireVisual"))) return;
        projectile.setCustomData("xi.arsenal.rifle.fireVisual", Boolean.TRUE);
        Vector2f muzzle = new Vector2f(projectile.getSpawnLocation());
        Vector2f inherited = new Vector2f(weapon.getShip().getVelocity());
        BeamRiflePalette palette = BeamRiflePalette.forWeapon(weapon.getId());
        BeamRifleVisuals.increment(engine, "nativeOnFire");
        Vector2f visible = BeamRifleMount.visibleMuzzleWorld(weapon);
        Vector2f nativePoint = weapon.getFirePoint(0);
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("engineTime", engine.getTotalElapsedTime(false)); shot.put("slot", weapon.getSlot().getId());
        shot.put("weaponId", weapon.getId()); shot.put("projectileSpecId", projectile.getProjectileSpecId());
        shot.put("x", muzzle.x); shot.put("y", muzzle.y); shot.put("nativeClass", projectile.getClass().getName());
        shot.put("visibleMuzzleWorld", List.of(visible.x, visible.y));
        shot.put("nativeFirePointWorld", List.of(nativePoint.x, nativePoint.y));
        shot.put("spawnToVisibleMuzzle", Vector2f.sub(muzzle, visible, null).length());
        shot.put("sourceAngle", weapon.getCurrAngle());
        shot.put("barrelCenter", List.of(BeamRifleMount.visibleGunSprite(weapon).getCenterX(), BeamRifleMount.visibleGunSprite(weapon).getCenterY()));
        shot.put("rifleColor", List.of(palette.primary.getRed(), palette.primary.getGreen(), palette.primary.getBlue(), 255));
        BeamRifleVisuals.event(engine, "shots", shot);

        // The finite glow is attached to the current visible gun mouth by the
        // mount's renderer; it must not become a world-space moving afterimage.
        BeamRifleMount.nativeShot(weapon);
        BeamRifleVisuals.increment(engine, "muzzleGlows");
        engine.addHitParticle(muzzle, inherited, 55f, 1f, 0.16f, new Color(255, 255, 255, 220));
        engine.addSmoothParticle(muzzle, inherited, 80f, 0.8f, 0.22f, BeamRiflePalette.alpha(palette.primary, 150));
        for (int i = 0; i < 6; i++) {
            double angle = Math.toRadians(weapon.getCurrAngle() + random(-70f, 70f));
            float speed = random(45f, 140f);
            Vector2f velocity = new Vector2f(inherited.x + (float) Math.cos(angle) * speed,
                    inherited.y + (float) Math.sin(angle) * speed);
            engine.addSmoothParticle(muzzle, velocity, random(5f, 10f), 1f,
                    random(0.2f, 0.4f), i % 2 == 0 ? new Color(255, 255, 255, 220) : BeamRiflePalette.alpha(palette.spark, 180));
        }
        double backwards = Math.toRadians(weapon.getCurrAngle() + 180f);
        engine.addNebulaSmokeParticle(muzzle, new Vector2f(inherited.x + (float) Math.cos(backwards) * 25f,
                inherited.y + (float) Math.sin(backwards) * 25f), 15f, 2.5f, 0.1f, 0.1f, 0.6f, new Color(120, 120, 120, 100));
    }

    private static float random(float min, float max) { return min + (float) Math.random() * (max - min); }
}
