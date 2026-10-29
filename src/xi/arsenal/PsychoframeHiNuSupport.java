package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import data.scripts.weapons.Moci_WingmanSupport;
import java.util.*;

/** Shared, clock-neutral additions for the two owned ports of the original Moci Hi-Nu controller. */
public final class PsychoframeHiNuSupport {
    public static final String KEY="xi.arsenal.psychoframe.hinu", ADAPTER_KEY="xi.arsenal.hinuController",
            PROVENANCE_KEY="xi.arsenal.hinuProvenance";
    private static final float EPS=.00001f;
    private PsychoframeHiNuSupport() { }
    public static float effectLevel(ShipAPI mother){
        if(mother==null||mother.getSystem()==null||!PsychoframeEnhancement.SYSTEM_ID.equals(mother.getSystem().getId()))return 0;
        float level=mother.getSystem().getEffectLevel();return Float.isFinite(level)?Math.max(0,Math.min(1,level)):0;
    }
    public static float movementMult(ShipAPI mother){return blend(PsychoframeEnhancement.DRONE_SPEED,effectLevel(mother));}
    public static float turnMult(ShipAPI mother){return blend(PsychoframeEnhancement.DRONE_TURN,effectLevel(mother));}
    public static float cooldownMult(ShipAPI mother){return blend(PsychoframeEnhancement.COOLDOWN_MULT,effectLevel(mother));}
    private static float blend(float target,float level){return 1+(target-1)*level;}
    public static Map<String,Object> adapterMarker(String controller){return Map.of("movementAdapterOwned",true,"controller",controller,"schema",1);}
    /** Exact per-entity birth provenance. Never stored under a native ID and never read from a
     * nullable-ID native AI-flags cache. These references are local battle ownership,not wire data. */
    private record Provenance(ShipAPI drone,WeaponAPI weapon,ShipAPI source,ShipAPI mother,int launchOwner) { }
    public static void registerProvenance(ShipAPI drone,WeaponAPI sourceWeapon){
        Objects.requireNonNull(drone,"Hi-Nu drone");Objects.requireNonNull(sourceWeapon,"Hi-Nu source weapon");
        ShipAPI source=Objects.requireNonNull(sourceWeapon.getShip(),"Hi-Nu source ship");
        ShipAPI mother=Objects.requireNonNull(Moci_WingmanSupport.resolveRootMothership(source),"Hi-Nu root mother");
        Provenance born=new Provenance(drone,sourceWeapon,source,mother,drone.getOwner());
        Object previous=drone.getCustomData().get(PROVENANCE_KEY);
        if(previous!=null&&(!(previous instanceof Provenance old)||old.drone()!=drone||old.weapon()!=sourceWeapon
                ||old.source()!=source||old.mother()!=mother||old.launchOwner()!=born.launchOwner()))
            throw new IllegalStateException("Hi-Nu entity already has different owned source provenance");
        if(!valid(born))throw new IllegalStateException("Hi-Nu birth source is not attached/live with matching ownership");
        if(previous==null)drone.setCustomData(PROVENANCE_KEY,born);
        // Original public lifecycle support reads this entity-local custom data. Do NOT call
        // linkDroneToMothership: that also writes the native shared null-ID/no-AI flag cache.
        drone.setCustomData(Moci_WingmanSupport.ROOT_MOTHERSHIP_KEY,mother);
        Moci_WingmanSupport.attachLifecycleListener(drone);
    }
    public static ShipAPI motherFor(ShipAPI drone){Provenance value=provenance(drone);return value==null?null:value.mother();}
    public static WeaponAPI sourceWeaponFor(ShipAPI drone){Provenance value=provenance(drone);return value==null?null:value.weapon();}
    private static Provenance provenance(ShipAPI drone){
        if(drone==null)return null;Object value=drone.getCustomData().get(PROVENANCE_KEY);
        return value instanceof Provenance born&&born.drone()==drone&&valid(born)?born:null;
    }
    private static boolean valid(Provenance born){
        if(born.weapon().getShip()!=born.source()||Moci_WingmanSupport.resolveRootMothership(born.source())!=born.mother()
                ||!born.source().isAlive()||born.source().isHulk()||born.source().wasRemoved()
                ||!born.mother().isAlive()||born.mother().isHulk()||born.mother().wasRemoved()
                ||born.source().getOwner()!=born.launchOwner()||born.mother().getOwner()!=born.launchOwner()
                ||born.drone().getOwner()!=born.launchOwner())return false;
        List<WeaponAPI> actual=born.source().getAllWeapons();if(actual==null)return false;
        for(WeaponAPI weapon:actual)if(weapon==born.weapon())return true;return false;
    }
    /** Ignore only the actually confirmed native null/blank-ID/no-AI shared flag object. Other
     * public flag links remain authoritative and must still be checked for real conflicts. */
    public static boolean reliableNativeFlag(ShipAPI drone){return reliableNativeFlag(Global.getCombatEngine(),drone);}
    public static boolean reliableNativeFlag(CombatEngineAPI engine,ShipAPI drone){
        if(drone==null)return false;
        String id=drone.getId();
        if(id!=null&&!id.isBlank()||drone.getShipAI()!=null||engine==null)return true;
        Object actual=drone.getAIFlags();
        // Native concatenation distinguishes null ("null"), empty ("") and whitespace IDs.
        // Match the exact existing cache key/object; do not guess by owner or ignore every flag.
        return actual==null||engine.getCustomData().get("nonAI_SAIF_"+String.valueOf(id))!=actual;
    }

