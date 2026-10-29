package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPluginWithAdvanceAfter;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Sole native callback for the owned ordinary Hi-Nu arm. The engine invokes the
 * inherited Moci advance once, preserving its source linkage and recoil. Our
 * after hook selects only this new arm's pose after native weapon operations.
 */
public final class HiNuRightArmEffect
        extends data.scripts.weapons.Moci_MechProjDecoWeaponEffect_noHide
        implements EveryFrameWeaponEffectPluginWithAdvanceAfter {
    @Override
    public void advanceAfter(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || weapon == null || weapon.getShip() == null) return;
        HiNuHandCannon owner = HiNuHandCannon.instance(weapon.getShip());
        if (owner != null) owner.applyArmPose();
    }
}
