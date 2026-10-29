package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Two-handed cable combination pose. Both arms aim at the target; neither arm is rotated outward for display.
 * The body/shoulder pivots orbit together. Shield stations keep their native angle and receive only the
 * left-arm anchor translation on an instance-detached slot. No shared hull/animation data is changed. */
public final class ZetaTetherPose implements AutoCloseable {
    public static final String KEY="xi.arsenal.zeta.tetherPose";
    public static final float BODY_DEGREES=28,FORWARD=24,LEFT_PRESENT_SECONDS=.62f,RIGHT_PRESENT_SECONDS=.82f,RECOVER_SECONDS=.3f;
    private static final Set<String> SLOTS=Set.of("BODY","BODY_ARMOR","ARM_L","ARM_R","HEAD","LEGS","SHOULDER_L","SHOULDER_R","SHIELD_L_COVER","BACKPACK");
    private final ShipAPI ship;private final Object token;private final Thread owner=Thread.currentThread();
    private final Map<WeaponAPI,Part> parts=new IdentityHashMap<>();private final Map<String,Part> slots=new HashMap<>();private final List<ShieldLink> shields=new ArrayList<>();
    private final LeftArmAttachments leftAttachments;
    private final boolean oldBitmap;private float body,leftForward,rightForward,aim,leftAim,headAim,recoil=-1;private boolean closed;private int nativeLegFrame;private boolean wroteLeg;
    public ZetaTetherPose(ShipAPI ship,Object token){
        this.ship=Objects.requireNonNull(ship);this.token=Objects.requireNonNull(token);
        if(ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY)!=token||ship.getCustomData().containsKey(KEY)||!ZetaRifleModes.msForm(ship))throw new IllegalStateException("Tether pose needs exclusive normal-form arms");
        for(WeaponAPI weapon:ship.getAllWeapons())if(weapon.getSlot()!=null&&SLOTS.contains(weapon.getSlot().getId())){Part part=new Part(weapon);parts.put(weapon,part);slots.put(part.slot,part);}
        for(String required:List.of("BODY","ARM_L","ARM_R","HEAD","LEGS"))if(!slots.containsKey(required))throw new IllegalStateException("Tether requires original Zeta body bone: "+required);
        oldBitmap=ship.isDoNotRenderSprite();
        for(ShipAPI module:ship.getChildModulesCopy())if(module.getStationSlot()!=null&&"SHIELD_L".equals(module.getStationSlot().getId()))shields.add(new ShieldLink(module));
        leftAttachments=new LeftArmAttachments(ship,()->armAngle(true),()->armShift(true),this::valid);
        ship.setDoNotRenderSprite(true);ship.setCustomData(KEY,this);aim=leftAim=headAim=ship.getFacing();
    }
    public void advance(float dt,float bodyDegrees,float left,float right,float aimAngle){
        advance(dt,bodyDegrees,left,right,aimAngle,aimAngle,aimAngle);
    }
    /** Shared shoulder geometry with independent right gun, left tool/blade and head targets.
     * left/right remain legacy-scaled aiming weights, never world translations. */
    public void advance(float dt,float bodyDegrees,float left,float right,float aimAngle,float leftArmAimAngle,float headAimAngle){
        requireOwner();if(closed)return;if(!Float.isFinite(dt)||dt<0||!Float.isFinite(bodyDegrees)||!Float.isFinite(left)||!Float.isFinite(right)||!Float.isFinite(aimAngle)||!Float.isFinite(leftArmAimAngle)||!Float.isFinite(headAimAngle))throw new IllegalArgumentException("Invalid tether pose frame");
        body=Math.max(-BODY_DEGREES,Math.min(BODY_DEGREES,bodyDegrees));leftForward=Math.max(0,Math.min(FORWARD,left));rightForward=Math.max(0,Math.min(FORWARD,right));aim=aimAngle;leftAim=leftArmAimAngle;headAim=headAimAngle;if(recoil>=0)recoil+=dt;applyAll();
    }
    public void firstLight(){requireOwner();recoil=0;applyAll();}
    public static boolean blocksGuard(ShipAPI ship){Object value=ship.getCustomData().get(KEY);return value instanceof ZetaTetherPose pose&&pose.valid();}
    public static void apply(WeaponAPI weapon){if(weapon==null||weapon.getShip()==null)return;Object value=weapon.getShip().getCustomData().get(KEY);if(value instanceof ZetaTetherPose pose){if(!pose.valid()){pose.close();return;}Part part=pose.parts.get(weapon);if(part!=null){pose.apply(part);if("ARM_L".equals(part.slot))pose.leftAttachments.applyAll();}else LeftArmAttachments.apply(weapon);pose.applyShield();}}
    private boolean valid(){return !closed&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&ZetaRifleModes.msForm(ship)&&ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY)==token;}
    private Vector2f orbit(Part part){Vector2f pivot=part.pivot;if(part.slot.equals("BACKPACK")&&part.frameSafe)pivot=Vector2f.add(pivot,part.baseOffset,null);return RifleFiringPose.bodyPivotOrbit(pivot,slots.get("BODY").pivot,body,ship.getFacing());}
    private Vector2f armShift(boolean left){return orbit(slots.get(left?"ARM_L":"ARM_R"));}
    public Vector2f rightTranslation(){return armShift(false);}
    public float rightAimAngle(){return armAngle(false);}
    /** Read-only shared upper-body adapter; a sword action does not need a paid rifle lease. */
    public static float rightAimAngle(ShipAPI ship){Object value=ship==null?null:ship.getCustomData().get(KEY);return value instanceof ZetaTetherPose pose&&pose.valid()?pose.rightAimAngle():ship==null?0:ship.getFacing();}
    public float leftAimAngle(){return armAngle(true);}
    private float armAngle(boolean left){Part arm=slots.get(left?"ARM_L":"ARM_R");float weight=(left?Math.min(FORWARD,leftForward+rightForward):rightForward)/FORWARD,from=ship.getFacing()+arm.restAngle+body;return from+shortest((left?leftAim:aim)-from)*weight;}
    public static Vector2f armTranslation(ShipAPI ship,boolean left){Object value=ship==null?null:ship.getCustomData().get(KEY);return value instanceof ZetaTetherPose pose&&pose.valid()?pose.armShift(left):new Vector2f();}
    public float aim(){return aim;}
    public Vector2f leftAperture(){return ZetaWristMount.visibleMuzzleWorld(slots.get("ARM_L").weapon,true,0);}
    private void applyAll(){if(!valid()){close();return;}for(Part part:parts.values())apply(part);leftAttachments.applyAll();applyShield();}
    private void apply(Part part){String slot=part.slot;Vector2f shift=orbit(part);float angle=ship.getFacing()+part.restAngle+body;
        if(slot.equals("ARM_L")||slot.equals("SHIELD_L_COVER")){shift=armShift(true);angle=armAngle(true);}
        else if(slot.equals("ARM_R")){shift=armShift(false);angle=armAngle(false);}
        else if(slot.equals("HEAD"))angle=headAim;
        if(slot.equals("LEGS")&&part.weapon.getAnimation()!=null){int frame=part.weapon.getAnimation().getFrame();if(!wroteLeg||frame!=4)nativeLegFrame=frame;if(part.weapon.getAnimation().getNumFrames()<=4)throw new IllegalStateException("Zeta standing leg frame4 missing");part.weapon.getAnimation().setFrame(4);wroteLeg=true;}
        part.angle(angle);part.translate(shift);
    }
    private void applyShield(){if(!valid())return;Vector2f parentShift=rotate(armShift(true),-ship.getFacing());for(ShieldLink link:shields)link.apply(parentShift);AdaptiveShieldGuard.ensureVisualLayer(ship);}
    public Map<String,Object> status(){return Map.ofEntries(Map.entry("active",valid()),Map.entry("bodyDegrees",body),Map.entry("leftForward",0f),Map.entry("rightForward",0f),Map.entry("leftAimBlend",Math.min(FORWARD,leftForward+rightForward)/FORWARD),Map.entry("rightAimBlend",rightForward/FORWARD),Map.entry("aim",aim),Map.entry("leftArmAim",leftAim),Map.entry("headAim",headAim),Map.entry("standingLegFrame",4),Map.entry("parts",parts.size()),Map.entry("shieldLinks",shields.size()));}
    @Override public void close(){requireOwner();if(closed)return;closed=true;List<AutoCloseable> actions=new ArrayList<>();actions.add(leftAttachments);for(Part part:parts.values())actions.add(part::restore);for(ShieldLink link:shields)actions.add(link::restore);Throwable failure=null;for(AutoCloseable action:actions)try{action.close();}catch(Throwable error){if(error instanceof VirtualMachineError fatal)throw fatal;if(failure==null)failure=error;else failure.addSuppressed(error);}Part leg=slots.get("LEGS");if(wroteLeg&&leg!=null&&leg.weapon.getAnimation()!=null&&leg.weapon.getAnimation().getFrame()==4)leg.weapon.getAnimation().setFrame(nativeLegFrame);
        if(ship.getCustomData().get(KEY)==this){if(ship.isDoNotRenderSprite())ship.setDoNotRenderSprite(oldBitmap);ship.removeCustomData(KEY);}if(failure!=null)throw new IllegalStateException("Tether pose cleanup failed",failure);}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Tether pose requires combat owner thread");}
    private final class Part{
        final WeaponAPI weapon;final String slot;final float restAngle;final Vector2f pivot;final boolean frameSafe;final List<Layer> layers=new ArrayList<>();final Vector2f oldOffset,baseOffset;final RifleFiringPose.BackpackAnchors anchors;Vector2f writtenOffset;float writtenAngle;boolean angled;
        Part(WeaponAPI weapon){this.weapon=weapon;slot=weapon.getSlot().getId();restAngle=shortest(weapon.getCurrAngle()-ship.getFacing());pivot=rotate(Vector2f.sub(weapon.getLocation(),ship.getLocation(),null),-ship.getFacing());frameSafe=slot.equals("LEGS")||slot.startsWith("SHOULDER_")||slot.equals("BACKPACK")&&weapon.isDecorative()&&weapon.isBeam();oldOffset=frameSafe?weapon.getRenderOffsetForDecorativeBeamWeaponsOnly():null;baseOffset=oldOffset==null?new Vector2f():new Vector2f(oldOffset);anchors=slot.equals("BACKPACK")&&frameSafe?new RifleFiringPose.BackpackAnchors(weapon):null;if(!frameSafe){Set<SpriteAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());for(SpriteAPI sprite:new SpriteAPI[]{weapon.getSprite(),weapon.getBarrelSpriteAPI(),weapon.getUnderSpriteAPI(),weapon.getGlowSpriteAPI()})if(sprite!=null&&seen.add(sprite))layers.add(new Layer(sprite));}}
        void angle(float value){weapon.setCurrAngle(value);writtenAngle=weapon.getCurrAngle();angled=true;}
        void translate(Vector2f world){if(frameSafe){writtenOffset=Vector2f.add(baseOffset,rotate(world,-ship.getFacing()),null);weapon.setRenderOffsetForDecorativeBeamWeaponsOnly(writtenOffset);}else for(Layer layer:layers)layer.apply(world,weapon.getCurrAngle());if(anchors!=null)anchors.apply(world);}
        void restore(){if(angled&&Math.abs(shortest(weapon.getCurrAngle()-writtenAngle))<.01f)weapon.setCurrAngle(ship.getFacing()+restAngle);if(frameSafe&&weapon.getRenderOffsetForDecorativeBeamWeaponsOnly()==writtenOffset)weapon.setRenderOffsetForDecorativeBeamWeaponsOnly(oldOffset);for(Layer layer:layers)layer.restore();if(anchors!=null)anchors.restore();}
    }
    private static final class Layer{final SpriteAPI sprite;final float x,y;float writtenX,writtenY;boolean written;Layer(SpriteAPI sprite){this.sprite=sprite;x=sprite.getCenterX();y=sprite.getCenterY();}void apply(Vector2f shift,float facing){Vector2f local=rotate(shift,-facing);sprite.setCenter(x+local.y,y-local.x);writtenX=sprite.getCenterX();writtenY=sprite.getCenterY();written=true;}void restore(){if(written&&Math.abs(sprite.getCenterX()-writtenX)<.01f&&Math.abs(sprite.getCenterY()-writtenY)<.01f)sprite.setCenter(x,y);}}
    private final class ShieldLink{final ShipAPI module;final WeaponSlotAPI slot;final Vector2f original;Vector2f written;ShieldLink(ShipAPI module){this.module=module;module.ensureClonedStationSlotSpec();slot=module.getStationSlot();RifleFiringPose.detachLocation(slot);original=new Vector2f(slot.getLocation());}void apply(Vector2f shift){if(!module.isAlive()||module.wasRemoved()||module.getParentStation()!=ship||module.getStationSlot()!=slot){restore();return;}written=Vector2f.add(original,shift,null);slot.getLocation().set(written);}void restore(){if(written!=null&&Vector2f.sub(slot.getLocation(),written,null).lengthSquared()<.0001f)slot.getLocation().set(original);written=null;}}
    private static Vector2f rotate(Vector2f point,float degrees){double a=Math.toRadians(degrees);return new Vector2f((float)(point.x*Math.cos(a)-point.y*Math.sin(a)),(float)(point.x*Math.sin(a)+point.y*Math.cos(a)));}
    private static float shortest(float value){float a=(value%360+360)%360;return a>180?a-360:a;}
}
