package xi.arsenal;

import data.scripts.weapons.Moci_MechTransformController;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Native fire offsets follow the displayed aperture, not an engine/thruster point.
 * MS gun PNG 48x206: cap at (23,2.5). WR backpack frame11 118x220: nose aperture at (59,11).
 * Pixel +Y points down; Sprite +Y is up, native weapon +X is forward. */
final class ZetaRifleMount {
    static final float WR_WIDTH=118,WR_HEIGHT=220,WR_U=59,WR_V=11;
    static final float MS_WIDTH=48,MS_HEIGHT=206,MS_U=23,MS_V=2.5f;
    private final WeaponAPI source;private final Map<SpriteAPI,Vector2f> centers=new IdentityHashMap<>();
    ZetaRifleMount(WeaponAPI source){this.source=source;capture(source.getSprite());detach(source);}
    private void capture(SpriteAPI sprite){if(sprite!=null&&!centers.containsKey(sprite))centers.put(sprite,new Vector2f(sprite.getCenterX(),sprite.getCenterY()));}
    static void detach(WeaponAPI weapon){weapon.ensureClonedSpec();for(var offsets:List.of(weapon.getSpec().getTurretFireOffsets(),weapon.getSpec().getHardpointFireOffsets(),weapon.getSpec().getHiddenFireOffsets()))for(int i=0;i<offsets.size();i++)offsets.set(i,new Vector2f(offsets.get(i)));}
    void reconcile(){
        if(isWr(source)){source.setCurrAngle(source.getShip().getFacing());setFirePoint(source,wrMuzzle(source.getShip()));return;}
        SpriteAPI sprite=source.getSprite();if(sprite==null)return;capture(sprite);Vector2f rest=centers.get(sprite);
        float recoil=source.getCooldown()>0?clamp(source.getCooldownRemaining()/source.getCooldown()):0;
        Vector2f shift=ZetaRifleModes.gunTranslation(source),local=rotate(shift,-source.getCurrAngle());
        sprite.setCenter(rest.x+local.y,rest.y+6*recoil-local.x);
        setFirePoint(source,imageMuzzle(source,sprite,MS_WIDTH,MS_HEIGHT,MS_U,MS_V));
    }
    static boolean isWr(WeaponAPI weapon){return weapon!=null&&weapon.getSlot()!=null&&(ZetaRifleModes.WR_SOURCE_SLOT.equals(weapon.getSlot().getId())||ZetaRifleModes.WR_SNIPER_SLOT.equals(weapon.getSlot().getId()));}
    static Vector2f localPixel(SpriteAPI sprite,float width,float height,float u,float v){return new Vector2f((height-v)*sprite.getHeight()/height-sprite.getCenterY(),-(u*sprite.getWidth()/width-sprite.getCenterX()));}
    static Vector2f imageMuzzle(WeaponAPI weapon,SpriteAPI sprite,float width,float height,float u,float v){Vector2f at=rotate(localPixel(sprite,width,height,u,v),weapon.getCurrAngle());return Vector2f.add(at,weapon.getLocation(),at);}
    static Vector2f wrMuzzle(ShipAPI ship){WeaponAPI backpack=slot(ship,"BACKPACK");SpriteAPI sprite=backpack==null?null:backpack.getSprite();if(sprite==null)throw new IllegalStateException("Actual Zeta backpack sprite is required for its nose aperture");return imageMuzzle(backpack,sprite,WR_WIDTH,WR_HEIGHT,WR_U,WR_V);}
    static Vector2f msMuzzle(ShipAPI ship){WeaponAPI gun=slot(ship,ZetaRifleModes.SOURCE_SLOT);if(gun==null||gun.getSprite()==null)throw new IllegalStateException("Actual Zeta right rifle drawing is required");return imageMuzzle(gun,gun.getSprite(),MS_WIDTH,MS_HEIGHT,MS_U,MS_V);}
    static Vector2f visualMuzzle(ShipAPI ship){float blend=progress(ship);Vector2f a=msMuzzle(ship),b=wrMuzzle(ship);return new Vector2f(a.x+(b.x-a.x)*blend,a.y+(b.y-a.y)*blend);}
    static float progress(ShipAPI ship){WeaponAPI backpack=slot(ship,"BACKPACK");if(backpack!=null&&backpack.getEffectPlugin() instanceof Moci_MechTransformController transform)return transform.transformProgress();Object ready=ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORMED);return Boolean.TRUE.equals(ready)?1:0;}
    static void synchronize(WeaponAPI beam,WeaponAPI source){if(isWr(source)){source.setCurrAngle(source.getShip().getFacing());beam.setCurrAngle(source.getCurrAngle());setFirePoint(beam,wrMuzzle(source.getShip()));}else setFirePoint(beam,msMuzzle(source.getShip()));beam.updateBeamFromPoints();}
    static void setFirePoint(WeaponAPI weapon,Vector2f world){Vector2f relative=Vector2f.sub(world,weapon.getLocation(),null),local=rotate(relative,-weapon.getCurrAngle());for(var offsets:List.of(weapon.getSpec().getTurretFireOffsets(),weapon.getSpec().getHardpointFireOffsets(),weapon.getSpec().getHiddenFireOffsets()))for(Vector2f offset:offsets)offset.set(local);}
    static WeaponAPI slot(ShipAPI ship,String slot){for(WeaponAPI w:ship.getAllWeapons())if(w.getSlot()!=null&&slot.equals(w.getSlot().getId()))return w;return null;}
    private static Vector2f rotate(Vector2f p,float degrees){double r=Math.toRadians(degrees);return new Vector2f((float)(p.x*Math.cos(r)-p.y*Math.sin(r)),(float)(p.x*Math.sin(r)+p.y*Math.cos(r)));}
    private static float clamp(float value){return Math.max(0,Math.min(1,value));}
}
