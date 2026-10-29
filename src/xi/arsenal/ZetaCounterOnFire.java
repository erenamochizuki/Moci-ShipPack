package xi.arsenal;
import com.fs.starfarer.api.combat.*;
/** One native missile emission confirmation; never manually spawns a replacement projectile. */
public final class ZetaCounterOnFire implements OnFireEffectPlugin {
    @Override public void onFire(DamagingProjectileAPI projectile,WeaponAPI weapon,CombatEngineAPI engine){ZetaDefensiveMechanisms.counterShotFired(projectile,weapon);}
}
