package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit structure resource cost,not a weapon hit; no armor/EMP or extra damage event. */
public final class UnicornNtdBacklash {
    public static final float HULL_FRACTION_PER_LOCAL_SECOND=.002f,MAX_HULL_FRACTION=.25f,
            BASE_LOCK_SECONDS=5f,LOCK_SECONDS_PER_LOCAL_SECOND=.25f,MAX_LOCK_SECONDS=45f;
    public record Estimate(float onLocalSeconds,float hullCost,float hullFraction,float lockSeconds){ }
    private float remaining,lastHullCost,lastOnSeconds,lastLockSeconds,totalHullCost;
    private long events,lastSequence;
    private String reason="";
    public static Estimate estimate(ShipAPI ship,float seconds){
        if(!Float.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid NTD uptime");
        float max=ship.getMaxHitpoints(),current=ship.getHitpoints();
        float fraction=Math.min(MAX_HULL_FRACTION,HULL_FRACTION_PER_LOCAL_SECOND*seconds);
        float cost=Float.isFinite(max)&&Float.isFinite(current)?Math.min(Math.max(0,current-1),Math.max(0,max)*fraction):0;
        return new Estimate(seconds,cost,fraction,Math.min(MAX_LOCK_SECONDS,BASE_LOCK_SECONDS+LOCK_SECONDS_PER_LOCAL_SECOND*seconds));
    }
    public boolean begin(ShipAPI ship,UnicornToggleNtdSubsystem.Shutdown shutdown){
        if(shutdown==null||shutdown.sequence()<=lastSequence)return false;
        lastSequence=shutdown.sequence();
        if(!ship.isAlive()||ship.isHulk()||ship.wasRemoved()||ship.getHitpoints()<=0)return false;
        Estimate estimate=estimate(ship,shutdown.onLocalSeconds());
        lastHullCost=estimate.hullCost;lastOnSeconds=estimate.onLocalSeconds;lastLockSeconds=estimate.lockSeconds;
        if(lastHullCost>0)ship.setHitpoints(ship.getHitpoints()-lastHullCost);
        remaining=Math.max(remaining,lastLockSeconds);totalHullCost+=lastHullCost;events++;reason=shutdown.reason();
        suppressAndMaintain(ship);return true;
    }
    public void advance(float localStep){
        if(!Float.isFinite(localStep)||localStep<0)throw new IllegalArgumentException("Invalid backlash local step");
        remaining=Math.max(0,remaining-localStep);
    }
    public void suppressAndMaintain(ShipAPI ship){
        if(remaining<=0||!ship.isAlive()||ship.isHulk()||ship.wasRemoved())return;
        UnicornNtdEnhancement.forceOverloadAtLeast(ship,remaining);
        int count=0;for(WeaponAPI weapon:ship.getAllWeapons())if(count++<512&&weapon!=null&&!weapon.isDecorative()){
            weapon.setForceNoFireOneFrame(true);weapon.stopFiring();
        }
    }
    public float remaining(){return remaining;}
    public Map<String,Object> status(){Map<String,Object> facts=new LinkedHashMap<>();
        facts.put("remainingLocalSeconds",remaining);facts.put("events",events);facts.put("lastSequence",lastSequence);
        facts.put("lastOnLocalSeconds",lastOnSeconds);facts.put("lastHullCost",lastHullCost);facts.put("lastLockLocalSeconds",lastLockSeconds);
        facts.put("totalHullCost",totalHullCost);facts.put("lastReason",reason);return Map.copyOf(facts);
    }
}
