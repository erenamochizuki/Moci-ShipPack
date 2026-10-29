package xi.arsenal;

import com.fs.starfarer.api.combat.*;

/** Guard/coordinate callback only. Projectile onFire/onHit are declared once on their projectile specification. */
public final class ZetaTetherWeaponEffect implements EveryFrameWeaponEffectPluginWithAdvanceAfter,WeaponEffectPluginWithInit {
    private WeaponAPI bound;
    @Override public void init(WeaponAPI weapon){if(bound!=null&&bound!=weapon)throw new IllegalStateException("Tether weapon callback reused");bound=weapon;ZetaRifleMount.detach(weapon);}
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon){if(bound==null)init(weapon);if(engine!=null&&!engine.isPaused()){ZetaEnhancement.ensure(engine);ZetaTetherCombo.beforeWeapon(weapon,engine);}}
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon){if(engine!=null&&!engine.isPaused())ZetaTetherCombo.beforeWeapon(weapon,engine);}
}
