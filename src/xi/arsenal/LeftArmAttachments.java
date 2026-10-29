package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.settings.OoOO;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.lwjgl.util.vector.Vector2f;

/** Explicit left-hand attachment adapter, not a second limb or a Mod clock.
 * Zeta SOURCE is the real48x206 MSZ_006_arm_left_gun_Sabre handle. Its turret
 * aperture(30,-3.5) is PNG(27.5,73), distinct from ARM's FOLLOW barrel and engines.
 * Original callbacks still run once; their wrappers call apply at the late boundary. */
public final class LeftArmAttachments implements AutoCloseable {
    public static final String KEY="xi.arsenal.pose.leftAttachments",ZETA_SOURCE="MSZ_006_arm_left_gun_Sabre";
    private enum Role{SOURCE,FOLLOW}
    private final ShipAPI ship;private final WeaponAPI arm;private final Thread thread=Thread.currentThread();
    private final Supplier<Float> angle;private final Supplier<Vector2f> translation;private final BooleanSupplier valid;
    private final List<Member> members=new ArrayList<>();private boolean closed;
    LeftArmAttachments(ShipAPI ship,Supplier<Float> angle,Supplier<Vector2f> translation,BooleanSupplier valid){
        this.ship=Objects.requireNonNull(ship);this.angle=Objects.requireNonNull(angle);this.translation=Objects.requireNonNull(translation);this.valid=Objects.requireNonNull(valid);
        arm=ZetaRifleMount.slot(ship,"ARM_L");WeaponAPI source=ZetaRifleMount.slot(ship,"ARM_L_SOURCE");
        // A slot name alone is insufficient: do not attach unknown mods' weapons or a whole body.
        if(!ZetaMobility.supports(ship)||arm==null||source==null||!ZETA_SOURCE.equals(source.getId())||!source.isBeam()||source.isDecorative())return;
        if(ship.getCustomData().containsKey(KEY))throw new IllegalStateException("A left attachment pose lease is already active");
        members.add(new Member(source,Role.SOURCE));WeaponAPI follow=ZetaRifleMount.slot(ship,"ARM_L_FOLLOW");
        if(follow!=null&&!follow.isDecorative())members.add(new Member(follow,Role.FOLLOW));
        ship.setCustomData(KEY,this);
    }
    /** Safe both before and after the one original native SOURCE/FOLLOW callback. Never advances time. */
    public static void apply(WeaponAPI weapon){if(weapon==null||weapon.getShip()==null)return;Object value=weapon.getShip().getCustomData().get(KEY);if(value instanceof LeftArmAttachments lease){lease.requireThread();if(!lease.live()){lease.close();return;}for(Member member:lease.members)if(member.weapon==weapon){lease.apply(member);return;}}}
    void applyAll(){requireThread();if(closed||members.isEmpty())return;if(!live()){close();return;}for(Member member:members)apply(member);}
    private boolean live(){return !closed&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&ZetaRifleModes.msForm(ship)&&valid.getAsBoolean()&&ship.getCustomData().get(KEY)==this;}
    private void apply(Member member){float targetAngle=angle.get();Vector2f shift=translation.get();if(!Float.isFinite(targetAngle)||shift==null||!Float.isFinite(shift.x)||!Float.isFinite(shift.y))throw new IllegalStateException("Invalid declared left-hand pose transform");
        Vector2f anchor=Vector2f.add(arm.getLocation(),shift,null),delta=Vector2f.sub(anchor,member.weapon.getLocation(),null);
        member.weapon.setCurrAngle(targetAngle);member.writtenAngle=member.weapon.getCurrAngle();member.wroteAngle=true;
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());for(SpriteAPI sprite:new SpriteAPI[]{member.weapon.getSprite(),member.weapon.getBarrelSpriteAPI(),member.weapon.getUnderSpriteAPI(),member.weapon.getGlowSpriteAPI()})if(sprite!=null){
            // Native getSprite rebinds a cached API wrapper to the current animation frame;
            // under/glow/barrel getters instead return fresh wrappers for the same backend.
            // A wrapper identity is therefore neither a frame identity nor stable ownership.
            Object identity=sprite instanceof OoOO nativeWrapper?Objects.requireNonNull(nativeWrapper.getSprite(),"Native left attachment backend"):sprite;
            if(!seen.add(identity))continue;
            Layer layer=member.layers.get(identity);if(layer==null){
                if(member.layers.size()>=256)throw new IllegalStateException("Excessive actual left-attachment sprite backends");
                // This private view is pinned to the actual Sprite, so later native getter
                // rebinding cannot redirect restoration to a different animation frame.
                SpriteAPI pinned=sprite instanceof OoOO nativeWrapper?new OoOO(nativeWrapper.getSprite()):sprite;
                layer=new Layer(pinned);member.layers.put(identity,layer);
            }
            layer.apply(delta,targetAngle);
        }
        for(Offset offset:member.offsets){Vector2f value;
            if(member.role==Role.SOURCE){Vector2f local=rotate(delta,-targetAngle);value=Vector2f.add(offset.nativeBase(),local,null);}
            else{List<Vector2f> armOffsets=offset.kind==0?arm.getSpec().getTurretFireOffsets():offset.kind==1?arm.getSpec().getHardpointFireOffsets():arm.getSpec().getHiddenFireOffsets();
                if(armOffsets.isEmpty())armOffsets=arm.getSpec().getTurretFireOffsets();if(armOffsets.isEmpty())throw new IllegalStateException("Declared ARM has no native FOLLOW barrel");Vector2f point=Vector2f.add(anchor,rotate(armOffsets.get(Math.min(offset.index,armOffsets.size()-1)),targetAngle),null);value=rotate(Vector2f.sub(point,member.weapon.getLocation(),null),-targetAngle);}
            offset.write(value);
        }
        if(member.weapon.isBeam())member.weapon.updateBeamFromPoints();
    }
    public static Map<String,Object> status(ShipAPI ship){Object value=ship==null?null:ship.getCustomData().get(KEY);if(!(value instanceof LeftArmAttachments lease))return Map.of("active",false,"members",List.of());List<Map<String,Object>> rows=new ArrayList<>();for(Member member:lease.members)rows.add(Map.of("weapon",member.weapon.getId(),"slot",member.weapon.getSlot().getId(),"role",member.role.name(),"angle",member.weapon.getCurrAngle(),"clonedFireOffsets",member.offsets.size(),"capturedSprites",member.layers.size()));return Map.of("active",lease.live(),"members",List.copyOf(rows),"scope","Declared left handle/FOLLOW; sprite and native aperture share real left-arm pose");}
    @Override public void close(){requireThread();if(closed)return;closed=true;Throwable failure=null;for(Member member:members)try{member.restore();}catch(Throwable error){if(error instanceof VirtualMachineError fatal)throw fatal;if(failure==null)failure=error;else failure.addSuppressed(error);}if(ship.getCustomData().get(KEY)==this)ship.removeCustomData(KEY);if(failure!=null)throw new IllegalStateException("Left attachment cleanup failed",failure);}
    private void requireThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Left attachments require their pose owner thread");}
    private final class Member {
        final WeaponAPI weapon;final Role role;final float restAngle;final Map<Object,Layer> layers=new IdentityHashMap<>();final List<Offset> offsets=new ArrayList<>();float writtenAngle;boolean wroteAngle;
        Member(WeaponAPI weapon,Role role){this.weapon=weapon;this.role=role;restAngle=shortest(weapon.getCurrAngle()-ship.getFacing());ZetaRifleMount.detach(weapon);int kind=0;Set<List<Vector2f>> seen=Collections.newSetFromMap(new IdentityHashMap<>());for(List<Vector2f> list:List.of(weapon.getSpec().getTurretFireOffsets(),weapon.getSpec().getHardpointFireOffsets(),weapon.getSpec().getHiddenFireOffsets())){if(list.size()>64)throw new IllegalStateException("Excessive declared left-hand barrels");if(seen.add(list))for(int i=0;i<list.size();i++)offsets.add(new Offset(list,i,kind));kind++;}}
        void restore(){if(wroteAngle&&Math.abs(shortest(weapon.getCurrAngle()-writtenAngle))<.01f)weapon.setCurrAngle(ship.getFacing()+restAngle);for(Layer layer:layers.values())layer.restore();for(Offset offset:offsets)offset.restore();if(weapon.isBeam())weapon.updateBeamFromPoints();}
    }
    private static final class Layer {
        final SpriteAPI sprite;float baseX,baseY,writtenX,writtenY;boolean written;
        Layer(SpriteAPI sprite){this.sprite=sprite;baseX=sprite.getCenterX();baseY=sprite.getCenterY();}
        void apply(Vector2f world,float angle){if(written&&(Math.abs(sprite.getCenterX()-writtenX)>.001f||Math.abs(sprite.getCenterY()-writtenY)>.001f)){baseX=sprite.getCenterX();baseY=sprite.getCenterY();}Vector2f local=rotate(world,-angle);sprite.setCenter(baseX+local.y,baseY-local.x);writtenX=sprite.getCenterX();writtenY=sprite.getCenterY();written=true;}
        void restore(){if(written&&Math.abs(sprite.getCenterX()-writtenX)<.001f&&Math.abs(sprite.getCenterY()-writtenY)<.001f)sprite.setCenter(baseX,baseY);}
    }
    private static final class Offset {
        final List<Vector2f> list;final int index,kind;Vector2f point,base,written;
        Offset(List<Vector2f> list,int index,int kind){this.list=list;this.index=index;this.kind=kind;point=list.get(index);base=new Vector2f(point);}
        Vector2f nativeBase(){refresh();if(written!=null&&Vector2f.sub(point,written,null).lengthSquared()>.000001f)base.set(point);return base;}
        void refresh(){if(list.get(index)!=point){point=new Vector2f(list.get(index));list.set(index,point);base=new Vector2f(point);written=null;}}
        void write(Vector2f value){refresh();written=new Vector2f(value);point.set(value);}
        void restore(){if(list.get(index)==point&&written!=null&&Vector2f.sub(point,written,null).lengthSquared()<.000001f)point.set(base);}
    }
    private static Vector2f rotate(Vector2f point,float degrees){double r=Math.toRadians(degrees);return new Vector2f((float)(point.x*Math.cos(r)-point.y*Math.sin(r)),(float)(point.x*Math.sin(r)+point.y*Math.cos(r)));}
    private static float shortest(float value){float angle=(value%360+360)%360;return angle>180?angle-360:angle;}
}
