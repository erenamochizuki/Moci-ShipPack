package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import java.util.*;
import java.util.function.Supplier;
import org.lwjgl.util.vector.Vector2f;

/** One real missile, one native AI clock. Outbound combines native steering with bounded
 * world-velocity guidance; inherited source drift cannot silently move it off its launch ray. Return is a bounded
 * cable-winch force on that same missile's velocity; position, facing and damage are never fabricated. */
public final class ZetaTetherMissileAI implements MissileAIPlugin,GuidedMissileAI {
    public static final int MAX_SAMPLES=94;
    public static final float RETURN_SECONDS=12,RECOVERY_RADIUS=16;
    public static final float WINCH_ACCELERATION=8000,MAX_REEL_SPEED=1600,LIVE_WRIST_HANDOFF_RADIUS=320;
    public static final float OUTBOUND_CORRECTION_ACCELERATION=6000,FREE_RAY_CENTERING=10;
    public enum Flight{OUTBOUND,BRAKING,RETURNING,AT_WRIST,LOST}
    private final CombatEngineAPI engine;private final MissileAPI missile;private CombatEntityAPI target;private final CombatEntityAPI explicitLock;private final Vector2f aimPoint,outboundDirection,launchOrigin;
    private final List<Vector2f> samples=new ArrayList<>();private Supplier<Vector2f> wrist,wristVelocity;
    private Flight flight=Flight.OUTBOUND;private int cursor=-1,visited;private float returnTime,maxWaypointError,sampleTime;
    private float lastImpulse,maxImpulse,lastForceStep;private long reelFrames;private Vector2f forceBefore=new Vector2f(),forceAfter=new Vector2f();
    private java.util.function.BiConsumer<ShipAPI,Vector2f> hullContact;private Vector2f previousHullPosition;private boolean hullContactDone;private float reelMult=1;
    public void enableOverdrivePiercing(java.util.function.BiConsumer<ShipAPI,Vector2f> contact){hullContact=Objects.requireNonNull(contact);previousHullPosition=new Vector2f(missile.getLocation());reelMult=1.8f;}
    private boolean liveWristHandoff;private int bypassed;private Vector2f currentWrist=new Vector2f(),lastReturnGoal=new Vector2f();
    private long outboundFrames;private float outboundStep,outboundImpulse,outboundMaxImpulse,outboundAcceleration,outboundCrossTrack;private Vector2f outboundBefore=new Vector2f(),outboundAfter=new Vector2f(),outboundGoal=new Vector2f();
    public ZetaTetherMissileAI(CombatEngineAPI engine,MissileAPI missile,ShipAPI target){this(engine,missile,target,target==null?null:target.getLocation());}
    public ZetaTetherMissileAI(CombatEngineAPI engine,MissileAPI missile,ShipAPI target,Vector2f frozenAimPoint){
        this.engine=Objects.requireNonNull(engine);this.missile=Objects.requireNonNull(missile);this.target=explicitLock=target;
        aimPoint=frozenAimPoint==null?new Vector2f(missile.getLocation().x+(float)Math.cos(Math.toRadians(missile.getFacing()))*1500,missile.getLocation().y+(float)Math.sin(Math.toRadians(missile.getFacing()))*1500):new Vector2f(frozenAimPoint);
        if(!Float.isFinite(aimPoint.x)||!Float.isFinite(aimPoint.y))throw new IllegalArgumentException("Finite frozen tether aim point required");
        launchOrigin=new Vector2f(missile.getLocation());outboundDirection=Vector2f.sub(aimPoint,launchOrigin,null);float length=outboundDirection.length();if(length<.01f)outboundDirection.set((float)Math.cos(Math.toRadians(missile.getFacing())),(float)Math.sin(Math.toRadians(missile.getFacing())));else outboundDirection.scale(1/length);sample(true);
    }
    public void beginReturn(Supplier<Vector2f> wrist){beginReturn(wrist,()->missile.getSource()==null?new Vector2f():missile.getSource().getVelocity());}
    public void beginReturn(Supplier<Vector2f> wrist,Supplier<Vector2f> wristVelocity){if(flight!=Flight.OUTBOUND)return;this.wrist=Objects.requireNonNull(wrist);this.wristVelocity=Objects.requireNonNull(wristVelocity);sample(true);flight=Flight.BRAKING;returnTime=0;liveWristHandoff=false;bypassed=0;
        // Only this already paid native projectile gets more lifetime. Elapsed flight time is never reset.
        missile.setMaxFlightTime(Math.max(missile.getMaxFlightTime(),missile.getFlightTime()+RETURN_SECONDS+1));}
    @Override public void advance(float amount){
        if(!Float.isFinite(amount)||amount<0)throw new IllegalArgumentException("Finite native tether AI step required");if(engine.isPaused()||flight==Flight.LOST)return;
        if(!engine.isEntityInPlay(missile)||missile.isFading()||missile.isFizzling()){flight=Flight.LOST;return;}
        // Native physics or a moving wrist can separate the two callbacks. Stay braked while
        // awaiting the actor's removal; if they separate, resume homing rather than freeze forever.
        if(flight==Flight.AT_WRIST){if(Vector2f.sub(liveWrist(),missile.getLocation(),null).length()<=RECOVERY_RADIUS){winch(liveWristVelocity(),amount);missile.giveCommand(ShipCommand.DECELERATE);return;}flight=Flight.RETURNING;cursor=-1;}
        if(flight==Flight.OUTBOUND){if(sweepHull())return;sampleTime+=amount;sample(false);
            boolean guided=target!=null&&engine.isEntityInPlay(target);Vector2f goal;
            if(guided){Vector2f relative=Vector2f.sub(target.getLocation(),missile.getLocation(),null);float lead=interceptSeconds(relative,target.getVelocity(),Math.max(100,missile.getMaxSpeed()));goal=new Vector2f(target.getLocation().x+target.getVelocity().x*lead,target.getLocation().y+target.getVelocity().y*lead);}
            else goal=new Vector2f(missile.getLocation().x+outboundDirection.x*1500,missile.getLocation().y+outboundDirection.y*1500);
            turn(goal);correctOutbound(goal,guided,amount);
            // Free aim flies along the original ray even after passing the mouse point. It does
            // not orbit an empty coordinate, scan nearby ships, or acquire a target on its own.
            missile.giveCommand(ShipCommand.ACCELERATE);return;}
        returnTime+=amount;if(returnTime>RETURN_SECONDS){flight=Flight.LOST;return;}
        if(flight==Flight.BRAKING){sampleTime+=amount;sample(false);Vector2f at=samples.size()>1?samples.get(samples.size()-2):wrist.get();turn(at);winch(new Vector2f(),amount);missile.giveCommand(ShipCommand.DECELERATE);
            if(missile.getVelocity().length()<30){sample(true);cursor=samples.size()-2;flight=Flight.RETURNING;}return;}
        // A sampled path is a corridor, not a sequence of braking points. A native dart
        // must advance a passed sample before a tiny cross-track error causes a full circle.
        Vector2f live=liveWrist();float wristDistance=Vector2f.sub(live,missile.getLocation(),null).length();
        // Flight history is navigation evidence, not the physical rope. The old launch point
        // is never a mandatory destination after its owning wrist has moved. Near the actual
        // wrist (or the start of history), reel directly to that live endpoint from now on.
        if(cursor>=0&&(cursor<=1||wristDistance<=LIVE_WRIST_HANDOFF_RADIUS)){bypassed+=cursor+1;cursor=-1;liveWristHandoff=true;}
        float tolerance=Math.max(56,Math.min(72,missile.getVelocity().length()*amount*1.7f+24));
        while(cursor>0){float distance=Vector2f.sub(samples.get(cursor),missile.getLocation(),null).length();if(distance>tolerance&&!passedWithinCorridor(cursor))break;maxWaypointError=Math.max(maxWaypointError,distance);cursor--;visited++;}
        if(cursor==0){bypassed++;cursor=-1;liveWristHandoff=true;}
        Vector2f goal=cursor>=0?lookAhead():live;lastReturnGoal=new Vector2f(goal);
        if(cursor<0&&Vector2f.sub(goal,missile.getLocation(),null).length()<=RECOVERY_RADIUS){flight=Flight.AT_WRIST;missile.giveCommand(ShipCommand.DECELERATE);return;}
        turn(goal);Vector2f direction=Vector2f.sub(goal,missile.getLocation(),null);float distance=direction.length(),speed=cursor<0?Math.min(MAX_REEL_SPEED*reelMult,Math.max(0,(distance-8)*3*reelMult)):MAX_REEL_SPEED*reelMult;if(distance>.01f)direction.scale(speed/distance);else direction.set(0,0);
        // A moving wrist needs relative closing speed plus its actual source velocity. Pure
        // distance-only braking otherwise settles hundreds of units behind a moving pilot.
        if(cursor<0){Vector2f.add(direction,liveWristVelocity(),direction);float total=direction.length();if(total>MAX_REEL_SPEED*reelMult)direction.scale(MAX_REEL_SPEED*reelMult/total);}
        winch(direction,amount);missile.giveCommand(ShipCommand.DECELERATE);
    }
    /** Native travelled segment against actual hull edges. Shield arcs are deliberately absent.
     * One owned collision result, one native damage application, then the typed combo attachment. */
    private boolean sweepHull(){if(hullContact==null||hullContactDone)return false;Vector2f to=new Vector2f(missile.getLocation()),from=previousHullPosition;previousHullPosition=to;
        if(Vector2f.sub(to,from,null).lengthSquared()<.00001f)return false;ShipAPI best=null;float entry=Float.POSITIVE_INFINITY;int budget=512;
        for(ShipAPI target:engine.getShips()){if(--budget<0)break;if(target==missile.getSource()||!target.isAlive()||target.isHulk()||target.wasRemoved()||target.isPhased()||target.getOwner()==missile.getOwner()||target.getOwner()<0||target.getOwner()>1||target.getCollisionClass()==CollisionClass.NONE)continue;
            float t=ZetaBeamDeflection.hullEntry(from,to,target);if(t<entry){entry=t;best=target;}}
        if(best==null)return false;hullContactDone=true;Vector2f point=new Vector2f(from.x+(to.x-from.x)*entry,from.y+(to.y-from.y)*entry);
        engine.applyDamage(missile,best,point,missile.getDamageAmount(),missile.getDamageType(),missile.getEmpAmount(),true,false,missile.getSource(),false);
        hullContact.accept(best,point);return true;
    }
    private boolean passedWithinCorridor(int index){if(index+1>=samples.size())return false;Vector2f direction=Vector2f.sub(samples.get(index),samples.get(index+1),null),at=Vector2f.sub(missile.getLocation(),samples.get(index),null);float length=direction.length();if(length<.01f)return false;direction.scale(1/length);return Vector2f.dot(at,direction)>=0&&Math.abs(at.x*direction.y-at.y*direction.x)<=72;}
    private Vector2f lookAhead(){int index=cursor;float distance=0;while(index>1&&distance<140){distance+=Vector2f.sub(samples.get(index),samples.get(index-1),null).length();index--;}return samples.get(index);}
    /** Frozen free ray has no target acquisition. An explicit lock alone enables bounded lead. */
    private void correctOutbound(Vector2f goal,boolean guided,float amount){
        float nativeAcceleration=missile.getAcceleration(),maxSpeed=missile.getMaxSpeed();if(!Float.isFinite(nativeAcceleration)||nativeAcceleration<0||!Float.isFinite(maxSpeed)||maxSpeed<=0)throw new IllegalStateException("Invalid native tether flight acceleration/speed");
        outboundStep=Math.min(amount,.05f);outboundAcceleration=Math.min(OUTBOUND_CORRECTION_ACCELERATION,nativeAcceleration);outboundGoal=new Vector2f(goal);
        Vector2f velocity=missile.getVelocity();outboundBefore=new Vector2f(velocity);Vector2f desired;
        Vector2f offset=Vector2f.sub(missile.getLocation(),launchOrigin,null);float nx=-outboundDirection.y,ny=outboundDirection.x;outboundCrossTrack=offset.x*nx+offset.y*ny;
        if(guided){Vector2f direction=Vector2f.sub(goal,missile.getLocation(),null);float distance=direction.length();desired=new Vector2f(velocity);if(distance>.01f){direction.scale(Math.min(maxSpeed,velocity.length())/distance);desired=direction;}}
        else{float along=Math.max(0,Vector2f.dot(velocity,outboundDirection)),lateral=Math.max(-maxSpeed*.5f,Math.min(maxSpeed*.5f,-outboundCrossTrack*FREE_RAY_CENTERING));desired=new Vector2f(outboundDirection.x*along+nx*lateral,outboundDirection.y*along+ny*lateral);float speed=desired.length();if(speed>maxSpeed)desired.scale(maxSpeed/speed);}
        // Native ACCELERATE remains the forward propulsion owner. This finite impulse only
        // corrects the actual world vector; no position/facing, hit, damage or lifetime is forged.
        Vector2f impulse=Vector2f.sub(desired,velocity,null);float length=impulse.length(),limit=outboundAcceleration*outboundStep;if(length>limit&&length>0)impulse.scale(limit/length);Vector2f.add(velocity,impulse,velocity);outboundAfter=new Vector2f(velocity);outboundImpulse=impulse.length();outboundMaxImpulse=Math.max(outboundMaxImpulse,outboundImpulse);outboundFrames++;
    }
    public static float interceptSeconds(Vector2f relative,Vector2f velocity,float speed){
        if(relative==null||velocity==null||!Float.isFinite(relative.x)||!Float.isFinite(relative.y)||!Float.isFinite(velocity.x)||!Float.isFinite(velocity.y)||!Float.isFinite(speed)||speed<=0)throw new IllegalArgumentException("Finite native tether intercept required");
        double a=(double)velocity.x*velocity.x+(double)velocity.y*velocity.y-(double)speed*speed,b=2*((double)relative.x*velocity.x+(double)relative.y*velocity.y),c=(double)relative.x*relative.x+(double)relative.y*relative.y,t=Double.POSITIVE_INFINITY;
        if(Math.abs(a)<.0001){if(b<0)t=-c/b;}else{double d=b*b-4*a*c;if(d>=0){double root=Math.sqrt(d),one=(-b-root)/(2*a),two=(-b+root)/(2*a);if(one>=0)t=one;if(two>=0)t=Math.min(t,two);}}
        if(!Double.isFinite(t))t=Math.sqrt(c)/speed;return(float)Math.max(0,Math.min(.75,t));
    }
    private Vector2f liveWrist(){Vector2f value=Objects.requireNonNull(wrist.get(),"Current left wrist required");if(!Float.isFinite(value.x)||!Float.isFinite(value.y))throw new IllegalStateException("Current tether wrist is nonfinite");currentWrist=new Vector2f(value);return currentWrist;}
    private Vector2f liveWristVelocity(){Vector2f value=Objects.requireNonNull(wristVelocity.get(),"Current source velocity required");if(!Float.isFinite(value.x)||!Float.isFinite(value.y))throw new IllegalStateException("Current tether source velocity is nonfinite");Vector2f result=new Vector2f(value);float speed=result.length();if(speed>MAX_REEL_SPEED)result.scale(MAX_REEL_SPEED/speed);return result;}
    private void winch(Vector2f desired,float amount){lastForceStep=Math.min(amount,.05f);Vector2f velocity=missile.getVelocity();forceBefore=new Vector2f(velocity);Vector2f impulse=Vector2f.sub(desired,velocity,null);float length=impulse.length(),limit=WINCH_ACCELERATION*reelMult*lastForceStep;if(length>limit&&length>0)impulse.scale(limit/length);Vector2f.add(velocity,impulse,velocity);forceAfter=new Vector2f(velocity);lastImpulse=impulse.length();maxImpulse=Math.max(maxImpulse,lastImpulse);reelFrames++;}
    private float turn(Vector2f goal){float error=error(goal),steering=error-missile.getAngularVelocity()*.35f;if(steering>1)missile.giveCommand(ShipCommand.TURN_LEFT);else if(steering< -1)missile.giveCommand(ShipCommand.TURN_RIGHT);return error;}
    private float error(Vector2f goal){return shortest((float)Math.toDegrees(Math.atan2(goal.y-missile.getLocation().y,goal.x-missile.getLocation().x))-missile.getFacing());}
    private void sample(boolean force){Vector2f point=missile.getLocation();if(!Float.isFinite(point.x)||!Float.isFinite(point.y))throw new IllegalStateException("Native tether position is nonfinite");
        if(!samples.isEmpty()&&Vector2f.sub(point,samples.get(samples.size()-1),null).lengthSquared()<.01f)return;
        if(!force&&!samples.isEmpty()&&sampleTime<.06f&&Vector2f.sub(point,samples.get(samples.size()-1),null).lengthSquared()<576)return;
        sampleTime=0;if(samples.size()>=MAX_SAMPLES){List<Vector2f> compact=new ArrayList<>();compact.add(samples.get(0));for(int i=2;i<samples.size()-1;i+=2)compact.add(samples.get(i));compact.add(samples.get(samples.size()-1));samples.clear();samples.addAll(compact);}samples.add(new Vector2f(point));}
    public Flight flight(){return flight;}public boolean atWrist(){return flight==Flight.AT_WRIST;}public boolean lost(){return flight==Flight.LOST;}
    public int sampleCount(){return samples.size();}public int waypointIndex(){return cursor;}public int waypointsVisited(){return visited;}public float returnTime(){return returnTime;}public float maxWaypointError(){return maxWaypointError;}
    public Map<String,Object> reelStatus(){return Map.ofEntries(Map.entry("frames",reelFrames),Map.entry("accelerationLimit",WINCH_ACCELERATION),Map.entry("speedLimit",MAX_REEL_SPEED),Map.entry("lastImpulse",lastImpulse),Map.entry("maxImpulse",maxImpulse),Map.entry("lastForceStep",lastForceStep),Map.entry("velocityBefore",List.of(forceBefore.x,forceBefore.y)),Map.entry("velocityAfter",List.of(forceAfter.x,forceAfter.y)),Map.entry("liveWristHandoff",liveWristHandoff),Map.entry("bypassedHistoricalSamples",bypassed),Map.entry("currentWrist",List.of(currentWrist.x,currentWrist.y)),Map.entry("returnGoal",List.of(lastReturnGoal.x,lastReturnGoal.y)));}
    public Map<String,Object> outboundStatus(){return Map.ofEntries(Map.entry("frames",outboundFrames),Map.entry("correctionAcceleration",outboundAcceleration),Map.entry("lastForceStep",outboundStep),Map.entry("lastImpulse",outboundImpulse),Map.entry("maxImpulse",outboundMaxImpulse),Map.entry("velocityBefore",List.of(outboundBefore.x,outboundBefore.y)),Map.entry("velocityAfter",List.of(outboundAfter.x,outboundAfter.y)),Map.entry("launchOrigin",List.of(launchOrigin.x,launchOrigin.y)),Map.entry("frozenAim",List.of(aimPoint.x,aimPoint.y)),Map.entry("worldAim",List.of(outboundGoal.x,outboundGoal.y)),Map.entry("crossTrack",outboundCrossTrack),Map.entry("explicitLock",target!=null));}
    public List<Vector2f> flightSamples(){return samples.stream().map(Vector2f::new).toList();}
    /** The visible rope joins two current physical endpoints. Historical flight samples are
     * exclusively a return-navigation corridor and must never become rope vertices. */
    public List<Vector2f> cablePath(Vector2f from){return ZetaTetherCable.liveEndpoints(from,missile.getLocation());}
    @Override public CombatEntityAPI getTarget(){return flight==Flight.OUTBOUND?target:null;}
    @Override public void setTarget(CombatEntityAPI target){if(target==null||target==explicitLock)this.target=target;}
    private static float shortest(float value){float a=(value%360+360)%360;return a>180?a-360:a;}
}
