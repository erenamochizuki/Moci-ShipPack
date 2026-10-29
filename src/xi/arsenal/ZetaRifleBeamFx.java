package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import java.awt.Color;
import java.util.UUID;
import org.lwjgl.util.vector.Vector2f;

/** Real continuous native ray damage multiplier; normal and biosensor width writers have separate ownership. */
public final class ZetaRifleBeamFx implements BeamEffectPlugin {
    private final String damageId=ZetaRifleModes.KEY+".nativeChargeDamage."+UUID.randomUUID();private DamageAPI previous;private float carry;
    @Override public void advance(float amount,CombatEngineAPI engine,BeamAPI beam){
        if(beam==null||beam.getWeapon()==null)return;WeaponAPI weapon=beam.getWeapon();DamageAPI actual=beam.getDamage();
        if(actual!=null){if(previous!=null&&previous!=actual)previous.getModifier().unmodify(damageId);actual.getModifier().modifyMult(damageId,Math.max(1,Math.min(ZetaRifleModes.MAX_DAMAGE,ZetaRifleModes.firingScale(weapon))));previous=actual;}
        ShipSystemAPI system=weapon.getShip().getSystem();float bio=system!=null&&ZetaContent.SYSTEM.equals(system.getId())?system.getEffectLevel():0;
        // During biosensor activity ZetaOverdriveStats composes its existing instance width lease.
        // In normal state there is no stats width lease, so this callback owns only this real ray.
        if(bio<=.0001f&&weapon.getSpec() instanceof BeamWeaponSpecAPI spec)beam.setWidth(spec.getWidth()*ZetaRifleModes.firingWidthMultiplier(weapon));
        if(engine==null||engine.isPaused()||!Float.isFinite(amount)||amount<=0||beam.getBrightness()<=.01f)return;
        carry=Math.min(.2f,carry+amount);if(carry<.05f)return;carry%=.05f;
        if(engine.getViewport().isNearViewport(beam.getFrom(),100))engine.addHitParticle(new Vector2f(beam.getFrom()),beam.getSource().getVelocity(),beam.getWidth()*1.25f,beam.getBrightness()*.6f,.08f,new Color(255,180,240,160));
        if(beam.getDamageTarget()!=null&&engine.getViewport().isNearViewport(beam.getTo(),100))engine.addSmoothParticle(new Vector2f(beam.getTo()),new Vector2f(),beam.getWidth()*1.6f,beam.getBrightness()*.65f,.15f,new Color(255,75,200,170));
    }
}
