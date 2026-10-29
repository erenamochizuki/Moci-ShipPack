package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.awt.Color;

/** Real native first-shot callback plus idempotent setup for this pack's whole-ship salvo. */
public final class PsychoOnFire implements OnFireEffectPlugin {
    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (!(weapon.getEffectPlugin() instanceof PsychoWeaponClock clock))
            throw new IllegalStateException("Psycho missile weapon is missing its owned salvo controller");
        if (!record(projectile, weapon, engine, false)) return;
        clock.startSalvo(engine, weapon);
    }

    static boolean record(DamagingProjectileAPI projectile, WeaponAPI weapon,
                          CombatEngineAPI engine, boolean scheduled) {
        if (!(projectile instanceof MissileAPI missile)) return false;
        if (Boolean.TRUE.equals(projectile.getCustomData().get("xi.arsenal.psycho.fireObserved"))) return false;
        projectile.setCustomData("xi.arsenal.psycho.fireObserved", Boolean.TRUE);
        missile.setEccmChanceOverride(1f);
        missile.setEmpResistance(999);
        missile.setArmingTime(0.05f);
        missile.setArmedWhileFizzling(false);
        PsychoTelemetry.emitted(engine, weapon, scheduled);
        engine.addHitParticle(projectile.getLocation(), weapon.getShip().getVelocity(),
                25f, 0.8f, 0.12f, new Color(135, 240, 255, 190));
        return true;
    }
}
