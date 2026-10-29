package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.lwjgl.util.vector.Vector2f;

/** Only real native callbacks launch/attach the cable. This class never applies projectile damage twice. */
public final class ZetaTetherAttackEffect implements OnFireEffectPlugin,OnHitEffectPlugin {
    @Override public void onFire(DamagingProjectileAPI projectile,WeaponAPI weapon,CombatEngineAPI engine){ZetaTetherCombo own=ZetaTetherCombo.instance(weapon.getShip());if(ZetaTetherCombo.COMMAND.equals(weapon.getId())){if(projectile!=null)engine.removeEntity(projectile);Vector2f mouse=weapon.getShip().getMouseTarget();ZetaTetherCombo.Request result=own==null?new ZetaTetherCombo.Request(false,"NO_TETHER_ACTOR"):own.requestAim(mouse==null?null:new Vector2f(mouse),weapon.getShip().getShipTarget());ZetaTetherEntryProbe.nativeRequest(engine,weapon,result);}else if(ZetaTetherCombo.LAUNCHER.equals(weapon.getId())&&own!=null)own.onLaunch(projectile);else if(projectile!=null)engine.removeEntity(projectile);}
    @Override public void onHit(DamagingProjectileAPI projectile,CombatEntityAPI target,Vector2f point,boolean shieldHit,ApplyDamageResultAPI result,CombatEngineAPI engine){Object owner=projectile.getCustomData().get(ZetaTetherCombo.KEY+".owner");if(owner instanceof ZetaTetherCombo own)own.onHit(projectile,target,point,shieldHit);}
}
