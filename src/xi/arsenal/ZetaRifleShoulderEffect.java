package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import data.scripts.weapons.Moci_MechShoulderDecoWeaponEffect;

/** 保留原肩部source/arm/wing同步；仅在原生advanceAfter边界应用同一实例动作。 */
public final class ZetaRifleShoulderEffect extends Moci_MechShoulderDecoWeaponEffect implements EveryFrameWeaponEffectPluginWithAdvanceAfter {
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon) {
        super.advance(amount,engine,weapon);
    }
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon) {
        if(engine!=null&&!engine.isPaused()){RifleFiringPose.apply(weapon);ZetaTetherPose.apply(weapon);ZetaBeamDeflection.applyPose(weapon);}
    }
}
