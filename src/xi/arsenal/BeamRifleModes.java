package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.WeaponRangeModifier;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.lwjgl.util.vector.Vector2f;

/** Authoritative semantic actions separated from the local mapped-input adapter.
 * Native mode groups and actual native first-light own firing/ammunition/flux. */
public final class BeamRifleModes implements AutoCloseable {
    public static final String KEY="xi.arsenal.rifle.modes",CHARGE_SLOT="ARSENAL_RIFLE_CHARGE",XI_CHARGE="Moci_fw_xi_rifle_charge",PENELOPE_CHARGE="Moci_fw_penelope_rifle_charge";
    /** 远程接管标记：xi 语义所有权的唯一外部写入点，为将来的机魂/多单位接管预留。
     *  当前全仓无写入方，因此等价于「只有本地驾驶机体拥有 xi 语义」。 */
    public static final String REMOTE_CONTROL=KEY+".remoteControl";
    /** 常驻排查开关（不依赖 devMode）：开启后每条装配/跳过决定都会在日志里留下 [MOCI-RIFLE] 行。 */
    static final boolean TRACE_OWNERSHIP=false;
    /** AI 意图排查开关（常驻、可审计、与 devMode 无关）：每船 2 秒最多一条 [MOCI-RIFLE-AI] 行。 */
    public static final boolean TRACE_AI=true;
    /** AI 蓄力保持时长：约 1.0 秒，使 estimate(hold) 落在 ~1.5x 伤害档。 */
    static final float AI_HOLD=1f;
    /** AI 点射臂炮的触发条件：目标进入射程占比 AI_POINT_RANGE，或结构占比降到 AI_POINT_HULL。 */
    static final float AI_POINT_RANGE=.45f,AI_POINT_HULL=.35f,AI_LOG_INTERVAL=2f;
    /** MAX_HOLD is the legacy normal-full visual alias, never a held-time cap. */
    public static final float MIN_HOLD=.2f,MAX_HOLD=3f,MIN_DURATION=.45f,MAX_DURATION=2.4f,MAX_DAMAGE_SCALE=2.5f,COOLDOWN=2.4f;
    public static final float NORMAL_FULL_HOLD=MAX_HOLD,OVERLIMIT_HOLD=10f,OVERLIMIT_FULL_HOLD=15f,MAX_ACTUAL_DAMAGE_SCALE=4f,OVERLIMIT_LOCKOUT_SECONDS=5f;
    public enum Mode{PULSE,CHARGE}
    public enum Phase{IDLE,HOLDING,PRESENTING,RELEASING,FIRING,COOLDOWN,CLOSED}
    public record Request(boolean accepted,String reason){}
    public record Charge(float duration,float damageScale,boolean overlimit,float overlimitPower,float rangeMultiplier,float width){}
    private final Thread owner=Thread.currentThread();
    private final ShipAPI ship;
    private final CombatEngineAPI engine;
    private final WeaponAPI pulse,beam;
    private final UnicornMagnumGroups groups;
    private final UnicornMagnumAmmo ammo;
    private final WeaponRangeModifier rangeModifier;
    /** AI 语义标记：false = 本地驾驶机体（xi 语义）；true = AI 载机（只做禁火/放行的原生语义）。 */
    private final boolean aiMode;
    private final BeamRifleAiIntent intent;
    private boolean aiPointFire;
    private String aiReason="AI 就绪";
    private double aiLastLog=-AI_LOG_INTERVAL;
    private Mode mode=Mode.PULSE;
    private Phase phase=Phase.IDLE;
    private boolean fireDown,confirmed,closed,wasLocal,mappedInput;
    private float hold,duration,scale=1,fired,cooldown,wait,presenting;
    private double localElapsed,firstLightLocal=-1;
    private String reason="就绪",authorityOwner="";
    private long authorityEpoch,shots,cancellations;
    private BooleanSupplier authority;
    private float activeRangeMult=1,shotWidth=42,overlimitPower,lockoutRemaining,lockoutAge=-1;
    private boolean overlimit,lockoutPending,lockoutApplied,rangeNeedsReset;
    private long lockoutEpoch;
    private double watchdogElapsed;
    private int staleTicks;
    private final boolean executionProfile;
    private RifleExecutionPose executionPose;
    private RifleExecutionTimeLease executionTime;
    private ShipAPI executionTarget;
    private RifleBracedPose bracePose;
    private ShipAPI pendingExecutionTarget;
    private Map<String,Object> executionEntry=Map.of(),lastExecution=Map.of();
    private float executionElapsed,executionRecovery,executionFactor=1,executionRecoveryFactor=1,executionMaxFactor=1;
    /** 原生首亮之后的照射计时：时缓只在这段时间里递增。 */
    private float executionStrike;
    private boolean executionRecovering;
    private long executionAttempts,executionShots,executionCancelled;
    public BeamRifleModes(ShipAPI ship){this(ship,null);}
    BeamRifleModes(ShipAPI ship,UnicornMagnumGroups.Access groupAccess){
        this.ship=Objects.requireNonNull(ship);
        engine=Objects.requireNonNull(Global.getCombatEngine());
        pulse=ship.getAllWeapons().stream()
                .filter(w->BeamRiflePalette.isOwnRifle(w.getId())&&w.getSlot()!=null&&"ARM_R_SOURCE".equals(w.getSlot().getId()))
                .findFirst()
                .orElseThrow(()->new IllegalStateException("Owned visible right rifle missing"));
        String id=BeamRiflePalette.PENELOPE_WEAPON.equals(pulse.getId())?PENELOPE_CHARGE:XI_CHARGE;
        beam=ship.getAllWeapons().stream()
                .filter(w->id.equals(w.getId())&&w.getSlot()!=null&&CHARGE_SLOT.equals(w.getSlot().getId()))
                .findFirst()
                .orElseThrow(()->new IllegalStateException("Owned native charge rifle missing: "+id));
        if(!beam.isBeam()||beam.isBurstBeam()||pulse.isBeam())throw new IllegalStateException("Expected native pulse plus continuous beam");
        if(ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Rifle mode owner already installed");
        executionProfile=DskRifleCurveV2.isDsk(ship);
        aiMode=!controlOwned(ship,engine);
        intent=aiMode?new BeamRifleAiIntent():null;
        /* AI 模式不接管弹仓与原生武器组：两张弹仓的容量强耦合、以及 giveCommand(SELECT_GROUP)
           都会改写 AI 舰的原生装配语义。AI 继续用原生弹仓（beam.getAmmo()）与原生武器组打仗。 */
        ammo=aiMode?null:new UnicornMagnumAmmo(pulse,beam);
        groups=aiMode?null:groupAccess==null?new UnicornMagnumGroups(ship,pulse,beam):new UnicornMagnumGroups(ship,pulse,beam,groupAccess);
        if(aiMode)mode=Mode.CHARGE; // AI 的语义就是「蓄力照射」，顺带跳过点射冷却判据。
        rangeModifier=new WeaponRangeModifier(){
            public float getWeaponRangePercentMod(ShipAPI s,WeaponAPI w){return 0;}
            public float getWeaponRangeFlatMod(ShipAPI s,WeaponAPI w){return 0;}
            public float getWeaponRangeMultMod(ShipAPI s,WeaponAPI w){return s==ship&&w==beam?activeRangeMult:1;}
        };
        ship.addListener(rangeModifier);
        wasLocal=engine.getPlayerShip()==ship;
        ship.setCustomData(KEY+".owner",this);
        prime();
        publish();
    }
    /** xi 语义的显式所有权契约：只有本地驾驶机体（或显式远程接管者）拥有蓄力状态机、
     *  姿态接管与禁火接管。其余载机（AI）走「AI 语义」：控制器只提供禁火/放行，
     *  姿态、fireOffsets、武器组、弹仓、HUD 全部留给原生 —— 否则 AI 会被永久哑火。 */
    public static boolean controlOwned(ShipAPI ship,CombatEngineAPI engine){
        if(ship==null)return false;
        CombatEngineAPI actual=engine==null?Global.getCombatEngine():engine;
        if(actual!=null&&actual.getPlayerShip()==ship)return true;
        return Boolean.TRUE.equals(ship.getCustomData().get(REMOTE_CONTROL));
    }
    public static boolean controlOwned(ShipAPI ship){return controlOwned(ship,Global.getCombatEngine());}
    /** 结构装配判据：与所有权无关，只看这条船体有没有装齐「可见步枪 + 蓄力枪」。 */
    public static boolean supports(ShipAPI ship){
        return ship!=null
                &&!ship.isFighter()
                &&!ship.isStationModule()
                &&ship.getAllWeapons().stream().anyMatch(w->BeamRiflePalette.isOwnRifle(w.getId()));
    }
    /** 该不该为这条船建控制器：装配齐，且要么拥有 xi 语义（本地驾驶机体），
     *  要么是能打的 AI 载机（只驱动原生开火，不接管姿态/弹仓/武器组/HUD）。 */
    public static boolean hostEligible(ShipAPI ship){
        return supports(ship)&&(controlOwned(ship)||aiDrivable(ship));
    }
    private static boolean aiDrivable(ShipAPI ship){
        return ship!=null&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved();
    }
    /** true = 这台控制器只做 AI 语义（禁火/放行 + 意图驱动），不碰任何玩家专属状态。 */
    public boolean aiMode(){return aiMode;}
    public static BeamRifleModes instance(ShipAPI ship){
        Object p=ship==null?null:ship.getCustomData().get(KEY+".owner");
        return p instanceof BeamRifleModes m&&!m.closed?m:null;
    }
    public static boolean isCharge(WeaponAPI w){return w!=null&&(XI_CHARGE.equals(w.getId())||PENELOPE_CHARGE.equals(w.getId()));}
    public WeaponAPI source(){check();return pulse;}
    public WeaponAPI chargeWeapon(){check();return beam;}
    public float shotOverlimitPower(){check();return overlimit?overlimitPower:0;}
    public boolean rightArmLocked(){check();return lockoutPending||lockoutRemaining>0;}
    private float presentationSeconds(){
        return executionPose!=null
                ?DskRifleCurveV2.toPresentation(overlimit?overlimitPower:0).total()
                :pendingExecutionTarget!=null
                        ?RifleBracedPose.EXIT_SECONDS+DskRifleCurveV2.toPresentation(overlimit?overlimitPower:0).total()
                        :RifleBracedPose.ENTER_SECONDS;
    }
    /** Existing semantic schema identifies real arm attacks. No slot-name similarity or shared damage state is used. */
    static Set<String> boundRightArmSlots(Map<?,?> aliases,Map<?,?> capabilities,Collection<String> attacks){
        Object root=aliases.get("ARM_R");
        if(!(root instanceof String canonical)||!(capabilities.get(canonical) instanceof Map<?,?> description)||!"ARM_R".equals(description.get("bodySlot")))return Set.of();
        Set<String> result=new TreeSet<>();
        for(String slot:attacks)if(canonical.equals(aliases.get(slot)))result.add(slot);
        return Collections.unmodifiableSet(result);
    }
    private List<WeaponAPI> rightArmAttacks(){
        List<WeaponAPI> attacks=ship.getAllWeapons().stream().filter(w->!w.isDecorative()&&w.getSlot()!=null).limit(MociWeaponTargetBindings.MAX_WEAPONS).toList();
        Set<String> slots=Set.of();
        Object raw=engine.getCustomData().get(MociWeaponTargetBindings.KEY);
        if(raw instanceof Map<?,?> bridge&&bridge.get("schema") instanceof Number n&&n.intValue()==1
                &&bridge.get("bindings") instanceof Map<?,?> bindings&&bindings.get(ship) instanceof Map<?,?> aliases
                &&bridge.get("capabilities") instanceof Map<?,?> caps&&caps.get(ship) instanceof Map<?,?> descriptions)
            slots=boundRightArmSlots(aliases,descriptions,attacks.stream().map(w->w.getSlot().getId()).toList());
        Set<String> bound=slots;
        return attacks.stream().filter(w->w==pulse||w==beam||bound.contains(w.getSlot().getId())).toList();
    }
    public static Charge estimate(float held){
        if(!Float.isFinite(held)||held<0)throw new IllegalArgumentException("Hold duration must be finite and nonnegative");
        float q=Math.max(0,Math.min(1,(held-MIN_HOLD)/(NORMAL_FULL_HOLD-MIN_HOLD)));
        boolean over=held>OVERLIMIT_HOLD;
        float power=over?.2f+.8f*Math.max(0,Math.min(1,(held-OVERLIMIT_HOLD)/(OVERLIMIT_FULL_HOLD-OVERLIMIT_HOLD))):0;
        return new Charge(MIN_DURATION+(MAX_DURATION-MIN_DURATION)*q,over?MAX_DAMAGE_SCALE+(MAX_ACTUAL_DAMAGE_SCALE-MAX_DAMAGE_SCALE)*power:1+(MAX_DAMAGE_SCALE-1)*q,over,power,1+.25f*power,over?110+30*power:42+18*q);
    }
    /** Host-side controller capability. Caller authenticates and binds its player before installing this guard. */
    public Request bindAuthority(String controller,long epoch,BooleanSupplier valid){
        check();
        if(controller==null||controller.isBlank()||epoch<=authorityEpoch||valid==null)return no("控制代次无效");
        cancel("控制权已重新绑定");
        authorityOwner=controller;
        authorityEpoch=epoch;
        authority=valid;
        publish();
        return new Request(true,"权威动作控制已绑定");
    }
    public void releaseAuthority(String controller,long epoch){
        check();
        if(authority!=null&&authorityOwner.equals(controller)&&authorityEpoch==epoch){cancel("控制权已释放");authority=()->false;publish();}
    }
    public Request requestMode(Mode wanted){
        check();
        Objects.requireNonNull(wanted);
        if(groups==null)return no("AI 载机不接管原生武器组");
        if(!live())return no("机体失效");
        if(wanted!=mode)cancel("切换步枪模式");
        if(!groups.select(wanted==Mode.CHARGE))return no("原生武器组已被移除");
        publish();
        return new Request(true,"原生武器组选择已请求");
    }
    public Request requestFire(boolean down){
        check();
        selection();
        return down?beginCharge():releaseCharge();
    }
    private Request beginCharge(){
        if(lockoutPending||lockoutRemaining>0)return no(String.format(Locale.ROOT,"破限后右臂武装宕机 %.1f秒",lockoutPending?OVERLIMIT_LOCKOUT_SECONDS:lockoutRemaining));
        if(fireDown)return new Request(true,"保持蓄力");
        if(phase==Phase.PRESENTING||phase==Phase.RELEASING){cancel("上一次释放未完成，重新开始蓄力");}
        String block=block();
        if(!block.isEmpty())return no(block);
        if(phase!=Phase.IDLE)return no("步枪状态未就绪："+phase.name());
        if(cooldown>0||!dark())return no("步枪冷却或光束余辉未结束");
        /* 点射冷却只属于点射模式，蓄力模式不受它影响 */
        if(mode==Mode.PULSE&&pulse.getCooldownRemaining()>.0001f)return no("点射冷却未完成");
        if(ammo!=null){ammo.observe();if(ammo.reserve()<1)return no("步枪弹仓为空");}
        else if(!aiCanPay())return no("步枪弹仓为空");
        fireDown=true;hold=0;confirmed=false;overlimit=false;overlimitPower=0;lockoutApplied=false;activeRangeMult=1;shotWidth=42;firstLightLocal=-1;phase=Phase.HOLDING;reason="按住蓄力，松手照射";
        prime();
        publish();
        return new Request(true,reason);
    }
    private Request releaseCharge(){
        if(!fireDown||phase!=Phase.HOLDING)return no("没有待释放的蓄力");
        fireDown=false;
        String block=block();
        if(!block.isEmpty()){cancel(block);return no(block);}
        if(hold+1e-5f<MIN_HOLD){cancel("蓄力不足0.2秒");return no(reason);}
        applyCharge(estimate(hold));
        phase=Phase.PRESENTING;presenting=wait=fired=0;confirmed=false;
        if(executionProfile&&!aiMode){ // 处决姿态与时间缓只属于本地驾驶机体语义，AI 不触发。
            ShipAPI target=ship.getShipTarget();
            RifleExecutionGate.Snapshot candidate=RifleExecutionGate.evaluate(engine,ship,target);
            executionEntry=candidate.status();
            if(candidate.allowed()&&engine.getPlayerShip()==ship&&ContentTargetLocks.inRange(ship,target,beam.getRange())){
                if(bracePose!=null)pendingExecutionTarget=target;
                else if(!beginExecution(target))return no(reason);
                reason="处决窗口："+candidate.reason()+" · 同一发锁定射击";
            }else reason="蓄力释放 · 普通照射";
        }else reason="端枪对准后照射";
        prime();
        align();
        if(executionPose==null&&pendingExecutionTarget==null)armAfterPresenting();
        publish();
        return new Request(true,reason);
    }
    public void cancelCharge(String reason){check();cancel(reason==null?"蓄力已取消":reason);publish();}
    public void processInput(List<InputEventAPI> events){
        check();
        if(aiMode||engine.getPlayerShip()!=ship||authority!=null)return;
        for(var event:events){
            if(event.isConsumed()||event.isRepeat())continue;
            if(ship.getSelectedGroupAPI()!=groups.chargeGroup&&!fireDown)continue;
            if(event.isControlDownEvent("SHIP_FIRE")){mappedInput=true;requestFire(true);}else if(event.isControlUpEvent("SHIP_FIRE")){requestFire(false);mappedInput=false;}
        }
    }
    public void advance(float localStep){
        try{
            check();
            if(engine.isPaused()){reconcileExecutionOwnership();return;}
            if(!Float.isFinite(localStep)||localStep<0)throw new IllegalArgumentException("Invalid rifle local time");
            localElapsed+=localStep;
            tickLockout(localStep);
            if(ammo!=null)ammo.observe();
            reconcileExecutionOwnership();
            if(aiMode){aiTick(localStep);return;}
            confirm();
            selection();
            if(groups.chargeGroup.isAutofiring())groups.chargeGroup.toggleOff();
            trackLocalOwnership();
            enforceActionBlock();
            advancePhase(localStep);
            advanceBrace(localStep);
            advanceExecution(localStep);
            prime();
            align();
            armAfterPresenting();
            settleShot();
            publish();
            hud();
        }catch(RuntimeException|LinkageError failure){fail("步枪控制器异常："+failure);}
    }
    /** 超限锁出计时：remaining 归零时同步关掉 age 视图。 */
    private void tickLockout(float localStep){
        if(lockoutRemaining>0){lockoutAge+=localStep;lockoutRemaining=Math.max(0,lockoutRemaining-localStep);if(lockoutRemaining<=0)lockoutAge=-1;}
    }
    /** 本地驾驶权跟踪：只在「本轮本来是本地、现在不是」时放弃蓄力。 */
    private void trackLocalOwnership(){
        boolean local=engine.getPlayerShip()==ship;
        if(authority==null&&wasLocal&&!local)cancel("驾驶机体已切换");
        wasLocal=local;
    }
    /* ------------------------------------------------------------------
       AI 语义：同一台状态机的另一个驱动器。只决定「什么时候按下 / 什么时候松开」，
       不写姿态、不碰武器组、不碰弹仓、不写 HUD —— 那些是本地驾驶机体的专属语义。
       ------------------------------------------------------------------ */

    private void aiTick(float dt){
        String why=blockAi();
        if(!why.isEmpty()){
            if(phase==Phase.HOLDING||phase==Phase.PRESENTING||phase==Phase.RELEASING||phase==Phase.FIRING)cancel(why);
            else aiReason=why;
            primeAi();
            return;
        }
        ShipAPI target=intent.target(ship,beam,engine);
        aiPointFire=pointFireWanted(target);
        if(phase==Phase.IDLE){
            if(target!=null&&!aiPointFire){
                Request started=beginCharge();
                aiReason=started.accepted()?"AI 开始蓄力":started.reason();
            }else aiReason=target==null?"AI 无目标":"AI 点射臂炮";
        }else if(phase==Phase.HOLDING){
            if(target==null)cancel("AI 目标丢失");else if(hold>=AI_HOLD&&!releaseCharge().accepted())aiReason=reason;
        }
        advancePhase(dt);
        armAfterPresenting();
        confirm();
        settleShot();
        primeAi();
        aiLog(target);
    }
    /** 近距/残血走原生点射臂炮；其余交给蓄力照射。 */
    private boolean pointFireWanted(ShipAPI target){
        if(target==null)return false;
        float range=Math.max(1f,beam.getRange());
        float distance=(float)Math.sqrt(BeamRifleAiIntent.squaredDistance(ship.getLocation(),target.getLocation()));
        /* 蓄力光束整场只有一仓弹（ammoPerSecond=0）。打空后不要再空转蓄力，
           改由 2.25 发/秒自回弹的臂炮接战；臂炮够不着就老实等目标进射程。 */
        if(!aiCanPay())return distance<=Math.max(1f,pulse.getRange());
        return distance<=range*AI_POINT_RANGE&&(target.getHullLevel()<=AI_POINT_HULL||distance<=range*.25f);
    }
    /** AI 版阻断器：玩家专属判据（本地驾驶权、映射输入、UI 占用、窗口焦点、选中武器组）在这里全不适用；
     *  照搬会让人 enforceActionBlock 每帧取消动作，那正是 P1 哑火的第二层原因。 */
    private String blockAi(){
        if(closed||!live()||Global.getCombatEngine()!=engine)return "机体或战场失效";
        if(engine.isPaused()||ship.controlsLocked())return "当前控制已暂停";
        if(ship.getFluxTracker().isOverloadedOrVenting())return "过载或排幅取消蓄力";
        if(rifleDisabled())return "步枪已禁火";
        return "";
    }
    /** AI 版武器可用性：只看这两把武器自身是否被禁用；
     *  舰级 holdFire 是原版 AI 对「它自己那批武器」的每帧决定，而我们已接管这两把，不能拿它当阻断。 */
    private boolean rifleDisabled(){
        return pulse.isDisabled()||beam.isDisabled()||pulse.isForceDisabled()||beam.isForceDisabled();
    }
    /** AI 版禁火闸门：光束只在 RELEASING/FIRING 放行，臂炮只在点射意图成立时放行。 */
    private void primeAi(){
        if(rightArmLocked())for(WeaponAPI attack:rightArmAttacks())attack.setForceNoFireOneFrame(true);
        if(nativeBeamHoldBlocked())beam.setForceNoFireOneFrame(true);
        else if(!beam.isForceNoFireOneFrame())beam.setForceFireOneFrame(true);
        if(aiPointFire&&phase==Phase.IDLE&&blockAi().isEmpty()){if(!pulse.isForceNoFireOneFrame())pulse.setForceFireOneFrame(true);}
        else pulse.setForceNoFireOneFrame(true);
    }
    private void beforeWeaponAi(WeaponAPI weapon){
        suppressRightArmAttacks(weapon);
        if(weapon==pulse){
            if(aiPointFire&&phase==Phase.IDLE&&blockAi().isEmpty()){if(!weapon.isForceNoFireOneFrame())weapon.setForceFireOneFrame(true);}
            else weapon.setForceNoFireOneFrame(true);
        }else if(weapon==beam)suppressOrReleaseBeam(weapon);
    }
    private void afterWeaponAi(WeaponAPI weapon){
        armAfterPresenting();
        if(weapon==beam)confirm();
        settleShot();
    }
    /** AI 没有姿态与托握可用，释放条件只剩「相位、阻断、原生弹仓」。 */
    private void armAfterPresentingAi(){
        if(phase!=Phase.PRESENTING)return;
        String why=blockAi();
        if(!why.isEmpty()){cancel(why);return;}
        if(!aiCanPay()){cancel("步枪弹仓为空");return;}
        phase=Phase.RELEASING;
        wait=0;
        reason="AI 释放蓄力，等待原生首亮";
        primeAi();
    }
    private boolean aiCanPay(){return beam.getAmmo()>0||beam.getMaxAmmo()<=0;}
    private void aiLog(ShipAPI target){
        if(!TRACE_AI)return;
        if(localElapsed-aiLastLog<AI_LOG_INTERVAL)return;
        aiLastLog=localElapsed;
        Global.getLogger(BeamRifleModes.class).info("[MOCI-RIFLE-AI] "+ship.getHullSpec().getHullId()
                +" phase="+phase.name()+" mode="+mode.name()
                +String.format(Locale.ROOT," hold=%.2f scale=%.2f ammo=%d",hold,scale,ammo==null?beam.getAmmo():ammo.reserve())
                +" target="+(target==null?"none":target.getHullSpec().getHullId())
                +" point="+aiPointFire+" note="+aiReason);
    }
    /** 蓄力/抬枪/释放/照射期间任何阻断条件都立刻取消。 */
    private void enforceActionBlock(){
        if(phase==Phase.HOLDING||phase==Phase.PRESENTING||phase==Phase.RELEASING||phase==Phase.FIRING){String why=block();if(!why.isEmpty())cancel(why);}
    }
    /** 相位时钟推进；原生首亮仍由 confirm() 触发，不在这里臆测。 */
    private void advancePhase(float localStep){
        if(phase==Phase.HOLDING){hold+=localStep;applyCharge(estimate(hold));}else if(phase==Phase.PRESENTING){presenting+=localStep;if(presenting>presentationSeconds()+1.5f)cancel("抬枪对准超时，已撤销本次释放");}else if(phase==Phase.RELEASING){wait+=localStep;if(wait>.35f)cancel("原生首亮未确认，已撤销预留");}else if(phase==Phase.FIRING){fired+=localStep;if(fired>=duration){phase=Phase.COOLDOWN;cooldown=COOLDOWN;finishConfirmedShot();reason="照射结束";}}else if(phase==Phase.COOLDOWN){cooldown=Math.max(0,cooldown-localStep);if(cooldown<=0&&dark()){phase=Phase.IDLE;reason="就绪";}}
    }
    /** 把任意角度归一化到 (-180,180]，避免处决姿态把 360° 外的绝对角直接写进武器。 */
    private static float shortest(float x){float v=((x%360)+360)%360;return v>180?v-360:v;}
    /** 处决瞄准角：贴脸时枪口可能已经越进目标碰撞体，muzzle->center 向量会退化甚至反向；
     *  此时改用「本舰->目标」轴，仍然是瞄准目标本体，只是方向更稳定。 */
    private float executionAim(){
        Vector2f muzzle=BeamRifleMount.visibleMuzzleWorld(pulse),goal=executionTarget.getLocation(),origin=ship.getLocation();
        if(muzzle==null||goal==null||origin==null)return ship.getFacing();
        float dx=goal.x-muzzle.x,dy=goal.y-muzzle.y;
        float radius=Math.max(1f,executionTarget.getCollisionRadius());
        if(!Float.isFinite(dx)||!Float.isFinite(dy)||(float)Math.sqrt(dx*dx+dy*dy)<radius+20f){dx=goal.x-origin.x;dy=goal.y-origin.y;}
        if(!Float.isFinite(dx)||!Float.isFinite(dy)||(float)Math.sqrt(dx*dx+dy*dy)<1f)return ship.getFacing();
        return shortest((float)Math.toDegrees(Math.atan2(dy,dx)));
    }
    /** 时钟失步看门狗：相位仍会强推原生光束、但 advance 已不再推进时强制收火。 */
    private void watchdog(){
        if(phase==Phase.RELEASING||phase==Phase.FIRING){
            if(localElapsed==watchdogElapsed){if(++staleTicks>8)fail("步枪时钟失步，强制收火");}else{staleTicks=0;watchdogElapsed=localElapsed;}
        }else{staleTicks=0;}
    }
    /** 异常兜底：退回安全相位并停掉原生光束，绝不让异常逃到战斗总线（总线会 detach 整个 sink）。 */
    private void fail(String why){
        try{cancel(why);}catch(RuntimeException|LinkageError ignored){}
        try{phase=Phase.COOLDOWN;cooldown=Math.max(cooldown,COOLDOWN);beam.stopFiring();beam.setForceNoFireOneFrame(true);pulse.setForceNoFireOneFrame(true);closeExecutionTime();}catch(RuntimeException|LinkageError ignored){}
        Global.getLogger(BeamRifleModes.class).warn("Beam rifle controller recovered: "+why);
    }
    private void selection(){
        if(groups==null)return; // AI 载机不接管原生武器组
        Mode next=ship.getSelectedGroupAPI()==groups.chargeGroup?Mode.CHARGE:ship.getSelectedGroupAPI()==groups.pulseGroup?Mode.PULSE:null;
        if(next!=null&&next!=mode){cancel("原生武器组已切换");mode=next;}
    }
    /** 空串 = 当前允许原生开火；非空 = 阻断原因（也是日志里的可读原因）。 */
    private String block(){
        if(aiMode)return blockAi();
        if(closed||!live()||Global.getCombatEngine()!=engine)return "机体或战场失效";
        if(engine.isPaused()||ship.controlsLocked())return "当前控制已暂停";
        if(authority==null&&engine.getPlayerShip()!=ship)return "不是当前本地驾驶机体";
        if(authority!=null&&!authority.getAsBoolean())return "远程控制代次已失效";
        if(authority==null&&mappedInput&&!org.lwjgl.opengl.Display.isActive())return "失去窗口焦点，取消本地蓄力";
        if(authority==null&&uiOccupied())return "界面正在占用操作";
        if(ship.getSelectedGroupAPI()!=groups.chargeGroup)return "未选择步枪蓄力组";
        if(ship.getFluxTracker().isOverloadedOrVenting())return "过载或排幅取消蓄力";
        String poseBlock=RifleFiringPose.blockedReason(ship);
        if(!poseBlock.isEmpty())return poseBlock;
        if(weaponsSuppressed())return "步枪已禁火";
        return "";
    }
    private boolean uiOccupied(){
        return engine.isUIShowingDialog()||engine.getCombatUI()!=null&&(engine.getCombatUI().isShowingCommandUI()||engine.getCombatUI().isShowingDeploymentDialog());
    }
    private boolean weaponsSuppressed(){
        return pulse.isDisabled()||beam.isDisabled()||pulse.isForceDisabled()||beam.isForceDisabled()||ship.isHoldFire()||ship.isHoldFireOneFrame();
    }
    private boolean live(){return ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship);}
    private boolean dark(){return beam.getBeams().stream().noneMatch(b->b.getBrightness()>.0001f);}
    private void cancel(String why){
        if(phase==Phase.HOLDING||phase==Phase.PRESENTING||phase==Phase.RELEASING||phase==Phase.FIRING){
            cancellations++;
            if(!confirmed){if(ammo!=null)ammo.cancelReservation();activeRangeMult=1;overlimit=false;overlimitPower=0;}
            if(confirmed){phase=Phase.COOLDOWN;cooldown=Math.max(cooldown,COOLDOWN);finishConfirmedShot();}else phase=Phase.IDLE;
            reason=why;
        }
        fireDown=false;mappedInput=false;hold=wait=presenting=0;
        pendingExecutionTarget=null;
        if(executionPose!=null){
            if(!executionRecovering)executionCancelled++;
            beginExecutionRecovery();
            phase=Phase.COOLDOWN;
            cooldown=Math.max(cooldown,RifleExecutionPose.RECOVER_SECONDS);
        }
        closeExecutionTime();
        align();
    }
    private void applyCharge(Charge q){
        duration=q.duration;scale=q.damageScale;overlimit=q.overlimit;overlimitPower=q.overlimitPower;activeRangeMult=q.rangeMultiplier;shotWidth=q.width;
    }
    private void finishConfirmedShot(){rangeNeedsReset=true;if(overlimit&&!lockoutApplied)lockoutPending=true;}
    private void settleShot(){
        if(!dark())return;
        if(rangeNeedsReset){activeRangeMult=1;rangeNeedsReset=false;}
        if(lockoutPending){lockoutPending=false;lockoutApplied=true;lockoutRemaining=OVERLIMIT_LOCKOUT_SECONDS;lockoutAge=0;lockoutEpoch++;}
    }
    private Request no(String why){reason=why;publish();return new Request(false,why);}
    /** 原生禁火闸门：没有 xi 所有者的载机一律不碰（AI 走原生语义）。 */
    private void prime(){
        if(aiMode){primeAi();return;}
        if(rightArmLocked())for(WeaponAPI attack:rightArmAttacks())attack.setForceNoFireOneFrame(true);
        if(nativeBeamHoldBlocked())beam.setForceNoFireOneFrame(true);
        if(nativePulseHoldBlocked())pulse.setForceNoFireOneFrame(true);
    }
    private boolean nativeBeamHoldBlocked(){return phase!=Phase.RELEASING&&phase!=Phase.FIRING||lockoutPending||lockoutRemaining>0;}
    private boolean nativePulseHoldBlocked(){return mode==Mode.CHARGE||phase==Phase.FIRING||cooldown>0||lockoutPending||lockoutRemaining>0;}
    public static void beforeWeapon(WeaponAPI weapon){
        try{
            BeamRifleModes own=instance(weapon.getShip());
            if(own==null){suppressOwnerlessChargeRifle(weapon);return;}
            if(own.aiMode){own.beforeWeaponAi(weapon);return;}
            own.selection();
            own.align();
            own.suppressRightArmAttacks(weapon);
            if(weapon==own.pulse)own.suppressPulse(weapon);
            else if(weapon==own.beam)own.suppressOrReleaseBeam(weapon);
        }catch(RuntimeException|LinkageError failure){
            BeamRifleModes failed=instance(weapon==null?null:weapon.getShip());
            if(failed!=null)failed.fail("步枪开火回调异常："+failure);
        }
    }
    /** 没有控制器 = 该舰不拥有 xi 语义（AI 载机，或装配不全）。
     *  原生开火一律不干预；只有本地驾驶机体的蓄力枪要先禁火，等玩家输入驱动原生首亮。 */
    private static void suppressOwnerlessChargeRifle(WeaponAPI weapon){
        if(isCharge(weapon)&&controlOwned(weapon.getShip()))weapon.setForceNoFireOneFrame(true);
    }
    private void suppressRightArmAttacks(WeaponAPI weapon){
        if(rightArmLocked()&&rightArmAttacks().contains(weapon))weapon.setForceNoFireOneFrame(true);
    }
    private void suppressPulse(WeaponAPI weapon){
        if(mode!=Mode.PULSE||phase==Phase.HOLDING||phase==Phase.PRESENTING||phase==Phase.RELEASING||phase==Phase.FIRING||cooldown>0||lockoutPending||lockoutRemaining>0)weapon.setForceNoFireOneFrame(true);
    }
    private void suppressOrReleaseBeam(WeaponAPI weapon){
        watchdog();
        if((phase!=Phase.RELEASING&&phase!=Phase.FIRING)||lockoutPending||lockoutRemaining>0||!block().isEmpty())weapon.setForceNoFireOneFrame(true);
        else if(!weapon.isForceNoFireOneFrame())weapon.setForceFireOneFrame(true);
    }
    public static void afterWeapon(WeaponAPI weapon){
        try{
            BeamRifleModes own=instance(weapon.getShip());
            if(own!=null){
                if(own.aiMode){own.afterWeaponAi(weapon);return;}
                own.align();own.armAfterPresenting();if(weapon==own.beam)own.confirm();own.settleShot();
            }
        }catch(RuntimeException|LinkageError failure){
            BeamRifleModes failed=instance(weapon==null?null:weapon.getShip());
            if(failed!=null)failed.fail("步枪瞄准回调异常："+failure);
        }
    }
    private void align(){
        if(aiMode)return; // AI 不写枪口角、不写 fireOffsets：原生瞄准与弹道起点全部交还原生。
        aimBeamAndPulse();
        if(executionPose!=null)executionPose.applyAll();else if(bracePose!=null)bracePose.applyAll();
        applyVisibleMuzzleToNativeBeam();
    }
    /** 处决姿态 > 蓄力模式 > 点射模式：谁在主导这一帧的枪口角，就由谁写两个武器件。 */
    private void aimBeamAndPulse(){
        if(executionPose!=null&&!executionRecovering&&executionTarget!=null&&executionTarget.isAlive()&&!executionTarget.isHulk()){
            beam.setCurrAngle(executionAim());
        }
        if(mode==Mode.CHARGE||executionPose!=null)pulse.setCurrAngle(shortest(executionPose!=null?executionPose.aimAngle(beam.getCurrAngle()):beam.getCurrAngle()));
        else beam.setCurrAngle(shortest(pulse.getCurrAngle()));
    }
    /** 原生光束的发射点必须落在可见枪口上：先同步可见炮管，再把本地枪口写进三组 fireOffsets。 */
    private void applyVisibleMuzzleToNativeBeam(){
        BeamRifleMount.reconcileNativePose(pulse);
        if(beam.getSpec()==null)return;
        Vector2f at=BeamRifleMount.visibleMuzzleWorld(pulse),pivot=beam.getLocation();
        double a=Math.toRadians(beam.getCurrAngle());
        float x=at.x-pivot.x,y=at.y-pivot.y;
        Vector2f local=new Vector2f(x*(float)Math.cos(a)+y*(float)Math.sin(a),-x*(float)Math.sin(a)+y*(float)Math.cos(a));
        for(var list:List.of(beam.getSpec().getTurretFireOffsets(),beam.getSpec().getHardpointFireOffsets(),beam.getSpec().getHiddenFireOffsets()))
            for(Vector2f offset:list)offset.set(local);
        beam.updateBeamFromPoints();
    }
    private void armAfterPresenting(){
        if(aiMode){armAfterPresentingAi();return;}
        if(phase!=Phase.PRESENTING||pendingExecutionTarget!=null)return;
        if(executionPose!=null){if(!executionPose.readyForBeam())return;}else if(bracePose==null||!bracePose.canRelease())return;
        float difference=shortest(pulse.getCurrAngle()-beam.getCurrAngle());
        if(Math.abs(difference)>.05f)return;
        String why=block();
        if(!why.isEmpty()){cancel(why);return;}
        if(!ammo.reserveBeam()){cancel("步枪弹仓为空");return;}
        phase=Phase.RELEASING;
        wait=0;
        reason=executionPose!=null?"处决瞄准完成，等待这一发原生首亮":bracePose.ready()?"双手托握完成，等待原生首亮":"极限角度使用原生单手，等待原生首亮";
        publish();
    }
    private void confirm(){
        if(phase!=Phase.RELEASING||dark())return;
        if(ammo!=null&&!ammo.reservationPending())throw new IllegalStateException("Unpaid native rifle charge beam");
        if(ammo!=null)ammo.confirmBeam();
        confirmed=true;
        phase=Phase.FIRING;
        executionStrike=0; // 时缓只从原生首亮这一帧开始给：抬枪/对准阶段不再减速。
        fired=0;
        firstLightLocal=localElapsed;
        shots++;
        if(executionPose!=null){executionPose.onNativeFirstLight();executionShots++;}
        align();
        reason=executionPose!=null?"处决照射中 · 同一发弹药":"端枪照射中";
        if(Global.getSoundPlayer()!=null)Global.getSoundPlayer().playSound("Moci_fw_arsenal_beam_rifle_shot",1f,.9f,pulse.getFirePoint(0),ship.getVelocity());
        publish();
    }
    private void advanceBrace(float dt){
        if(bracePose!=null&&FullSalvoPose.isBlockingRifle(ship))closeBrace();
        boolean wanted=braceWanted();
        if(bracePose==null&&wanted&&!ship.getCustomData().containsKey(RifleFiringPose.KEY)&&!ship.getCustomData().containsKey(RifleFiringPose.KEY+".releaseRig"))bracePose=new RifleBracedPose(ship,pulse);
        if(bracePose!=null){
            bracePose.advance(dt,wanted,beam.getCurrAngle());
            if(bracePose.finished())closeBrace();
        }
        if(bracePose==null&&pendingExecutionTarget!=null){
            ShipAPI target=pendingExecutionTarget;
            pendingExecutionTarget=null;
            if(!beginExecution(target))cancel(reason);
        }
    }
    /** 收掉托握姿态并把可见枪口写回原生武器（否则枪口停在动画中间帧）。 */
    private void closeBrace(){
        if(bracePose==null)return;
        bracePose.close();
        bracePose=null;
        BeamRifleMount.reconcileNativePose(pulse);
    }
    /** 双手托握是否该存在：蓄力组被选中、无处决动作、机体可用且拥有 xi 语义。 */
    private boolean braceWanted(){
        if(groups==null)return false; // AI 不建托握姿态
        return mode==Mode.CHARGE&&ship.getSelectedGroupAPI()==groups.chargeGroup&&executionPose==null&&pendingExecutionTarget==null&&live()&&!ship.getFluxTracker().isOverloadedOrVenting()&&!rightArmLocked()&&!FullSalvoPose.isBlockingRifle(ship)&&!ship.controlsLocked()&&(engine.getPlayerShip()==ship||authority!=null);
    }
    private boolean beginExecution(ShipAPI target){
        RifleExecutionTimeLease temporal=null;RifleExecutionPose pose=null;
        try{
            temporal=new RifleExecutionTimeLease(engine,ship);
            pose=new RifleExecutionPose(ship,pulse,overlimit?overlimitPower:0);
        }catch(IllegalStateException blocked){
            try{if(pose!=null)pose.close();}finally{if(temporal!=null)temporal.close();}
            phase=Phase.IDLE;reason="处决动作暂不可用："+blocked.getMessage();return false;
        }
        executionTime=temporal;executionPose=pose;executionTarget=target;
        executionElapsed=executionRecovery=0;executionFactor=executionRecoveryFactor=executionMaxFactor=1;executionRecovering=false;executionAttempts++;
        return true;
    }
    /** Ownership checks also run while paused; no animation clock is consumed. */
    public void reconcileExecutionOwnership(){
        if(pendingExecutionTarget!=null&&!RifleExecutionGate.stillLocked(engine,ship,pendingExecutionTarget)){cancel("处决目标已解锁，撤销动作");return;}
        if(closed||executionPose==null||executionRecovering)return;
        // A killing first tick can precede this actor's frame callback. Pay and
        // count the already emitted native shot before treating a dead target as loss.
        if(phase==Phase.RELEASING&&!dark())confirm();
        if(executionTime!=null&&!executionTime.valid()){cancel("处决控制权已失效");return;}
        if(executionTarget==null||!executionTarget.isAlive()||executionTarget.isHulk()||executionTarget.wasRemoved()||!engine.isEntityInPlay(executionTarget)){
            if(confirmed&&phase==Phase.FIRING){beam.stopFiring();phase=Phase.COOLDOWN;cooldown=Math.max(cooldown,COOLDOWN);finishConfirmedShot();beginExecutionRecovery();reason="处决射击结束，收势";}
            else cancel("处决目标已失效，撤销未发射的动作");
        }else if(!RifleExecutionGate.stillLocked(engine,ship,executionTarget))cancel("处决目标已解锁或切换，撤销动作");
        else if(!ContentTargetLocks.inRange(ship,executionTarget,beam.getRange()))cancel("处决目标已离开射程");
    }
    private void beginExecutionRecovery(){
        if(executionPose==null||executionRecovering)return;
        executionRecovering=true;executionRecovery=0;executionRecoveryFactor=executionFactor;
    }
    private void advanceExecution(float dt){
        if(executionPose==null)return;
        if(phase!=Phase.PRESENTING&&phase!=Phase.RELEASING&&phase!=Phase.FIRING)beginExecutionRecovery();
        if(executionRecovering){
            executionRecovery+=dt;
            if(executionTime!=null){executionFactor=1+(executionRecoveryFactor-1)*(1-smoother(executionRecovery/.2f));if(!executionTime.setFactor(executionFactor))closeExecutionTime();else if(executionRecovery>=.2f)closeExecutionTime();}
            executionPose.advance(dt,RifleExecutionPose.Stage.RECOVER);
            if(executionPose.recovered())closeExecution();
        }else{
            /* 时缓窗口：只在 phase==FIRING（原生已首亮）内生效，进入 0.10 秒从 1 平滑拉到 3。
               PRESENTING/RELEASING 的抬枪对准阶段因子恒为 1 —— 不再让玩家在瞄准阶段变慢。 */
            executionElapsed+=dt;
            if(phase==Phase.FIRING)executionStrike+=dt;
            executionFactor=phase==Phase.FIRING?1+2*smoother(executionStrike/.1f):1;
            executionMaxFactor=Math.max(executionMaxFactor,executionFactor);
            if(executionFactor>1.0001f&&(executionTime==null||!executionTime.setFactor(executionFactor))){cancel("处决时间控制已失效");return;}
            executionPose.advance(dt,phase==Phase.FIRING?RifleExecutionPose.Stage.FIRING:RifleExecutionPose.Stage.PRESENTING);
            String block=executionPose.blockedReason();if(!block.isEmpty())cancel(block);
        }
    }
    private static float smoother(float value){float t=Math.max(0,Math.min(1,value));return t*t*t*(t*(t*6-15)+10);}
    private void closeExecutionTime(){if(executionTime!=null){executionTime.close();executionTime=null;}executionFactor=1;}
    private void closeExecution(){
        if(executionPose==null){closeExecutionTime();return;}
        /* 取快照本身可能抛（executionTime/executionPose 的状态查询），绝不能让它挡住收尾：
           租约必须无条件释放，否则 game 会停在时缓里出不来。 */
        try{lastExecution=executionStatus();}catch(RuntimeException|LinkageError ignored){}
        try{executionPose.close();}finally{closeExecutionTime();}
        executionPose=null;executionTarget=null;executionRecovering=false;
    }
    public Map<String,Object> executionStatus(){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",executionProfile);result.put("active",executionPose!=null||pendingExecutionTarget!=null);
        result.put("ordinaryChargePose","BIMANUAL_BRACE");result.put("state",executionPose==null?(pendingExecutionTarget==null?"IDLE":"UNBRACE"):executionRecovering?"RECOVER":phase.name());
        result.put("targetId",executionTarget==null?"":executionTarget.getId());result.put("entry",executionEntry);result.put("attempts",executionAttempts);result.put("shots",executionShots);result.put("cancelled",executionCancelled);
        result.put("factor",executionFactor);result.put("maxFactor",executionMaxFactor);result.put("time",executionTime==null?Map.of("closed",true):executionTime.status());result.put("pose",executionPose==null?Map.of():executionPose.status());
        result.put("triggerPolicy","LOCKED_TARGET_AND_ANY_OF_OVERLOAD_HULL_LE_25_PERCENT_NO_ACTIVE_DEFENSE");result.put("shotPolicy","SAME_NATIVE_CHARGE_SHOT_ONE_AMMO_NO_ADDITIONAL_SHOT");
        return Collections.unmodifiableMap(result);
    }
    public static float heldRatio(WeaponAPI weapon){BeamRifleModes own=instance(weapon.getShip());return own!=null&&own.phase==Phase.HOLDING?own.hold/MAX_HOLD:0;}
    public static float firingScale(WeaponAPI weapon){BeamRifleModes own=instance(weapon.getShip());return own==null?1:own.scale;}
    public static float firingWidth(WeaponAPI weapon){BeamRifleModes own=instance(weapon.getShip());return own==null?42:own.shotWidth;}
    public static float firingElapsed(WeaponAPI weapon){BeamRifleModes own=instance(weapon.getShip());return own==null||!own.confirmed?-1:own.fired;}
    public static float chargeRatio(WeaponAPI weapon){BeamRifleModes own=instance(weapon.getShip());return own==null||!(own.phase==Phase.HOLDING||own.phase==Phase.PRESENTING||own.phase==Phase.RELEASING||own.phase==Phase.FIRING)?0:Math.max(0,Math.min(1,own.hold/MAX_HOLD));}
    public Map<String,Object> status(){
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("brace",bracePose==null?Map.of("active",false):bracePose.status());r.put("execution",executionStatus());r.put("lastExecution",lastExecution);
        r.put("mode",mode.name());r.put("phase",phase.name());r.put("heldLocal",hold);r.put("maximumHold",-1);r.put("normalFullHold",NORMAL_FULL_HOLD);
        r.put("overlimitThreshold",OVERLIMIT_HOLD);r.put("overlimitFullHold",OVERLIMIT_FULL_HOLD);r.put("holdHasHardCap",false);
        r.put("duration",duration);r.put("damageScale",scale);r.put("firedLocal",fired);r.put("presentingLocal",presenting);r.put("presentSeconds",presentationSeconds());
        r.put("chargeRatio",chargeRatio(beam));r.put("firstLightLocal",firstLightLocal);r.put("localElapsed",localElapsed);
        r.put("overlimit",overlimit);r.put("overlimitPower",overlimitPower);r.put("lockoutRemaining",lockoutRemaining);r.put("lockoutAge",lockoutAge);r.put("lockoutEpoch",lockoutEpoch);
        r.put("rightArmLocked",lockoutPending||lockoutRemaining>0);r.put("armLockoutRemaining",lockoutPending?OVERLIMIT_LOCKOUT_SECONDS:lockoutRemaining);r.put("armLockoutAge",lockoutAge);r.put("armLockoutEpoch",lockoutEpoch);
        r.put("rightArmAttackSlots",rightArmAttacks().stream().map(w->w.getSlot().getId()).sorted().toList());
        r.put("armLockScope","This arm native SOURCE/CHARGE and exact declared arm binding attacks; no ship-wide disabled/repair writes");
        r.put("lockoutPending",lockoutPending);r.put("activeRangeMultiplier",activeRangeMult);r.put("chargeRange",beam.getRange());r.put("pointRange",pulse.getRange());
        r.put("firingWidth",shotWidth);r.put("cooldownRemaining",cooldown);r.put("sharedAmmo",ammo==null?beam.getAmmo():ammo.reserve());r.put("ammo",ammo==null?Map.of("mode","ai-native","beamAmmo",beam.getAmmo()):ammo.status());r.put("reason",reason);
        r.put("shots",shots);r.put("cancellations",cancellations);r.put("authority",authority==null?"local-native-pilot":authorityOwner);r.put("possessionEpoch",authorityEpoch);
        r.put("groups",groups==null?Map.of("mode","ai-native"):groups.status());r.put("pose",RifleFiringPose.status(ship));r.put("closed",closed);
        return Collections.unmodifiableMap(r);
    }
    private void publish(){ship.setCustomData(KEY,status());}
    private void hud(){
        if(engine.getPlayerShip()!=ship)return;
        engine.maintainStatusForPlayerShip(KEY,"graphics/icons/hullsys/temporal_shell.png","光束步枪 · "+(mode==Mode.PULSE?"点射":"蓄力照射")+" · "+ammo.reserve()+"发",hudText(),false);
    }
    private String hudText(){
        return lockoutPending||lockoutRemaining>0
                ?String.format(Locale.ROOT,"破限后右臂武装宕机 %.1f秒",lockoutPending?OVERLIMIT_LOCKOUT_SECONDS:lockoutRemaining)
                :phase==Phase.HOLDING
                        ?String.format(Locale.ROOT,(overlimit?"破限蓄力 %.1f秒 · 威力×%.2f":"蓄力 %.1f秒 · 松手照射"),hold,scale)
                        :phase==Phase.PRESENTING
                                ?String.format(Locale.ROOT,"抬枪对准 %.0f%%",Math.min(1,presenting/presentationSeconds())*100)
                                :phase==Phase.FIRING?String.format(Locale.ROOT,"照射 %.1f/%.1f秒 · 伤害×%.2f",fired,duration,scale)
                                        :phase==Phase.COOLDOWN?String.format(Locale.ROOT,"冷却 %.1f秒",cooldown):reason;
    }
    private void check(){
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Rifle actions require authoritative combat thread");
        if(closed)throw new IllegalStateException("Rifle modes closed");
    }
    @Override public void close(){
        if(closed)return;
        /* 先立 closed 再收尾：任何一步抛异常，也绝不允许把「已关闭」这件事丢掉，
           否则下一次 ensure() 会把半个死状态机当活的重用。 */
        closed=true;
        try{cancel("步枪模式已结束");}catch(RuntimeException|LinkageError ignored){}
        try{closeExecution();}catch(RuntimeException|LinkageError ignored){}
        if(bracePose!=null){try{bracePose.close();}catch(RuntimeException|LinkageError ignored){}bracePose=null;}
        phase=Phase.CLOSED;
        pulse.setForceNoFireOneFrame(true);
        beam.setForceNoFireOneFrame(true);
        activeRangeMult=1;
        ship.removeListener(rangeModifier);
        lockoutRemaining=0;lockoutAge=-1;lockoutPending=false;
        if(ammo!=null)ammo.close();
        if(groups!=null)groups.close();
        if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");
        publish();
    }
}
