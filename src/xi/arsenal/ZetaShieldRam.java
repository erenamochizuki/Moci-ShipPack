package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.ValueShifterAPI;
import java.util.*;
import java.util.function.Supplier;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lazywizard.lazylib.combat.CombatUtils;

/**
 * Owned, command-driven WR nose impact. Native collision, shields and armor remain enabled.
 * The actual frame11 attachment supplies an audited sprite landmark; no energy prow is made.
 * Exact native hull/shield sweeps prevent this source from tunnelling, then a bounded PUSH
 * phase keeps forward thrust for at most five seconds. Only source contact resolution may
 * correct its current integrated segment. A target is never relocated or assigned a facing.
 * Damage uses actual world speed plus one real entry hit in fixed 0.1-second native HE pulses;
 * no raw armor-grid writes, EMP, projectile, duplicate Mod callbacks or engine stepping.
 */
public final class ZetaShieldRam implements AutoCloseable {
    public static final String KEY="xi.arsenal.zetaShieldRam";
    public static final float MAX_DISTANCE=1800f,MAX_DASH_SECONDS=5f,MAX_PUSH_SECONDS=5f;
    public static final float REFERENCE_SPEED=2200f,MIN_DPS=60f,FULL_DPS=1800f,DAMAGE_STEP=.1f,MAX_IMPACT_DAMAGE=1200f;
    public static final float CONTACT_SLOP=4f,CONTACT_LOST_GRACE=.18f;
    public static final float SPEED_BONUS=2000f,ACCEL_BONUS=6000f,BRAKE_BONUS=6000f;
    public static final float ALIGN_SECONDS=3f,BRAKE_SECONDS=1.5f,ALIGN_TURN_BONUS=360f,ALIGN_TURN_ACCEL_BONUS=1440f;
    public static final float ENGINE_CHARGE_SECONDS=.3f;
    // Audited MSZ_006_backpack_11.png (118x220), foremost opaque physical centreline pixel.
    // PNG uses top-left coordinates; SpriteAPI local up uses the opposite Y direction.
    public static final float WR_IMAGE_WIDTH=118f,WR_IMAGE_HEIGHT=220f,WR_NOSE_PIXEL_X=59f,WR_NOSE_PIXEL_Y=11f;
    private static final int MAX_CANDIDATES=512,MAX_WEAPONS=256;
    public enum Phase { IDLE,ALIGN,DASH,PUSH,BRAKE,CLOSED }
    public record StartResult(boolean accepted,String reason){}
    private record Contact(ShipAPI target,Vector2f point,boolean shield,float parameter){}
    private final CombatEngineAPI engine;
    private final ShipAPI ship;
    private final Supplier<Vector2f> nose;
    private final boolean rearShieldFacing;
    private final boolean ownedPhysicalNose;
    private final Object flameKey=new Object();
    private final Thread owner=Thread.currentThread();
    private final String modifier=KEY+"."+Integer.toUnsignedString(System.identityHashCode(this));
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private final Map<String,Object> view=Collections.unmodifiableMap(facts);
    private final IdentityHashMap<ShipAPI,Vector2f> previousTargets=new IdentityHashMap<>();
    private final Vector2f start=new Vector2f(),direction=new Vector2f(),previousNose=new Vector2f(),previousRoot=new Vector2f(),aimPoint=new Vector2f();
    private Phase phase=Phase.IDLE;
    private String reason="idle",contactKind="",contactTarget="";
    private float distance,phaseTime,dashTime,travelled,lastDashForwardSpeed;
    private ShipAPI pushedTarget;
    private Contact damageContact;
    private float pushTime,contactLostTime,driveSpeed,previousActualDamageSpeed,thrustBudgetSpeed,queuedImpactDamage,pendingDamage,pendingDamageTime,totalDamage,lastPushFactor,lastPushAcceleration;
    private long forceApplications;
    private float contactX,contactY,collisionToi=-1;
    private List<Float> resolutionFrom=List.of(),resolutionTo=List.of(),resolvedPoint=List.of();
    private long starts,rejected,contacts,nativeHits,contactStopWrites,resolvedCollisions,scans,budgetHits;
    private boolean resolvedCollision;
    private boolean modified,braking,aligning,closed;
    private boolean flameActive,engineFlameAvailable;
    private float flameLength,flameWidth,flameGlow;
    private int enginePlumeCount;
    private float visualPlumeLength,peakVisualLength;
    private List<Map<String,Object>> renderedEnginePlumes=List.of();

