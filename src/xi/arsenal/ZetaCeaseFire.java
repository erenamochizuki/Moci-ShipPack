package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.*;
import org.lwjgl.input.Keyboard;

/** Per-pilot letter binding for the original native hold-fire state. X remains transform.
 * The Zeta actor owns the only clock; this helper never installs another engine plugin. */
public final class ZetaCeaseFire implements AutoCloseable {
    public static final String KEY="xi.arsenal.zeta.ceaseFire";
    @FunctionalInterface interface BindingLookup {OwnNewtypeBindings.Result select(CombatEngineAPI engine,ShipAPI ship,int current);}
    private final Thread thread=Thread.currentThread();private final CombatEngineAPI engine;private final ShipAPI ship;private final Runnable cancelOwnCharge;
    private final BindingLookup lookup;private final String reservation="Zeta 武器停火#"+UUID.randomUUID();
    private OwnNewtypeBindings.Result binding=new OwnNewtypeBindings.Result(false,List.of(),"等待本机键位检查");
    private float clock;private boolean closed;private long toggles;private String reason="等待本机键位检查";
    public ZetaCeaseFire(CombatEngineAPI engine,ShipAPI ship,Runnable cancelOwnCharge){this(engine,ship,cancelOwnCharge,(e,s,k)->OwnNewtypeBindings.select(e,s,k,Map.of(Keyboard.KEY_X,"Z高达变形")));}
    ZetaCeaseFire(CombatEngineAPI engine,ShipAPI ship,Runnable cancelOwnCharge,BindingLookup lookup){
        this.engine=Objects.requireNonNull(engine);this.ship=Objects.requireNonNull(ship);this.cancelOwnCharge=Objects.requireNonNull(cancelOwnCharge);this.lookup=Objects.requireNonNull(lookup);
        if(!ZetaMobility.supports(ship)||ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Complete unowned Zeta cease-fire actor required");
        ship.setCustomData(KEY+".owner",this);publish();
    }
    public void advance(float dt){check();if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid cease-fire frame amount");
        if(ship!=engine.getPlayerShip()||!ship.isAlive()||ship.isHulk()){clearReservation();binding=new OwnNewtypeBindings.Result(false,List.of(),"仅当前驾驶的Z高达");}
        else if((clock-=dt)<=0)refresh();hud();publish();
    }
    public void processInput(List<InputEventAPI> events){check();if(events==null||!canPilot())return;
        for(InputEventAPI event:events){if(event==null||event.isConsumed()||!event.isKeyboardEvent()||!event.isKeyDownEvent()||event.isRepeat())continue;
            // Recheck before consuming a potentially reassigned key; never steal a conflicting input.
            if(binding.available()&&event.getEventValue()==binding.keyCode()){refresh();if(binding.available()&&event.getEventValue()==binding.keyCode()){toggle();event.consume();}}}
    }
    public boolean toggle(){check();if(!canPilot()){reason="当前不能操作本机武器停火";publish();return false;}
        ship.setHoldFire(!ship.isHoldFire());toggles++;if(ship.isHoldFire())cancelOwnCharge.run();reason=ship.isHoldFire()?"已停火":"已恢复开火";hud();publish();return true;
    }
    private boolean canPilot(){return !closed&&ship==engine.getPlayerShip()&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship)&&!engine.isPaused()&&!ship.controlsLocked()&&!engine.isUIShowingDialog()&&(engine.getCombatUI()==null||!engine.getCombatUI().isShowingCommandUI()&&!engine.getCombatUI().isShowingDeploymentDialog());}
    private void refresh(){clearReservation();int current=binding.available()?binding.keyCode():Keyboard.KEY_H;binding=lookup.select(engine,ship,current);clock=.5f;
        if(!binding.available()){reason=binding.error().isEmpty()?"没有可用停火字母键":binding.error();return;}
        Object old=engine.getCustomData().get(OwnNewtypeBindings.RESERVATIONS_KEY);Map<Integer,String> copy=new LinkedHashMap<>();
        if(old!=null&&!(old instanceof Map<?,?>)){binding=new OwnNewtypeBindings.Result(false,List.of(),"键位登记表类型冲突");return;}
        if(old instanceof Map<?,?> entries)for(var entry:entries.entrySet()){if(!(entry.getKey() instanceof Integer key)||!(entry.getValue() instanceof String value)){binding=new OwnNewtypeBindings.Result(false,List.of(),"键位登记格式错误");return;}copy.put(key,value);}
        if(copy.putIfAbsent(binding.keyCode(),reservation)!=null){binding=new OwnNewtypeBindings.Result(false,List.of(),"停火字母键已被占用");return;}
        engine.getCustomData().put(OwnNewtypeBindings.RESERVATIONS_KEY,copy);reason=ship.isHoldFire()?"已停火":"正常开火";
    }
    private void clearReservation(){Object old=engine.getCustomData().get(OwnNewtypeBindings.RESERVATIONS_KEY);if(old instanceof Map<?,?> entries&&entries.containsValue(reservation)){Map<Object,Object> copy=new LinkedHashMap<>(entries);copy.entrySet().removeIf(e->reservation.equals(e.getValue()));if(copy.isEmpty())engine.getCustomData().remove(OwnNewtypeBindings.RESERVATIONS_KEY);else engine.getCustomData().put(OwnNewtypeBindings.RESERVATIONS_KEY,copy);}}
    private void hud(){if(ship==engine.getPlayerShip()&&engine.isUIShowingHUD())engine.maintainStatusForPlayerShip(KEY,"graphics/icons/hullsys/high_energy_focus.png","Z高达 · 武器停火",binding.available()?binding.keyName()+" "+(ship.isHoldFire()?"恢复开火 · 已停火":"停火 · 正常开火"):reason,ship.isHoldFire());}
    public Map<String,Object> status(){return Map.of("closed",closed,"key",binding.keyName(),"keyCode",binding.keyCode(),"available",binding.available(),"holdFire",ship.isHoldFire(),"toggles",toggles,"reason",reason,"scope","Native ship hold-fire; local current pilot only, X transform unchanged");}
    private void publish(){ship.setCustomData(KEY,status());}
    private void check(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Zeta cease-fire requires its battle owner thread");if(closed)throw new IllegalStateException("Cease-fire closed");}
    @Override public void close(){if(closed)return;check();clearReservation();closed=true;if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");publish();}
}
