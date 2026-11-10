package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.input.InputEventAPI;
import data.subsystems.Moci_HotkeySubsystem;
import data.subsystems.Moci_HotkeySubsystem.Binding;
import java.util.*;

/** Per-pilot key for the original native hold-fire state. X remains transform.
 * The key is a MagicLib subsystem hotkey (LunaLib「武器停火键（Z高达）」can rebind it).
 * The Zeta actor owns the only clock; this helper never installs another engine plugin. */
public final class ZetaCeaseFire implements AutoCloseable {
    public static final String KEY="xi.arsenal.zeta.ceaseFire";
    private final Thread thread=Thread.currentThread();private final CombatEngineAPI engine;private final ShipAPI ship;private final Runnable cancelOwnCharge;
    private final Hotkey hotkey;
    private Binding binding=Binding.none("等待本机键位检查");
    private float clock;private boolean closed;private long toggles;private String reason="等待本机键位检查";
    public ZetaCeaseFire(CombatEngineAPI engine,ShipAPI ship,Runnable cancelOwnCharge){
        this.engine=Objects.requireNonNull(engine);this.ship=Objects.requireNonNull(ship);this.cancelOwnCharge=Objects.requireNonNull(cancelOwnCharge);
        if(!ZetaMobility.supports(ship)||ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Complete unowned Zeta cease-fire actor required");
        hotkey=Moci_HotkeySubsystem.attach(ship,Hotkey.class,Hotkey::new);
        ship.setCustomData(KEY+".owner",this);publish();
    }
    public void advance(float dt){check();if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid cease-fire frame amount");
        if(ship!=engine.getPlayerShip()||!ship.isAlive()||ship.isHulk()){binding=Binding.none("仅当前驾驶的Z高达");clock=0;}
        else if((clock-=dt)<=0)refresh();hud();publish();
    }
    public void processInput(List<InputEventAPI> events){check();if(events==null||!canPilot())return;
        for(InputEventAPI event:events){if(event==null||event.isConsumed()||!event.isKeyboardEvent()||!event.isKeyDownEvent()||event.isRepeat())continue;
            // Recheck before consuming: MagicLib may have reassigned subsystem keys since the last refresh.
            if(binding.available()&&event.getEventValue()==binding.keyCode()){refresh();if(binding.available()&&event.getEventValue()==binding.keyCode()){toggle();event.consume();}}}
    }
    public boolean toggle(){check();if(!canPilot()){reason="当前不能操作本机武器停火";publish();return false;}
        ship.setHoldFire(!ship.isHoldFire());toggles++;if(ship.isHoldFire())cancelOwnCharge.run();reason=ship.isHoldFire()?"已停火":"已恢复开火";hud();publish();return true;
    }
    private boolean canPilot(){return !closed&&ship==engine.getPlayerShip()&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship)&&!engine.isPaused()&&!ship.controlsLocked()&&!engine.isUIShowingDialog()&&(engine.getCombatUI()==null||!engine.getCombatUI().isShowingCommandUI()&&!engine.getCombatUI().isShowingDeploymentDialog());}
    private void refresh(){binding=hotkey==null?Binding.none("停火按键子系统挂载失败"):hotkey.binding();clock=.5f;
        reason=binding.available()?(ship.isHoldFire()?"已停火":"正常开火"):binding.error();
    }
    private void hud(){if(hotkey!=null)hotkey.setStatus(binding.available()?(ship.isHoldFire()?"已停火":"正常开火"):"");
        if(ship==engine.getPlayerShip()&&engine.isUIShowingHUD())engine.maintainStatusForPlayerShip(KEY,"graphics/icons/hullsys/high_energy_focus.png","Z高达 · 武器停火",binding.available()?binding.keyName()+" "+(ship.isHoldFire()?"恢复开火 · 已停火":"停火 · 正常开火"):reason,ship.isHoldFire());}
    public Map<String,Object> status(){return Map.of("closed",closed,"key",binding.keyName(),"keyCode",binding.keyCode(),"available",binding.available(),"holdFire",ship.isHoldFire(),"toggles",toggles,"reason",reason,"scope","Native ship hold-fire; local current pilot only, X transform unchanged");}
    private void publish(){ship.setCustomData(KEY,status());}
    private void check(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Zeta cease-fire requires its battle owner thread");if(closed)throw new IllegalStateException("Cease-fire closed");}
    @Override public void close(){if(closed)return;check();closed=true;if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");publish();}

    /** 武器停火的按键：MagicLib 子系统热键，LunaLib「武器停火键（Z高达）」可改。 */
    public static final class Hotkey extends Moci_HotkeySubsystem {
        public Hotkey(ShipAPI ship){super(ship);}
        @Override protected String keySettingId(){return "Moci_ZetaCeaseFireKey";}
        @Override protected String hintText(){return "点按切换停火 / 恢复开火";}
        @Override public String getDisplayText(){return "武器停火";}
        // 喷射背包(9)、NTD(8)先分键；E-cap 换弹(7) > 生物传感器压制(6) > 武器停火(5)；新人类是 0，最后分。
        @Override public int getOrder(){return ORDER_SHIP_MODULAR-3;}
    }
}
