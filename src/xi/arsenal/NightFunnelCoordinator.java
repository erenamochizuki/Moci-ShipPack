package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import data.scripts.weapons.Moci_WingmanSupport;
import java.util.*;

/** Discovers proven original Nightingale launches; engine, never this manager, advances each ShipAI. */
public final class NightFunnelCoordinator implements AutoCloseable {
    public static final String KEY="xi.arsenal.nightFunnelCoordinator";
    private static final int MAX_SCAN=8192,MAX_OWNED=1024;
    private final CombatEngineAPI engine;
    private final Map<ShipAPI,Entry> owned=new IdentityHashMap<>();
    private final Set<ShipAPI> retired=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private final List<Map<String,Object>> retiredHistory=new ArrayList<>();
    private float scanClock;
    private int cursor;
    private long ordinal,installed,closedControllers,sourceConflicts,budgetSkips,finishedShots,finishedMoves;
    private boolean closed;
    private record Entry(ShipAPI mother,NightShootMoveAI ai){}
    public NightFunnelCoordinator(CombatEngineAPI engine){
        this.engine=Objects.requireNonNull(engine);
        if(engine.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Night funnel coordinator already owned");
        engine.getCustomData().put(KEY+".owner",this);engine.getCustomData().put(KEY,Collections.unmodifiableMap(facts));publish();
    }
    public void advance(float dt){
        if(closed)return;if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid night coordinator amount");
        if(Global.getCombatEngine()!=engine||engine.isCombatOver()){close();return;}if(engine.isPaused())return;
        // A completed landing can disappear from engine.getShips() before the next AI
        // call. Keep that exact entity lease until its real finished-landing flag is read.
        for(Iterator<Map.Entry<ShipAPI,Entry>> it=owned.entrySet().iterator();it.hasNext();){
            var item=it.next();ShipAPI drone=item.getKey();Entry entry=item.getValue();
            entry.ai.serviceLandingCompletion(); // completion check only, never a second AI advance
            Map<String,Object> state=entry.ai.status();
            if(!live(drone)||Boolean.TRUE.equals(state.get("closed"))||!entry.ai.ownsNativeAi()){
                if(retiredHistory.size()>=32)retiredHistory.remove(0);
                retiredHistory.add(Collections.unmodifiableMap(new LinkedHashMap<>(state)));
                finishedShots+=number(state,"shots");finishedMoves+=number(state,"moves");entry.ai.close();closedControllers++;
                if(live(drone))retired.add(drone);it.remove();
            }
        }
        retired.removeIf(drone->!live(drone));
        scanClock-=dt;if(scanClock<=0){scanClock=.1f;
            List<ShipAPI> ships=engine.getShips();int size=ships.size(),limit=Math.min(size,MAX_SCAN);
            for(int n=0;n<limit;n++){
                ShipAPI drone=ships.get((cursor+n)%Math.max(1,size));
                if(!live(drone)||!NightShootMoveAI.isExactHull(drone)||!drone.isDrone()||owned.containsKey(drone)||retired.contains(drone))continue;
                ShipAPI mother=source(drone);if(!live(mother)||!PsychoframeEnhancement.supports(mother)||mother.getOwner()!=drone.getOwner())continue;
                if(owned.size()>=MAX_OWNED||ordinal==Long.MAX_VALUE){budgetSkips++;continue;}
                NightShootMoveAI ai=NightShootMoveAI.install(engine,drone,mother,++ordinal);
                if(ai!=null){owned.put(drone,new Entry(mother,ai));installed++;}
            }
            if(size>limit)budgetSkips+=size-limit;cursor=size==0?0:(cursor+limit)%size;
        }
        publish();
    }
    private ShipAPI source(ShipAPI drone){
        Object flag=drone.getAIFlags()==null?null:drone.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.DRONE_MOTHERSHIP);
        ShipAPI found=null;
        for(ShipAPI link:new ShipAPI[]{drone.getDroneSource(),Moci_WingmanSupport.getTrackedMothership(drone),flag instanceof ShipAPI s?s:null})if(link!=null){
            ShipAPI root=Moci_WingmanSupport.resolveRootMothership(link);if(found!=null&&found!=root){sourceConflicts++;return null;}found=root;
        }return found;
    }
    private boolean live(ShipAPI ship){return ship!=null&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship);}
    private void publish(){
        List<Map<String,Object>> active=new ArrayList<>();long shots=finishedShots,moves=finishedMoves;
        for(var item:owned.entrySet()){
            Map<String,Object> state=new LinkedHashMap<>(item.getValue().ai.status());
            state.put("droneId",MociFunnelTargetRouter.stableEntityId(item.getKey()));active.add(Collections.unmodifiableMap(state));
            shots+=number(state,"shots");moves+=number(state,"moves");
        }
        active.sort(Comparator.comparingLong(row->number(row,"ordinal")));
        facts.put("schema",1);facts.put("closed",closed);facts.put("installed",installed);facts.put("closedControllers",closedControllers);
        facts.put("activeCount",owned.size());facts.put("shots",shots);facts.put("moves",moves);facts.put("controllers",List.copyOf(active));
        facts.put("sourceConflicts",sourceConflicts);facts.put("budgetSkips",budgetSkips);facts.put("aiAdvanceOwner","native engine only");
        facts.put("retiredHistory",List.copyOf(retiredHistory));
    }
    private static long number(Map<String,Object> map,String key){return map.get(key) instanceof Number n?n.longValue():0;}
    public Map<String,Object> status(){publish();return Collections.unmodifiableMap(new LinkedHashMap<>(facts));}
    @Override public void close(){
        if(closed)return;closed=true;for(Entry entry:owned.values())entry.ai.close();owned.clear();retired.clear();
        if(engine.getCustomData().get(KEY+".owner")==this)engine.getCustomData().remove(KEY+".owner");publish();
    }
}
