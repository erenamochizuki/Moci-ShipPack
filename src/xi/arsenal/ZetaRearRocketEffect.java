package xi.arsenal;

import data.scripts.weapons.Moci_MechTransformController;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Real native, rear-facing WR rocket launcher. No scripted projectile, ammo refill or original callback replay. */
public final class ZetaRearRocketEffect implements EveryFrameWeaponEffectPluginWithAdvanceAfter,WeaponEffectPluginWithInit {
    public static final String WEAPON_ID="Moci_fw_zeta_rear_rocket",SLOT_ID="WR_REAR_ROCKET";
    private WeaponAPI bound;private long samples;private String reason="等待WR形态";
    @Override public void init(WeaponAPI weapon){
        if(bound!=null&&bound!=weapon)throw new IllegalStateException("Rear launcher callback shared by different weapons");
        if(!WEAPON_ID.equals(weapon.getId())||weapon.getSlot()==null||!SLOT_ID.equals(weapon.getSlot().getId()))throw new IllegalArgumentException("Exact Zeta WR rear rocket slot required");
        bound=weapon;weapon.ensureClonedSpec();for(var offsets:List.of(weapon.getSpec().getTurretFireOffsets(),weapon.getSpec().getHardpointFireOffsets(),weapon.getSpec().getHiddenFireOffsets()))for(int i=0;i<offsets.size();i++)offsets.set(i,new Vector2f(offsets.get(i)));
    }
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon){if(bound==null)init(weapon);if(engine==null||engine.isPaused())return;reconcile(weapon);}
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon){if(engine!=null&&!engine.isPaused())reconcile(weapon);}
    private void reconcile(WeaponAPI weapon){
        ShipAPI ship=weapon.getShip();WeaponAPI backpack=ship.getAllWeapons().stream().filter(w->w.getSlot()!=null&&"BACKPACK".equals(w.getSlot().getId())).findFirst().orElse(null);
        boolean ready=ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&backpack!=null&&backpack.getEffectPlugin() instanceof Moci_MechTransformController transform&&transform.waveRiderReady();
        if(!ready){weapon.setForceNoFireOneFrame(true);weapon.stopFiring();reason="只在完整WR形态使用";ship.setCustomData("xi.arsenal.zeta.rearRocket",Map.of("ready",false,"slot",SLOT_ID,"samples",samples,"ammo",weapon.getAmmo(),"reason",reason));return;}
        weapon.setCurrAngle(ship.getFacing()+180f);for(var offsets:List.of(weapon.getSpec().getTurretFireOffsets(),weapon.getSpec().getHardpointFireOffsets(),weapon.getSpec().getHiddenFireOffsets()))for(int i=0;i<offsets.size();i++){
            Vector2f world=rearMuzzle(backpack,i&1),relative=Vector2f.sub(world,weapon.getLocation(),null);double angle=Math.toRadians(-weapon.getCurrAngle());offsets.get(i).set((float)(relative.x*Math.cos(angle)-relative.y*Math.sin(angle)),(float)(relative.x*Math.sin(angle)+relative.y*Math.cos(angle)));
        }
        samples++;reason="WR后向火箭 · 原生开火/弹量";
        ship.setCustomData("xi.arsenal.zeta.rearRocket",Map.of("ready",true,"slot",SLOT_ID,"samples",samples,"angle",weapon.getCurrAngle(),"leftMuzzle",point(rearMuzzle(backpack,0)),"rightMuzzle",point(rearMuzzle(backpack,1)),"ammo",weapon.getAmmo(),"reason",reason));
    }
    /** Added launcher points on outer trailing-wing housings, separate from the existing engine nozzles. */
    public static Vector2f rearMuzzle(WeaponAPI backpack,int barrel){
        SpriteAPI sprite=backpack.getSprite();if(sprite==null)throw new IllegalStateException("Real WR backpack sprite is required");
        float u=(barrel&1)==0?28:90,v=147,width=118,height=220;
        float forward=(height-v)*sprite.getHeight()/height-sprite.getCenterY(),left=-(u*sprite.getWidth()/width-sprite.getCenterX());double angle=Math.toRadians(backpack.getCurrAngle());return new Vector2f(backpack.getLocation().x+(float)(forward*Math.cos(angle)-left*Math.sin(angle)),backpack.getLocation().y+(float)(forward*Math.sin(angle)+left*Math.cos(angle)));
    }
    private static List<Float> point(Vector2f value){return List.of(value.x,value.y);}
}
