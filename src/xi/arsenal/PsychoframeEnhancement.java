package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import data.scripts.weapons.Moci_WingmanSupport;
import java.util.*;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** Owned stat overlay for the shared native F system. Never advances a Moci callback or moves a drone. */
public final class PsychoframeEnhancement implements AutoCloseable {
    public static final String KEY="xi.arsenal.psychoframe", SYSTEM_ID="Moci_fw_psychoframe";
    public static final String MODIFIER=KEY+".stats", ORIGINAL_PSYCOMMU="Moci_PsycommuSystem";
    public static final float SPEED=1.2f, ACCEL=1.8f, BRAKE=1.8f, TURN=1.65f, TURN_ACCEL=2.1f,
            GUN_TURN=1.8f, COOLDOWN_MULT=1.25f, DRONE_SPEED=1.35f, DRONE_ACCEL=1.5f,
            DRONE_TURN=1.4f, DRONE_AIM=1.7f, DRONE_ENERGY_DAMAGE=1.5f, SPREAD=.65f;
    private static final int MAX_SCAN=8192, MAX_ACTORS=256, MAX_DRONES=2048, MAX_WEAPONS=256;
    private static final float EPS=.0001f;
    private final CombatEngineAPI engine;
    private final String systemId;
    private final Map<ShipAPI,Actor> actors=new IdentityHashMap<>(), drones=new IdentityHashMap<>();
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private float scanClock;
    private int cursor;
    private long scans,budgetSkips,sourceConflicts,unclonableSpecs,fieldConflicts;
    private boolean closed;

