package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.combat.ai.BasicShipAI;
import data.scripts.weapons.Moci_WingmanSupport;
import java.util.*;
import java.util.function.Predicate;

/** Shared pools for original MSN04 funnels and the owned shoot/move controller. Never advances either AI. */
public final class MociFunnelTargetRouter implements AutoCloseable {
    public static final String KEY="xi.arsenal.funnelTargetRouter", ASSIGNMENT_KEY=KEY+".assignment";
    public static final float SCAN_SECONDS=.1f, FLAG_SECONDS=.35f;
    private static final int MAX_SCANNED=8192, MAX_ASSIGNED=1024, MAX_MODULES=64, MAX_WEAPONS=512;
    private static final Set<String> LAUNCHERS=Set.of("Moci_MSN_04_2_wing_left","Moci_MSN_04_2_wing_right");
    private final CombatEngineAPI engine;
    private final Predicate<ShipAIPlugin> defaultAi;
    private final Map<ShipAPI,Lease> leases=new IdentityHashMap<>();
    private final Map<ShipAPI,Long> blankIdOrdinals=new IdentityHashMap<>();
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private float scanClock;
    private int cursor;
    private long nextBlankIdOrdinal;
    private boolean closed;
    private long scans,assignmentChanges,releases,sourceConflicts,unsupportedControllers,budgetSkips,missingIds;

