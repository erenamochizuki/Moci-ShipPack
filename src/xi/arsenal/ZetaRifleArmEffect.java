package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import data.scripts.weapons.Moci_MechProjDecoWeaponEffect_noHide;

/** Exactly one original arm callback, then a pure shared release-pose/muzzle reconciliation. */
public final class ZetaRifleArmEffect extends Moci_MechProjDecoWeaponEffect_noHide implements EveryFrameWeaponEffectPluginWithAdvanceAfter {
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI arm){if(engine==null||engine.isPaused())return;ZetaRifleModes.alignInactiveSource(arm.getShip());super.advance(amount,engine,arm);}
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI arm){
        if(engine==null||engine.isPaused()||!ZetaRifleModes.msForm(arm.getShip()))return;ZetaRifleModes.alignInactiveSource(arm.getShip());
        WeaponAPI source=ZetaRifleMount.slot(arm.getShip(),ZetaRifleModes.SOURCE_SLOT);if(source!=null)arm.setCurrAngle(source.getCurrAngle());
        RifleFiringPose.apply(arm);ZetaTetherPose.apply(arm);ZetaBeamDeflection.applyPose(arm);ZetaRifleModes.alignInactiveSource(arm.getShip());
    }
}