    public PsychoframeEnhancement(CombatEngineAPI engine){this(engine,SYSTEM_ID);}
    public PsychoframeEnhancement(CombatEngineAPI engine,String systemId){
        this.engine=Objects.requireNonNull(engine);this.systemId=Objects.requireNonNull(systemId);
        if(systemId.isBlank())throw new IllegalArgumentException("Missing owned psychoframe system ID");
        if(engine.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Psychoframe overlay already owned");
        engine.getCustomData().put(KEY+".owner",this);engine.getCustomData().put(KEY,facts);publish();
    }
    /** Exact body/base/D-parent identities; shield/arm/wing modules are not eligible sources. */
    public static boolean supports(ShipAPI ship){
        return ship!=null&&!ship.isDrone()&&!ship.isFighter()&&!ship.isStationModule()
                &&ship.getParentStation()==null&&hullMatches(ship.getHullSpec(),Set.of("Moci_MSN_04_2","Moci_RX_93_v2"));
    }
    public void advance(float dt){
        if(closed)return;if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid psychoframe frame amount");
        if(Global.getCombatEngine()!=engine||engine.isCombatOver()){close();return;}
        if(engine.isPaused())return;
        scanClock-=dt;if(scanClock<=0){scanClock=.1f;scan();}
        for(Iterator<Actor> it=actors.values().iterator();it.hasNext();){Actor actor=it.next();
            if(!live(actor.ship)||!supports(actor.ship)||!ownedSystem(actor.ship)){actor.reset();if(actor.cannon!=null)actor.cannon.close();it.remove();continue;}
            actor.apply(level(actor.ship));
            if(actor.cannon!=null){actor.cannon.advance(dt);actor.cannon.present(dt);}
        }
        for(Iterator<Actor> it=drones.values().iterator();it.hasNext();){Actor drone=it.next();
            ShipAPI source=resolveSource(drone.ship);
            if(!live(drone.ship)||source!=drone.source||!live(source)||source.getOwner()!=drone.ship.getOwner()
                    ||!actors.containsKey(source)||!knownDrone(drone.ship)){drone.reset();it.remove();continue;}
            drone.apply(level(source));
        }
        publish();
    }
    private void scan(){
        scans++;List<ShipAPI> ships=engine.getShips();int size=ships.size(),limit=Math.min(size,MAX_SCAN);
        // Discover mothers before children. A newly deployed bit can otherwise wait until the next bounded scan.
        for(int n=0;n<limit;n++){ShipAPI ship=ships.get((cursor+n)%Math.max(1,size));
            if(!live(ship)||!supports(ship)||!ownedSystem(ship)||actors.containsKey(ship))continue;
            if(actors.size()>=MAX_ACTORS){budgetSkips++;continue;}actors.put(ship,new Actor(ship,null,false));}
        for(int n=0;n<limit;n++){ShipAPI ship=ships.get((cursor+n)%Math.max(1,size));
            if(!live(ship)||!knownDrone(ship)||drones.containsKey(ship))continue;
            ShipAPI source=resolveSource(ship);if(!live(source)||!actors.containsKey(source)||source.getOwner()!=ship.getOwner())continue;
            if(drones.size()>=MAX_DRONES){budgetSkips++;continue;}
            boolean privateMovement=hullMatches(ship.getHullSpec(),Set.of("Moci_RX_93_v2_bit"));
            drones.put(ship,new Actor(ship,source,privateMovement));}
        if(size>limit)budgetSkips+=size-limit;cursor=size==0?0:(cursor+limit)%size;
    }
    private boolean ownedSystem(ShipAPI ship){return ship.getSystem()!=null&&systemId.equals(ship.getSystem().getId());}
    private static float level(ShipAPI ship){return ship!=null&&ship.getSystem()!=null?clamp(ship.getSystem().getEffectLevel()):0f;}
    private boolean live(ShipAPI ship){return ship!=null&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship);}
    private static boolean knownDrone(ShipAPI ship){return ship!=null&&ship.isDrone()&&hullMatches(ship.getHullSpec(),Set.of("Moci_MSN_04_2_bit","Moci_RX_93_v2_bit"));}
    /** All non-null native/public links must agree. Ownership and proximity alone never identify a mother. */
    private ShipAPI resolveSource(ShipAPI drone){
        if(drone==null)return null;Object flag=!PsychoframeHiNuSupport.reliableNativeFlag(drone)||drone.getAIFlags()==null?null:drone.getAIFlags().getCustom(AIFlags.DRONE_MOTHERSHIP);
        ShipAPI found=null;for(ShipAPI candidate:new ShipAPI[]{PsychoframeHiNuSupport.motherFor(drone),drone.getDroneSource(),Moci_WingmanSupport.getTrackedMothership(drone),flag instanceof ShipAPI s?s:null}){
            if(candidate==null)continue;ShipAPI root=Moci_WingmanSupport.resolveRootMothership(candidate);
            if(found!=null&&found!=root){sourceConflicts++;return null;}found=root;
        }return found;
    }
    private static boolean hullMatches(ShipHullSpecAPI hull,Set<String> ids){
        if(hull==null)return false;Set<ShipHullSpecAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ShipHullSpecAPI> queue=new ArrayDeque<>();queue.add(hull);
        for(int count=0;count<16&&!queue.isEmpty();count++){ShipHullSpecAPI item=queue.removeFirst();if(!seen.add(item))continue;
            if(member(ids,item.getHullId())||member(ids,item.getBaseHullId())||member(ids,item.getDParentHullId()))return true;
            if(item.getBaseHull()!=null)queue.addLast(item.getBaseHull());if(item.getDParentHull()!=null)queue.addLast(item.getDParentHull());}
        return false;
    }
    private static boolean member(Set<String> ids,String id){return id!=null&&ids.contains(id);}
    private final class Actor {
        final ShipAPI ship,source;final MutableShipStatsAPI stats;final boolean privateMovement;
        final HiNuHandCannon cannon;
        final Map<WeaponAPI,SpreadChange> spread=new IdentityHashMap<>();
        float effect,originalEnergy=1f,originalBallistic=1f;
        Actor(ShipAPI ship,ShipAPI source,boolean privateMovement){this.ship=ship;this.source=source;this.privateMovement=privateMovement;stats=Objects.requireNonNull(ship.getMutableStats());cannon=source==null&&HiNuHandCannon.supports(ship)?new HiNuHandCannon(ship):null;}
        void apply(float value){effect=value;if(value<=0){reset();return;}
            // Hi-Nu movement belongs to its single owned, clock-neutral callback port.
            // It reads the real F level directly; an extra ShipStats speed multiplier
            // would not move that scripted controller and is deliberately omitted.
            if(!privateMovement){
                stats.getMaxSpeed().modifyMult(MODIFIER,blend(source==null?SPEED:DRONE_SPEED,value));
                stats.getAcceleration().modifyMult(MODIFIER,blend(source==null?ACCEL:DRONE_ACCEL,value));
                stats.getDeceleration().modifyMult(MODIFIER,blend(source==null?BRAKE:DRONE_ACCEL,value));
                stats.getMaxTurnRate().modifyMult(MODIFIER,blend(source==null?TURN:DRONE_TURN,value));
                stats.getTurnAcceleration().modifyMult(MODIFIER,blend(source==null?TURN_ACCEL:DRONE_TURN,value));
            }
            float gunTurn=blend(source==null?GUN_TURN:DRONE_AIM,value);
            stats.getWeaponTurnRateBonus().modifyMult(MODIFIER,gunTurn);stats.getBeamWeaponTurnRateBonus().modifyMult(MODIFIER,gunTurn);
            improveAim(stats,value);
            float rate=1f/blend(COOLDOWN_MULT,value);
            // Both exact mothers include original Moci_PsycommuSystem (non-capital RoF x1.5).
            // Compensate ONLY that known multiplier without deleting it or cancelling other mods.
            originalEnergy=source==null?knownOriginal(stats.getEnergyRoFMult()):1f;
            originalBallistic=source==null?knownOriginal(stats.getBallisticRoFMult()):1f;
            stats.getEnergyRoFMult().modifyMult(MODIFIER,rate/originalEnergy);
            stats.getBallisticRoFMult().modifyMult(MODIFIER,rate/originalBallistic);
            stats.getMissileRoFMult().modifyMult(MODIFIER,rate);
            /* 赛克缪（F）开启时，浮游炮额外获得 +50% 能量武器伤害。
               只作用于有真实母舰来源的无人机（source!=null）；母舰本体不吃这条，
               那条线仍归 Moci_PsycommuSystem 原生域，避免双重叠加。 */
            if(source!=null)stats.getEnergyWeaponDamageMult().modifyMult(MODIFIER,blend(DRONE_ENERGY_DAMAGE,value));
            updateWeapons(value);
        }
        void updateWeapons(float value){
            List<WeaponAPI> weapons=ship.getAllWeapons();Set<WeaponAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            int count=0;for(WeaponAPI weapon:weapons){if(count++>=MAX_WEAPONS){budgetSkips+=Math.max(0,weapons.size()-MAX_WEAPONS);break;}
                if(weapon==null||weapon.isDecorative())continue;seen.add(weapon);
                SpreadChange old=spread.get(weapon);if(old!=null&&old.spec!=weapon.getSpec()){old.restore();spread.remove(weapon);old=null;}
                if(old==null){WeaponSpecAPI before=weapon.getSpec();if(before==null||!hasSpread(before))continue;
                    weapon.ensureClonedSpec();WeaponSpecAPI local=weapon.getSpec();
                    if(local==null||local==weapon.getOriginalSpec()){unclonableSpecs++;continue;}
                    old=new SpreadChange(local);spread.put(weapon,old);}
                old.apply(blend(SPREAD,value));
            }
            for(Iterator<Map.Entry<WeaponAPI,SpreadChange>> it=spread.entrySet().iterator();it.hasNext();){var entry=it.next();if(!seen.contains(entry.getKey())){entry.getValue().restore();it.remove();}}
        }
        void reset(){
            effect=0f;for(MutableStat stat:new MutableStat[]{stats.getMaxSpeed(),stats.getAcceleration(),stats.getDeceleration(),stats.getMaxTurnRate(),stats.getTurnAcceleration(),
                    stats.getAutofireAimAccuracy(),stats.getEnergyRoFMult(),stats.getBallisticRoFMult(),stats.getMissileRoFMult(),
                    stats.getEnergyWeaponDamageMult()})stat.unmodify(MODIFIER);
            stats.getWeaponTurnRateBonus().unmodify(MODIFIER);stats.getBeamWeaponTurnRateBonus().unmodify(MODIFIER);
            for(SpreadChange change:spread.values())change.restore();spread.clear();
        }
        /**
         * 海牛内藏炮：只在既有战斗时钟里推进，不注册任何插件。
         * 发射段由<b>真实原生开火边沿</b>触发（同一机体上非装饰武器 isFiring() false→true），
         * 不伪造伤害、不重放回调；被拒绝时 cannon 自己返回 accepted=false。
         */
        Map<String,Object> status(){Map<String,Object> row=new LinkedHashMap<>();row.put("shipId",id(ship));row.put("hullId",ship.getHullSpec().getHullId());row.put("effectLevel",effect);
            boolean adapter=privateMovement&&ownedHiNuAdapter(ship);
            row.put("sourceId",source==null?"":id(source));row.put("movementControllerLimited",privateMovement&&!adapter);
            row.put("movementMode",adapter?"Owned Hi-Nu callback port: real scripted speed/turn and native shot cooldown gate":privateMovement?"Unadapted original Hi-Nu private controller; movement boost unavailable through public stats":"Native movement stats");
            if(cannon!=null)row.put("handCannon",cannon.status());
            row.put("clonedSpreadWeapons",spread.size());row.put("originalEnergyRoFMult",originalEnergy);row.put("originalBallisticRoFMult",originalBallistic);
            if(source!=null)row.put("droneEnergyDamageMult",blend(DRONE_ENERGY_DAMAGE,effect));
            row.put("weaponCooldownMult",blend(COOLDOWN_MULT,effect));return Collections.unmodifiableMap(row);}
    }
    private static float knownOriginal(MutableStat stat){MutableStat.StatMod original=stat.getMultStatMod(ORIGINAL_PSYCOMMU);
        return original!=null&&Float.isFinite(original.getValue())&&original.getValue()>EPS?original.getValue():1f;}
    private static void improveAim(MutableShipStatsAPI stats,float level){MutableStat aim=stats.getAutofireAimAccuracy();aim.unmodify(MODIFIER);
        float current=aim.getModifiedValue(),mult=aim.getMult();if(!Float.isFinite(current)||!Float.isFinite(mult)||mult<=EPS||current>=1f)return;
        float target=current+(1f-current)*level;aim.modifyFlat(MODIFIER,(target-current)/mult);}
    private static boolean hasSpread(WeaponSpecAPI spec){return spec.getMinSpread()>0||spec.getMaxSpread()>0||spec.getSpreadBuildup()>0;}
    private final class SpreadChange {
        final WeaponSpecAPI spec;final Field min,max,buildup;
        SpreadChange(WeaponSpecAPI spec){this.spec=spec;min=new Field(spec::getMinSpread,v->spec.setMinSpread((float)v));max=new Field(spec::getMaxSpread,v->spec.setMaxSpread((float)v));buildup=new Field(spec::getSpreadBuildup,v->spec.setSpreadBuildup((float)v));}
        void apply(float mult){min.apply(mult);max.apply(mult);buildup.apply(mult);}
        void restore(){min.restore();max.restore();buildup.restore();}
    }
    private final class Field {
        final DoubleSupplier getter;final DoubleConsumer setter;final float original;float written;boolean relinquished;
        Field(DoubleSupplier getter,DoubleConsumer setter){this.getter=getter;this.setter=setter;original=(float)getter.getAsDouble();written=original;relinquished=!Float.isFinite(original);}
        void apply(float mult){if(relinquished)return;if(!same((float)getter.getAsDouble(),written)){relinquished=true;fieldConflicts++;return;}
            written=original*mult;setter.accept(written);}
        void restore(){if(!relinquished&&same((float)getter.getAsDouble(),written))setter.accept(original);}
    }
    public Map<String,Object> status(){return Collections.unmodifiableMap(new LinkedHashMap<>(facts));}
    private void publish(){facts.put("schema",1);facts.put("closed",closed);facts.put("systemId",systemId);facts.put("sources",actors.values().stream().map(Actor::status).toList());
        facts.put("drones",drones.values().stream().map(Actor::status).toList());facts.put("sourceCount",actors.size());facts.put("droneCount",drones.size());
        facts.put("movementLimitedDrones",drones.values().stream().filter(a->a.privateMovement&&!ownedHiNuAdapter(a.ship)).count());facts.put("nativeAccuracyCap",1f);
        facts.put("scans",scans);facts.put("budgetSkips",budgetSkips);facts.put("sourceConflicts",sourceConflicts);facts.put("unclonableSpecs",unclonableSpecs);facts.put("fieldConflicts",fieldConflicts);
        facts.put("scope","Exact Nightingale/Hi-Nu main bodies and proven-source funnels; Hi-Nu scripted movement requires the owned callback marker");}
    private static boolean ownedHiNuAdapter(ShipAPI ship){
        Object marker=ship.getCustomData().get("xi.arsenal.hinuController");
        if(!(marker instanceof Map<?,?> row)||!Boolean.TRUE.equals(row.get("movementAdapterOwned")))
            return false;
        if(!(row.get("schema") instanceof Number version)||version.intValue()!=1)return false;
        Object owner=row.get("controller");
        if(!(owner instanceof String controller))return false;
        // 海牛翼部浮游炮控制器已收编回我方类（data.scripts.weapons），旧 xi fork 名保留兼容。
        return "data.scripts.weapons.Moci_HSITunnelController_new".equals(controller)
                ||"data.scripts.weapons.Moci_HSITunnelController".equals(controller)
                ||"xi.arsenal.PsychoframeHiNuController".equals(controller)
                ||"xi.arsenal.PsychoframeHiNuHwsController".equals(controller);
    }
    public void processHandInput(java.util.List<com.fs.starfarer.api.input.InputEventAPI> events){Actor actor=actors.get(engine.getPlayerShip());if(actor!=null&&actor.cannon!=null)actor.cannon.processInput(events);}
    public void renderHands(){for(Actor actor:actors.values())if(actor.cannon!=null)actor.cannon.render();}
    public void renderHandHud(){Actor actor=actors.get(engine.getPlayerShip());if(actor!=null&&actor.cannon!=null)actor.cannon.renderHud();}
    @Override public void close(){if(closed)return;closed=true;for(Actor actor:drones.values())actor.reset();for(Actor actor:actors.values()){actor.reset();if(actor.cannon!=null)actor.cannon.close();}drones.clear();actors.clear();
        if(engine.getCustomData().get(KEY+".owner")==this)engine.getCustomData().remove(KEY+".owner");publish();}
    private static float clamp(float value){return Float.isFinite(value)?Math.max(0f,Math.min(1f,value)):0f;}
    private static float blend(float target,float level){return 1f+(target-1f)*level;}
    private static boolean same(float a,float b){return Float.isFinite(a)&&Float.isFinite(b)&&Math.abs(a-b)<=EPS;}
    private static String id(ShipAPI ship){return ship.getId()==null?"":ship.getId();}
}