    public ZetaShieldRam(CombatEngineAPI engine,ShipAPI ship){this(engine,ship,()->shieldNose(ship),false,true);}
    /** Explicit attachment adapters retain their supplied physical geometry. */
    public ZetaShieldRam(CombatEngineAPI engine,ShipAPI ship,Supplier<Vector2f> nose){this(engine,ship,nose,false,false);}
    public ZetaShieldRam(CombatEngineAPI engine,ShipAPI ship,Supplier<Vector2f> nose,boolean rearShieldFacing){
        this(engine,ship,nose,rearShieldFacing,false);
    }
    private ZetaShieldRam(CombatEngineAPI engine,ShipAPI ship,Supplier<Vector2f> nose,boolean rearShieldFacing,boolean ownedPhysicalNose){
        this.engine=Objects.requireNonNull(engine);this.ship=Objects.requireNonNull(ship);this.nose=Objects.requireNonNull(nose);
        this.rearShieldFacing=rearShieldFacing;this.ownedPhysicalNose=ownedPhysicalNose;
        if(ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Zeta ram already owned");
        ship.setCustomData(KEY+".owner",this);ship.setCustomData(KEY,view);publish();
    }
    /** Physical frame11 landmark, transformed once by the actual current native attachment. */
    public static Vector2f shieldNose(ShipAPI source){return physicalNose(source);}
    public static Vector2f physicalNose(ShipAPI source){
        if(source==null)return null;int checked=0;for(WeaponAPI backpack:source.getAllWeapons()){
            if(checked++>=MAX_WEAPONS)break;
            if(backpack==null||!"Moci_MSZ_006_backpack".equals(backpack.getId())||backpack.getSlot()==null
                    ||!"BACKPACK".equals(backpack.getSlot().getId())||backpack.getAnimation()==null||backpack.getAnimation().getFrame()!=11)continue;
            SpriteAPI sprite=backpack.getSprite();Vector2f center=backpack.getLocation();
            if(sprite==null||!finite(center)||!Float.isFinite(backpack.getCurrAngle())||sprite.getWidth()<=0||sprite.getHeight()<=0)return null;
            float right=WR_NOSE_PIXEL_X/WR_IMAGE_WIDTH*sprite.getWidth()-sprite.getCenterX();
            float up=(1-WR_NOSE_PIXEL_Y/WR_IMAGE_HEIGHT)*sprite.getHeight()-sprite.getCenterY();
            double a=Math.toRadians(backpack.getCurrAngle());float cos=(float)Math.cos(a),sin=(float)Math.sin(a);
            return new Vector2f(center.x+up*cos+right*sin,center.y+up*sin-right*cos);
        }return null;
    }
    /** Public calibration point in original PNG top-left pixels; no extra forward clearance. */
    public static Map<String,Object> noseLandmark(){return Map.of("sprite","MSZ_006_backpack_11.png","imageWidth",WR_IMAGE_WIDTH,"imageHeight",WR_IMAGE_HEIGHT,"pixelX",WR_NOSE_PIXEL_X,"pixelY",WR_NOSE_PIXEL_Y,"extraClearance",0);}
    public StartResult start(Vector2f point){
        requireOwner();if(closed)return reject("closed");if(phase!=Phase.IDLE)return reject("busy");
        if(!liveSource()||ship.isPhased())return reject("source-unavailable");
        if(!finite(point))return reject("invalid-point");Vector2f tip=nose.get();if(!finite(tip))return reject("physical-nose-unavailable");
        float dx=point.x-tip.x,dy=point.y-tip.y,len=(float)Math.hypot(dx,dy);if(len<20)return reject("point-too-close");
        start.set(tip);previousNose.set(tip);previousRoot.set(ship.getLocation());direction.set(dx/len,dy/len);distance=Math.min(MAX_DISTANCE,len);
        aimPoint.set(tip.x+direction.x*distance,tip.y+direction.y*distance);travelled=0;dashTime=0;lastDashForwardSpeed=0;pushTime=contactLostTime=driveSpeed=previousActualDamageSpeed=thrustBudgetSpeed=queuedImpactDamage=pendingDamage=pendingDamageTime=totalDamage=0;
        pushedTarget=null;damageContact=null;lastPushFactor=lastPushAcceleration=0;
        contactKind="";contactTarget="";resolvedCollision=false;collisionToi=-1;resolutionFrom=List.of();resolutionTo=List.of();resolvedPoint=List.of();previousTargets.clear();snapshotTargets();phaseTime=0;phase=Phase.ALIGN;
        enginePlumeCount=0;visualPlumeLength=peakVisualLength=0;renderedEnginePlumes=List.of();
        reason="awaiting-allowed-alignment";starts++;publish();return new StartResult(true,reason);
    }
    private StartResult reject(String why){rejected++;reason=why;publish();return new StartResult(false,why);}
    public void advance(float dt,boolean allowed){
        requireOwner();if(closed)return;if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid Zeta ram dt");
        if(engine.isPaused()||dt==0)return;
        if(phase==Phase.IDLE){publish();return;}
        if(!liveSource()){clearModifiers();finish("source-unavailable");publish();return;}
        if(!allowed||ship.isPhased()){beginBrake("state-no-longer-allowed");}
        phaseTime+=dt;
        if(phase==Phase.BRAKE){brake();if(ship.getVelocity().length()<=8||phaseTime>=BRAKE_SECONDS)finish(reason);publish();return;}
        Vector2f tip=nose.get();if(!finite(tip)){beginBrake("physical-nose-unavailable");brake();publish();return;}
        if(phase==Phase.ALIGN){
            applyAlignAssist();
            applyEngineCharge(false);
            // Alignment may swing the real attachment nose around the root. Keep the captured aim
            // position fixed, and settle its final straight direction from the actual nose.
            float aimDx=aimPoint.x-tip.x,aimDy=aimPoint.y-tip.y,aimDistance=(float)Math.hypot(aimDx,aimDy);
            if(aimDistance>8)direction.set(aimDx/aimDistance,aimDy/aimDistance);
            brake();turn();previousNose.set(tip);previousRoot.set(ship.getLocation());
            if(!allowed){publish();return;}
            if(phaseTime>ALIGN_SECONDS){beginBrake("alignment-timeout");}
            else if(phaseTime>=ENGINE_CHARGE_SECONDS&&Math.abs(angleError())<=6&&Math.abs(ship.getAngularVelocity())<=20){
                float remaining=(aimPoint.x-tip.x)*direction.x+(aimPoint.y-tip.y)*direction.y;
                if(remaining<=8){beginBrake("destination-already-reached-during-alignment");}
                else{distance=Math.min(MAX_DISTANCE,remaining);start.set(tip);clearAlignAssist();applyModifiers();phase=Phase.DASH;phaseTime=0;reason="straight-dash";
                    previousNose.set(tip);previousRoot.set(ship.getLocation());previousTargets.clear();snapshotTargets();}
            }
        }else if(phase==Phase.DASH){
            applyEngineCharge(true);
            dashTime+=dt;Contact contact=findContact(previousNose,tip,dt);
            if(contact!=null){onContact(contact,dt*(1-contact.parameter));}
            else if(phase==Phase.DASH){
                lastDashForwardSpeed=forwardSpeed(ship);
                travelled=Math.max(travelled,(tip.x-start.x)*direction.x+(tip.y-start.y)*direction.y);
                float remaining=distance-travelled;
                float speedAlong=Math.max(0,ship.getVelocity().x*direction.x+ship.getVelocity().y*direction.y);
                float stopping=speedAlong*speedAlong/(2*Math.max(1,ship.getDeceleration()));
                if(dashTime>=MAX_DASH_SECONDS)beginBrake("dash-timeout");
                else if(remaining<=Math.max(8,stopping))beginBrake("destination-brake");
                else drive(tip);
            }
            rememberPhysicalFrame();
        }else if(phase==Phase.PUSH){
            applyEngineCharge(true);advancePush(dt);
            rememberPhysicalFrame();
        }
        if(phase==Phase.BRAKE){brake();if(ship.getVelocity().length()<=8||phaseTime>=BRAKE_SECONDS)finish(reason);}
        publish();
    }
    private void snapshotTargets(){int checked=0;for(ShipAPI target:engine.getShips()){
        if(checked++>=MAX_CANDIDATES){budgetHits++;break;}if(target!=null&&target!=ship)previousTargets.put(target,new Vector2f(target.getLocation()));}}
    private Contact findContact(Vector2f from,Vector2f to,float dt){
        CollisionGridAPI grid=engine.getShipGrid();Iterator<?> candidates;
        if(grid!=null){Vector2f center=new Vector2f((from.x+to.x)*.5f,(from.y+to.y)*.5f);
            candidates=grid.getCheckIterator(center,Math.abs(to.x-from.x)+32,Math.abs(to.y-from.y)+32);
        }else candidates=engine.getShips().iterator();
        Contact earliest=null;Set<ShipAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());int checked=0;
        IdentityHashMap<ShipAPI,Vector2f> nextTargets=new IdentityHashMap<>();
        while(candidates.hasNext()){
            if(checked++>=MAX_CANDIDATES){budgetHits++;beginBrake("collision-scan-budget");previousTargets.clear();return null;}
            Object entity=candidates.next();if(!(entity instanceof ShipAPI target)||!seen.add(target)||!collisionTarget(target))continue;scans++;
            Vector2f current=target.getLocation(),old=previousTargets.get(target);Vector2f relativeStart=new Vector2f(from);
            if(old!=null){relativeStart.x+=current.x-old.x;relativeStart.y+=current.y-old.y;}
            nextTargets.put(target,new Vector2f(current));
            Vector2f hull=target.checkCollisionVsRay(relativeStart,to);
            if(hull==null&&target.isPointInBounds(relativeStart))hull=new Vector2f(relativeStart);
            if(hull==null&&target.isPointInBounds(to))hull=new Vector2f(to);
            Contact hit=null;
            if(finite(hull)){float t=parameter(relativeStart,to,hull);if(t>=0&&t<=1)hit=new Contact(target,new Vector2f(hull),false,t);}
            ShieldAPI shield=target.getShield();Vector2f shieldHit=shieldIntersection(relativeStart,to,shield);
            if(shieldHit!=null){float t=parameter(relativeStart,to,shieldHit);if(hit==null||t<=hit.parameter)hit=new Contact(target,shieldHit,true,t);}
            if(hit!=null){float t=hit.parameter;
                float progress=(from.x+(to.x-from.x)*t-start.x)*direction.x+(from.y+(to.y-from.y)*t-start.y)*direction.y;
                if(phase==Phase.DASH&&(progress>distance+.01f||dashTime-dt+dt*t>MAX_DASH_SECONDS+.0001f))hit=null;
            }
            if(hit!=null&&(earliest==null||hit.parameter<earliest.parameter))earliest=hit;
        }
        previousTargets.clear();previousTargets.putAll(nextTargets);return earliest;
    }
    private boolean collisionTarget(ShipAPI target){
        if(target==ship||target.wasRemoved()||target.isPhased()||!engine.isEntityInPlay(target)||target.getCollisionClass()==CollisionClass.NONE)return false;
        if(!target.isAlive()&&!target.isHulk())return false;
        ShipAPI root=target;for(int depth=0;depth<16&&root.getParentStation()!=null&&root.getParentStation()!=root;depth++)root=root.getParentStation();
        return root!=ship;
    }
    static Vector2f shieldIntersection(Vector2f from,Vector2f to,ShieldAPI shield){
        if(shield==null||!shield.isOn()||shield.getRadius()<=0)return null;
        Vector2f center=shield.getLocation();if(!finite(center))return null;
        float dx=to.x-from.x,dy=to.y-from.y,ox=from.x-center.x,oy=from.y-center.y,r=shield.getRadius();
        float aa=dx*dx+dy*dy,cc=ox*ox+oy*oy-r*r;
        if(cc<=0&&shield.isWithinArc(from))return new Vector2f(from);
        if(aa<.000001f)return null;float bb=2*(ox*dx+oy*dy),discriminant=bb*bb-4*aa*cc;if(discriminant<0)return null;
        float root=(float)Math.sqrt(discriminant);float[] intersections={(-bb-root)/(2*aa),(-bb+root)/(2*aa)};
        for(float t:intersections)if(t>=0&&t<=1){Vector2f point=new Vector2f(from.x+t*dx,from.y+t*dy);if(shield.isWithinArc(point))return point;}
        return null;
    }
    static float parameter(Vector2f from,Vector2f to,Vector2f point){float dx=to.x-from.x,dy=to.y-from.y,denom=dx*dx+dy*dy;
        return denom<.000001f?0:((point.x-from.x)*dx+(point.y-from.y)*dy)/denom;}
    private void rememberPhysicalFrame(){Vector2f current=nose.get();if(finite(current))previousNose.set(current);previousRoot.set(ship.getLocation());}
    private boolean hostile(ShipAPI target){return ship.getOwner()>=0&&ship.getOwner()<=1&&target.getOwner()>=0&&target.getOwner()<=1&&ship.getOwner()!=target.getOwner();}
    private void onContact(Contact contact,float contactSeconds){
        contacts++;pushedTarget=contact.target;contactX=contact.point.x;contactY=contact.point.y;contactTarget=Objects.toString(contact.target.getId(),"");
        boolean enemy=hostile(contact.target);contactKind=enemy?(contact.shield?"enemy-shield":"enemy-hull"):"friendly-or-neutral-block";
        // Native collision may have already constrained this frame's world velocity.
        // Retain the preceding dash speed, then subtract the target's same-direction speed.
        float observed=Math.max(0,Math.max(lastDashForwardSpeed,forwardSpeed(ship))-forwardSpeedSigned(physicalRoot(contact.target)));
        queuedImpactDamage=impactDamage(observed);thrustBudgetSpeed=Math.min(REFERENCE_SPEED,observed);
        resolveSource(contact);driveSpeed=previousActualDamageSpeed=boundedSpeed(forwardSpeed(ship));
        if(!enemy||!contact.target.isAlive()||contact.target.isHulk()){beginBrake(contactKind);return;}
        phase=Phase.PUSH;phaseTime=pushTime=contactLostTime=0;reason="nose-contact-push";
        applyModifiers();drive(nose.get());
        float slice=Math.min(MAX_PUSH_SECONDS,Math.max(0,contactSeconds));pushTime+=slice;
        applyContactInterval(contact,slice);applyPushForce(contact.target,slice);
        if(pushTime>=MAX_PUSH_SECONDS-.00001f)beginBrake("push-complete");
    }
    private void advancePush(float dt){
        if(pushedTarget==null||!collisionTarget(pushedTarget)||!pushedTarget.isAlive()||pushedTarget.isHulk()||!hostile(pushedTarget)){beginBrake("contact-target-unavailable");return;}
        float step=Math.min(dt,Math.max(0,MAX_PUSH_SECONDS-pushTime));pushTime+=step;
        Vector2f tip=nose.get();if(!finite(tip)){beginBrake("physical-nose-unavailable");return;}
        // The four-unit ray covers only the two-unit CCD separation and numeric tolerance;
        // there is no forward visual shell and no contact damage across a genuine gap.
        Vector2f probe=new Vector2f(tip.x+direction.x*CONTACT_SLOP,tip.y+direction.y*CONTACT_SLOP);
        Contact contact=findContact(previousNose,probe,step);
        if(phase!=Phase.PUSH)return;
        if(contact!=null&&contact.target!=pushedTarget){beginBrake("push-blocked-by-another-body");return;}
        drive(tip);
        if(contact!=null){
            contactLostTime=0;contactX=contact.point.x;contactY=contact.point.y;
            contactKind=contact.shield?"enemy-shield":"enemy-hull";
            // Resolve only an actual integrated sweep, never the short static probe extension.
            Contact swept=findContact(previousNose,tip,step);if(swept!=null&&swept.target==pushedTarget)resolveSource(swept);
            applyContactInterval(contact,step);applyPushForce(contact.target,step);
        }else{contactLostTime+=step;lastPushFactor=lastPushAcceleration=0;if(contactLostTime>=CONTACT_LOST_GRACE)beginBrake("nose-contact-lost");}
        if(phase==Phase.PUSH&&pushTime>=MAX_PUSH_SECONDS-.00001f)beginBrake("push-complete");
    }
    /** Resolve this source's proven current segment; target coordinates and facing stay native. */
    private void resolveSource(Contact contact){
        collisionToi=contact.parameter;Vector2f actual=ship.getLocation();float moveX=actual.x-previousRoot.x,moveY=actual.y-previousRoot.y,moveLength=(float)Math.hypot(moveX,moveY);
        resolutionFrom=List.of(previousRoot.x,previousRoot.y);resolutionTo=List.of(actual.x,actual.y);
        if(contact.parameter<.99999f&&moveLength>.5f){float resolveT=Math.max(0,contact.parameter-2f/moveLength);
            float x=previousRoot.x+moveX*resolveT,y=previousRoot.y+moveY*resolveT;
            actual.set(x,y);resolvedCollision=true;resolvedCollisions++;resolvedPoint=List.of(x,y);
        }
        Vector2f velocity=ship.getVelocity();float targetAlong=forwardSpeedSigned(physicalRoot(contact.target));
        float closing=velocity.x*direction.x+velocity.y*direction.y-targetAlong;
        if(closing>0){velocity.x-=direction.x*closing;velocity.y-=direction.y*closing;contactStopWrites++;}
    }
    private float forwardSpeed(ShipAPI source){return Math.max(0,forwardSpeedSigned(source));}
    private float forwardSpeedSigned(ShipAPI source){Vector2f v=source.getVelocity();return finite(v)?v.x*direction.x+v.y*direction.y:0;}
    private ShipAPI physicalRoot(ShipAPI target){ShipAPI root=target;for(int i=0;i<16&&root.getParentStation()!=null&&root.getParentStation()!=root;i++)root=root.getParentStation();return root;}
    /** 0-speed rubbing is120 DPS;2200 forward/drive speed is7000DPS, quadratic in between. */
    public static float damagePerSecond(float speed){float ratio=boundedSpeed(speed)/REFERENCE_SPEED;return MIN_DPS+(FULL_DPS-MIN_DPS)*ratio*ratio;}
    private static float boundedSpeed(float speed){return Float.isFinite(speed)?Math.max(0,Math.min(REFERENCE_SPEED,speed)):0;}
    /** One real entry-energy hit, queued once in the first native damage pulse. */
    public static float impactDamage(float relativeEntrySpeed){float ratio=boundedSpeed(relativeEntrySpeed)/REFERENCE_SPEED;return MAX_IMPACT_DAMAGE*ratio*ratio;}
    /** Exact quadratic integral between two observed actual world-speed endpoints. */
    public static float damageOverActualSpeedInterval(float fromSpeed,float toSpeed,float seconds){
        if(!Float.isFinite(seconds)||seconds<0||seconds>MAX_PUSH_SECONDS)throw new IllegalArgumentException("Invalid contact interval");
        double a=boundedSpeed(fromSpeed),b=boundedSpeed(toSpeed);
        return (float)(seconds*(MIN_DPS+(FULL_DPS-MIN_DPS)*(a*a+a*b+b*b)/(3*REFERENCE_SPEED*REFERENCE_SPEED)));
    }
    private void applyContactInterval(Contact contact,float seconds){
        if(seconds<=0)return;damageContact=contact;
        // Damage samples actual post-collision world motion. Thrust may continue against a
        // stationary capital ship, but it must never manufacture full-speed damage there.
        float endSpeed=boundedSpeed(forwardSpeed(ship)),startSpeed=previousActualDamageSpeed,elapsed=0,remaining=seconds;
        int pulses=0;
        while(remaining>.000001f&&pulses++<=51){float part=Math.min(remaining,DAMAGE_STEP-pendingDamageTime);
            float from=startSpeed+(endSpeed-startSpeed)*(elapsed/seconds),to=startSpeed+(endSpeed-startSpeed)*((elapsed+part)/seconds);
            pendingDamage+=damageOverActualSpeedInterval(from,to,part);pendingDamageTime+=part;remaining-=part;elapsed+=part;
            if(pendingDamageTime>=DAMAGE_STEP-.000001f)flushDamage();
        }
        driveSpeed=previousActualDamageSpeed=endSpeed;
        // This separate budget only controls available pushing force; it is never used by
        // damagePerSecond, damage integration or the one-time real entry-energy calculation.
        float acceleration=ship.getAcceleration();if(!Float.isFinite(acceleration)||acceleration<0)acceleration=0;
        thrustBudgetSpeed=Math.min(REFERENCE_SPEED,thrustBudgetSpeed+acceleration*.2f*seconds);
    }
    private void flushDamage(){
        if((pendingDamageTime>.00001f||queuedImpactDamage>0)&&(pendingDamage>0||queuedImpactDamage>0)&&damageContact!=null&&damageContact.target.isAlive()&&!damageContact.target.isHulk()&&engine.isEntityInPlay(damageContact.target)){
            float amount=pendingDamage+queuedImpactDamage;nativeHits++;totalDamage+=amount;
            engine.applyDamage(this,damageContact.target,new Vector2f(damageContact.point),amount,DamageType.HIGH_EXPLOSIVE,0,false,false,ship,true);
        }pendingDamage=pendingDamageTime=queuedImpactDamage=0;
    }
    /** Size and mass both reduce thrust transfer; capital ships/stations are never actively pushed. */
    public static float pushFactor(float sourceMass,float targetMass,ShipAPI.HullSize size){
        if(!Float.isFinite(sourceMass)||!Float.isFinite(targetMass)||sourceMass<=0||targetMass<=0||size==null)return 0;
        float hull=switch(size){case FIGHTER,FRIGATE->1f;case DESTROYER->.65f;case CRUISER->.22f;default->0f;};
        return hull*Math.min(1,2*sourceMass/(sourceMass+targetMass));
    }
    public static float pushAcceleration(float sourceMass,float targetMass,ShipAPI.HullSize size){return 500f*pushFactor(sourceMass,targetMass,size);}
    private void applyPushForce(ShipAPI target,float dt){
        if(dt<=0)return;ShipAPI body=physicalRoot(target);float mass=body.getMass();
        lastPushFactor=pushFactor(ship.getMass(),mass,body.getHullSize());lastPushAcceleration=500f*lastPushFactor;
        if(lastPushFactor<=0)return;
        float desired=Math.min(650f*lastPushFactor,thrustBudgetSpeed*.55f),current=forwardSpeedSigned(body);
        float delta=Math.min(lastPushAcceleration*dt,Math.max(0,desired-current));if(delta<=0)return;
        // LazyLib's public force helper applies impulse to velocity using native mass. No
        // target position/facing, collision, mass, AI or max-speed property is overwritten.
        CombatUtils.applyForce(body,new Vector2f(direction),delta*Math.max(1,mass)/100f);forceApplications++;
    }
    private void drive(Vector2f tip){
        turn();Vector2f velocity=ship.getVelocity();float speed=Math.max(1,ship.getMaxSpeed())*.96f;
        float lateral=(tip.x-start.x)*(-direction.y)+(tip.y-start.y)*direction.x;
        float correction=Math.max(-180,Math.min(180,-lateral*3));float ex=direction.x*speed-direction.y*correction-velocity.x,ey=direction.y*speed+direction.x*correction-velocity.y;
        double a=Math.toRadians(ship.getFacing());float forward=(float)(ex*Math.cos(a)+ey*Math.sin(a)),left=(float)(-ex*Math.sin(a)+ey*Math.cos(a));
        if(forward>5){ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);ship.giveCommand(ShipCommand.ACCELERATE,null,0);}
        else if(forward< -5){ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS,null,0);}
        if(left>5){ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);ship.giveCommand(ShipCommand.STRAFE_LEFT,null,0);}
        else if(left< -5){ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);ship.giveCommand(ShipCommand.STRAFE_RIGHT,null,0);}
    }
    private float angleError(){return normalize((float)Math.toDegrees(Math.atan2(direction.y,direction.x))+(rearShieldFacing?180:0)-ship.getFacing());}
    private void turn(){float error=angleError();float desired=Math.copySign(Math.min(Math.max(1,ship.getMaxTurnRate()),(float)Math.sqrt(2*Math.max(1,ship.getTurnDeceleration())*Math.abs(error))),error);
        if(desired-ship.getAngularVelocity()>1){ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);ship.giveCommand(ShipCommand.TURN_LEFT,null,0);}
        else if(desired-ship.getAngularVelocity()< -1){ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT);ship.giveCommand(ShipCommand.TURN_RIGHT,null,0);}}
    private void brake(){ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);ship.giveCommand(ShipCommand.DECELERATE,null,0);}
    private void applyEngineCharge(boolean dash){
        ShipEngineControllerAPI engines=ship.getEngineController();engineFlameAvailable=engines!=null;if(engines==null)return;
        float charge=Math.max(0,Math.min(1,phaseTime/ENGINE_CHARGE_SECONDS));
        flameLength=dash?2.8f:.6f+charge*.8f;flameWidth=dash?1.9f:.4f+charge;
        flameGlow=dash?1.7f:.4f+charge*.8f;
        // Native extendFlame uses one-second fades. The same public shifters allow a visible
        // short charge while the original engine controller still advances them exactly once.
        shiftFlame(engines.getExtendLengthFraction(),flameLength);
        shiftFlame(engines.getExtendWidthFraction(),flameWidth);
        shiftFlame(engines.getExtendGlowFraction(),flameGlow);
        engines.forceShowAccelerating();flameActive=true;
    }
    private void shiftFlame(ValueShifterAPI shifter,float amount){if(shifter!=null)shifter.shift(flameKey,shifter.getBase()+amount,.08f,.15f,1f);}
    private void clearEngineCharge(){
        if(!flameActive)return;ShipEngineControllerAPI engines=ship.getEngineController();
        if(engines!=null)for(ValueShifterAPI shifter:new ValueShifterAPI[]{engines.getExtendLengthFraction(),engines.getExtendWidthFraction(),engines.getExtendGlowFraction()})
            if(shifter!=null)shifter.shift(flameKey,shifter.getBase(),0f,0f,0f);
        flameActive=false;flameLength=flameWidth=flameGlow=0;
    }
    /** Pure presentation. The caller renders this after the normal WR sprite and its coating. */
    public void renderWorld(ViewportAPI viewport){
        requireOwner();
        enginePlumeCount=0;visualPlumeLength=0;renderedEnginePlumes=List.of();
        if(closed||viewport==null||!liveSource()||ship.isPhased()||(phase!=Phase.ALIGN&&phase!=Phase.DASH&&phase!=Phase.PUSH))return;
        Vector2f tip=nose.get();if(!finite(tip)||!viewport.isNearViewport(tip,400))return;
        float charge=phase==Phase.DASH||phase==Phase.PUSH?1:Math.max(.2f,Math.min(1,phaseTime/ENGINE_CHARGE_SECONDS));
        float alpha=charge*Math.max(0,Math.min(1,viewport.getAlphaMult()));
        double a=Math.toRadians(ship.getFacing());float fx=(float)Math.cos(a),fy=(float)Math.sin(a),sx=-fy,sy=fx;
        int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),texture=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try{
            GL20.glUseProgram(0);GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE);
            List<Map<String,Object>> rendered=new ArrayList<>();
            for(EnginePlume plume:exhaustPlumes(ship,phase,Math.max(0,Math.min(1,phaseTime/ENGINE_CHARGE_SECONDS)))){
                if(!viewport.isNearViewport(new Vector2f(plume.x,plume.y),plume.length+plume.width))continue;
                drawEnginePlume(plume,alpha);enginePlumeCount++;visualPlumeLength=Math.max(visualPlumeLength,plume.length);
                peakVisualLength=Math.max(peakVisualLength,plume.length);
                rendered.add(Map.of("engineIndex",plume.engineIndex,"x",plume.x,"y",plume.y,"angle",plume.angle,"length",plume.length,"width",plume.width));
            }
            renderedEnginePlumes=List.copyOf(rendered);
        }finally{GL20.glUseProgram(program);GL13.glActiveTexture(texture);GL11.glPopAttrib();}
    }
    /** Immutable render geometry sampled exclusively from real native engine instances. */
    public record EnginePlume(int engineIndex,float x,float y,float angle,float length,float width) { }
    public static List<EnginePlume> exhaustPlumes(ShipAPI source,Phase phase,float chargeLevel){
        if(source==null||(phase!=Phase.ALIGN&&phase!=Phase.DASH&&phase!=Phase.PUSH))return List.of();
        ShipEngineControllerAPI controller=source.getEngineController();if(controller==null)return List.of();
        List<ShipEngineControllerAPI.ShipEngineAPI> engines=controller.getShipEngines();if(engines==null)return List.of();
        List<EnginePlume> result=new ArrayList<>();float charge=Float.isFinite(chargeLevel)?Math.max(0,Math.min(1,chargeLevel)):0;
        int index=0;for(ShipEngineControllerAPI.ShipEngineAPI engine:engines){
            if(index>=32)break;int engineIndex=index++;if(engine==null||engine.isDisabled()||engine.isPermanentlyDisabled())continue;
            EngineSlotAPI slot=engine.getEngineSlot();Vector2f point=engine.getLocation();if(slot==null||!finite(point))continue;
            float angle=slot.computeMidArcAngle(source.getFacing());if(!Float.isFinite(angle))continue;
            // These are charge jets from available main rear nozzles, before propulsion begins.
            // Native isActive can be false while ALIGN deliberately issues DECELERATE.
            if(Math.cos(Math.toRadians(angle-source.getFacing()))>=-.25)continue;
            float nativeWidth=slot.getWidth();if(!Float.isFinite(nativeWidth)||nativeWidth<=0)continue;
            float scale=Math.max(.8f,Math.min(1.25f,nativeWidth/4f));
            result.add(new EnginePlume(engineIndex,point.x,point.y,angle,phase==Phase.DASH||phase==Phase.PUSH?215f:45f+55f*charge,
                    (phase==Phase.DASH||phase==Phase.PUSH?26f:8f+8f*charge)*scale));
        }
        return List.copyOf(result);
    }
    private static void drawEnginePlume(EnginePlume plume,float alpha){
        double a=Math.toRadians(plume.angle);float fx=(float)Math.cos(a),fy=(float)Math.sin(a),sx=-fy,sy=fx;
        // Radial bands fade to transparent edges; longitudinal width and alpha both taper.
        // No hard beam rectangle, particles, physics entity or extra damage is created.
        float[] bands={-1f,-.62f,-.22f,0f,.22f,.62f,1f};
        for(int band=0;band<bands.length-1;band++){
            GL11.glBegin(GL11.GL_QUAD_STRIP);
            for(int i=0;i<=24;i++){
                float t=i/24f,shape=(.45f+.75f*(float)Math.sin(Math.PI*t))*(float)Math.pow(1-t,.4f);
                float half=plume.width*.5f*shape,fade=(float)Math.pow(1-t,1.55f)*alpha;
                for(int edge=0;edge<2;edge++){
                    float radial=bands[band+edge],abs=Math.abs(radial),strength=abs>=.99f?0:abs>.5f?.35f:abs>.1f?.85f:1;
                    GL11.glColor4f(abs>.5f?.62f:1f,abs>.5f?.16f:.82f,1f,fade*strength*.9f);
                    float side=half*radial;
                    GL11.glVertex2f(plume.x+fx*plume.length*t+sx*side,plume.y+fy*plume.length*t+sy*side);
                }
            }
            GL11.glEnd();
        }
    }
    private void applyModifiers(){if(modified)return;modified=true;MutableShipStatsAPI stats=ship.getMutableStats();stats.getMaxSpeed().modifyFlat(modifier,SPEED_BONUS);stats.getAcceleration().modifyFlat(modifier,ACCEL_BONUS);stats.getDeceleration().modifyFlat(modifier,BRAKE_BONUS);}
    private void applyAlignAssist(){if(aligning)return;aligning=true;MutableShipStatsAPI stats=ship.getMutableStats();stats.getMaxTurnRate().modifyFlat(modifier,ALIGN_TURN_BONUS);stats.getTurnAcceleration().modifyFlat(modifier,ALIGN_TURN_ACCEL_BONUS);}
    private void clearAlignAssist(){if(!aligning)return;aligning=false;MutableShipStatsAPI stats=ship.getMutableStats();stats.getMaxTurnRate().unmodify(modifier);stats.getTurnAcceleration().unmodify(modifier);}
    private void clearModifiers(){clearAlignAssist();if(!modified&&!braking)return;modified=false;braking=false;MutableShipStatsAPI stats=ship.getMutableStats();stats.getMaxSpeed().unmodify(modifier);stats.getAcceleration().unmodify(modifier);stats.getDeceleration().unmodify(modifier);}
    private void beginBrake(String why){flushDamage();if(phase!=Phase.BRAKE){phase=Phase.BRAKE;phaseTime=0;}reason=why;clearModifiers();
        clearEngineCharge();
        // Cancel speed/acceleration immediately; keep only a finite owned braking aid until the
        // real DECELERATE command stops the ship. Never clamp velocity on a miss or cancellation.
        ship.getMutableStats().getDeceleration().modifyFlat(modifier,BRAKE_BONUS);braking=true;}
    private void finish(String why){clearModifiers();clearEngineCharge();phase=Phase.IDLE;phaseTime=0;reason=why;previousTargets.clear();pushedTarget=null;damageContact=null;}
    public void cancel(String why){requireOwner();if(closed||phase==Phase.IDLE)return;beginBrake(Objects.requireNonNull(why));brake();publish();}
    public boolean active(){requireOwner();return phase==Phase.ALIGN||phase==Phase.DASH||phase==Phase.PUSH||phase==Phase.BRAKE;}
    public Map<String,Object> status(){requireOwner();publish();return Collections.unmodifiableMap(new LinkedHashMap<>(facts));}
    private void publish(){facts.put("schema",1);facts.put("phase",phase.name());facts.put("closed",closed);facts.put("reason",reason);facts.put("starts",starts);facts.put("rejected",rejected);facts.put("contacts",contacts);facts.put("nativeHits",nativeHits);facts.put("contactStopWrites",contactStopWrites);facts.put("scans",scans);facts.put("budgetHits",budgetHits);
        facts.put("rearShieldFacing",rearShieldFacing);facts.put("noseSource",ownedPhysicalNose?"physical WR frame11 sprite landmark":"explicit attachment supplier");
        Vector2f tip=nose.get();facts.put("noseX",finite(tip)?tip.x:0f);facts.put("noseY",finite(tip)?tip.y:0f);facts.put("noseAvailable",finite(tip));
        facts.put("noseLandmark",noseLandmark());facts.put("extraForwardClearance",0f);facts.put("energyProwRendered",false);
        facts.put("engineChargeSeconds",ENGINE_CHARGE_SECONDS);facts.put("pushSeconds",pushTime);facts.put("maxPushSeconds",MAX_PUSH_SECONDS);
        facts.put("driveSpeed",driveSpeed);facts.put("actualDamageSpeed",driveSpeed);facts.put("lastDashForwardSpeed",lastDashForwardSpeed);facts.put("thrustBudgetSpeed",thrustBudgetSpeed);facts.put("queuedImpactDamage",queuedImpactDamage);facts.put("currentDps",damagePerSecond(driveSpeed));facts.put("totalDamage",totalDamage);
        facts.put("pendingDamage",pendingDamage);facts.put("damageStep",DAMAGE_STEP);facts.put("contactLostSeconds",contactLostTime);
        facts.put("pushFactor",lastPushFactor);facts.put("pushAcceleration",lastPushAcceleration);facts.put("forceApplications",forceApplications);
        facts.put("engineChargeProgress",phase==Phase.ALIGN?Math.max(0,Math.min(1,phaseTime/ENGINE_CHARGE_SECONDS)):phase==Phase.DASH||phase==Phase.PUSH?1f:0f);
        facts.put("flameActive",flameActive);facts.put("engineFlameAvailable",engineFlameAvailable);facts.put("flameLength",flameLength);facts.put("flameWidth",flameWidth);facts.put("flameGlow",flameGlow);
        facts.put("enginePlumeCount",enginePlumeCount);facts.put("visualPlumeLength",visualPlumeLength);facts.put("peakVisualLength",peakVisualLength);facts.put("enginePlumes",renderedEnginePlumes);
        facts.put("resolvedCollision",resolvedCollision);facts.put("resolvedCollisions",resolvedCollisions);facts.put("collisionToi",collisionToi);facts.put("resolutionFrom",resolutionFrom);facts.put("resolutionTo",resolutionTo);facts.put("resolvedPoint",resolvedPoint);
        facts.put("directionX",direction.x);facts.put("directionY",direction.y);facts.put("aimX",aimPoint.x);facts.put("aimY",aimPoint.y);facts.put("distance",distance);facts.put("travelled",travelled);facts.put("dashTime",dashTime);facts.put("contactKind",contactKind);facts.put("contactTarget",contactTarget);facts.put("contactX",contactX);facts.put("contactY",contactY);facts.put("minDps",MIN_DPS);facts.put("fullDps",FULL_DPS);facts.put("armorRemoved",0f);facts.put("modified",modified);facts.put("brakingAid",braking);facts.put("alignmentAssist",aligning);}
    private boolean liveSource(){return ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship)&&!engine.isCombatOver();}
    private static boolean finite(Vector2f point){return point!=null&&Float.isFinite(point.x)&&Float.isFinite(point.y);}
    private static float normalize(float degrees){float result=degrees%360;if(result>180)result-=360;if(result< -180)result+=360;return result;}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Zeta ram requires battle thread");}
    @Override public void close(){requireOwner();if(closed)return;if(active())brake();clearModifiers();clearEngineCharge();pendingDamage=pendingDamageTime=queuedImpactDamage=0;closed=true;phase=Phase.CLOSED;previousTargets.clear();pushedTarget=null;damageContact=null;
        if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");if(ship.getCustomData().get(KEY)==view)ship.removeCustomData(KEY);publish();}
}
