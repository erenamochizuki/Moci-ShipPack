package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import java.awt.Color;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Native three-shot telemetry and colour only; no additional projectile or damage is generated. */
public final class ZetaRifleBurstFx implements OnFireEffectPlugin,OnHitEffectPlugin {
    public static final String KEY="xi.arsenal.zeta.rifle.nativeShots";
    @Override public void onFire(DamagingProjectileAPI projectile,WeaponAPI weapon,CombatEngineAPI engine){
        if(!ZetaRifleModes.BURST.equals(weapon.getId()))throw new IllegalArgumentException("Wrong Zeta burst rifle");
        Object old=weapon.getShip().getCustomData().get(KEY);List<Map<String,Object>> rows=new ArrayList<>();
        if(old instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> map){Map<String,Object> row=new LinkedHashMap<>();map.forEach((k,v)->row.put(String.valueOf(k),v));rows.add(row);}
        if(rows.size()>=128)rows.remove(0);rows.add(Map.of("engineTime",engine.getTotalElapsedTime(false),"slot",weapon.getSlot().getId(),"damage",projectile.getDamage().getDamage(),"spawn",List.of(projectile.getSpawnLocation().x,projectile.getSpawnLocation().y)));weapon.getShip().setCustomData(KEY,List.copyOf(rows));
        ZetaDefensiveMechanisms.opticalShotFired(projectile,weapon);
        Vector2f at=projectile.getSpawnLocation(),v=weapon.getShip().getVelocity();engine.addHitParticle(at,v,54,1,.1f,new Color(255,230,255,240));engine.addSmoothParticle(at,v,82,.7f,.16f,new Color(255,80,185,180));
    }
    @Override public void onHit(DamagingProjectileAPI projectile,CombatEntityAPI target,Vector2f point,boolean shieldHit,ApplyDamageResultAPI result,CombatEngineAPI engine){
        if(Boolean.TRUE.equals(projectile.getCustomData().get(KEY+".hit")))return;projectile.setCustomData(KEY+".hit",true);Vector2f inherited=target==null?new Vector2f():target.getVelocity();
        engine.addHitParticle(point,inherited,90,1,.16f,new Color(255,245,255,235));engine.addSmoothParticle(point,inherited,125,.65f,.25f,new Color(255,65,180,175));
        for(int i=0;i<8;i++){double a=Math.random()*Math.PI*2;float speed=40+(float)Math.random()*100;engine.addSmoothParticle(point,new Vector2f(inherited.x+(float)Math.cos(a)*speed,inherited.y+(float)Math.sin(a)*speed),5+(float)Math.random()*4,.8f,.2f+(float)Math.random()*.2f,new Color(235,135,255,220));}
    }
}
