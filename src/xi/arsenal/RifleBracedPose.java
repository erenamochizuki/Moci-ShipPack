package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.settings.OoOO;
import java.awt.Color;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Generic rigid-rifle / two-hand pose. The owning weapon controller supplies
 * its single local clock; native callbacks only reapply the sampled transform.
 * Arms retain native slot pivots; shoulder and forearm share group movement.
 */
public final class RifleBracedPose implements AutoCloseable {
    public static final String KEY="xi.arsenal.rifle.brace";
    public static final float ENTER_SECONDS=.55f,EXIT_SECONDS=.4f;
    private final Thread owner=Thread.currentThread();
    private final ShipAPI ship;
    private final WeaponAPI gun;
    private final RifleBraceProfile profile;
    private final Map<WeaponAPI,Part> parts=new IdentityHashMap<>();
    private final List<ShieldLink> shields=new ArrayList<>();
    private final Map<Object,ForegroundLayer> foregroundLayers=new IdentityHashMap<>();
    private final boolean foreground;
    private final Part body,right,left;
    private final Vector2f rightPalm,leftPalm,gunGrip,gunSupport;
    private final Vector2f gunShift=new Vector2f();
    private final boolean originalBitmapHidden;
    private boolean bitmapOwned,closed,requested,previousRequested;
    private float weight,age,startWeight,aim,rightError,leftError,bodyYaw,rightAngle,leftAngle;
    /** 握把标定残差：两个标记点在各自贴图上被假定为同一张 102×360 画布，
     *  实际加载的枪/臂贴图尺寸或旋转中心不同就会留下一个恒定偏差。
     *  它不随动作进度或瞄准角变化，所以 ready() 必须按「残差 + 容差」判断，
     *  否则姿态视觉上已经合拢（weight=1）却永远判不通过，玩家永远打不出这一发。 */
    private final float rightResidual,rightTolerance;
    private boolean reachable;
    private RifleBraceNativeKinematics.Solution solution;
    private float[] solvedInput;private long solverSamples,applications;

    public RifleBracedPose(ShipAPI ship,WeaponAPI gun){
        this.ship=Objects.requireNonNull(ship);this.gun=Objects.requireNonNull(gun);
        profile=Objects.requireNonNull(RifleBraceProfile.forWeapon(gun),"Measured rifle/arm profile required");
        if(gun.getShip()!=ship||gun.getSlot()==null||!"ARM_R_SOURCE".equals(gun.getSlot().getId()))throw new IllegalArgumentException("Brace requires this ship's visible right rifle");
        if(ship.getCustomData().containsKey(KEY)||ship.getCustomData().containsKey(RifleFiringPose.KEY)||ship.getCustomData().containsKey(RifleFiringPose.KEY+".releaseRig")||FullSalvoPose.isBlockingRifle(ship))throw new IllegalStateException("Another upper-body pose owns this ship");
        for(WeaponAPI w:ship.getAllWeapons())if(w.getSlot()!=null&&Set.of("BODY","BODY_ARMOR","HEAD","ARM_R","ARM_L","SHOULDER_R","SHOULDER_L","SHOULDER_R_UNDER","SHOULDER_L_UNDER","BACKPACK").contains(w.getSlot().getId()))parts.put(w,new Part(w));
        body=find("BODY");right=find("ARM_R");left=find("ARM_L");
        if(body==null||right==null||left==null)throw new IllegalStateException("Brace requires real body and both arm bones");
        SpriteAPI rs=right.weapon.getSprite(),ls=left.weapon.getSprite(),gs=BeamRifleMount.visibleGunSprite(gun);
        rightPalm=profile.local(rs,profile.rightPalmX(),profile.rightPalmY(),rs.getCenterX(),rs.getCenterY());
        leftPalm=profile.local(ls,profile.leftPalmX(),profile.leftPalmY(),ls.getCenterX(),ls.getCenterY());
        Vector2f center=BeamRifleMount.baseGunCenter(gun);
        gunGrip=profile.local(gs,profile.gunGripX(),profile.gunGripY(),center.x,center.y);
        gunSupport=profile.local(gs,profile.supportX(),profile.supportY(),center.x,center.y);
        rightResidual=Vector2f.sub(gunGrip,rightPalm,new Vector2f()).length();
        rightTolerance=rightResidual+.75f;
        aim=gun.getCurrAngle();rightAngle=right.weapon.getCurrAngle();leftAngle=left.weapon.getCurrAngle();
        originalBitmapHidden=ship.isDoNotRenderSprite();ship.setCustomData(KEY,this);
        for(ShipAPI module:ship.getChildModulesCopy())if(module.getParentStation()==ship&&module.getStationSlot()!=null&&"SHIELD_L".equals(module.getStationSlot().getId())&&module.isAlive())shields.add(new ShieldLink(module));
        // Keep arms in their native render order. The separately layered
        // shield must sit above the legs but below the held rifle.
        foreground=true;
        if(foreground)Global.getCombatEngine().addLayeredRenderingPlugin(new Foreground());
    }
    private Part find(String id){for(Part p:parts.values())if(id.equals(p.slot))return p;return null;}
    public static RifleBracedPose instance(ShipAPI ship){Object o=ship==null?null:ship.getCustomData().get(KEY);return o instanceof RifleBracedPose p&&!p.closed?p:null;}
    public static boolean blocksGuard(ShipAPI ship){RifleBracedPose p=instance(ship);return p!=null&&((p.requested&&p.reachable)||p.weight>0);}
    public static Vector2f gunTranslation(WeaponAPI weapon){RifleBracedPose p=instance(weapon.getShip());return p!=null&&p.gun==weapon?new Vector2f(p.gunShift):null;}
    public static void apply(WeaponAPI weapon){RifleBracedPose p=weapon==null?null:instance(weapon.getShip());if(p!=null&&p.parts.containsKey(weapon)){p.requireOwner();Part part=p.parts.get(weapon);part.observeNative();p.applyAll();}}

