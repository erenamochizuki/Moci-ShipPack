package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.input.InputEventAPI;
import java.awt.Color;
import java.util.*;
import org.lwjgl.input.Keyboard;

/** Four target-local seconds of control shutdown. No EMP, component HP damage or repair is used. */
public final class ZetaBiosensorJam implements AutoCloseable {
    public static final String KEY="xi.arsenal.zeta.biosensorJam";
    public static final float SECONDS=4f,COOLDOWN=8f,RANGE=1600f,FLUX=450f;
    private static final int MAX_TARGETS=32,MAX_PARTS=64,MAX_WEAPONS=512;
    private static final Color PURPLE=new Color(210,105,255,210);
    private static final ShipCommand[] BLOCKED={ShipCommand.ACCELERATE,ShipCommand.ACCELERATE_BACKWARDS,ShipCommand.DECELERATE,
            ShipCommand.STRAFE_LEFT,ShipCommand.STRAFE_RIGHT,ShipCommand.TURN_LEFT,ShipCommand.TURN_RIGHT};
    private final CombatEngineAPI engine;
    private final Set<ShipAPI> active=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ShipAPI,Float> cooldowns=new IdentityHashMap<>();
    private final Map<ShipAPI,Jam> jams=new IdentityHashMap<>();
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private final List<Map<String,Object>> releases=new ArrayList<>();
    private final String reservation="Zeta 生物传感器压制#"+UUID.randomUUID();
    private OwnNewtypeBindings.Result binding=new OwnNewtypeBindings.Result(false,List.of(),"需要生物传感器过载");
    private float keyClock;
    private long casts,affected;
    private boolean closed;
    private String message="需要生物传感器过载";
    public record Result(boolean accepted,String reason,int targets){}
    public ZetaBiosensorJam(CombatEngineAPI engine){
        this.engine=Objects.requireNonNull(engine);
        if(engine.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Zeta jam already owned");
        engine.getCustomData().put(KEY+".owner",this);engine.getCustomData().put(KEY,Collections.unmodifiableMap(facts));publish();
    }
    public Result requestActivation(ShipAPI source){
        if(closed||!canPilot(source)||!active.contains(source)||source.getSystem()==null
                ||!ZetaContent.SYSTEM.equals(source.getSystem().getId())||source.getSystem().getEffectLevel()<.8f
                ||source.getFluxTracker().isOverloaded()||source.getFluxTracker().isVenting())return refuse("需要本机生物传感器过载");
        if(cooldowns.getOrDefault(source,0f)>0)return refuse("生物传感器压制正在冷却");
        List<ShipAPI> pool=ContentTargetLocks.managed(engine,source)?ContentTargetLocks.inRange(engine,source,RANGE):
                ContentTargetLocks.legal(engine,source,source.getShipTarget())&&ContentTargetLocks.inRange(source,source.getShipTarget(),RANGE)?List.of(source.getShipTarget()):List.of();
        List<ShipAPI> targets=new ArrayList<>();
        for(ShipAPI target:pool){if(targets.size()>=MAX_TARGETS)break;if(!ZetaOverdriveLifecycle.immune(target)&&!jams.containsKey(target))targets.add(target);}
        if(targets.isEmpty())return refuse("需要1600范围内尚未被压制的锁定敌人");
        FluxTrackerAPI flux=source.getFluxTracker();
        if(flux.getMaxFlux()-flux.getCurrFlux()<=FLUX||!flux.increaseFlux(FLUX,false))return refuse("需要450软幅能空间");
        cooldowns.put(source,COOLDOWN);casts++;
        for(ShipAPI target:targets){Jam jam=new Jam(target);jams.put(target,jam);jam.apply();affected++;
            engine.addFloatingText(target.getLocation(),"生物传感器：宕机",22,PURPLE,target,0,0);}
        message="压制 "+targets.size()+" 个锁定目标，持续4秒";publish();return new Result(true,message,targets.size());
    }
    private Result refuse(String reason){message=reason;publish();return new Result(false,reason,0);}
    private boolean canPilot(ShipAPI source){return source!=null&&source==engine.getPlayerShip()&&live(source)&&!engine.isPaused()
            &&!source.controlsLocked()&&!engine.isUIShowingDialog()&&(engine.getCombatUI()==null||
            !engine.getCombatUI().isShowingCommandUI()&&!engine.getCombatUI().isShowingDeploymentDialog());}
    public void advance(Collection<ShipAPI> realOverdriveSources,float dt,List<InputEventAPI> events){
        if(closed)return;if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid Zeta jam clock");
        if(Global.getCombatEngine()!=engine||engine.isCombatOver()){close();return;}
        active.clear();if(realOverdriveSources!=null)active.addAll(realOverdriveSources);
        ShipAPI player=engine.getPlayerShip();
        if(!active.contains(player)){clearReservation();binding=new OwnNewtypeBindings.Result(false,List.of(),"需要生物传感器过载");}
        if(!engine.isPaused()){
            keyClock-=dt;if(active.contains(player)&&keyClock<=0)refreshBinding(player);
            for(Iterator<Map.Entry<ShipAPI,Float>> it=cooldowns.entrySet().iterator();it.hasNext();){var entry=it.next();
                float value=entry.getValue()-local(entry.getKey(),dt);if(value<=0||!live(entry.getKey()))it.remove();else entry.setValue(value);}
            if(events!=null)for(InputEventAPI event:events){
                if(event==null||event.isConsumed()||!event.isKeyboardEvent()||!event.isKeyDownEvent()||event.isRepeat()||!binding.available())continue;
                if(event.getEventValue()==binding.keyCode()&&requestActivation(player).accepted())event.consume();
            }
            for(Iterator<Jam> it=jams.values().iterator();it.hasNext();){Jam jam=it.next();
                if(ZetaOverdriveLifecycle.immune(jam.target)||!live(jam.target)||jam.target.getOwner()!=jam.owner){jam.release("target-invalid-or-owner-changed");it.remove();continue;}
                jam.remaining-=local(jam.target,dt);
                if(jam.remaining<=0){jam.release("four-local-seconds-finished");it.remove();}else jam.apply();
            }
        }
        hud();publish();
    }
    private void refreshBinding(ShipAPI player){
        // The actual Zeta subsystem panel advertises N for information display.
        // Its content hook is not consistently present in MagicLib's registry.
        clearReservation();binding=OwnNewtypeBindings.select(engine,player,binding.available()?binding.keyCode():Keyboard.KEY_H,
                Map.of(Keyboard.KEY_N,"Moci 子系统信息显示",Keyboard.KEY_X,"Z变形"));keyClock=.5f;
        if(!binding.available())return;
        Object value=engine.getCustomData().get(OwnNewtypeBindings.RESERVATIONS_KEY);if(value!=null&&!(value instanceof Map<?,?>)){binding=new OwnNewtypeBindings.Result(false,List.of(),"扩展键位表类型冲突，未接管字母键");return;}Map<Integer,String> copy=new LinkedHashMap<>();
        if(value instanceof Map<?,?> previous)for(var item:previous.entrySet()){
            if(!(item.getKey() instanceof Integer key)||!(item.getValue() instanceof String text))throw new IllegalStateException("Invalid combat input reservation");copy.put(key,text);}
        if(copy.containsKey(binding.keyCode())){binding=new OwnNewtypeBindings.Result(false,List.of(),"字母键刚被其他机制占用");return;}
        copy.put(binding.keyCode(),reservation);engine.getCustomData().put(OwnNewtypeBindings.RESERVATIONS_KEY,copy);
    }
    private void clearReservation(){Object value=engine.getCustomData().get(OwnNewtypeBindings.RESERVATIONS_KEY);
        if(value instanceof Map<?,?> previous&&previous.containsValue(reservation)){
            Map<Object,Object> copy=new LinkedHashMap<>(previous);copy.entrySet().removeIf(e->reservation.equals(e.getValue()));
            if(copy.isEmpty())engine.getCustomData().remove(OwnNewtypeBindings.RESERVATIONS_KEY);else engine.getCustomData().put(OwnNewtypeBindings.RESERVATIONS_KEY,copy);}}
    private final class Jam {
        final ShipAPI target;final int owner;final String modifier=KEY+"."+UUID.randomUUID();
        final Set<ShipAPI> parts=Collections.newSetFromMap(new IdentityHashMap<>());
        float remaining=SECONDS;
        Jam(ShipAPI target){this.target=target;owner=target.getOwner();}
        void apply(){
            ArrayDeque<ShipAPI> queue=new ArrayDeque<>();Set<ShipAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>()),current=Collections.newSetFromMap(new IdentityHashMap<>());queue.add(target);int weapons=0;
            while(!queue.isEmpty()&&seen.size()<MAX_PARTS){ShipAPI part=queue.removeFirst();if(!seen.add(part)||!live(part)||part.getOwner()!=owner)continue;
                current.add(part);parts.add(part);MutableShipStatsAPI s=part.getMutableStats();
                for(MutableStat stat:new MutableStat[]{s.getMaxSpeed(),s.getAcceleration(),s.getDeceleration(),s.getMaxTurnRate(),s.getTurnAcceleration()})stat.modifyMult(modifier,0);
                s.getWeaponTurnRateBonus().modifyMult(modifier,0);s.getBeamWeaponTurnRateBonus().modifyMult(modifier,0);
                for(ShipCommand command:BLOCKED)part.blockCommandForOneFrame(command);
                for(WeaponAPI weapon:part.getAllWeapons())if(++weapons<=MAX_WEAPONS&&!weapon.isDecorative()){
                    weapon.setForceNoFireOneFrame(true);weapon.stopFiring();}
                ShipEngineControllerAPI control=part.getEngineController();if(control!=null)
                    for(ShipEngineControllerAPI.ShipEngineAPI drive:control.getShipEngines())control.setFlameLevel(drive.getEngineSlot(),0);
                part.setJitterUnder(this,PURPLE,.22f,3,1,3);
                List<ShipAPI> children=part.getChildModulesCopy();if(children!=null)for(ShipAPI child:children)if(child!=null&&queue.size()<MAX_PARTS)queue.addLast(child);
            }
            for(Iterator<ShipAPI> it=parts.iterator();it.hasNext();){ShipAPI part=it.next();if(!current.contains(part)){clearPart(part);it.remove();}}
        }
        void clearPart(ShipAPI part){MutableShipStatsAPI s=part.getMutableStats();
            for(MutableStat stat:new MutableStat[]{s.getMaxSpeed(),s.getAcceleration(),s.getDeceleration(),s.getMaxTurnRate(),s.getTurnAcceleration()})stat.unmodify(modifier);
            s.getWeaponTurnRateBonus().unmodify(modifier);s.getBeamWeaponTurnRateBonus().unmodify(modifier);}
        void release(String reason){for(ShipAPI part:parts)clearPart(part);
            if(releases.size()>=32)releases.remove(0);releases.add(Map.of("targetId",Objects.toString(target.getId(),""),"reason",reason,"remaining",Math.max(0,remaining)));parts.clear();}
        Map<String,Object> state(){return Map.of("targetId",Objects.toString(target.getId(),""),"remainingTargetLocalSeconds",remaining,"parts",parts.size(),"originalOwner",owner);}
    }
    private static float local(ShipAPI ship,float dt){float value=ship.getMutableStats().getTimeMult().getModifiedValue();
        if(!Float.isFinite(value)||value<0)throw new IllegalStateException("Invalid Zeta local time");return dt*value;}
    private boolean live(ShipAPI ship){return ship!=null&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship);}
    private void hud(){ShipAPI player=engine.getPlayerShip();if(!active.contains(player)||!engine.isUIShowingHUD())return;
        float cd=cooldowns.getOrDefault(player,0f);engine.maintainStatusForPlayerShip(KEY,"graphics/icons/hullsys/targeting_feed.png",
                "生物传感器压制 ["+binding.keyName()+"]",cd>0?String.format(Locale.ROOT,"冷却 %.1f秒",cd):"锁定后压制4秒 · 450软幅能 · 无EMP",false);}
    private void publish(){facts.put("schema",1);facts.put("closed",closed);facts.put("casts",casts);facts.put("affected",affected);facts.put("message",message);
        facts.put("keyCode",binding.keyCode());facts.put("keyName",binding.keyName());facts.put("keyAvailable",binding.available());facts.put("jams",jams.values().stream().map(Jam::state).toList());
        facts.put("cooldown",cooldowns.getOrDefault(engine.getPlayerShip(),0f));facts.put("releases",List.copyOf(releases));
        facts.put("scope","4 target-local seconds of engine/weapon control shutdown; no EMP, overload, HP modification, damage or component repair; existing momentum drifts naturally");}
    public Map<String,Object> status(){publish();return Collections.unmodifiableMap(new LinkedHashMap<>(facts));}
    @Override public void close(){if(closed)return;closed=true;for(Jam jam:jams.values())jam.release("battle-close");jams.clear();cooldowns.clear();active.clear();clearReservation();publish();
        if(engine.getCustomData().get(KEY+".owner")==this)engine.getCustomData().remove(KEY+".owner");}
}