    /** Retains only the added cooldown portion when the original attack cycle intentionally clears
     * its normal cooldown. Its clock is exactly the existing controller's amount * mother time. */
    public static final class ShotGate {
        private float remaining,lastNativeRemaining,lastAdded,lastMultiplier=1;
        private long penalties;
        public void advance(float originalLocalAmount){
            if(!Float.isFinite(originalLocalAmount)||originalLocalAmount<0)throw new IllegalArgumentException("Invalid Hi-Nu controller clock");
            remaining=Math.max(0,remaining-originalLocalAmount);
        }
        public void retainPenaltyAndResetNative(WeaponAPI weapon,ShipAPI mother){
            float raw=weapon.getCooldownRemaining();lastNativeRemaining=Float.isFinite(raw)?Math.max(0,raw):0;
            lastMultiplier=cooldownMult(mother);
            lastAdded=lastNativeRemaining*(1-1/lastMultiplier);
            remaining=Math.max(remaining,lastAdded);if(lastAdded>EPS)penalties++;
            weapon.setRemainingCooldownTo(remaining);
        }
        public boolean allowsFire(WeaponAPI weapon){return remaining<=EPS&&weapon!=null&&weapon.getCooldownRemaining()<=EPS;}
        public float remaining(){return remaining;}
        public Map<String,Object> status(){return Map.of("remaining",remaining,"lastNativeRemaining",lastNativeRemaining,
                "lastAdded",lastAdded,"lastMultiplier",lastMultiplier,"penalties",penalties);}
    }

    /** A bounded read-only business probe. Counts actual native bright-beam edges separately from
     * the original controller's FIRE requests; it never fires, advances or modifies a weapon. */
    public static final class Probe {
        private final Map<String,Object> facts=new LinkedHashMap<>();
        private final Map<String,Object> view=Collections.unmodifiableMap(facts);
        private final List<Map<String,Object>> events=new ArrayList<>();
        private boolean wasBright;private String previousStage="";private long advances,beamPulses,fireRequests;
        public void fireRequested(){fireRequests++;}
        public void record(ShipAPI drone,WeaponAPI source,WeaponAPI gun,String stage,int point,int attackCount,
                           float returnCooldown,ShotGate gate){
            boolean bright=gun!=null&&gun.getBeams().stream().anyMatch(beam->beam.getBrightness()>EPS);
            if(bright&&!wasBright){beamPulses++;event("native-beam-bright-edge",stage,drone);}wasBright=bright;
            if(!Objects.equals(stage,previousStage)){event("stage",stage,drone);previousStage=stage;}
            advances++;ShipAPI mother=source.getShip();
            facts.put("schema",1);facts.put("movementAdapterOwned",true);facts.put("droneId",Objects.toString(drone.getId(),""));
            facts.put("droneIdentity",System.identityHashCode(drone));facts.put("sourceId",Objects.toString(mother.getId(),""));
            facts.put("sourceSlot",source.getSlot()==null?"":source.getSlot().getId());facts.put("point",point);facts.put("stage",stage);
            facts.put("provenanceValid",motherFor(drone)==Moci_WingmanSupport.resolveRootMothership(mother));
            facts.put("x",drone.getLocation().x);facts.put("y",drone.getLocation().y);facts.put("facing",drone.getFacing());
            facts.put("effectLevel",effectLevel(mother));facts.put("movementMult",movementMult(mother));facts.put("turnMult",turnMult(mother));
            facts.put("effectiveScriptSpeed",600*movementMult(mother));facts.put("effectiveScriptTurn",720*turnMult(mother));
            facts.put("clock","original amount * mother.timeMult.getMult; no owned time multiplier");
            facts.put("motherTimeMult",mother.getMutableStats().getTimeMult().getMult());facts.put("originalReturnCooldown",returnCooldown);
            facts.put("originalAttackCount",attackCount);facts.put("shotGate",gate.status());facts.put("nativeBeamPulseCount",beamPulses);
            facts.put("fireCommandCount",fireRequests);facts.put("nativeBeamBright",bright);facts.put("callbackObservations",advances);
            facts.put("nativeWeapon",gun==null?"":gun.getId());facts.put("nativeCooldown",gun==null?0:gun.getCooldownRemaining());
            facts.put("events",List.copyOf(events));drone.setCustomData(KEY,view);
        }
        private void event(String type,String stage,ShipAPI drone){if(events.size()>=32)events.remove(0);
            events.add(Map.of("type",type,"stage",stage,"x",drone.getLocation().x,"y",drone.getLocation().y,"facing",drone.getFacing()));}
        public Map<String,Object> status(){return Collections.unmodifiableMap(new LinkedHashMap<>(facts));}
    }
}
