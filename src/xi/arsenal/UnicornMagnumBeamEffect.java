package xi.arsenal;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import java.util.UUID;

/** The real native charge beam dispatches its complete original Magnum effect once.
 * This preserves force-hard-flux,spawn width pulse,path particles and actual-hit distortion.
 * Damage belongs to the actual native ray,not the weapon's independently cached DamageAPI.
 * Its modifier is ray-local and is discarded with that native beam;no ship/spec/global stat is changed.
 * It is never called on a pulse MovingRay and is not advanced by the mode owner.
 */
public class UnicornMagnumBeamEffect extends data.scripts.weapons.Moci_Magnum_BeamEffect {
    private final String damageModifier=UnicornMagnumModes.KEY+".nativeBeamDamage."+UUID.randomUUID();
    private DamageAPI previousDamage;
    @Override public void advance(float amount,CombatEngineAPI engine,BeamAPI beam){
        if(beam!=null&&beam.getWeapon()!=null&&UnicornMagnumContent.CHARGE.equals(beam.getWeapon().getId())&&beam.getDamage()!=null){
            DamageAPI actual=beam.getDamage();
            if(previousDamage!=null&&previousDamage!=actual)previousDamage.getModifier().unmodify(damageModifier);
            float scale=UnicornMagnumModes.firingScale(beam.getWeapon());
            scale=Float.isFinite(scale)?Math.max(1,Math.min(UnicornMagnumModes.MAX_DAMAGE_SCALE,scale)):1;
            actual.getModifier().modifyMult(damageModifier,scale);previousDamage=actual;
        }
        advanceOriginal(amount,engine,beam); // exactly one original callback,including hit/EMP/particle logic
    }
    /** Tests may substitute only the GL/original-art callback;production always delegates once. */
    protected void advanceOriginal(float amount,CombatEngineAPI engine,BeamAPI beam){super.advance(amount,engine,beam);}
}
