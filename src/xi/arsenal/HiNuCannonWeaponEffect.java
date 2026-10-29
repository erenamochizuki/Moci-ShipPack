package xi.arsenal;
import com.fs.starfarer.api.combat.*;
/** Engine-owned callback only. The controller's sole clock lives in PsychoframeEnhancement. */
public final class HiNuCannonWeaponEffect implements EveryFrameWeaponEffectPluginWithAdvanceAfter {
    @Override public void advance(float dt,CombatEngineAPI engine,WeaponAPI weapon){HiNuHandCannon.beforeCannon(weapon);}
    @Override public void advanceAfter(float dt,CombatEngineAPI engine,WeaponAPI weapon){HiNuHandCannon.afterCannon(weapon);}
}
