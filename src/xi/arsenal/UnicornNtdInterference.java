package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import java.awt.Color;
import java.util.*;

/** Owned NTD interference. Caller passes only actual active-NTD main ships, once per battle tick. */
public final class UnicornNtdInterference implements AutoCloseable {
    public static final String KEY="xi.arsenal.ntd.interference", JAM_KEY="xi.arsenal.targetSuppression", SOURCE_KEY=JAM_KEY+".source";
    public static final float MISSILE_RADIUS=900f, EMP_RADIUS=1000f, SCAN_SECONDS=.1f, LEASE_SECONDS=.35f;
    public static final int MAX_SOURCES=32, MAX_JAMMED=512, MAX_EMP_SHIPS=64, MAX_CANDIDATES=2048, MAX_COMPONENTS=2048;
    public static final int MAX_EMP_VISUALS_PER_SCAN=8;
    private static final Set<String> UNICORNS=Set.of("Moci_RX_0_1","Moci_RX_0_2","Moci_RX_0_3");
    private static final Set<String> SHIELDS=Set.of("Moci_RX_0_1_shield","Moci_RX_0_2_shield","Moci_RX_0_3_backpack_shield_left","Moci_RX_0_3_backpack_shield_right");
    // Exact original ship_data.csv floating-weapon rows, never display-name guesses.
    private static final Set<String> FUNNELS=Set.of("Moci_Vangnix_bit","Moci_Aestivalis_bit","Moci_RX_93_bit","Moci_RX_93_v2_bit",
            "Moci_MSN_03_bit","Moci_AMX_004_bit","Moci_NZ_333_bit","Moci_MSN_04_2_bit","Moci_ZGMF_X20A_bit");
    private final CombatEngineAPI engine;
    private final String token=KEY+"."+UUID.randomUUID();
    private final Set<ShipAPI> sources=identitySet();
    private final Map<ShipAPI,EmpState> empShips=new IdentityHashMap<>();
    private final Map<ShipAPI,Map<String,Object>> sourceLeases=new IdentityHashMap<>();
    private final Map<MissileAPI,Jam> jams=new IdentityHashMap<>();
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private float scanClock;
    private boolean closed;
    private long scans,missileCandidates,shipCandidates,jamEntries,targetClears,engineDisables,weaponDisables,budgetHits,sourceTruncations,unsupportedGuidance,conflicts,errors,empVisualRequests,empVisualSkips;
    private int sourceCursor,visualBudget;
    private static <T> Set<T> identitySet(){return Collections.newSetFromMap(new IdentityHashMap<>());}
    private static final class Jam {
        final MissileAPI missile;final ShipAPI source;final Set<ShipAPI> targets;final Map<String,Object> marker=new LinkedHashMap<>();
        Jam(MissileAPI missile,ShipAPI source,Set<ShipAPI> targets){this.missile=missile;this.source=source;this.targets=targets;}
    }
    private static final class EmpState {
        final ShipAPI ship;final List<WeaponAPI> weapons=new ArrayList<>();final List<String> slots=new ArrayList<>();
        double nextPulse;long pulses;int engineCount,groupCount;float interval;
        EmpState(ShipAPI ship){this.ship=ship;}
    }
    public UnicornNtdInterference(CombatEngineAPI engine){
        this.engine=Objects.requireNonNull(engine);
        if(engine.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("NTD interference already has an owner");
        engine.getCustomData().put(KEY+".owner",this);engine.getCustomData().put(KEY,facts);
        // 无引擎外资源：close() 只把导弹的转向速率/转向加速度修饰还原并清 customData marker，
        // 没有句柄；收尾由持有者 UnicornNtdEnhancement.close() 负责。
        publish();
    }
    /** Actual NTD activation is owned/read by the caller; this class never starts or advances a subsystem. */
    public void advance(Collection<? extends ShipAPI> activeUnicornShips,float dt){
        if(closed)return;
        if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid NTD interference time step");
        try{
            Set<ShipAPI> current=identitySet(),seenSources=identitySet();List<ShipAPI> candidates=new ArrayList<>();int checked=0;
            if(activeUnicornShips!=null)for(ShipAPI ship:activeUnicornShips){if(++checked>512){sourceTruncations++;budgetHits++;break;}if(validSource(ship)&&seenSources.add(ship))candidates.add(ship);}
            ShipAPI local=LocalCockpitAudio.resolve(engine);
            candidates.sort(Comparator.<ShipAPI>comparingInt(ship->ship==local?0:1).thenComparing(ship->Objects.toString(ship.getId(),"")));
            for(ShipAPI ship:candidates)if(current.size()<MAX_SOURCES)current.add(ship);else sourceTruncations++;
            if(candidates.size()>MAX_SOURCES)budgetHits++;
            for(ShipAPI old:new ArrayList<>(sources))if(!current.contains(old)){clearSource(old);sources.remove(old);}
            sources.addAll(current);
            if(engine.isCombatOver()){close();return;}
            if(engine.isPaused())return;
            double expires=engine.getTotalElapsedTime(false)+LEASE_SECONDS;
            for(ShipAPI ship:sources){Map<String,Object> lease=sourceLeases.computeIfAbsent(ship,s->new LinkedHashMap<>());
                lease.put("issuer",token);lease.put("expiresAt",expires);ship.setCustomData(SOURCE_KEY,lease);}
            for(Iterator<Jam> it=jams.values().iterator();it.hasNext();){Jam jam=it.next();
                if(!jamValid(jam)){release(jam);it.remove();}else jam.marker.put("expiresAt",expires);}
            scanClock-=dt;
            if(scanClock<=0){scanClock=SCAN_SECONDS;scan(expires);scans++;}
            for(Iterator<EmpState> it=empShips.values().iterator();it.hasNext();){EmpState emp=it.next();if(!inEmpField(emp.ship)){it.remove();continue;}
                for(WeaponAPI weapon:emp.weapons)weapon.setForceNoFireOneFrame(true);}
            publish();
        }catch(RuntimeException failure){errors++;facts.put("lastError",failure.toString());publish();throw failure;}
    }
    private boolean validSource(ShipAPI ship){return live(ship)&&!ship.isStationModule()&&ship.getParentStation()==null&&isUnicornHull(ship.getHullSpec());}
    private boolean live(ShipAPI ship){return ship!=null&&ship.isAlive()&&!ship.isHulk()&&validSide(ship.getOwner())&&engine.isEntityInPlay(ship);}
    private static boolean validSide(int owner){return owner==0||owner==1;}
    private static boolean enemy(int owner,int other){return validSide(owner)&&validSide(other)&&owner!=other;}
    public static boolean isUnicornHull(ShipHullSpecAPI hull){return hullMatches(hull,UNICORNS);}
    public static boolean isKnownFunnelHull(ShipHullSpecAPI hull){return hullMatches(hull,FUNNELS);}
    /** Shared classifier for owned active abilities; no display-name, crew-count or fighter guess. */
    public static boolean isElectronicTarget(ShipAPI ship){return ship!=null&&(ship.isDrone()||isKnownFunnelHull(ship.getHullSpec())||isAutomatedTarget(ship));}
    public static boolean isAutomatedTarget(ShipAPI ship){
        if(ship==null)return false;
        if(ship.getVariant()!=null&&ship.getVariant().hasHullMod("automated"))return true;
        return ship.getHullSpec()!=null&&ship.getHullSpec().getBuiltInMods()!=null&&ship.getHullSpec().getBuiltInMods().contains("automated");
    }
    private static boolean hullMatches(ShipHullSpecAPI spec,Set<String> ids){
        if(spec==null)return false;Set<ShipHullSpecAPI> seen=identitySet();ArrayDeque<ShipHullSpecAPI> queue=new ArrayDeque<>();queue.add(spec);
        for(int count=0;count<8&&!queue.isEmpty();count++){ShipHullSpecAPI hull=queue.removeFirst();if(!seen.add(hull))continue;
            String id=hull.getHullId(),base=hull.getBaseHullId(),parent=hull.getDParentHullId();
            if(id!=null&&ids.contains(id)||base!=null&&ids.contains(base)||parent!=null&&ids.contains(parent))return true;
            if(hull.getBaseHull()!=null)queue.addLast(hull.getBaseHull());if(hull.getDParentHull()!=null)queue.addLast(hull.getDParentHull());}
        return false;
    }
    private Set<ShipAPI> protectedTargets(ShipAPI root){
        Set<ShipAPI> targets=identitySet(),seen=identitySet();targets.add(root);ArrayDeque<ShipAPI> queue=new ArrayDeque<>();queue.add(root);
        while(!queue.isEmpty()&&seen.size()<64){ShipAPI ship=queue.removeFirst();if(!seen.add(ship))continue;
            List<ShipAPI> children=ship.getChildModulesCopy();if(children==null)continue;
            for(ShipAPI child:children)if(child!=null&&!seen.contains(child)){if(hullMatches(child.getHullSpec(),SHIELDS))targets.add(child);if(queue.size()<64)queue.addLast(child);}}
        return targets;
    }
    private boolean jamValid(Jam jam){GuidedMissileAI current=guided(jam.missile);CombatEntityAPI target=current==null?null:current.getTarget();
        return current!=null&&(target==null||jam.targets.contains(target))&&sources.contains(jam.source)&&validSource(jam.source)&&enemy(jam.missile.getOwner(),jam.source.getOwner())
            &&!jam.missile.isExpired()&&!jam.missile.wasRemoved()&&!jam.missile.isFizzling()&&engine.isEntityInPlay(jam.missile)
            &&distanceSquared(jam.source,jam.missile)<=MISSILE_RADIUS*MISSILE_RADIUS;}
    private static GuidedMissileAI guided(MissileAPI missile){
        MissileAIPlugin actual=missile.getUnwrappedMissileAI();if(actual instanceof GuidedMissileAI result)return result;
        return missile.getMissileAI() instanceof GuidedMissileAI result?result:null;
    }
    private void scan(double expires){
        int budget=MAX_CANDIDATES,components=MAX_COMPONENTS;visualBudget=MAX_EMP_VISUALS_PER_SCAN;Set<ShipAPI> affected=identitySet();
        List<ShipAPI> ordered=new ArrayList<>(sources);ordered.sort(Comparator.comparing(ship->Objects.toString(ship.getId(),"")));
        if(!ordered.isEmpty())sourceCursor=Math.floorMod(sourceCursor,ordered.size());
        for(int sourceIndex=0;sourceIndex<ordered.size();sourceIndex++){
            ShipAPI source=ordered.get((sourceCursor+sourceIndex)%ordered.size());
            Set<ShipAPI> targets=protectedTargets(source);CollisionGridAPI grid=engine.getMissileGrid();
            if(grid!=null){Iterator<Object> missiles=grid.getCheckIterator(source.getLocation(),MISSILE_RADIUS*2,MISSILE_RADIUS*2);
                while(missiles.hasNext()&&budget>0){budget--;missileCandidates++;Object object=missiles.next();if(!(object instanceof MissileAPI missile))continue;
                    if(!enemy(missile.getOwner(),source.getOwner())||missile.isExpired()||missile.wasRemoved()||missile.isFizzling()||missile.isFlare()
                            ||!engine.isEntityInPlay(missile)||distanceSquared(source,missile)>MISSILE_RADIUS*MISSILE_RADIUS)continue;
                    GuidedMissileAI ai=guided(missile);if(ai==null){unsupportedGuidance++;continue;}
                    Jam existing=jams.get(missile);CombatEntityAPI target=ai.getTarget();
                    if(existing!=null&&target!=null&&!existing.targets.contains(target)){release(existing);jams.remove(missile);existing=null;}
                    if(!targets.contains(target)){if(existing!=null&&target==null)existing.marker.put("expiresAt",expires);continue;}
                    if(existing==null){if(jams.size()>=MAX_JAMMED){budgetHits++;continue;}
                        Object previous=missile.getCustomData().get(JAM_KEY);if(previous!=null){conflicts++;continue;}
                        existing=new Jam(missile,source,targets);existing.marker.put("schema",1);existing.marker.put("issuer",token);
                        existing.marker.put("protectedRoot",source);existing.marker.put("protectedTargets",targets);existing.marker.put("expiresAt",expires);
                        missile.setCustomData(JAM_KEY,existing.marker);jams.put(missile,existing);jamEntries++;
                        MutableShipStatsAPI stats=missile.getEngineStats();if(stats!=null){stats.getMaxTurnRate().modifyMult(token,0f);stats.getTurnAcceleration().modifyMult(token,0f);}}
                    ai.setTarget(null);targetClears++;
                }
                if(missiles.hasNext())budgetHits++;
            }
            CollisionGridAPI ships=engine.getShipGrid();if(ships==null)continue;
            Iterator<Object> nearby=ships.getCheckIterator(source.getLocation(),EMP_RADIUS*2,EMP_RADIUS*2);
            while(nearby.hasNext()&&budget>0){budget--;shipCandidates++;Object object=nearby.next();if(!(object instanceof ShipAPI drone)||!empCandidate(drone,source))continue;
                if(affected.size()>=MAX_EMP_SHIPS&&!affected.contains(drone)){budgetHits++;continue;}affected.add(drone);
                EmpState state=empShips.computeIfAbsent(drone,EmpState::new);
                if(engine.getTotalElapsedTime(false)>=state.nextPulse)components=empPulse(state,components,source);
            }
            if(nearby.hasNext())budgetHits++;
        }
        if(!ordered.isEmpty())sourceCursor=(sourceCursor+1)%ordered.size();
        empShips.keySet().retainAll(affected);
    }
    private int empPulse(EmpState state,int budget,ShipAPI source){
        ShipAPI ship=state.ship;ShipAPI.HullSize size=ship.getHullSize();
        boolean small=size==ShipAPI.HullSize.FIGHTER||size==ShipAPI.HullSize.FRIGATE;
        state.interval=size==ShipAPI.HullSize.FIGHTER?.5f:size==ShipAPI.HullSize.FRIGATE?.8f:size==ShipAPI.HullSize.DESTROYER?1.2f:2f;
        state.nextPulse=engine.getTotalElapsedTime(false)+state.interval;state.pulses++;state.weapons.clear();state.slots.clear();
        int cap=small?Integer.MAX_VALUE:size==ShipAPI.HullSize.DESTROYER?2:1;
        int allowed=small?cap:Math.min(cap,Math.max(0,UnicornNtdScale.forShip(ship).weaponGroups()));
        List<List<WeaponAPI>> groups=new ArrayList<>();Set<WeaponAPI> assigned=identitySet();
        List<WeaponAPI> all=ship.getAllWeapons();
        if(small){List<WeaponAPI> group=new ArrayList<>();for(WeaponAPI weapon:all)if(!weapon.isDecorative()&&group.size()<64)group.add(weapon);if(!group.isEmpty())groups.add(group);}
        else{
            List<WeaponGroupAPI> nativeGroups=ship.getWeaponGroupsCopy();
            if(nativeGroups!=null)for(WeaponGroupAPI group:nativeGroups){if(groups.size()>=64)break;List<WeaponAPI> weapons=new ArrayList<>();
                for(WeaponAPI weapon:group.getWeaponsCopy())if(!weapon.isDecorative()&&weapons.size()<64&&assigned.add(weapon))weapons.add(weapon);
                weapons.sort(Comparator.comparing(UnicornNtdInterference::slotId));if(!weapons.isEmpty())groups.add(weapons);}
            for(WeaponAPI weapon:all)if(!weapon.isDecorative()&&assigned.add(weapon)&&groups.size()<64)groups.add(List.of(weapon));
            groups.sort(Comparator.comparing(group->slotId(group.get(0))));
        }
        state.groupCount=Math.min(allowed,groups.size());
        for(int index=0;index<state.groupCount;index++)for(WeaponAPI weapon:groups.get(index)){
            if(state.weapons.size()>=64||budget<=0){budgetHits++;break;}budget--;state.weapons.add(weapon);state.slots.add(slotId(weapon));
            if(!weapon.isDisabled()&&!weapon.isPermanentlyDisabled()){weapon.disable(false);weaponDisables++;}}
        state.engineCount=0;
        if(ship.getEngineController()!=null){List<ShipEngineControllerAPI.ShipEngineAPI> motors=ship.getEngineController().getShipEngines();
            int engineLimit=small?Math.min(32,motors.size()):size==ShipAPI.HullSize.DESTROYER?Math.min(16,motors.size()/2):0;
            for(int index=0;index<engineLimit&&budget>0;index++){budget--;state.engineCount++;ShipEngineControllerAPI.ShipEngineAPI motor=motors.get(index);
                if(!motor.isDisabled()&&!motor.isPermanentlyDisabled()){motor.disable(false);engineDisables++;}}}
        if(visualBudget>0&&(state.groupCount>0||state.engineCount>0)){
            visualBudget--;engine.spawnEmpArcVisual(source.getLocation(),source,ship.getLocation(),ship,3f,new Color(230,65,220,180),new Color(255,225,250,220));empVisualRequests++;
        }else empVisualSkips++;
        return budget;
    }
    private static String slotId(WeaponAPI weapon){return weapon.getSlot()!=null&&weapon.getSlot().getId()!=null?weapon.getSlot().getId():Objects.toString(weapon.getId(),"unresolved");}
    private boolean empCandidate(ShipAPI drone,ShipAPI source){return !ZetaOverdriveLifecycle.immune(drone)&&live(drone)&&enemy(drone.getOwner(),source.getOwner())&&!drone.isStationModule()
            &&drone.getParentStation()==null&&isElectronicTarget(drone)&&distanceSquared(drone,source)<=EMP_RADIUS*EMP_RADIUS;}
    private boolean inEmpField(ShipAPI drone){for(ShipAPI source:sources)if(empCandidate(drone,source))return true;return false;}
    private static float distanceSquared(CombatEntityAPI a,CombatEntityAPI b){float x=a.getLocation().x-b.getLocation().x,y=a.getLocation().y-b.getLocation().y;return x*x+y*y;}
    private void release(Jam jam){MutableShipStatsAPI stats=jam.missile.getEngineStats();if(stats!=null){stats.getMaxTurnRate().unmodify(token);stats.getTurnAcceleration().unmodify(token);}
        if(jam.missile.getCustomData().get(JAM_KEY)==jam.marker)jam.missile.getCustomData().remove(JAM_KEY);}
    private void clearSource(ShipAPI source){Map<String,Object> marker=sourceLeases.remove(source);if(source.getCustomData().get(SOURCE_KEY)==marker)source.getCustomData().remove(SOURCE_KEY);}
    /** Public targeted-denial contract, independent of the foreign AI implementation. */
    public static boolean isTargetSuppressed(MissileAPI missile,CombatEntityAPI candidate){
        Map<?,?> marker=activeMarker(missile);return marker!=null&&marker.get("protectedTargets") instanceof Set<?> targets&&targets.contains(candidate);
    }
    public static boolean isMissileSuppressed(MissileAPI missile){return activeMarker(missile)!=null;}
    private static Map<?,?> activeMarker(MissileAPI missile){
        if(missile==null||!(missile.getCustomData().get(JAM_KEY) instanceof Map<?,?> marker)||!(marker.get("expiresAt") instanceof Number expiry))return null;
        CombatEngineAPI engine=Global.getCombatEngine();if(engine==null||engine.getTotalElapsedTime(false)>expiry.doubleValue())return null;
        if(!(marker.get("protectedRoot") instanceof ShipAPI source)||!source.isAlive()||source.isHulk()||!engine.isEntityInPlay(source)
                ||!enemy(missile.getOwner(),source.getOwner())||distanceSquared(source,missile)>MISSILE_RADIUS*MISSILE_RADIUS)return null;
        if(!(source.getCustomData().get(SOURCE_KEY) instanceof Map<?,?> sourceLease)||!Objects.equals(marker.get("issuer"),sourceLease.get("issuer"))
                ||!(sourceLease.get("expiresAt") instanceof Number sourceExpiry)||engine.getTotalElapsedTime(false)>sourceExpiry.doubleValue())return null;
        return marker;
    }
    private void publish(){facts.put("schema",1);facts.put("name","NTD：干扰");facts.put("closed",closed);facts.put("activeSources",sources.size());facts.put("jammedMissiles",jams.size());facts.put("empShips",empShips.size());
        facts.put("scans",scans);facts.put("missileCandidates",missileCandidates);facts.put("shipCandidates",shipCandidates);facts.put("jamEntries",jamEntries);facts.put("targetClears",targetClears);
        facts.put("engineDisables",engineDisables);facts.put("weaponDisables",weaponDisables);facts.put("budgetHits",budgetHits);facts.put("sourceTruncations",sourceTruncations);facts.put("unsupportedGuidance",unsupportedGuidance);facts.put("markerConflicts",conflicts);facts.put("errors",errors);
        facts.put("empVisualRequests",empVisualRequests);facts.put("empVisualSkips",empVisualSkips);
        facts.put("empTargets",empShips.values().stream().map(emp->Map.of("shipId",Objects.toString(emp.ship.getId(),"unknown"),"hullSize",Objects.toString(emp.ship.getHullSize()),"affectedSlots",List.copyOf(emp.slots),
                "weaponGroups",emp.groupCount,"engineCount",emp.engineCount,"pulseInterval",emp.interval,"nextPulse",emp.nextPulse,"pulses",emp.pulses)).toList());
        facts.put("scope","active-NTD caller sources only; hostile targeted missiles900; hostile electronic targets1000; size-scaled selected groups/engines, temporary disable(false), natural repair, no hull damage/AI replacement");}
    @Override public void close(){if(closed)return;closed=true;for(Jam jam:jams.values())release(jam);jams.clear();for(ShipAPI source:new ArrayList<>(sources))clearSource(source);
        sources.clear();empShips.clear();if(engine.getCustomData().get(KEY+".owner")==this)engine.getCustomData().remove(KEY+".owner");publish();}
}
