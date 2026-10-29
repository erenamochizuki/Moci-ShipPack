package xi.arsenal;

import com.fs.starfarer.api.combat.*;

/** Z的BODY原定义没有每帧脚本；只接动作末端，不推进或重放任何原Mod逻辑。 */
public final class ZetaRifleBodyEffect implements EveryFrameWeaponEffectPluginWithAdvanceAfter {
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon) { }
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon) {
        if(engine!=null&&!engine.isPaused()){RifleFiringPose.apply(weapon);ZetaTetherPose.apply(weapon);ZetaBeamDeflection.applyPose(weapon);}
    }
}
