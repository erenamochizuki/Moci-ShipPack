package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import data.scripts.weapons.Moci_MechBodyController_MSZ006;

/** 原Z腿部行走/变形控制器每帧仅运行一次，动作在末端租用真实站姿帧4。 */
public final class ZetaRifleLegEffect extends Moci_MechBodyController_MSZ006 implements EveryFrameWeaponEffectPluginWithAdvanceAfter {
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon) {
        super.advance(amount,engine,weapon);
    }
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon) {
        if(engine!=null&&!engine.isPaused()){RifleFiringPose.apply(weapon);ZetaTetherPose.apply(weapon);ZetaBeamDeflection.applyPose(weapon);}
    }
}
