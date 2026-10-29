package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;
import org.lwjgl.util.vector.Vector2f;

/** Bounded radial velocity constraint. No location/health or global engine time is written. */
public final class ZetaTetherPhysics {
    private ZetaTetherPhysics(){}
    public static final float RELATIVE_ACCELERATION=2600,RELATIVE_SPEED=900;
    public record PairImpulse(Vector2f sourceDelta,Vector2f targetDelta,float sourceAcceleration,float targetAcceleration,float desiredClosing,float forceMultiplier,float speedMultiplier,float sourceShare,float targetShare){}
    /** Two bodies converge using effective native hull inertia. Thrusters are external forces:
     * a working reverse engine can brace the source without changing its controls or position. */
    public static PairImpulse pairImpulse(Vector2f sourcePoint,Vector2f targetPoint,Vector2f sourceVelocity,Vector2f targetVelocity,float sourceMass,float targetMass,ShipAPI.HullSize size,float stopDistance,float dt,float phaseForce,float effect,float sourcePullScale){
        if(sourcePoint==null||targetPoint==null||sourceVelocity==null||targetVelocity==null||!finite(sourcePoint.x,sourcePoint.y,targetPoint.x,targetPoint.y,sourceVelocity.x,sourceVelocity.y,targetVelocity.x,targetVelocity.y,sourceMass,targetMass,stopDistance,dt,phaseForce,effect,sourcePullScale)||sourceMass<=0||targetMass<=0||size==null||stopDistance<0||dt<0||phaseForce<0||effect<0||effect>1||sourcePullScale<0||sourcePullScale>1)throw new IllegalArgumentException("Invalid bilateral tether frame");
        float inertiaFactor=switch(size){case FIGHTER,FRIGATE->1;case DESTROYER->.7f;case CRUISER->.25f;case CAPITAL_SHIP->.025f;default->.02f;};
        double effectiveTarget=(double)targetMass/inertiaFactor,total=sourceMass+effectiveTarget;float sourceShare=(float)(effectiveTarget/total),targetShare=(float)(sourceMass/total);
        float force=1+3f*effect,speed=1+1.75f*effect,accel=RELATIVE_ACCELERATION*phaseForce*force;
        Vector2f direction=Vector2f.sub(sourcePoint,targetPoint,null);float distance=direction.length();if(!Float.isFinite(distance))throw new IllegalArgumentException("Bilateral tether distance overflow");
        float remaining=Math.max(0,distance-stopDistance),desired=Math.min(RELATIVE_SPEED*speed,(float)Math.sqrt(2*accel*remaining));
        if(distance<.01f)return new PairImpulse(new Vector2f(),new Vector2f(),accel*sourceShare*sourcePullScale,accel*targetShare,desired,force,speed,sourceShare,targetShare);
        direction.scale(1/distance);float closing=Vector2f.dot(Vector2f.sub(targetVelocity,sourceVelocity,null),direction),limit=accel*Math.min(dt,.05f),change=Math.max(-limit,Math.min(limit,desired-closing));
        Vector2f targetDelta=new Vector2f(direction);targetDelta.scale(change*targetShare);Vector2f sourceDelta=new Vector2f(direction);sourceDelta.scale(-change*sourceShare*sourcePullScale);
        return new PairImpulse(sourceDelta,targetDelta,accel*sourceShare*sourcePullScale,accel*targetShare,desired,force,speed,sourceShare,targetShare);
    }
    public record PullLimits(float acceleration,float desiredClosing,float forceMultiplier,float speedMultiplier){}
    /** Native Biosensor effect scales force/speed, while the original mass/hull attenuation remains. */
    public static PullLimits limits(float sourceMass,float targetMass,ShipAPI.HullSize size,float phaseForce,float baseClosing,float distanceRemaining,float effect){
        if(!finite(phaseForce,baseClosing,distanceRemaining,effect)||phaseForce<0||baseClosing<0||distanceRemaining<0||effect<0||effect>1)throw new IllegalArgumentException("Finite bounded tether phase/effect required");
        float force=1+3f*effect,speed=1+1.75f*effect;
        return new PullLimits(acceleration(sourceMass,targetMass,size)*phaseForce*force,Math.min(baseClosing*speed,distanceRemaining*1.5f*speed),force,speed);
    }
    public static float acceleration(float sourceMass,float targetMass,ShipAPI.HullSize size){
        if(!Float.isFinite(sourceMass)||!Float.isFinite(targetMass)||sourceMass<=0||targetMass<=0||size==null)throw new IllegalArgumentException("Finite positive tether masses required");
        float sizeFactor=switch(size){case FIGHTER->1;case FRIGATE->1;case DESTROYER->.55f;case CRUISER->.18f;case CAPITAL_SHIP->.025f;default->.02f;};
        return 700*Math.max(.02f,Math.min(2,sourceMass/targetMass))*sizeFactor;
    }
    public static Vector2f velocityDelta(Vector2f anchor,Vector2f attached,Vector2f sourceVelocity,Vector2f targetVelocity,float sourceMass,float targetMass,ShipAPI.HullSize size,float stopDistance,float dt){
        if(anchor==null||attached==null||sourceVelocity==null||targetVelocity==null||!finite(anchor.x,anchor.y,attached.x,attached.y,sourceVelocity.x,sourceVelocity.y,targetVelocity.x,targetVelocity.y,stopDistance,dt)||stopDistance<0||dt<0)throw new IllegalArgumentException("Invalid finite tether frame");
        float acceleration=acceleration(sourceMass,targetMass,size);
        Vector2f direction=Vector2f.sub(anchor,attached,null);float distance=direction.length();if(!Float.isFinite(distance))throw new IllegalArgumentException("Tether distance overflow");if(distance<=stopDistance||distance<.01f)return new Vector2f();direction.scale(1/distance);
        float radial=Vector2f.dot(Vector2f.sub(targetVelocity,sourceVelocity,null),direction),desired=Math.min(220,Math.max(0,(distance-stopDistance)*1.5f));
        float limit=acceleration*Math.min(dt,.05f),change=Math.max(-limit,Math.min(limit,desired-radial));direction.scale(change);return direction;
    }
    private static boolean finite(float... values){for(float value:values)if(!Float.isFinite(value))return false;return true;}
}
