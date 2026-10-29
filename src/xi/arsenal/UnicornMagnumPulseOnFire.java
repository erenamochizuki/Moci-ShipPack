package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import java.awt.Color;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Native shot notification and original-family muzzle/hit palette,never a second damaging shot. */
public final class UnicornMagnumPulseOnFire implements OnFireEffectPlugin,OnHitEffectPlugin {
    public static final String KEY="xi.arsenal.unicorn.magnum.pulseShots";
    @Override public void onFire(DamagingProjectileAPI projectile,WeaponAPI weapon,CombatEngineAPI engine){
        if(!UnicornMagnumPulseEffect.WEAPON_ID.equals(weapon.getId()))throw new IllegalArgumentException("Exact Magnum pulse required");
        if(Boolean.TRUE.equals(projectile.getCustomData().get(KEY+".fire")))return;projectile.setCustomData(KEY+".fire",true);
        UnicornMagnumModes.nativePulseShot(weapon);UnicornMagnumPulseEffect.nativeShot(weapon);
        projectile.getDamage().setForceHardFlux(true);
        Vector2f at=projectile.getSpawnLocation(),inherited=weapon.getShip().getVelocity();
        engine.addHitParticle(at,inherited,42,1,.12f,new Color(255,255,255,220));engine.addSmoothParticle(at,inherited,90,.9f,.19f,new Color(53,208,255,180));
        engine.addNebulaSmokeParticle(at,inherited,15,2.5f,.1f,.1f,.7f,new Color(120,120,120,100));
        if(Global.getSoundPlayer()!=null)Global.getSoundPlayer().playSound("Moci_Magnum_03",1,1,at,inherited);
        List<Map<String,Object>> rows=new ArrayList<>();Object old=weapon.getShip().getCustomData().get(KEY);
        if(old instanceof List<?> list)for(Object value:list)if(value instanceof Map<?,?> map){Map<String,Object> copy=new LinkedHashMap<>();map.forEach((k,v)->copy.put(String.valueOf(k),v));rows.add(copy);}
        if(rows.size()>=128)rows.remove(0);rows.add(Map.of("engineTime",engine.getTotalElapsedTime(false),"weapon",weapon.getId(),"slot",weapon.getSlot().getId(),"nativeClass",projectile.getClass().getName(),"damage",projectile.getDamage().getDamage(),"spawn",List.of(at.x,at.y),"firePoint",List.of(weapon.getFirePoint(0).x,weapon.getFirePoint(0).y)));
        weapon.getShip().setCustomData(KEY,List.copyOf(rows));
    }
    @Override public void onHit(DamagingProjectileAPI projectile,CombatEntityAPI target,Vector2f point,boolean shieldHit,ApplyDamageResultAPI result,CombatEngineAPI engine){
        if(Boolean.TRUE.equals(projectile.getCustomData().get(KEY+".hit")))return;projectile.setCustomData(KEY+".hit",true);
        Vector2f inherited=target==null?new Vector2f():target.getVelocity();engine.addHitParticle(point,inherited,70,1,.16f,new Color(255,255,255,220));engine.addSmoothParticle(point,inherited,125,.8f,.25f,new Color(53,208,255,180));
    }
}