    /** Already ship-local combat time. Render/late reapply never changes this clock. */
    public void advance(float dt,boolean wanted,float rawAim){
        requireOwner();if(closed)return;
        if(!Float.isFinite(dt)||dt<0||!Float.isFinite(rawAim))throw new IllegalArgumentException("Invalid brace sample");
        requested=wanted;aim=rawAim;
        if(requested!=previousRequested){age=0;startWeight=weight;previousRequested=requested;}
        age+=dt;float t=smoother(age/(requested?ENTER_SECONDS:EXIT_SECONDS));weight=startWeight+((requested?1:0)-startWeight)*t;
        applyAll();
    }
    public boolean ready(){return !closed&&requested&&reachable&&weight>.99999f&&rightError<=rightTolerance&&leftError<.75f;}
    public float weight(){return weight;}
    public float rightError(){return rightError;}
    public float leftError(){return leftError;}
    public float rightTolerance(){return rightTolerance;}
    public float gripResidual(){return rightResidual;}
    /** An unreachable extreme angle safely uses the native one-hand shot. */
    public boolean canRelease(){return ready()||!closed&&requested&&!reachable&&weight==0;}
    public boolean finished(){return closed||!requested&&weight<.00001f;}
    public void applyAll(){
        requireOwner();if(closed)return;if(ship.getCustomData().get(KEY)!=this)throw new IllegalStateException("Brace ownership replaced");
        applications++;
        float facing=ship.getFacing();Vector2f b=body.baseOrigin();
        float restRight=aim,restLeft=facing+left.baseRelative;
        Vector2f nativeRight=right.baseOrigin(),nativeLeft=left.baseOrigin();
        float[] input={b.x,b.y,nativeRight.x,nativeRight.y,nativeLeft.x,nativeLeft.y,facing,aim,restLeft};
        if(!Arrays.equals(input,solvedInput)){solution=RifleBraceNativeKinematics.solve(b,nativeRight,nativeLeft,leftPalm,gunSupport,facing,aim,restLeft);solvedInput=input;solverSamples++;}
        boolean previouslyReachable=reachable;reachable=solution.reachable();
        if(!reachable){
            // At the limits of the native firing arc, an anatomically valid
            // two-hand solution may not exist. Restore the native single-hand
            // pose rather than fold a limb or prevent the actual shot.
            for(Part p:parts.values())p.restore();
            for(ShieldLink shield:shields)shield.restore();
            for(ForegroundLayer layer:foregroundLayers.values())layer.restore();
            if(bitmapOwned&&ship.isDoNotRenderSprite())ship.setDoNotRenderSprite(originalBitmapHidden);
            bitmapOwned=false;gunShift.set(0,0);rightError=leftError=100000;
            weight=0;startWeight=0;age=0;return;
        }
        if(!previouslyReachable&&requested){weight=0;startWeight=0;age=0;}
        bodyYaw=solution.bodyYaw()*weight;
        rightAngle=restRight;
        float reach=smoother((weight-.2f)/.8f);
        leftAngle=restLeft+bodyYaw+BimanualRifleKinematics.shortest(restLeft+solution.bodyYaw(),solution.leftAngle())*reach;
        float clearance=smoother(weight/.55f);
        Vector2f moveR=rotate(new Vector2f(solution.rightForward()*clearance,0),facing+bodyYaw);
        Vector2f moveL=rotate(new Vector2f(solution.leftForward()*clearance,0),facing+bodyYaw);
        Vector2f rightOrigin=Vector2f.add(BimanualRifleKinematics.orbit(nativeRight,b,bodyYaw),moveR,null);
        Vector2f leftOrigin=Vector2f.add(BimanualRifleKinematics.orbit(nativeLeft,b,bodyYaw),moveL,null);
        Vector2f handR=Vector2f.add(rightOrigin,rotate(rightPalm,rightAngle),null),handL=Vector2f.add(leftOrigin,rotate(leftPalm,leftAngle),null);
        gunShift.set(Vector2f.sub(rightOrigin,gun.getLocation(),null));
        // Native weapon-only recoil cannot slide a rigid rifle out of two closed hands.
        // Blend that independent slide out as the bimanual constraint closes.
        Vector2f compensation=rotate(new Vector2f(BeamRifleMount.nativeRecoilPixels(gun)*weight,0),aim);
        Vector2f.add(gunShift,compensation,gunShift);
        for(Part p:parts.values()){
            if(p==right){p.write(rightAngle,rightOrigin);continue;}
            if(p==left){p.write(leftAngle,leftOrigin);continue;}
            float angle=facing+p.baseRelative+bodyYaw;Vector2f origin=BimanualRifleKinematics.orbit(p.baseOrigin(),b,bodyYaw);
            if("HEAD".equals(p.slot))angle=facing+p.baseRelative+BimanualRifleKinematics.shortest(facing+p.baseRelative,aim)*weight;
            // Shoulder armour follows the torso rigidly. Rotating it separately
            // from the measured arm root visually tears the joint apart.
            if(p.slot.startsWith("SHOULDER_R"))Vector2f.add(origin,moveR,origin);
            if(p.slot.startsWith("SHOULDER_L"))Vector2f.add(origin,moveL,origin);
            p.write(angle,origin);
        }
        Vector2f shieldShift=rotate(Vector2f.sub(leftOrigin,left.baseOrigin(),null),-facing);for(ShieldLink shield:shields)shield.apply(shieldShift);
        if(!shields.isEmpty())AdaptiveShieldGuard.ensureVisualLayer(ship);
        if(weight>0&&!bitmapOwned){ship.setDoNotRenderSprite(true);bitmapOwned=true;}
        Vector2f displayedGrip=Vector2f.add(gun.getLocation(),gunShift,null),displayedSupport=new Vector2f(displayedGrip);
        Vector2f recoil=rotate(new Vector2f(-BeamRifleMount.nativeRecoilPixels(gun),0),aim);
        Vector2f.add(displayedGrip,rotate(gunGrip,aim),displayedGrip);Vector2f.add(displayedGrip,recoil,displayedGrip);
        Vector2f.add(displayedSupport,rotate(gunSupport,aim),displayedSupport);Vector2f.add(displayedSupport,recoil,displayedSupport);
        rightError=Vector2f.sub(displayedGrip,handR,null).length();leftError=Vector2f.sub(displayedSupport,handL,null).length();
        if(foreground)hideForeground();
    }
    public Map<String,Object> status(){
        Map<String,Object> r=new LinkedHashMap<>();r.put("active",!closed);r.put("profile",profile.id());r.put("requested",requested);r.put("weight",weight);r.put("ready",ready());r.put("reachable",reachable);
        r.put("bodyYaw",bodyYaw);r.put("gunAngle",aim);r.put("rightArmAngle",rightAngle);r.put("leftArmAngle",leftAngle);r.put("rightGripError",rightError);r.put("leftSupportError",leftError);r.put("rightGripResidual",rightResidual);r.put("rightGripTolerance",rightTolerance);r.put("gunShiftX",gunShift.x);r.put("gunShiftY",gunShift.y);
        r.put("rightArmLength",rightPalm.length());r.put("leftArmLength",leftPalm.length());r.put("foregroundArms",false);r.put("foregroundGun",foreground);r.put("solverSamples",solverSamples);r.put("lateApplications",applications);r.put("rightForward",solution==null?0:solution.rightForward());r.put("leftForward",solution==null?0:solution.leftForward());r.put("limbPolicy","NATIVE_SLOT_PIVOTS; shoulder and arm use the same rigid group movement");r.put("clock","CALLER_LOCAL_TIME_ONCE");return Collections.unmodifiableMap(r);
    }
    @Override public void close(){
        requireOwner();if(closed)return;
        for(Part p:parts.values())p.restore();
        for(ShieldLink shield:shields)shield.restore();
        for(ForegroundLayer layer:foregroundLayers.values())layer.restore();
        if(bitmapOwned&&ship.isDoNotRenderSprite())ship.setDoNotRenderSprite(originalBitmapHidden);
        if(ship.getCustomData().get(KEY)==this)ship.removeCustomData(KEY);gunShift.set(0,0);closed=true;
    }
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Brace belongs to native combat thread");}
    private static Vector2f rotate(Vector2f v,float a){return BimanualRifleKinematics.rotate(v,a);}
    private static Vector2f mix(Vector2f a,Vector2f b,float t){return new Vector2f(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t);}
    private static float smoother(float value){float t=Math.max(0,Math.min(1,value));return t*t*t*(t*(t*6-15)+10);}
    private List<ForegroundLayer> currentForeground(){
        List<ForegroundLayer> result=new ArrayList<>();
        // Draw only the exact native rifle layers above the physical shield.
        // Both arms remain on the engine's original weapon layers.
        for(WeaponAPI w:new WeaponAPI[]{gun}){
            Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            for(SpriteAPI s:new SpriteAPI[]{w.getUnderSpriteAPI(),w.getSprite(),w.getBarrelSpriteAPI(),w.getGlowSpriteAPI()})if(s!=null){
                Object identity=s instanceof OoOO n?n.getSprite():s;if(!seen.add(identity))continue;
                ForegroundLayer layer=foregroundLayers.get(identity);
                if(layer==null){if(foregroundLayers.size()>256)throw new IllegalStateException("Too many brace image frames");layer=new ForegroundLayer(w,s instanceof OoOO n?new OoOO(n.getSprite()):s);foregroundLayers.put(identity,layer);}
                result.add(layer);
            }
        }
        return result;
    }
    private void hideForeground(){for(ForegroundLayer layer:currentForeground())layer.hide();}
    private final class ForegroundLayer {
        final WeaponAPI weapon;final SpriteAPI sprite;Color base;boolean hidden;
        ForegroundLayer(WeaponAPI w,SpriteAPI s){weapon=w;sprite=s;base=s.getColor();}
        void hide(){Color c=sprite.getColor();if(!hidden||c.getAlpha()!=0)base=c;sprite.setColor(new Color(base.getRed(),base.getGreen(),base.getBlue(),0));hidden=true;}
        void draw(){Color before=sprite.getColor();float angle=sprite.getAngle();try{sprite.setColor(base);sprite.setAngle(weapon.getCurrAngle()-90);Vector2f origin=new Vector2f(weapon.getLocation());if(weapon.isDecorative()&&weapon.isBeam()&&weapon.getRenderOffsetForDecorativeBeamWeaponsOnly()!=null)Vector2f.add(origin,rotate(weapon.getRenderOffsetForDecorativeBeamWeaponsOnly(),ship.getFacing()),origin);sprite.renderAtCenter(origin.x,origin.y);}finally{sprite.setColor(before);sprite.setAngle(angle);}}
        void restore(){Color c=sprite.getColor();if(hidden&&c.getAlpha()==0&&c.getRed()==base.getRed()&&c.getGreen()==base.getGreen()&&c.getBlue()==base.getBlue())sprite.setColor(base);}
    }
    private final class Foreground extends BaseCombatLayeredRenderingPlugin {
        @Override public EnumSet<CombatEngineLayers> getActiveLayers(){return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);}
        @Override public float getRenderRadius(){return 4000;}
        @Override public boolean isExpired(){return closed||!ship.isAlive()||ship.isHulk()||ship.wasRemoved();}
        @Override public void advance(float amount){if(entity!=null)entity.getLocation().set(ship.getLocation());}
        @Override public void render(CombatEngineLayers layer,ViewportAPI viewport){if(!isExpired()&&reachable&&weight>0)for(ForegroundLayer part:currentForeground())part.draw();}
    }
    private final class Part {
        final WeaponAPI weapon;final String slot;final boolean offsetBased;final Vector2f originalOffset,baseOffset;
        final List<Layer> layers=new ArrayList<>();float baseRelative,writtenAngle;boolean angleWritten;Vector2f writtenOffset;
        Part(WeaponAPI weapon){this.weapon=weapon;slot=weapon.getSlot().getId();baseRelative=BimanualRifleKinematics.shortest(ship.getFacing(),weapon.getCurrAngle());
            offsetBased=weapon.isDecorative()&&weapon.isBeam();originalOffset=offsetBased?weapon.getRenderOffsetForDecorativeBeamWeaponsOnly():null;baseOffset=originalOffset==null?new Vector2f():new Vector2f(originalOffset);
            if(!offsetBased){Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());for(SpriteAPI s:new SpriteAPI[]{weapon.getSprite(),weapon.getBarrelSpriteAPI(),weapon.getUnderSpriteAPI(),weapon.getGlowSpriteAPI()})if(s!=null){Object identity=s instanceof OoOO n?n.getSprite():s;if(seen.add(identity))layers.add(new Layer(s instanceof OoOO n?new OoOO(n.getSprite()):s));}}
        }
        Vector2f baseOrigin(){return Vector2f.add(weapon.getLocation(),rotate(baseOffset,ship.getFacing()),null);}
        void observeNative(){if(angleWritten&&Math.abs(BimanualRifleKinematics.shortest(writtenAngle,weapon.getCurrAngle()))>.001f&&this!=right)baseRelative=BimanualRifleKinematics.shortest(ship.getFacing(),weapon.getCurrAngle());}
        void write(float angle,Vector2f origin){weapon.setCurrAngle(angle);writtenAngle=weapon.getCurrAngle();angleWritten=true;
            Vector2f shift=Vector2f.sub(origin,weapon.getLocation(),null);
            if(offsetBased){writtenOffset=rotate(shift,-ship.getFacing());weapon.setRenderOffsetForDecorativeBeamWeaponsOnly(writtenOffset);}
            else for(Layer layer:layers)layer.write(shift,angle);
        }
        void restore(){if(angleWritten&&Math.abs(BimanualRifleKinematics.shortest(writtenAngle,weapon.getCurrAngle()))<.01f)weapon.setCurrAngle(this==right?gun.getCurrAngle():ship.getFacing()+baseRelative);
            if(offsetBased&&weapon.getRenderOffsetForDecorativeBeamWeaponsOnly()==writtenOffset)weapon.setRenderOffsetForDecorativeBeamWeaponsOnly(originalOffset);
            for(Layer layer:layers)layer.restore();}
    }
    private static final class Layer {
        final SpriteAPI sprite;final float baseX,baseY;float writtenX,writtenY;boolean written;
        Layer(SpriteAPI s){sprite=s;baseX=s.getCenterX();baseY=s.getCenterY();}
        void write(Vector2f world,float angle){Vector2f local=rotate(world,-angle);writtenX=baseX+local.y;writtenY=baseY-local.x;sprite.setCenter(writtenX,writtenY);written=true;}
        void restore(){if(written&&Math.abs(sprite.getCenterX()-writtenX)<.001f&&Math.abs(sprite.getCenterY()-writtenY)<.001f)sprite.setCenter(baseX,baseY);}
    }
    private final class ShieldLink {
        final ShipAPI module;final WeaponSlotAPI slot;final Vector2f original;Vector2f written;
        ShieldLink(ShipAPI module){this.module=module;module.ensureClonedStationSlotSpec();slot=module.getStationSlot();RifleFiringPose.detachLocation(slot);original=new Vector2f(slot.getLocation());}
        void apply(Vector2f shift){if(!module.isAlive()||module.wasRemoved()||module.getParentStation()!=ship||module.getStationSlot()!=slot){restore();return;}written=Vector2f.add(original,shift,null);slot.getLocation().set(written);}
        void restore(){if(written!=null&&Vector2f.sub(slot.getLocation(),written,null).lengthSquared()<.0001f)slot.getLocation().set(original);written=null;}
    }
}