    public MociFunnelTargetRouter(CombatEngineAPI engine){this(engine,ai->ai!=null&&(ai.getClass()==BasicShipAI.class||NightShootMoveAI.fromNative(ai)!=null));}
    MociFunnelTargetRouter(CombatEngineAPI engine,Predicate<ShipAIPlugin> defaultAi){
        this.engine=Objects.requireNonNull(engine);this.defaultAi=Objects.requireNonNull(defaultAi);
        if(engine.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Funnel target router already owned");
        engine.getCustomData().put(KEY+".owner",this);engine.getCustomData().put(KEY,facts);publish();
    }
    /** Called once by the owned main combat plugin, for every source with a shared pool, not only Unicorns. */
    public void advance(float dt){
        if(closed)return;if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid funnel target step");
        if(Global.getCombatEngine()!=engine||engine.isCombatOver()){close();return;}
        if(engine.isPaused())return;
        scanClock-=dt;if(scanClock>0)return;scanClock=SCAN_SECONDS;scans++;
        // Original FX drones legitimately have no ShipAPI ID. Keep our ordinal for
        // the living entity across clear/reacquire, and retire it only with that entity.
        blankIdOrdinals.keySet().removeIf(drone->!live(drone));
        for(Iterator<Lease> it=leases.values().iterator();it.hasNext();){Lease lease=it.next();
            if(!validLease(lease)){release(lease);it.remove();}}
        Map<ShipAPI,List<ShipAPI>> groups=new IdentityHashMap<>();
        for(Lease lease:leases.values())groups.computeIfAbsent(lease.source,s->new ArrayList<>()).add(lease.drone);
        List<ShipAPI> ships=engine.getShips();int size=ships.size(),limit=Math.min(size,MAX_SCANNED);
        for(int offset=0;offset<limit;offset++){
            ShipAPI drone=ships.get((cursor+offset)%Math.max(1,size));
            if(!live(drone)||!drone.isDrone()||!UnicornNtdInterference.isKnownFunnelHull(drone.getHullSpec()))continue;
            ShipAPI source=resolveMothership(drone);
            if(!live(source)||drone==source||drone.getOwner()!=source.getOwner()||!ContentTargetLocks.managed(engine,source))continue;
            if(ContentTargetLocks.snapshot(engine,source).isEmpty())continue;
            if(!isMsn04Hull(drone.getHullSpec())||!defaultAi.test(drone.getShipAI())){unsupportedControllers++;continue;}
            if(!registerEntityId(drone)){missingIds++;budgetSkips++;continue;}
            if(leases.containsKey(drone))continue;
            if(leases.size()>=MAX_ASSIGNED){budgetSkips++;continue;}
            if(drone.getCustomData().containsKey(ASSIGNMENT_KEY))continue;
            Lease lease=new Lease(drone,source,drone.getShipAI());leases.put(drone,lease);
            drone.setCustomData(ASSIGNMENT_KEY,lease);groups.computeIfAbsent(source,s->new ArrayList<>()).add(drone);
        }
        if(size>limit)budgetSkips+=size-limit;
        cursor=size==0?0:(cursor+limit)%size;
        List<Map<String,Object>> distributions=new ArrayList<>();
        for(var entry:groups.entrySet()){
            ShipAPI source=entry.getKey();List<ShipAPI> drones=entry.getValue();drones.sort(Comparator.comparing(MociFunnelTargetRouter::stableEntityId));
            float range=launcherRange(source);List<ShipAPI> targets=range>0?ContentTargetLocks.inRange(engine,source,range):List.of();
            if(targets.isEmpty()){
                for(ShipAPI drone:drones){Lease lease=leases.remove(drone);if(lease!=null)release(lease);}
                continue;
            }
            Map<String,Integer> counts=new LinkedHashMap<>();for(ShipAPI target:targets)counts.put(id(target),0);
            for(int i=0;i<drones.size();i++){
                Lease lease=leases.get(drones.get(i));if(lease==null)continue;
                ShipAPI target=targets.get(i%targets.size());assign(lease,target);counts.merge(id(target),1,Integer::sum);
            }
            distributions.add(Map.of("sourceId",id(source),"droneCount",drones.size(),"targetCounts",counts,"sourceLauncherRange",range));
        }
        facts.put("distributions",List.copyOf(distributions));publish();
    }
    private boolean validLease(Lease lease){
        return live(lease.drone)&&live(lease.source)&&lease.drone.getOwner()==lease.source.getOwner()
                &&lease.drone.getShipAI()==lease.ai&&defaultAi.test(lease.ai)&&resolveMothership(lease.drone)==lease.source
                &&ContentTargetLocks.managed(engine,lease.source)&&lease.drone.getCustomData().get(ASSIGNMENT_KEY)==lease;
    }
    /** Public original Mod/native links only. Conflicting links are rejected, never guessed by distance/owner. */
    private ShipAPI resolveMothership(ShipAPI drone){
        ShipAPI nativeSource=drone.getDroneSource(),tracked=Moci_WingmanSupport.getTrackedMothership(drone);
        Object flag=!PsychoframeHiNuSupport.reliableNativeFlag(drone)||drone.getAIFlags()==null?null:drone.getAIFlags().getCustom(AIFlags.DRONE_MOTHERSHIP);
        ShipAPI flagged=flag instanceof ShipAPI ship?ship:null;
        ShipAPI found=null;
        for(ShipAPI candidate:new ShipAPI[]{PsychoframeHiNuSupport.motherFor(drone),nativeSource,tracked,flagged})if(candidate!=null){
            ShipAPI root=Moci_WingmanSupport.resolveRootMothership(candidate);
            if(found!=null&&found!=root){sourceConflicts++;return null;}found=root;
        }
        return found;
    }
    private static boolean isMsn04Hull(ShipHullSpecAPI hull){
        if(hull==null)return false;Set<ShipHullSpecAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ShipHullSpecAPI> queue=new ArrayDeque<>();queue.add(hull);
        for(int checked=0;checked<8&&!queue.isEmpty();checked++){ShipHullSpecAPI next=queue.removeFirst();if(!seen.add(next))continue;
            if("Moci_MSN_04_2_bit".equals(next.getHullId())||"Moci_MSN_04_2_bit".equals(next.getBaseHullId())||"Moci_MSN_04_2_bit".equals(next.getDParentHullId()))return true;
            if(next.getBaseHull()!=null)queue.addLast(next.getBaseHull());if(next.getDParentHull()!=null)queue.addLast(next.getDParentHull());}
        return false;
    }
    /** Effective mother launcher range, not the drone's short gun range or current distance to it. */
    private float launcherRange(ShipAPI source){
        float range=0;int modules=0,weapons=0;Set<ShipAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ShipAPI> queue=new ArrayDeque<>();queue.add(source);
        while(!queue.isEmpty()&&modules++<MAX_MODULES&&weapons<MAX_WEAPONS){ShipAPI ship=queue.removeFirst();if(!seen.add(ship))continue;
            for(WeaponAPI weapon:ship.getAllWeapons()){
                if(++weapons>MAX_WEAPONS)break;
                if(weapon!=null&&LAUNCHERS.contains(weapon.getId())){float actual=weapon.getRange();if(Float.isFinite(actual)&&actual>range)range=actual;}
            }
            List<ShipAPI> children=ship.getChildModulesCopy();if(children!=null)for(ShipAPI child:children)if(child!=null&&!seen.contains(child)&&queue.size()<MAX_MODULES)queue.addLast(child);
        }
        return range;
    }
    private void assign(Lease lease,ShipAPI target){
        ShipAPI previous=lease.target;ShipwideAIFlags flags=lease.drone.getAIFlags();
        NightShootMoveAI own=NightShootMoveAI.fromNative(lease.ai);if(own!=null){
            lease.target=target;own.setAssignedTarget(target);
            if(previous!=target)assignmentChanges++;
            return;
        }
        if(flags!=null){Object current=flags.getCustom(AIFlags.MANEUVER_TARGET);
            if(!flags.hasFlag(AIFlags.MANEUVER_TARGET)||(lease.flagOwned&&current==previous)){
                flags.setFlag(AIFlags.MANEUVER_TARGET,FLAG_SECONDS,target);lease.flagOwned=true;
            }else if(current!=previous)lease.flagOwned=false;
        }
        lease.target=target;lease.drone.setShipTarget(target);
        if(previous!=target){assignmentChanges++;lease.ai.forceCircumstanceEvaluation();}
    }
    private void release(Lease lease){
        if(lease.drone.getCustomData().get(ASSIGNMENT_KEY)!=lease)return;
        NightShootMoveAI own=NightShootMoveAI.fromNative(lease.ai);if(own!=null)own.clearAssignedTarget();
        // Cleanup never clears a target/flag changed by another controller after our assignment.
        if(lease.target!=null&&lease.drone.getShipTarget()==lease.target)lease.drone.setShipTarget(null);
        ShipwideAIFlags flags=lease.drone.getAIFlags();
        if(flags!=null&&lease.flagOwned&&flags.getCustom(AIFlags.MANEUVER_TARGET)==lease.target)flags.unsetFlag(AIFlags.MANEUVER_TARGET);
        lease.drone.removeCustomData(ASSIGNMENT_KEY);releases++;
    }
    private boolean live(ShipAPI ship){return ship!=null&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship);}
    /**
     * Read-only display/association ID. Native nonblank IDs pass through unchanged;
     * original blank-ID FX drones resolve only AFTER this engine registered them.
     * Never changes native ShipAPI IDs and never assigns from a rendering callback.
     */
    public static String stableEntityId(ShipAPI ship){
        if(ship==null)return "";
        String nativeId=ship.getId();if(nativeId!=null&&!nativeId.isBlank())return nativeId;
        CombatEngineAPI current=Global.getCombatEngine();
        Object owner=current==null?null:current.getCustomData().get(KEY+".owner");
        if(!(owner instanceof MociFunnelTargetRouter router)||router.closed)return "";
        Long ordinal=router.blankIdOrdinals.get(ship);
        return ordinal==null?"":String.format(Locale.ROOT,"fx-drone-%012d",ordinal);
    }
    private boolean registerEntityId(ShipAPI ship){
        String nativeId=ship.getId();if(nativeId!=null&&!nativeId.isBlank()||blankIdOrdinals.containsKey(ship))return true;
        if(blankIdOrdinals.size()>=MAX_ASSIGNED||nextBlankIdOrdinal==Long.MAX_VALUE)return false;
        blankIdOrdinals.put(ship,++nextBlankIdOrdinal);return true;
    }
    private static String id(ShipAPI ship){return stableEntityId(ship);}
    private void publish(){facts.put("schema",1);facts.put("closed",closed);facts.put("scans",scans);facts.put("assignedDrones",leases.size());
        facts.put("assignmentChanges",assignmentChanges);facts.put("releases",releases);facts.put("sourceConflicts",sourceConflicts);
        facts.put("unsupportedControllers",unsupportedControllers);facts.put("budgetSkips",budgetSkips);facts.put("missingIds",missingIds);
        facts.put("registeredBlankIds",blankIdOrdinals.size());facts.put("blankIdRegistrations",nextBlankIdOrdinal);
        facts.put("assignments",leases.values().stream().sorted(Comparator.comparing(lease->id(lease.drone))).map(lease->Map.<String,Object>of(
                "droneId",id(lease.drone),"sourceId",id(lease.source),"targetId",lease.target==null?"":id(lease.target),"aiClass",lease.ai.getClass().getName())).toList());
        facts.put("supported","Original Moci_MSN_04_2_bit; exact native BasicShipAI or owned shoot/move; proven public mother links and actual launcher range");
        facts.put("unsupported","Other private/unrecognized target controllers are not routed by this coordinator");
    }
    public Map<String,Object> status(){publish();return Collections.unmodifiableMap(new LinkedHashMap<>(facts));}
    @Override public void close(){if(closed)return;for(Lease lease:new ArrayList<>(leases.values()))release(lease);leases.clear();blankIdOrdinals.clear();closed=true;
        if(engine.getCustomData().get(KEY+".owner")==this)engine.getCustomData().remove(KEY+".owner");publish();}
    private static final class Lease {
        final ShipAPI drone,source;final ShipAIPlugin ai;ShipAPI target;boolean flagOwned;
        Lease(ShipAPI drone,ShipAPI source,ShipAIPlugin ai){this.drone=drone;this.source=source;this.ai=ai;}
    }
}
