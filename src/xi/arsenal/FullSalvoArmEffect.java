package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPluginWithAdvanceAfter;
import com.fs.starfarer.api.combat.WeaponAPI;
import data.scripts.weapons.Moci_MechProjDecoWeaponEffect_noHide;

/** Original noHide is inherited once; only own-variant arm angle gets an overlay. */
public final class FullSalvoArmEffect extends Moci_MechProjDecoWeaponEffect_noHide
        implements EveryFrameWeaponEffectPluginWithAdvanceAfter {
    @Override public void advanceAfter(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine != null && !engine.isPaused()) {FullSalvoPose.applyWeaponPose(weapon);RifleFiringPose.apply(weapon);AdaptiveShieldGuard.applyAfter(weapon);RifleBracedPose.apply(weapon);BeamRifleModes.afterWeapon(weapon);}
    }
}
