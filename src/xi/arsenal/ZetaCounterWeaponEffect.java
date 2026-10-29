package xi.arsenal;
import com.fs.starfarer.api.combat.*;
/** New owned launcher callback. No original Moci weapon callback is duplicated. */
public final class ZetaCounterWeaponEffect implements EveryFrameWeaponEffectPlugin {
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon){ZetaDefensiveMechanisms.guardCounterWeapon(weapon);}
}
