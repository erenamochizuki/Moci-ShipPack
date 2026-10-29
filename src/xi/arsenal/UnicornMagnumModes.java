package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** A single visible Magnum,two real native weapons,and an explicit shared-magazine release contract. */
public final class UnicornMagnumModes implements AutoCloseable {
    public static final String KEY="xi.arsenal.unicorn.magnum";
    public static final float MIN_HOLD=.2f,MAX_HOLD=3f,MIN_DURATION=.4f,MAX_DURATION=2.5f,
            COOLDOWN=3f,MAX_DAMAGE_SCALE=2.5f;
    public enum Mode {PULSE,CHARGE}
    public record Request(boolean accepted,String reason){}
    private enum Phase {IDLE,HOLDING,RELEASING,FIRING,COOLDOWN,CLOSED}
    private final ShipAPI ship;private final CombatEngineAPI engine;private final WeaponAPI pulse,beam;
    private final UnicornMagnumGroups groups;private final UnicornMagnumAmmo ammo;
    private final List<Vector2f> pulseTurret,pulseHard,pulseHidden,beamTurret,beamHard,beamHidden;
    private final float sourceCenterX,sourceCenterY;private final SpriteAPI sourceSprite;
    private Mode mode=Mode.PULSE;private Phase phase=Phase.IDLE;private boolean closed,fireDown,beamConfirmed,wasLocalPilot;
    private float heldLocal,preparedDuration,damageScale=1,firingLocal,cooldown,releaseWait;
    private long frames,shotSequence,pulseShots,beamShots,cancellations,guardCalls;private String reason="";
    private Map<String,Object> lastShot=Map.of();
    public UnicornMagnumModes(ShipAPI ship){this(ship,find(ship,UnicornMagnumContent.SOURCE_SLOT,UnicornMagnumContent.PULSE),find(ship,UnicornMagnumContent.CHARGE_SLOT,UnicornMagnumContent.CHARGE),null);}
    UnicornMagnumModes(ShipAPI ship,WeaponAPI pulse,WeaponAPI beam,UnicornMagnumGroups.Access access){
        this.ship=Objects.requireNonNull(ship);this.pulse=pulse;this.beam=beam;engine=Objects.requireNonNull(Global.getCombatEngine());
        if(!UnicornNtdStats.supports(ship))throw new IllegalArgumentException("Complete RX0 main required");
        if(ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Magnum modes already owned");
        if(pulse.isBeam()||!beam.isBeam()||beam.isBurstBeam())throw new IllegalStateException("Magnum native pulse/continuous-beam classes differ from declared contract");
        pulse.ensureClonedSpec();beam.ensureClonedSpec();
        sourceSprite=Objects.requireNonNull(pulse.getSprite(),"Visible original Magnum sprite required");sourceCenterX=sourceSprite.getCenterX();sourceCenterY=sourceSprite.getCenterY();
        pulseTurret=detach(pulse.getSpec().getTurretFireOffsets());pulseHard=detach(pulse.getSpec().getHardpointFireOffsets());pulseHidden=detach(pulse.getSpec().getHiddenFireOffsets());
        beamTurret=detach(beam.getSpec().getTurretFireOffsets());beamHard=detach(beam.getSpec().getHardpointFireOffsets());beamHidden=detach(beam.getSpec().getHiddenFireOffsets());
        ammo=new UnicornMagnumAmmo(pulse,beam);groups=access==null?new UnicornMagnumGroups(ship,pulse,beam):new UnicornMagnumGroups(ship,pulse,beam,access);
        wasLocalPilot=engine.getPlayerShip()==ship;ship.setCustomData(KEY+".owner",this);primeGuards();align();publish();
    }
    private static WeaponAPI find(ShipAPI ship,String slot,String id){return ship.getAllWeapons().stream().filter(w->w.getSlot()!=null&&slot.equals(w.getSlot().getId())&&id.equals(w.getId())).findFirst().orElseThrow(()->new IllegalStateException("Installed native Magnum mode missing: "+slot+" / "+id));}
    private static List<Vector2f> detach(List<Vector2f> list){if(list==null)return List.of();List<Vector2f> originals=new ArrayList<>();for(int i=0;i<list.size();i++){originals.add(new Vector2f(list.get(i)));list.set(i,new Vector2f(list.get(i)));}return List.copyOf(originals);}
    public static UnicornMagnumModes instance(ShipAPI ship){Object v=ship==null?null:ship.getCustomData().get(KEY+".owner");return v instanceof UnicornMagnumModes own&&!own.closed?own:null;}
    public Request requestMode(Mode wanted){
        Objects.requireNonNull(wanted);if(closed)return no("马格南模式已结束");if(!live())return no("机体失效");
        if(wanted!=mode)cancel("切换武器模式");if(!groups.select(wanted==Mode.CHARGE))return no("原生武器组已被其它内容移除");
        publish();return new Request(true,"已请求原生武器组选择，实际选择后生效");
    }
    /** Same mapped down/up event path as real input;never bypasses selection,resources or native fire gates. */
    public Request observeMappedFire(boolean down){
        synchronizeSelection();
        if(down){if(fireDown)return new Request(true,"保持蓄力");String block=holdBlock();if(!block.isEmpty())return no(block);fireDown=true;heldLocal=0;phase=Phase.HOLDING;reason="按住蓄力，松开发射";primeGuards();publish();return new Request(true,reason);}
        if(!fireDown)return no("没有待释放的蓄力");fireDown=false;
        if(phase!=Phase.HOLDING)return no("蓄力已取消");String block=holdBlock();if(!block.isEmpty()){cancel(block);return no(block);}
        if(heldLocal+1e-5f<MIN_HOLD){cancel("蓄力不足0.2秒");return no(reason);}
        ammo.observe();if(!ammo.reserveBeam()){cancel("共享马格南弹仓为空");return no(reason);}
        float q=Math.max(0,Math.min(1,(heldLocal-MIN_HOLD)/(MAX_HOLD-MIN_HOLD)));preparedDuration=MIN_DURATION+(MAX_DURATION-MIN_DURATION)*q;damageScale=1+(MAX_DAMAGE_SCALE-1)*q;
        phase=Phase.RELEASING;releaseWait=0;firingLocal=0;beamConfirmed=false;reason="松手：已预留1发，等待真实原生首亮";publish();return new Request(true,reason);
    }
    public void processInputPreCoreControls(List<InputEventAPI> events){
        if(closed||events==null)return;for(InputEventAPI event:events){if(event==null||event.isConsumed()||event.isRepeat())continue;
            if(ship.getSelectedGroupAPI()!=groups.chargeGroup&&!fireDown)continue;
            if(event.isControlDownEvent("SHIP_FIRE"))observeMappedFire(true);else if(event.isControlUpEvent("SHIP_FIRE"))observeMappedFire(false);}
    }
    public void advance(float localStep){
        if(closed)return;if(!Float.isFinite(localStep)||localStep<0)throw new IllegalArgumentException("Invalid local Magnum step");frames++;ammo.observe();confirmNativeLight();
        synchronizeSelection();
        if(groups.chargeGroup.isAutofiring())groups.chargeGroup.toggleOff();
        boolean pilot=engine.getPlayerShip()==ship;if(wasLocalPilot&&!pilot)cancel("驾驶机体已切换");wasLocalPilot=pilot;
        if(phase==Phase.HOLDING||phase==Phase.RELEASING||phase==Phase.FIRING){String block=cycleBlock();if(!block.isEmpty())cancel(block);}
        if(phase==Phase.HOLDING){heldLocal=Math.min(MAX_HOLD,heldLocal+localStep);}
        else if(phase==Phase.RELEASING){releaseWait+=localStep;if(releaseWait>.35f)cancel("原生开火未确认，已撤销预留");}
        else if(phase==Phase.FIRING){firingLocal+=localStep;if(firingLocal>=preparedDuration){phase=Phase.COOLDOWN;cooldown=COOLDOWN;reason="照射结束，等待自然淡出与冷却";}}
        else if(phase==Phase.COOLDOWN){cooldown=Math.max(0,cooldown-localStep);if(cooldown<=0&&dark(beam)){phase=Phase.IDLE;reason="就绪";}}
        primeGuards();align();publish();hud();
    }
    private void synchronizeSelection(){Mode selected=selectedMode();if(selected!=null&&selected!=mode){cancel("原生武器组已切换");mode=selected;}}
    /** Prime before native controls/weapon input is sampled;callbacks maintain the next native frame's gate. */
    private void primeGuards(){if(phase!=Phase.RELEASING&&phase!=Phase.FIRING)beam.setForceNoFireOneFrame(true);if(mode==Mode.CHARGE||phase==Phase.FIRING||cooldown>0)pulse.setForceNoFireOneFrame(true);}
    private Mode selectedMode(){WeaponGroupAPI selected=ship.getSelectedGroupAPI();if(selected==groups.pulseGroup)return Mode.PULSE;if(selected==groups.chargeGroup)return Mode.CHARGE;return null;}
    private String holdBlock(){String b=cycleBlock();if(!b.isEmpty())return b;if(phase!=Phase.IDLE&&phase!=Phase.HOLDING)return "马格南正在照射或冷却";if(cooldown>0||pulse.getCooldownRemaining()>.0001f||!dark(beam))return "马格南冷却尚未完成";if(ammo.reserve()<1)return "共享马格南弹仓为空";float minimum=beam.getSpec() instanceof BeamWeaponSpecAPI spec?Math.max(1,spec.getEnergyPerSecond()*.15f):1;if(ship.getMaxFlux()-ship.getCurrFlux()<minimum)return "幅能空间不足以安全启动照射";return "";}
    private String cycleBlock(){
        if(closed||!live()||Global.getCombatEngine()!=engine)return "机体或战场失效";
        if(engine.isPaused()||engine.isUIShowingDialog()||ship.controlsLocked())return "当前控制已暂停";
        if(engine.getPlayerShip()!=ship)return "不是当前本地驾驶机体";
        if(ship.getSelectedGroupAPI()!=groups.chargeGroup)return "未选择马格南蓄力照射组";
        if(ship.getFluxTracker().isOverloadedOrVenting())return "过载或排幅取消蓄力";
        if(pulse.isDisabled()||beam.isDisabled()||pulse.isForceDisabled()||beam.isForceDisabled())return "马格南武器已禁用";
        if(ship.isHoldFire()||ship.isHoldFireOneFrame())return "停火命令阻断";
        UnicornToggleNtdSubsystem ntd=UnicornNtdEnhancement.findNtd(ship) instanceof UnicornToggleNtdSubsystem own?own:null;
        if(ntd!=null&&ntd.fatigueLocked())return "NT-D反噬禁止开火";
        Object raw=engine.getCustomData().get(UnicornNtdOverloadSkill.KEY);if(raw instanceof Map<?,?> ew&&Boolean.TRUE.equals(ew.get("charging")))return "NT-D电子战蓄力禁止开火";
        if(engine.getCombatUI()!=null&&(engine.getCombatUI().isShowingCommandUI()||engine.getCombatUI().isShowingDeploymentDialog()))return "战术界面阻断开火";
        return "";
    }
    private boolean live(){return ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship);}
    private Request no(String why){reason=why;publish();return new Request(false,why);}
    private void cancel(String why){if(phase==Phase.HOLDING||phase==Phase.RELEASING||phase==Phase.FIRING){cancellations++;if(!beamConfirmed)ammo.cancelReservation();if(beamConfirmed){cooldown=Math.max(cooldown,COOLDOWN);phase=Phase.COOLDOWN;}else phase=Phase.IDLE;reason=why;}fireDown=false;heldLocal=0;releaseWait=0;}
    /** Native callbacks call only this guard/pose seam;no clocks,group edits or original callback replay. */
    public static void beforeWeapon(WeaponAPI weapon){UnicornMagnumModes own=instance(weapon.getShip());if(own==null){weapon.setForceNoFireOneFrame(true);return;}own.guardCalls++;own.align();own.guard(weapon);}
    private void guard(WeaponAPI weapon){
        if(weapon==pulse){if(mode!=Mode.PULSE||phase==Phase.HOLDING||phase==Phase.RELEASING||phase==Phase.FIRING||cooldown>0)weapon.setForceNoFireOneFrame(true);return;}
        if(weapon!=beam)return;
        String block=cycleBlock();boolean firing=(phase==Phase.RELEASING&&ammo.reservationPending()||phase==Phase.FIRING&&beamConfirmed)&&block.isEmpty();
        if(!firing){weapon.setForceNoFireOneFrame(true);return;}
        // Never clear a no-fire flag owned by NTD,another skill or another content.
        if(weapon.isForceNoFireOneFrame()){cancel("外部禁火阻断，取消本次照射");return;}
        weapon.setForceFireOneFrame(true);
    }
    public static void afterWeapon(WeaponAPI weapon){UnicornMagnumModes own=instance(weapon.getShip());if(own==null)return;own.align();if(weapon==own.beam)own.confirmNativeLight();}
    /** Reads real native light;the outer owner also calls this when advanceAfter is unavailable. */
    private void confirmNativeLight(){if(phase!=Phase.RELEASING||dark(beam))return;
        if(!ammo.reservationPending())throw new IllegalStateException("Unpaid Magnum beam cannot confirm native light");
        ammo.confirmBeam();beamConfirmed=true;phase=Phase.FIRING;beamShots++;shotSequence++;lastShot=shot("CHARGE",preparedDuration,damageScale);reason="原生照射已确认，费用已提交";publish();}
    public static void nativePulseShot(WeaponAPI weapon){UnicornMagnumModes own=instance(weapon.getShip());if(own==null||weapon!=own.pulse)throw new IllegalStateException("Unowned native Magnum pulse callback");own.ammo.observe();own.pulseShots++;own.shotSequence++;own.lastShot=own.shot("PULSE",0,1);own.publish();}
    private Map<String,Object> shot(String kind,float duration,float scale){return Map.of("sequence",shotSequence,"kind",kind,"heldLocal",heldLocal,"preparedDuration",duration,"damageScale",scale,"sharedAmmo",ammo.reserve(),"beamCostRule","explicit-source-magazine-not-continuous-native-auto");}
    public static float heldRatio(WeaponAPI weapon){UnicornMagnumModes own=instance(weapon.getShip());return own!=null&&own.phase==Phase.HOLDING?Math.min(1,own.heldLocal/MAX_HOLD):0;}
    public static float firingScale(WeaponAPI weapon){UnicornMagnumModes own=instance(weapon.getShip());return own==null?1:own.damageScale;}
    private void align(){
        if(mode==Mode.CHARGE)pulse.setCurrAngle(beam.getCurrAngle());else beam.setCurrAngle(pulse.getCurrAngle());
        float recoilX=sourceSprite.getCenterX()-sourceCenterX,recoilY=sourceSprite.getCenterY()-sourceCenterY;
        offsets(pulse.getSpec().getTurretFireOffsets(),pulseTurret,recoilX,recoilY);offsets(pulse.getSpec().getHardpointFireOffsets(),pulseHard,recoilX,recoilY);offsets(pulse.getSpec().getHiddenFireOffsets(),pulseHidden,recoilX,recoilY);
        // Both slots share the same native pivot;reuse the actual source-local muzzle for all hidden mounts.
        List<Vector2f> source=pulse.getSpec().getTurretFireOffsets();if(source!=null&&!source.isEmpty()){Vector2f p=source.get(0);setAll(beam.getSpec().getTurretFireOffsets(),p);setAll(beam.getSpec().getHardpointFireOffsets(),p);setAll(beam.getSpec().getHiddenFireOffsets(),p);}
        beam.updateBeamFromPoints();
    }
    private static void offsets(List<Vector2f> target,List<Vector2f> base,float x,float y){if(target==null)return;for(int i=0;i<Math.min(target.size(),base.size());i++)target.get(i).set(base.get(i).x-y,base.get(i).y+x);}
    private static void setAll(List<Vector2f> target,Vector2f p){if(target!=null)for(Vector2f q:target)q.set(p);}
    private static boolean dark(WeaponAPI weapon){return weapon.getBeams().stream().noneMatch(b->b.getBrightness()>.0001f);}
    public Map<String,Object> status(){Map<String,Object> r=new LinkedHashMap<>();r.put("mode",mode.name());r.put("phase",phase.name());r.put("heldLocal",heldLocal);r.put("preparedDuration",preparedDuration);r.put("damageScale",damageScale);r.put("firingLocal",firingLocal);r.put("cooldownRemaining",cooldown);r.put("reason",reason);r.put("sharedAmmo",ammo.reserve());r.put("ammo",ammo.status());r.put("shotSequence",shotSequence);r.put("pulseShots",pulseShots);r.put("beamShots",beamShots);r.put("lastShot",lastShot);r.put("beamReservationPending",ammo.reservationPending());r.put("beamConfirmed",beamConfirmed);r.put("cancellations",cancellations);r.put("frames",frames);r.put("guardCalls",guardCalls);r.put("groups",groups.status());r.put("closed",closed);return Collections.unmodifiableMap(r);}
    private void publish(){ship.setCustomData(KEY,status());}
    public void present(){if(!closed)hud();}
    private void hud(){if(engine.getPlayerShip()!=ship)return;String text=switch(phase){case HOLDING->String.format(Locale.ROOT,"蓄力 %.1f/3秒 · 松开发射",heldLocal);case RELEASING->"已预留1发，等待原生照射确认";case FIRING->String.format(Locale.ROOT,"照射 %.1f/%.1f秒 · 伤害×%.2f",firingLocal,preparedDuration,damageScale);case COOLDOWN->String.format(Locale.ROOT,"冷却 %.1f秒",cooldown);default->mode==Mode.PULSE?"点射 · 原生武器组切换为蓄力照射":"按住开火蓄力，松开发射 · 0.2至3秒";};engine.maintainStatusForPlayerShip(KEY+".hud","graphics/icons/hullsys/temporal_shell.png","马格南 · "+(mode==Mode.PULSE?"点射":"蓄力照射")+" · "+ammo.reserve()+"发",text,false);}
    @Override public void close(){if(closed)return;cancel("马格南模式生命周期结束");closed=true;phase=Phase.CLOSED;pulse.setForceNoFireOneFrame(true);beam.setForceNoFireOneFrame(true);ammo.close();groups.close();if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");publish();}
}
