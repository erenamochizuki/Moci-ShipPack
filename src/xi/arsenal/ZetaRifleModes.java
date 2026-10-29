package xi.arsenal;

import data.scripts.weapons.Moci_MechTransformController;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.WeaponRangeModifier;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.combat.entities.Ship;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.lwjgl.util.vector.Vector2f;

/** One held-charge clock and magazine follow the same rifle through MS/WR changes.
 * All damaging shots remain real installed native weapons. Callbacks only gate and align them. */
public final class ZetaRifleModes implements AutoCloseable {
    public static final String KEY="xi.arsenal.zeta.rifle",BURST="Moci_fw_zeta_rifle_burst",SNIPER="Moci_fw_zeta_rifle_sniper",
            SOURCE_SLOT="ARM_R_SOURCE",SNIPER_SLOT="ZETA_RIFLE_SNIPER",WR_SOURCE_SLOT="WR_MAINGUN",WR_SNIPER_SLOT="WR_RIFLE_SNIPER",WRIST="Moci_fw_zeta_wrist_grenade";
    public static final float MIN_HOLD=.2f,FULL_HOLD=3f,MIN_DURATION=.65f,MAX_DURATION=2.4f,MAX_DAMAGE=2.5f,MIN_WIDTH=34f,BASE_WIDTH=48f,COOLDOWN=2.4f;
    public enum Mode{BURST,SNIPER}
    public enum Phase{IDLE,HOLDING,WAITING_FORM,PRESENTING,RELEASING,FIRING,COOLDOWN,CLOSED}
    public enum Form{MS,WR,TRANSITION}
    public record Request(boolean accepted,String reason){}
    public record ReloadResult(boolean accepted,String reason,int added){}
    public record Charge(float duration,float damageScale,float rangeMultiplier,float width){}
    /** A trusted same-thread combo owns its physical pose; native firing still belongs to this controller. */
    public interface ComboRig{Object token();boolean valid();boolean readyForBeam();float gunAimAngle(float nativeAim);Vector2f gunTranslation();void onNativeFirstLight();}
    public record ComboRequest(boolean accepted,String reason,ComboLease lease){}
    public final class ComboLease implements AutoCloseable{
        private final ComboRig rig;private final Mode previousMode;private final WeaponGroupAPI originalSelection;private boolean released;
        private ComboLease(ComboRig rig){this.rig=rig;previousMode=mode;originalSelection=ship.getSelectedGroupAPI();}
        // Internal beam mode is independent of the player's selected manual group. In particular,
        // the paid native beam does not need the visible UI to leave the tether command group.
        public Request beginCharge(){checkLease();mode=Mode.SNIPER;comboCall=true;try{return requestFire(true);}finally{comboCall=false;}}
        public Request releaseCharge(){checkLease();comboCall=true;try{return requestFire(false);}finally{comboCall=false;}}
        public boolean active(){return !released&&!closed&&combo==this&&rig.valid();}
        private void checkLease(){check();if(!active())throw new IllegalStateException("Zeta combo lease is stale");}
        @Override public void close(){if(released)return;check();released=true;if(combo==this)try{cancel("连携步枪控制已归还");}finally{combo=null;mode=previousMode;pending=null;groupSyncFrames=0;publish();}}
    }
    private ComboLease combo;private boolean comboCall;
    public static final String ARM_ACTION_KEY="xi.arsenal.zeta.armAction.owner";
    interface PoseDriver extends AutoCloseable{void advance(RifleFiringPose.Stage stage,float dt);boolean ready();void firstLight();void clear();float aim(float nativeAngle);}
    private final Thread owner=Thread.currentThread();private final ShipAPI ship;private final CombatEngineAPI engine;
    private final WeaponAPI msBurst,msBeam,wrBurst,wrBeam;private final List<WeaponAPI> weapons;private final ZetaRifleMagazine ammo;
    private final PoseDriver pose;private final WeaponRangeModifier rangeModifier;
    private Mode mode=Mode.BURST,pending;private Phase phase=Phase.IDLE;private Form form=Form.MS,shotForm;private WeaponAPI shotBeam;
    private boolean closed,fireDown,wasLocal,mappedInput,confirmed,rapidHeld,rapidShot,overdriveWas;private int groupSyncFrames;
    private float hold,duration=MIN_DURATION,scale=1,range=1,width=MIN_WIDTH,fired,presenting,wait,cooldown;
    private double elapsed,firstLight=-1;private long frames,guardCalls,transitionWaitFrames,shots,cancellations,groupSelections;
    private String reason="就绪",authorityOwner="";private long authorityEpoch;private BooleanSupplier authority;
    public ZetaRifleModes(ShipAPI ship){this(ship,Objects.requireNonNull(Global.getCombatEngine()),null);}
    ZetaRifleModes(ShipAPI ship,CombatEngineAPI engine,PoseDriver testPose){
        this.ship=Objects.requireNonNull(ship);this.engine=Objects.requireNonNull(engine);
        if(!ZetaOverdriveStats.supports(ship))throw new IllegalArgumentException("Complete Zeta required");
        if(ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Zeta rifle owner already installed");
        msBurst=find(ship,SOURCE_SLOT,BURST);msBeam=find(ship,SNIPER_SLOT,SNIPER);wrBurst=find(ship,WR_SOURCE_SLOT,BURST);wrBeam=find(ship,WR_SNIPER_SLOT,SNIPER);
        weapons=List.of(msBurst,msBeam,wrBurst,wrBeam);
        if(!msBeam.isBeam()||msBeam.isBurstBeam()||!wrBeam.isBeam()||wrBeam.isBurstBeam()||msBurst.isBeam()||wrBurst.isBeam())throw new IllegalStateException("Zeta needs native pulse plus continuous beam in each form");
        prepareGroups(ship);ammo=new ZetaRifleMagazine(msBurst,weapons);
        if(testPose!=null)pose=testPose;
        else{
            AutoCloseable lease=RifleFiringPose.attach(ship,new RifleReleaseRig(){public ShipAPI ship(){return ship;}public WeaponAPI source(){return msBurst;}public int standingLegFrame(){return 4;}});
            pose=new PoseDriver(){public void advance(RifleFiringPose.Stage stage,float dt){RifleFiringPose.advance(ship,stage,dt);}public boolean ready(){return RifleFiringPose.readyForBeam(ship);}public void firstLight(){RifleFiringPose.onNativeFirstLight(ship);}public void clear(){RifleFiringPose.clear(ship);}public float aim(float angle){return RifleFiringPose.gunAimAngle(msBurst,angle);}public void close()throws Exception{lease.close();}};
        }
        rangeModifier=new WeaponRangeModifier(){public float getWeaponRangePercentMod(ShipAPI s,WeaponAPI w){return 0;}public float getWeaponRangeFlatMod(ShipAPI s,WeaponAPI w){return 0;}public float getWeaponRangeMultMod(ShipAPI s,WeaponAPI w){return s==ship&&(w==msBeam||w==wrBeam)?range:1;}};
        ship.addListener(rangeModifier);wasLocal=engine.getPlayerShip()==ship;form=readForm(ship);ship.setCustomData(KEY+".owner",this);prime();publish();
    }
    public static Charge estimate(float seconds){if(!Float.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid Zeta held charge");float q=Math.max(0,Math.min(1,(seconds-MIN_HOLD)/(FULL_HOLD-MIN_HOLD)));return new Charge(MIN_DURATION+(MAX_DURATION-MIN_DURATION)*q,1+(MAX_DAMAGE-1)*q,1+.2f*q,MIN_WIDTH+(BASE_WIDTH-MIN_WIDTH)*q);}
    /** Only the outer actor may create native groups. Firing callbacks never edit a live group iterator. */
    public static void prepareGroups(ShipAPI ship){if(ship==null||!ZetaOverdriveStats.supports(ship))return;if(ship instanceof Ship){ZetaWeaponGroups.ensure(ship);return;}for(WeaponAPI weapon:ship.getAllWeapons())if((BURST.equals(weapon.getId())||SNIPER.equals(weapon.getId())||WRIST.equals(weapon.getId()))&&ship.getWeaponGroupFor(weapon)==null)throw new IllegalStateException("Native Ship needed to install missing Zeta groups");}
    public Request requestMode(Mode wanted){check();Objects.requireNonNull(wanted);if(combo!=null)return no("钢索连携正在独占步枪");if(!live())return no("机体失效");if(wanted!=mode)cancel("手动切换步枪模式");mode=wanted;pending=null;groupSyncFrames=4;if(form!=Form.TRANSITION)selectMode();publish();return new Request(true,"已请求原生步枪组");}
    public Request requestFire(boolean down){
        check();if(combo!=null&&!comboCall)return no("钢索连携正在独占步枪");updateForm(false);
        if(combo==null&&overdrive()&&mode==Mode.BURST){if(!down){rapidHeld=false;return new Request(true,"停止超载连射");}String block=blocked();if(!block.isEmpty())return no(block);rapidHeld=true;tryRapid();publish();return new Request(true,"超载照射连发");}
        if(down){if(fireDown)return new Request(true,"保持蓄力");if(mode!=Mode.SNIPER)return no("请选择蓄力狙击组");String block=blocked();if(!block.isEmpty())return no(block);ammo.observe();if(phase!=Phase.IDLE||cooldown>0||nativeCooldown()>0||!dark(msBeam)||!dark(wrBeam)||cycleActive(msBurst)||cycleActive(wrBurst))return no("步枪冷却或上次射击未结束");if(ammo.reserve()<1)return no("步枪弹仓为空");rapidShot=false;fireDown=true;hold=0;confirmed=false;firstLight=-1;phase=Phase.HOLDING;apply(estimate(0));reason="按住蓄力，松手释放；变形保留蓄力";prime();publish();return new Request(true,reason);}
        if(!fireDown||phase!=Phase.HOLDING)return no("没有待释放的蓄力");fireDown=false;
        if(hold+1e-5f<MIN_HOLD){cancel("蓄力不足0.2秒");publish();return new Request(false,reason);}
        String block=blocked();if(!block.isEmpty()){cancel(block);publish();return new Request(false,reason);}
        apply(estimate(hold));phase=Phase.WAITING_FORM;reason="等待形态就绪后释放";beginRelease();prime();publish();return new Request(true,reason);
    }
    public ComboRequest acquireCombo(ComboRig rig){
        check();Objects.requireNonNull(rig);Object arms=ship.getCustomData().get(ARM_ACTION_KEY);
        if(combo!=null||arms!=rig.token()||!rig.valid()||readForm(ship)!=Form.MS||!live()||engine.isPaused()||ship.controlsLocked()||ship.getFluxTracker().isOverloadedOrVenting())return new ComboRequest(false,"机体或双臂正在执行其他动作",null);
        if(phase!=Phase.IDLE||cooldown>0||nativeCooldown()>0||!dark(msBeam)||!dark(wrBeam)||cycleActive(msBurst)||cycleActive(wrBurst))return new ComboRequest(false,"等待步枪本次开火与冷却结束",null);
        ammo.observe();if(ammo.reserve()<1)return new ComboRequest(false,"步枪弹仓为空",null);
        if(authority==null&&engine.getPlayerShip()!=ship||authority!=null&&!authority.getAsBoolean())return new ComboRequest(false,"当前驾驶控制未授权",null);
        pose.clear();combo=new ComboLease(rig);pending=null;groupSyncFrames=0;prime();publish();return new ComboRequest(true,"取得连携步枪租约，保留当前手动武器组",combo);
    }
    public static Vector2f gunTranslation(WeaponAPI source){ZetaRifleModes own=instance(source.getShip());if(own!=null&&own.combo!=null)return new Vector2f(own.combo.rig.gunTranslation());if(ZetaTetherPose.blocksGuard(source.getShip()))return ZetaTetherPose.armTranslation(source.getShip(),false);return RifleFiringPose.gunTranslation(source);}
    public Request bindAuthority(String controller,long epoch,BooleanSupplier valid){check();if(controller==null||controller.isBlank()||epoch<=authorityEpoch||valid==null)return no("控制代次无效");cancel("驾驶控制已重新绑定");authorityOwner=controller;authorityEpoch=epoch;authority=valid;return new Request(true,"权威步枪动作已绑定");}
    public void releaseAuthority(String controller,long epoch){check();if(authority!=null&&authorityOwner.equals(controller)&&authorityEpoch==epoch){cancel("驾驶控制已释放");authority=()->false;publish();}}
    public void cancelCharge(String why){check();cancel(why==null?"蓄力已取消":why);publish();}
    /** Atomically refill all four native drivers. The caller owns/debits the E-cap inventory. */
    public ReloadResult refillMagazine(){
        check();if(!live()||Global.getCombatEngine()!=engine)return new ReloadResult(false,"机体或战场失效",0);
        if(ship.controlsLocked()||ship.getFluxTracker().isOverloaded())return new ReloadResult(false,"当前无法更换E-cap",0);
        ammo.observe();if(ammo.pending())return new ReloadResult(false,"等待照射首亮确认后再更换E-cap",0);
        int added=ammo.refill();publish();return new ReloadResult(added>0,added>0?"E-cap已补满共享弹匣":"共享弹匣已满",added);
    }
    public void processInput(List<InputEventAPI> events){check();if(events==null||engine.getPlayerShip()!=ship||authority!=null||combo!=null)return;
        for(InputEventAPI event:events){if(event==null||event.isConsumed()||event.isRepeat())continue;
            if(event.isControlUpEvent("SHIP_FIRE")&&(fireDown||rapidHeld||mappedInput)){requestFire(false);mappedInput=false;event.consume();}
            else if(event.isControlDownEvent("SHIP_FIRE")&&(selectedMode()==Mode.SNIPER||phase==Phase.HOLDING||overdrive()&&selectedMode()==Mode.BURST)){mode=overdrive()?Mode.BURST:Mode.SNIPER;mappedInput=true;requestFire(true);event.consume();}}
    }
    public void advance(float localStep,boolean completeWr){
        check();if(!Float.isFinite(localStep)||localStep<0)throw new IllegalArgumentException("Invalid Zeta rifle local time");frames++;elapsed+=localStep;ammo.observe();updateForm(true);confirm();
        boolean local=engine.getPlayerShip()==ship;if(authority==null&&wasLocal&&!local)cancel("驾驶机体已切换");wasLocal=local;
        boolean overloaded=overdrive();if(overdriveWas!=overloaded){if(combo==null){cancel("超载武装状态切换");mode=Mode.BURST;groupSyncFrames=3;}overdriveWas=overloaded;}
        for(WeaponAPI beam:List.of(msBeam,wrBeam)){WeaponGroupAPI group=ship.getWeaponGroupFor(beam);if(group!=null&&group.isAutofiring())group.toggleOff();}
        if(combo==null&&groupSyncFrames>0&&form!=Form.TRANSITION){selectMode();groupSyncFrames--;}
        else if(!activeCharge()&&combo==null)refreshSelection();
        else if(combo==null&&activeCharge()&&form!=Form.TRANSITION&&groupSyncFrames==0&&ship.getSelectedGroupAPI()!=null&&selectedMode()!=(rapidShot?Mode.BURST:Mode.SNIPER))cancel("原生武器组已切换");
        if(activeCharge()){String block=blocked();if(!block.isEmpty())cancel(block);}
        if(phase==Phase.HOLDING){hold+=localStep;apply(estimate(hold));}
        else if(phase==Phase.WAITING_FORM){transitionWaitFrames++;beginRelease();}
        else if(phase==Phase.PRESENTING){presenting+=localStep;if(form==Form.MS){if(combo==null)pose.advance(RifleFiringPose.Stage.PRESENTING,localStep);if(combo==null?pose.ready():combo.rig.readyForBeam())arm();}}
        else if(phase==Phase.RELEASING){wait+=localStep;if(wait>.5f)cancel("原生照射首亮未确认，已撤销预留");}
        else if(phase==Phase.FIRING){fired+=localStep;if(fired>=duration)finish("照射完成");}
        else if(phase==Phase.COOLDOWN){cooldown=Math.max(0,cooldown-localStep*cooldownRate());if(cooldown<=0&&dark(msBeam)&&dark(wrBeam)){phase=Phase.IDLE;rapidShot=false;confirmed=false;reason="就绪";range=1;}}
        if(rapidHeld&&(selectedMode()!=Mode.BURST||!overloaded||!blocked().isEmpty()))rapidHeld=false;
        if(combo==null&&overloaded&&selectedMode()!=Mode.BURST)rapidHeld=false;
        if(rapidHeld)tryRapid();
        if(combo==null&&!rapidShot&&form==Form.MS){if(phase==Phase.HOLDING)pose.advance(RifleFiringPose.Stage.HOLDING,localStep);else if(phase==Phase.FIRING||phase==Phase.RELEASING)pose.advance(RifleFiringPose.Stage.FIRING,localStep);else if(phase!=Phase.PRESENTING)pose.advance(RifleFiringPose.Stage.CLOSING,localStep);}else pose.clear();
        align();prime();publish();hud();
    }
    private void updateForm(boolean outer){Form next=readForm(ship);if(next==form)return;Form previous=form;form=next;
        if(next==Form.TRANSITION){pose.clear();if(phase==Phase.PRESENTING||phase==Phase.RELEASING){ammo.cancel();phase=Phase.WAITING_FORM;presenting=wait=0;reason="保留蓄力，等待变形完成";}else if(phase==Phase.FIRING)finish("变形中结束已发出的照射");}
        else{if(combo==null){groupSyncFrames=4;if(outer)selectMode();}if(previous==Form.TRANSITION&&phase==Phase.HOLDING)reason="形态就绪，保留原蓄力";}
    }
    private void beginRelease(){if(phase!=Phase.WAITING_FORM||form==Form.TRANSITION)return;if(combo==null){groupSyncFrames=Math.max(groupSyncFrames,3);selectMode();}
        if(form==Form.MS){phase=Phase.PRESENTING;presenting=0;if(combo==null)pose.advance(RifleFiringPose.Stage.PRESENTING,0);reason=combo==null?"侧身停顿，抬枪瞄准后照射":"等待连携右臂瞄准门禁";}
        else{pose.clear();arm();}
    }
    private void arm(){if(form==Form.TRANSITION)return;if(!ammo.reserveBeam()){cancel("步枪弹仓为空");return;}shotForm=form;shotBeam=beam();phase=Phase.RELEASING;wait=0;reason="等待原生照射首亮";}
    private void confirm(){if(phase!=Phase.RELEASING||shotBeam==null||dark(shotBeam))return;if(!ammo.pending())throw new IllegalStateException("Zeta native beam lacks its ammunition reservation");ammo.confirmBeam();confirmed=true;phase=Phase.FIRING;fired=0;firstLight=elapsed;shots++;if(shotForm==Form.MS&&!rapidShot){if(combo==null)pose.firstLight();else combo.rig.onNativeFirstLight();}reason="照射中";if(Global.getSoundPlayer()!=null)Global.getSoundPlayer().playSound("Moci_Magnum_03",1,.25f,shotBeam.getFirePoint(0),ship.getVelocity());publish();}
    private boolean overdrive(){return ZetaOverdriveLifecycle.immune(ship);}
    /** Short actual continuous-beam pulses reuse the installed beam, its native flux costs and
     * shared magazine. Dark/charge-down is mandatory; there is no extra scripted refire timer. */
    private void tryRapid(){if(combo!=null||!overdrive()||mode!=Mode.BURST||form==Form.TRANSITION||phase!=Phase.IDLE||!blocked().isEmpty()||!dark(msBeam)||!dark(wrBeam)||cycleActive(msBurst)||cycleActive(wrBurst))return;
        ammo.observe();if(ammo.reserve()<1)return;rapidShot=true;confirmed=false;hold=0;duration=.22f;scale=1;range=1.2f;width=BASE_WIDTH;fired=wait=0;arm();}
    private void finish(String why){phase=Phase.COOLDOWN;cooldown=rapidShot?0:Math.max(cooldown,COOLDOWN);fireDown=false;reason=why;}
    private void cancel(String why){rapidHeld=false;if(activeCharge()){cancellations++;if(confirmed)finish(why);else{ammo.cancel();phase=Phase.IDLE;range=1;}}fireDown=false;mappedInput=false;hold=presenting=wait=0;pose.clear();reason=why;prime();}
    private void apply(Charge charge){duration=charge.duration();scale=charge.damageScale();range=charge.rangeMultiplier();width=charge.width();}
    private boolean activeCharge(){return phase==Phase.HOLDING||phase==Phase.WAITING_FORM||phase==Phase.PRESENTING||phase==Phase.RELEASING||phase==Phase.FIRING;}
    private String blocked(){if(closed||!live()||Global.getCombatEngine()!=engine)return "机体或战场失效";if(engine.isPaused()||ship.controlsLocked())return "机体控制已暂停";
        if(authority==null&&engine.getPlayerShip()!=ship)return "不是当前本地驾驶机体";if(authority!=null&&!authority.getAsBoolean())return "远端驾驶代次失效";
        if(authority==null&&mappedInput&&!org.lwjgl.opengl.Display.isActive())return "窗口失焦，取消本地蓄力";
        if(authority==null&&(engine.isUIShowingDialog()||engine.getCombatUI()!=null&&(engine.getCombatUI().isShowingCommandUI()||engine.getCombatUI().isShowingDeploymentDialog())))return "界面正在占用输入";
        if(ship.getFluxTracker().isOverloadedOrVenting())return "过载或排幅取消蓄力";
        if(ship.isHoldFire()||ship.isHoldFireOneFrame())return "机体暂时禁火";
        if(form!=Form.TRANSITION&&(burst().isDisabled()||burst().isForceDisabled()||beam().isDisabled()||beam().isForceDisabled()))return "步枪部位已禁用";
        Object arms=ship.getCustomData().get(ARM_ACTION_KEY);
        if(form==Form.MS&&arms!=null&&(combo==null||arms!=combo.rig.token()))return "其他技能正在占用双臂";
        if(combo!=null&&!combo.rig.valid())return "钢索连携控制已失效";
        if(form==Form.MS&&ZetaBeamDeflection.isBlockingRifle(ship))return "双臂正在执行光束弹开";
        if(form==Form.MS&&combo==null){String poseBlock=RifleFiringPose.blockedReason(ship);if(!poseBlock.isEmpty())return poseBlock;}return "";
    }
    private void refreshSelection(){Mode wanted=selectedMode();if(wanted==null||wanted==mode){pending=null;return;}if(cycleActive(burst())||!dark(msBeam)||!dark(wrBeam)){pending=wanted;transitionWaitFrames++;}else{mode=wanted;pending=null;}}
    private Mode selectedMode(){WeaponGroupAPI selected=ship.getSelectedGroupAPI();if(selected==null)return null;for(WeaponAPI weapon:selected.getWeaponsCopy())if(weapon==msBeam||weapon==wrBeam)return Mode.SNIPER;for(WeaponAPI weapon:selected.getWeaponsCopy())if(weapon==msBurst||weapon==wrBurst)return Mode.BURST;return null;}
    private boolean selectMode(){if(combo!=null)return false;WeaponAPI wanted=mode==Mode.SNIPER?beam():burst();List<WeaponGroupAPI> groups=ship.getWeaponGroupsCopy();for(int i=0;i<groups.size();i++)if(groups.get(i).getWeaponsCopy().stream().anyMatch(w->w==wanted)){if(ship.getSelectedGroupAPI()!=groups.get(i)){ship.giveCommand(ShipCommand.SELECT_GROUP,null,i);groupSelections++;}return true;}return false;}
    private boolean live(){return ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship);}
    private float cooldownRate(){float rate=ship.getMutableStats().getEnergyRoFMult().getModifiedValue();if(!Float.isFinite(rate)||rate<0)throw new IllegalStateException("Invalid native optical fire rate");return Math.min(100,rate);}
    private float nativeCooldown(){return Math.max(msBurst.getCooldownRemaining(),wrBurst.getCooldownRemaining());}
    private WeaponAPI burst(){return form==Form.WR?wrBurst:msBurst;}
    private WeaponAPI beam(){return form==Form.WR?wrBeam:msBeam;}
    private void prime(){boolean holdFire=ship.isHoldFire()||ship.isHoldFireOneFrame();for(WeaponAPI weapon:weapons){boolean allowed;if(SNIPER.equals(weapon.getId()))allowed=weapon==shotBeam&&(phase==Phase.RELEASING||phase==Phase.FIRING)&&form==shotForm&&form!=Form.TRANSITION;
            else allowed=!overdrive()&&combo==null&&weapon==burst()&&form!=Form.TRANSITION&&mode==Mode.BURST&&phase==Phase.IDLE&&cooldown<=0&&!(form==Form.MS&&ship.getCustomData().get(ARM_ACTION_KEY)!=null);
            if(holdFire||!allowed)weapon.setForceNoFireOneFrame(true);if(form==Form.TRANSITION)weapon.stopFiring();}}
    private void align(){boolean upperBody=form==Form.MS&&ZetaTetherPose.blocksGuard(ship);if(form==Form.MS&&rapidShot&&combo==null&&!upperBody)msBeam.setCurrAngle(msBurst.getCurrAngle());if(form==Form.MS&&(combo!=null||upperBody||mode==Mode.SNIPER)){msBurst.setCurrAngle(combo!=null?combo.rig.gunAimAngle(msBeam.getCurrAngle()):upperBody?ZetaTetherPose.rightAimAngle(ship):pose.aim(msBeam.getCurrAngle()));if(combo!=null||upperBody)msBeam.setCurrAngle(msBurst.getCurrAngle());}if(form==Form.WR){wrBurst.setCurrAngle(ship.getFacing());wrBeam.setCurrAngle(ship.getFacing());}
        ZetaRifleWeaponEffect.reconcile(msBurst);ZetaRifleWeaponEffect.reconcile(wrBurst);
        ZetaRifleMount.synchronize(msBeam,msBurst);ZetaRifleMount.synchronize(wrBeam,wrBurst);}
    public static void beforeWeapon(WeaponAPI weapon){ZetaRifleModes own=instance(weapon.getShip());if(own==null){weapon.setForceNoFireOneFrame(true);return;}own.guardCalls++;own.updateForm(false);own.align();own.prime();if(weapon==own.shotBeam&&(own.phase==Phase.RELEASING||own.phase==Phase.FIRING)&&own.form==own.shotForm&&!weapon.isForceNoFireOneFrame()&&own.blocked().isEmpty())weapon.setForceFireOneFrame(true);}
    public static void afterWeapon(WeaponAPI weapon){ZetaRifleModes own=instance(weapon.getShip());if(own!=null){own.align();own.confirm();}}
    public static boolean cycleActive(WeaponAPI weapon){boolean charging=weapon.isFiring()&&weapon.getChargeLevel()>.0001f;return weapon.isBeam()?charging||!dark(weapon):weapon.isInBurst()||charging;}
    private static boolean dark(WeaponAPI weapon){return weapon.getBeams().stream().noneMatch(b->b.getBrightness()>.0001f);}
    public static boolean msForm(ShipAPI ship){Object state=ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORM_STATE);return !Boolean.TRUE.equals(ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORMED))&&!Boolean.TRUE.equals(ship.getCustomData().get(Moci_MechTransformController.CUSTOM_PARTS_HIDDEN))&&(state==null||"NORMAL".equals(state));}
    static Form readForm(ShipAPI ship){if(msForm(ship))return Form.MS;Object state=ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORM_STATE);WeaponAPI pack=ZetaRifleMount.slot(ship,"BACKPACK");if("TRANSFORMED".equals(state)&&Boolean.TRUE.equals(ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORMED))&&pack!=null&&pack.getAnimation()!=null&&pack.getAnimation().getFrame()==11)return Form.WR;return Form.TRANSITION;}
    public static WeaponAPI activeDriver(ShipAPI ship){ZetaRifleModes own=instance(ship);return own==null?optional(ship,SOURCE_SLOT,BURST):own.mode==Mode.SNIPER?own.beam():own.burst();}
    public static void alignInactiveSource(ShipAPI ship){ZetaRifleModes own=instance(ship);if(own!=null)own.align();}
    public static ZetaRifleModes instance(ShipAPI ship){Object value=ship==null?null:ship.getCustomData().get(KEY+".owner");return value instanceof ZetaRifleModes own&&!own.closed?own:null;}
    public static float firingScale(WeaponAPI weapon){ZetaRifleModes own=instance(weapon.getShip());return own==null?1:own.scale;}
    public static float firingWidthMultiplier(WeaponAPI weapon){ZetaRifleModes own=instance(weapon.getShip());return own==null?MIN_WIDTH/BASE_WIDTH:own.width/BASE_WIDTH;}
    public static float heldRatio(ShipAPI ship){ZetaRifleModes own=instance(ship);return own==null?0:Math.min(1,own.hold/FULL_HOLD);}
    public Map<String,Object> status(){Map<String,Object> row=new LinkedHashMap<>();row.put("rapidHeld",rapidHeld);row.put("rapidShot",rapidShot);row.put("overdrive",overdrive());row.put("closed",closed);row.put("comboExclusive",combo!=null);row.put("comboOriginalGroupIdentity",combo==null?"":groupIdentity(combo.originalSelection));row.put("selectedGroupIdentity",groupIdentity(ship.getSelectedGroupAPI()));row.put("comboUserChangedSelection",combo!=null&&ship.getSelectedGroupAPI()!=combo.originalSelection);row.put("comboSelectionPolicy","internal native beam never selects or restores a manual group");row.put("groupSelections",groupSelections);row.put("mode",mode.name());row.put("pendingMode",pending==null?"":pending.name());row.put("phase",phase.name());row.put("form",form.name());row.put("msEnabled",form==Form.MS&&!closed);row.put("heldLocal",hold);row.put("duration",duration);row.put("damageScale",scale);row.put("rangeMultiplier",range);row.put("firingWidth",width);row.put("firedLocal",fired);row.put("presentingLocal",presenting);row.put("presentSeconds",form==Form.WR?0:RifleFiringPose.presentation(0).total());row.put("firstLightLocal",firstLight);row.put("localElapsed",elapsed);row.put("shots",shots);row.put("cancellations",cancellations);row.put("fireDown",fireDown);row.put("sharedAmmo",ammo.reserve());row.put("ammo",ammo.status());row.put("cooldownRemaining",cooldown);row.put("reason",reason);row.put("frames",frames);row.put("guardCalls",guardCalls);row.put("transitionWaitFrames",transitionWaitFrames);row.put("sourceSlot",burst().getSlot().getId());row.put("sniperSlot",beam().getSlot().getId());row.put("sourceAngle",burst().getCurrAngle());row.put("sniperAngle",beam().getCurrAngle());groupFields(row,"burst",ship.getWeaponGroupFor(burst()));groupFields(row,"sniper",ship.getWeaponGroupFor(beam()));WeaponAPI wrist=optional(ship,"ZETA_WRIST_L",WRIST);groupFields(row,"wrist",wrist==null?null:ship.getWeaponGroupFor(wrist));return Collections.unmodifiableMap(row);}
    private static String groupIdentity(WeaponGroupAPI group){return group==null?"":Integer.toHexString(System.identityHashCode(group));}
    private void groupFields(Map<String,Object> row,String prefix,WeaponGroupAPI wanted){List<WeaponGroupAPI> groups=ship.getWeaponGroupsCopy();int index=-1;for(int i=0;i<groups.size();i++)if(groups.get(i)==wanted&&wanted!=null){index=i;break;}row.put(prefix+"GroupIndex",index);row.put(prefix+"GroupVisible",index>=0);if(index>=0)row.put(prefix+"GroupNumber",index+1);}
    private Request no(String why){reason=why;publish();return new Request(false,why);}
    private void publish(){ship.setCustomData(KEY,status());}
    private void hud(){if(engine.getPlayerShip()==ship)engine.maintainStatusForPlayerShip(KEY,"graphics/icons/hullsys/high_energy_focus.png","Z步枪 · "+(overdrive()?"超载照射连发":mode==Mode.SNIPER?"蓄力狙击":"三连点射"),phase==Phase.HOLDING?String.format(Locale.ROOT,"蓄力 %.1f秒 · 松手释放 · 变形保留",hold):reason,phase==Phase.COOLDOWN);}
    private void check(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Zeta rifle requires its battle owner thread");if(closed)throw new IllegalStateException("Zeta rifle closed");}
    private static WeaponAPI find(ShipAPI ship,String slot,String id){WeaponAPI weapon=optional(ship,slot,id);if(weapon==null)throw new IllegalStateException("Zeta native rifle driver missing: "+slot+" / "+id);return weapon;}
    private static WeaponAPI optional(ShipAPI ship,String slot,String id){for(WeaponAPI weapon:ship.getAllWeapons())if(weapon.getSlot()!=null&&slot.equals(weapon.getSlot().getId())&&id.equals(weapon.getId()))return weapon;return null;}
    @Override public void close(){
        if(closed)return;if(combo!=null)combo.close();if(Thread.currentThread()!=owner)throw new IllegalStateException("Zeta rifle cleanup requires its owner thread");Throwable failure=null;
        for(AutoCloseable item:new AutoCloseable[]{()->cancel("步枪已关闭"),ammo,pose,()->ship.removeListener(rangeModifier)})try{item.close();}catch(Throwable error){if(error instanceof VirtualMachineError fatal)throw fatal;if(failure==null)failure=error;else failure.addSuppressed(error);}
        closed=true;phase=Phase.CLOSED;for(WeaponAPI weapon:weapons)weapon.setForceNoFireOneFrame(true);if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");publish();
        if(failure!=null)throw new IllegalStateException("Zeta rifle cleanup failed",failure);
    }
}
