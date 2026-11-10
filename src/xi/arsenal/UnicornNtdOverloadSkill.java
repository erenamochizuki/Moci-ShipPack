package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import data.subsystems.Moci_HotkeySubsystem;
import data.subsystems.Moci_NTDSubsystem;
import java.awt.Color;
import java.util.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.subsystems.MagicSubsystem;

/** Player-triggered NTD electronic warfare. The caller owns actual NTD detection and the only clock. */
public final class UnicornNtdOverloadSkill implements AutoCloseable {
    public static final String KEY="xi.arsenal.ntd.electronicWarfare", LEASE_KEY=KEY+".targetLease";
    public static final float CHARGE_SECONDS=1f, COOLDOWN_SECONDS=10f, SOFT_FLUX_COST=750f, RANGE=1800f;
    public static final float ARM_ANGLE=18f;
    private static final String ICON="graphics/icons/hullsys/moci_skill1.png";
    private static final Color FRINGE=new Color(245,80,110,220), CORE=new Color(255,220,235,255);
    private static final int MAX_TARGETS=32, MAX_CAPTURED=32, MAX_WEAPONS=512;
    private static final String TARGET_LOCKS_KEY="host.combat.targetLocks";
    private final CombatEngineAPI engine;
    private final NtdProbe ntdProbe;
    private final Set<ShipAPI> active=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ShipAPI,Float> cooldowns=new IdentityHashMap<>();
    private final Map<ShipAPI,Capture> captures=new IdentityHashMap<>();
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private final List<Map<String,Object>> releaseHistory=new ArrayList<>();
    private final List<Map<String,String>> rejectedTargets=new ArrayList<>();
    private final List<String> castTargetIds=new ArrayList<>(),affectedTargetIds=new ArrayList<>();
    private Moci_HotkeySubsystem.Binding binding=Moci_HotkeySubsystem.Binding.none("等待真实NTD");
    private Charge charge;
    private float keyClock;
    private float feedbackRemaining;
    private ShipAPI observedPlayer,feedbackSource;
    private String feedback="",feedbackCondition="";
    private boolean feedbackError,feedbackCharging;
    private boolean closed;
    private long activations,cancellations,overloads,captureEntries,captureReturns,expirationBlasts,foreignOwnerChanges;
    private String message="等待NTD", lastTarget="", lastShape="";

    @FunctionalInterface interface NtdProbe { NativeNtd read(ShipAPI source); }
    record NativeNtd(String state,boolean awakened,float effectLevel) {
        boolean ready(){return awakened&&Float.isFinite(effectLevel)&&effectLevel>=.8f
                &&Set.of("IN","ACTIVE","OUT").contains(state);}
    }
    public UnicornNtdOverloadSkill(CombatEngineAPI engine) { this(engine,UnicornNtdOverloadSkill::readNativeNtd); }
    UnicornNtdOverloadSkill(CombatEngineAPI engine,NtdProbe ntdProbe) {
        this.engine=Objects.requireNonNull(engine);this.ntdProbe=Objects.requireNonNull(ntdProbe);
        if(engine.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("NTD electronic warfare already has an owner");
        engine.getCustomData().put(KEY+".owner",this);engine.getCustomData().put(KEY,facts);
        // The owning UnicornNtdEnhancement registers and closes this child once.
        // 首次 publish 推迟到 advance：标题界面背景战斗在 CombatEngine.init 时还没有玩家舰船，
        // 这里若取 player() 会迫使引擎创建玩家舰船并因 variant 为 null 抛 NPE。
        keyClock = 0;
    }
    public record Activation(boolean accepted,String reason) { }
    /** One immutable, read-only view drives both the real action and its local HUD. */
    public record Availability(boolean ready,String reason,boolean ntdActive,String nativeNtdState,float ntdEffectLevel,
            boolean ntdAwakened,boolean keyAvailable,int keyCode,String keyName,float cooldownRemaining,
            boolean charging,float chargeElapsed,int lockedTargetCount,int validTargetCount,float fluxSpace,
            float requiredFlux,List<String> validTargetIds,List<String> missingConditions) { }
    private record Candidate(ShipAPI target,int owner,boolean electronic) { }
    private record Plan(Availability view,LockedTargets locks,List<Candidate> candidates,List<Map<String,String>> rejected) { }
    public Availability availability(ShipAPI source){return evaluate(source,Keyboard.KEY_NONE).view;}

    /** Public action has the same actual player, NTD, native target, key, flux and lease checks as keyboard input. */
    public Activation requestActivation(ShipAPI source) {
        return requestActivation(source,Keyboard.KEY_NONE);
    }
    private Activation requestActivation(ShipAPI source,int actualInputKey) {
        observePlayer();
        if(eligiblePilot(source))refreshBinding(source);
        Plan plan=evaluate(source,actualInputKey);
        rejectedTargets.clear();
        rejectedTargets.addAll(plan.rejected);
        if(!plan.view.ready())return refuse(source,plan.view.reason(),true);
        List<TargetSnapshot> accepted=new ArrayList<>();
        for(Candidate target:plan.candidates)accepted.add(new TargetSnapshot(target.target,target.owner,target.electronic,new Object()));
        FluxTrackerAPI flux=source.getFluxTracker();
        if(!flux.increaseFlux(SOFT_FLUX_COST,false))return refuse(source,"原生幅能消耗被拒绝");
        for(TargetSnapshot target:accepted)target.ship.setCustomData(LEASE_KEY,target.lease);
        charge=new Charge(source,source.getOwner(),plan.locks.shared,accepted);
        castTargetIds.clear();affectedTargetIds.clear();for(TargetSnapshot target:accepted)castTargetIds.add(shipId(target.ship));
        cooldowns.put(source,COOLDOWN_SECONDS);activations++;lastTarget=shipId(accepted.get(0).ship);
        lastShape=String.valueOf(accepted.get(0).ship.getHullSize());message="电子战蓄力："+accepted.size()+"个目标";
        showFeedback(source,"已受理"+accepted.size()+"目标 · 自动蓄力1秒，无需长按",false);blockWeapons(source);applyPose();hud();publish();
        return new Activation(true,"受理 "+accepted.size()+" 个目标，开始1秒蓄力，期间本机武器禁用");
    }

    private Plan evaluate(ShipAPI source,int actualInputKey){
        NativeNtd nativeNtd=source==null?new NativeNtd("MISSING",false,0):ntdProbe.read(source);
        if(nativeNtd==null)nativeNtd=new NativeNtd("MISSING",false,0);
        List<String> missing=new ArrayList<>();String reason=sourceReason(source,true,nativeNtd);
        if(!reason.isEmpty())missing.add(reason);
        boolean charging=charge!=null&&charge.source==source;
        float cooldown=cooldowns.getOrDefault(source,0f),space=source==null||source.getFluxTracker()==null?0:source.getFluxTracker().getMaxFlux()-source.getFluxTracker().getCurrFlux();
        if(charge!=null)missing.add("电子战正在自动蓄力，无需继续按键");
        else if(cooldown>0)missing.add(String.format(Locale.ROOT,"电子战冷却还剩%.1f秒",cooldown));
        boolean keyAvailable=binding.available()&&source==observedPlayer;
        if(!keyAvailable)missing.add(binding.error().isEmpty()?"没有可用的电子战按键":binding.error());
        if(actualInputKey!=Keyboard.KEY_NONE&&actualInputKey!=binding.keyCode())missing.add("按键已改变，请使用 "+binding.keyName());
        LockedTargets locks=source==null?new LockedTargets(false,List.of(),0,""):lockedTargets(source);
        if(!locks.error.isEmpty())missing.add(locks.error);
        List<Candidate> accepted=new ArrayList<>();List<Map<String,String>> rejected=new ArrayList<>();int electronicCount=0;
        for(ShipAPI target:locks.targets){
            reason=targetReason(source,target,target.getOwner());
            boolean electronic=UnicornNtdInterference.isElectronicTarget(target);
            if(reason.isEmpty()&&target.getCustomData().containsKey(LEASE_KEY)&&!ownChargingLease(source,target))reason="目标已被其他电子战占用";
            if(reason.isEmpty()&&electronic&&captures.size()+electronicCount>=MAX_CAPTURED)reason="电子战控制目标达到上限";
            if(!reason.isEmpty()){if(rejected.size()<64)rejected.add(Map.of("targetId",shipId(target),"reason",reason));continue;}
            accepted.add(new Candidate(target,target.getOwner(),electronic));if(electronic)electronicCount++;
        }
        if(locks.omitted>0)rejected.add(Map.of("targetId","","reason","其余 "+locks.omitted+" 个目标超过32目标预算"));
        if(accepted.isEmpty())missing.add(locks.targets.isEmpty()?"未锁定敌舰：先用R添加1800范围内的目标"
                :rejected.isEmpty()?"需要有效的锁定敌舰":rejected.get(0).get("reason"));
        if(!Float.isFinite(space)||space<=SOFT_FLUX_COST)missing.add("幅能空间不足：需大于750，避免激活即满幅能");
        boolean ntdReady=active.contains(source)&&nativeNtd.ready();
        Availability view=new Availability(missing.isEmpty(),missing.isEmpty()?"就绪":missing.get(0),ntdReady,nativeNtd.state,
                nativeNtd.effectLevel,nativeNtd.awakened,keyAvailable,binding.keyCode(),binding.keyName(),Math.max(0,cooldown),charging,
                charging?charge.elapsed:0,locks.targets.size(),accepted.size(),space,SOFT_FLUX_COST,
                accepted.stream().map(candidate->shipId(candidate.target)).toList(),List.copyOf(missing));
        return new Plan(view,locks,List.copyOf(accepted),List.copyOf(rejected));
    }
    private boolean ownChargingLease(ShipAPI source,ShipAPI target){
        if(charge==null||charge.source!=source)return false;
        for(TargetSnapshot snapshot:charge.targets)if(snapshot.ship==target)return target.getCustomData().get(LEASE_KEY)==snapshot.lease;
        return false;
    }
    private static NativeNtd readNativeNtd(ShipAPI source){
        if(source==null)return new NativeNtd("MISSING",false,0);
        Moci_NTDSubsystem ntd=UnicornNtdEnhancement.findNtd(source);
        if(ntd==null)return new NativeNtd("MISSING",false,0);
        MagicSubsystem.State state=ntd.getCurrentState();
        return new NativeNtd(state==null?"MISSING":state.name(),ntd.isAwakened(),ntd.getCurrentEffectLevel());
    }

    public void advance(Collection<? extends ShipAPI> actualNtdShips,float dt,List<InputEventAPI> inputs) {
        if(closed)return;
        if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid NTD electronic warfare time step");
        if(Global.getCombatEngine()!=engine||engine.isCombatOver()){close();return;}
        observePlayer();
        ShipAPI player=player();
        active.clear();
        // Only the actual local pilot owns this active input. A large allied NTD
        // fleet is not an error and cannot crowd the player out of a fixed prefix.
        if(actualNtdShips!=null&&player!=null&&actualNtdShips.contains(player)&&live(player)
                &&UnicornNtdInterference.isUnicornHull(player.getHullSpec()))active.add(player);
        facts.put("suppliedNtdSources",actualNtdShips==null?0:actualNtdShips.size());
        if(!eligiblePilot(player))binding=Moci_HotkeySubsystem.Binding.none("当前驾驶机体没有NTD电子战");
        if(!engine.isPaused()) {
            keyClock-=dt;
            feedbackRemaining=Math.max(0,feedbackRemaining-dt);
            for(Iterator<Map.Entry<ShipAPI,Float>> it=cooldowns.entrySet().iterator();it.hasNext();){
                Map.Entry<ShipAPI,Float> entry=it.next();float remaining=entry.getValue()-localStep(entry.getKey(),dt);
                if(remaining<=0||!live(entry.getKey()))it.remove();else entry.setValue(remaining);
            }
            if(eligiblePilot(player)&&keyClock<=0)refreshBinding(player);
            processInput(inputs);
            if(charge!=null){
                String reason=sourceReason(charge.source,false);
                if(reason.isEmpty()&&charge.source.getOwner()!=charge.sourceOwner)reason="施术方阵营改变";
                if(!reason.isEmpty())cancelCharge(reason);
                else {
                    pruneChargeTargets();
                    if(charge.targets.isEmpty())cancelCharge("本次充能的锁定目标全部失效");
                    else {blockWeapons(charge.source);charge.elapsed+=localStep(charge.source,dt);applyPose();if(charge.elapsed>=CHARGE_SECONDS)finishCharge();}
                }
            }
            for(Iterator<Capture> it=captures.values().iterator();it.hasNext();){
                Capture capture=it.next();
                if(ZetaOverdriveLifecycle.immune(capture.target)){release(capture,false,"目标进入免控超载");it.remove();continue;}
                if(!live(capture.target)){release(capture,false,"控制目标失效");it.remove();continue;}
                if(capture.target.getOwner()!=capture.capturedOwner||capture.target.getCustomData().get(LEASE_KEY)!=capture.lease){
                    foreignOwnerChanges++;release(capture,false,"其他机制接管目标");it.remove();continue;
                }
                // A successful capture is an independent target-local lease. Caster
                // fatigue, NTD ending or moving away cannot erase the eventual penalty.
                if(capture.justEntered){capture.justEntered=false;continue;}
                capture.remaining-=localStep(capture.target,dt);
                if(capture.remaining<=0){release(capture,true,"控制到期");it.remove();}
            }
        }
        hud();publish();
    }
    /** Input ownership only; the owning plugin forwards this before native controls consume the key. */
    public void processInputPreCoreControls(float amount,List<InputEventAPI> inputs){
        if(closed||Global.getCombatEngine()!=engine||engine.isCombatOver())return;
        observePlayer();ShipAPI player=player();
        if(eligiblePilot(player)&&keyClock<=0)refreshBinding(player);
        processInput(inputs);hud();publish();
    }
    private void observePlayer(){
        ShipAPI player=player();if(player==observedPlayer)return;
        binding=Moci_HotkeySubsystem.Binding.none("等待当前驾驶机体键位检查");
        keyClock=0;clearFeedback();observedPlayer=player;
    }
    /** 玩家舰船的安全取用：标题界面背景战斗在 init 阶段没有玩家舰船，
     *  引擎懒创建失败会直接抛 NPE，这里降级为 null，让本系统本帧视为「无玩家指挥」。 */
    private ShipAPI player(){
        try{return engine.getPlayerShip();}catch(RuntimeException ignored){return null;}
    }

    private boolean eligiblePilot(ShipAPI source){return source!=null&&source==player()&&live(source)
            &&UnicornNtdStats.supports(source);}
    private void processInput(List<InputEventAPI> inputs) {
        if(inputs==null)return;
        for(InputEventAPI event:inputs){if(event==null||event.isConsumed()||!event.isKeyboardEvent())continue;int key=event.getEventValue();
            if(!event.isKeyDownEvent()||key<=0||event.isRepeat()||!binding.available()||key!=binding.keyCode())continue;
            if(!eligiblePilot(player()))continue;
            // A reserved skill key is a deliberate request even if a condition is missing.
            // Consume once so the post-core phase cannot retry or hide the refusal feedback.
            requestActivation(player(),key);event.consume();
        }
    }
    private void finishCharge() {
        Charge finished=charge;
        try {
            for(TargetSnapshot target:finished.targets){
                if(ZetaOverdriveLifecycle.immune(target.ship)){clearLease(target.ship,target.lease);continue;}
                UnicornNtdScale.Profile profile=UnicornNtdScale.forShip(target.ship);
                try {
                    if(target.electronic){
                        Capture capture=new Capture(finished,target,profile);captures.put(target.ship,capture);
                        target.ship.setOwner(finished.sourceOwner);target.ship.setShipTarget(null);target.ship.resetDefaultAI();
                        List<WeaponAPI> disabled=selectWeaponGroups(target.ship,profile.weaponGroups());
                        for(WeaponAPI weapon:disabled)if(!weapon.isDisabled()&&!weapon.isPermanentlyDisabled())weapon.disable(false);
                        captureEntries++;
                        engine.spawnEmpArcVisual(finished.source.getLocation(),finished.source,target.ship.getLocation(),target.ship,24f,FRINGE,CORE);
                        facts.put("lastDisabledWeaponSlots",disabled.stream().map(UnicornNtdOverloadSkill::slotId).toList());
                    }else{
                        UnicornNtdEnhancement.forceOverloadAtLeast(target.ship,profile.overloadSeconds());
                        engine.spawnEmpArcPierceShields(finished.source,finished.source.getLocation(),finished.source,target.ship,
                                DamageType.ENERGY,0f,profile.empDamage(),RANGE+500f,null,24f,FRINGE,CORE);
                        overloads++;clearLease(target.ship,target.lease);
                    }
                    affectedTargetIds.add(shipId(target.ship));
                    facts.put("lastProfile",Map.of("overloadSeconds",profile.overloadSeconds(),"controlSeconds",profile.controlSeconds(),
                            "empDamage",profile.empDamage(),"expirationDamage",profile.expirationDamage(),"expirationEmp",profile.expirationEmp(),"weaponGroups",profile.weaponGroups()));
                }catch(RuntimeException|LinkageError failure){
                    Capture partial=captures.remove(target.ship);
                    if(partial!=null)release(partial,false,"电子战回调失败，恢复控制");
                    else clearLease(target.ship,target.lease);
                    throw failure;
                }
            }
            message="电子战同时作用于 "+affectedTargetIds.size()+" 个目标";
            showFeedback(finished.source,"电子战已释放 · "+affectedTargetIds.size()+"个目标",false);
        }finally{
            // If one native callback fails, unprocessed target leases cannot linger.
            for(TargetSnapshot target:finished.targets)if(!captures.containsKey(target.ship))clearLease(target.ship,target.lease);
            restorePose(finished);charge=null;publish();
        }
    }
    private void pruneChargeTargets(){
        LockedTargets current=lockedTargets(charge.source);
        for(Iterator<TargetSnapshot> it=charge.targets.iterator();it.hasNext();){TargetSnapshot target=it.next();
            String reason=targetReason(charge.source,target.ship,target.owner);
            boolean stillLocked=current.error.isEmpty()&&(charge.sharedLocks?current.shared&&containsIdentity(current.targets,target.ship)
                    :containsIdentity(current.targets,target.ship));
            if(reason.isEmpty()&&!stillLocked)reason="该目标已解除锁定";
            if(reason.isEmpty()&&target.ship.getCustomData().get(LEASE_KEY)!=target.lease)reason="目标租约已改变";
            if(!reason.isEmpty()){clearLease(target.ship,target.lease);reject(target.ship,reason);it.remove();}
        }
    }
    /** Read only the current battle-thread bridge. An explicitly empty list means clear, never fallback. */
    private LockedTargets lockedTargets(ShipAPI source){
        Object root=engine.getCustomData().get(TARGET_LOCKS_KEY);
        if(root!=null){
            if(!(root instanceof Map<?,?> map)||!(map.get("schema") instanceof Number schema)||schema.intValue()!=1
                    ||!(map.get("targets") instanceof Map<?,?> targets))return new LockedTargets(true,List.of(),0,"多目标锁定桥格式不可用");
            if(targets.containsKey(source)){
                Object raw=targets.get(source);
                if(!(raw instanceof List<?> list))return new LockedTargets(true,List.of(),0,"本机锁定列表格式不可用");
                Set<ShipAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());List<ShipAPI> result=new ArrayList<>();int omitted=0,checked=0;
                for(Object candidate:list){if(++checked>MAX_TARGETS*2){omitted+=list.size()-MAX_TARGETS*2;break;}
                    if(!(candidate instanceof ShipAPI ship)||!seen.add(ship))continue;
                    if(result.size()<MAX_TARGETS)result.add(ship);else omitted++;
                }
                return new LockedTargets(true,List.copyOf(result),omitted,"");
            }
        }
        ShipAPI target=source.getShipTarget();return new LockedTargets(false,target==null?List.of():List.of(target),0,"");
    }
    private static boolean containsIdentity(List<ShipAPI> ships,ShipAPI target){for(ShipAPI ship:ships)if(ship==target)return true;return false;}
    private void reject(ShipAPI target,String reason){if(rejectedTargets.size()<64)rejectedTargets.add(Map.of("targetId",target==null?"":shipId(target),"reason",reason));}
    private static String shipId(ShipAPI ship){String id=ship.getId();return id==null?"<unnamed>":id;}
    private record LockedTargets(boolean shared,List<ShipAPI> targets,int omitted,String error) { }
    /** Stable slot ordering chooses whole native weapon groups, never random collection order. */
    public static List<WeaponAPI> selectWeaponGroups(ShipAPI ship,int groupLimit) {
        if(groupLimit<1)return List.of();
        List<WeaponAPI> weapons=new ArrayList<>();int checked=0;
        for(WeaponAPI weapon:ship.getAllWeapons())if(++checked<=MAX_WEAPONS&&weapon!=null&&!weapon.isDecorative())weapons.add(weapon);
        weapons.sort(Comparator.comparing(UnicornNtdOverloadSkill::slotId).thenComparing(WeaponAPI::getId));
        Set<Object> selected=Collections.newSetFromMap(new IdentityHashMap<>());List<WeaponAPI> result=new ArrayList<>();
        for(WeaponAPI weapon:weapons){Object group=ship.getWeaponGroupFor(weapon);if(group==null)group=weapon;
            if(selected.contains(group))result.add(weapon);
            else if(selected.size()<Math.min(2,groupLimit)){selected.add(group);result.add(weapon);}
        }
        return List.copyOf(result);
    }
    private void release(Capture capture,boolean expired,String reason) {
        if(capture.released)return;capture.released=true;
        boolean owned=capture.target.getCustomData().get(LEASE_KEY)==capture.lease;
        boolean restore=owned&&live(capture.target)&&capture.target.getOwner()==capture.capturedOwner;
        if(restore){capture.target.setOwner(capture.originalOwner);capture.target.setShipTarget(null);capture.target.resetDefaultAI();captureReturns++;}
        clearLease(capture.target,capture.lease);
        if(expired&&restore){
            UnicornNtdScale.Profile p=capture.profile;
            DamagingExplosionSpec explosion=new DamagingExplosionSpec(.15f,220f,110f,p.expirationDamage(),p.expirationDamage()*.5f,
                    CollisionClass.PROJECTILE_FF,CollisionClass.PROJECTILE_FIGHTER,8f,10f,.4f,30,FRINGE,CORE);
            explosion.setDamageType(DamageType.ENERGY);explosion.setMaxEMPDamage(p.expirationEmp());explosion.setMinEMPDamage(p.expirationEmp()*.5f);
            engine.spawnDamagingExplosion(explosion,capture.source,new Vector2f(capture.target.getLocation()));expirationBlasts++;
        }
        Map<String,Object> event=new LinkedHashMap<>();event.put("targetId",capture.target.getId());event.put("reason",reason);
        event.put("expired",expired);event.put("restored",restore);event.put("blast",expired&&restore);
        event.put("remainingTargetLocalSeconds",Math.max(0,capture.remaining));event.put("originalOwner",capture.originalOwner);
        event.put("currentOwner",capture.target.getOwner());if(releaseHistory.size()>=64)releaseHistory.remove(0);releaseHistory.add(Collections.unmodifiableMap(event));
        message=reason;
    }
    private String sourceReason(ShipAPI source,boolean manual) {
        NativeNtd ntd=source==null?new NativeNtd("MISSING",false,0):ntdProbe.read(source);
        return sourceReason(source,manual,ntd==null?new NativeNtd("MISSING",false,0):ntd);
    }
    private String sourceReason(ShipAPI source,boolean manual,NativeNtd nativeNtd) {
        if(closed||Global.getCombatEngine()!=engine||engine.isCombatOver())return "战场已经结束或替换";
        if(source==null||source!=player())return "仅当前本地驾驶机体可使用";
        if(!live(source)||source.isStationModule()||source.isPhased())return "当前机体状态不可用";
        if(!active.contains(source)||!nativeNtd.ready())return nativeNtd.effectLevel>0&&nativeNtd.effectLevel<.8f
                ?String.format(Locale.ROOT,"NTD展开仅%.0f%%：需觉醒且达到80%%",nativeNtd.effectLevel*100)
                :"需要已觉醒的真实NTD状态（效果至少80%）";
        if(engine.isPaused())return "暂停中不可激活";
        if(source.getFluxTracker()==null||source.getFluxTracker().isOverloadedOrVenting())return "过载或排幅中不可激活";
        if(manual&&(source.controlsLocked()||engine.isUIShowingDialog()
                ||engine.getCombatUI()!=null&&(engine.getCombatUI().isShowingCommandUI()||engine.getCombatUI().isShowingDeploymentDialog())))return "当前界面未接管电子战按键";
        if(manual&&Boolean.TRUE.equals(actualAutopilotState(engine)))return "自动驾驶中不可接管电子战按键";
        return "";
    }
    /** CombatEngineAPI.isUIAutopilotOn() 的语义是反的；读原生 UI 的真实自动驾驶标志，读不到返回 null。 */
    private static Boolean actualAutopilotState(CombatEngineAPI engine){
        if(engine instanceof com.fs.starfarer.combat.CombatEngine nativeEngine&&nativeEngine.getCombatUI()!=null)return nativeEngine.getCombatUI().isAutopilotOn();
        return null;
    }
    private String targetReason(ShipAPI source,ShipAPI target,int originalOwner) {
        if(ZetaOverdriveLifecycle.immune(target))return "目标生物传感器超载免疫电子战";
        if(!live(target)||target==source||target.isPhased())return "需要有效的原生锁定敌舰";
        if((source.getOwner()!=0&&source.getOwner()!=1)||target.getOwner()==source.getOwner()
                ||target.getOwner()!=originalOwner||(originalOwner!=0&&originalOwner!=1))return "锁定目标阵营已改变";
        if(distanceSquared(source,target)>RANGE*RANGE)return "锁定目标超出1800范围";
        return "";
    }
    private boolean live(ShipAPI ship){return ship!=null&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship);}
    private static float distanceSquared(ShipAPI a,ShipAPI b){float x=a.getLocation().x-b.getLocation().x,y=a.getLocation().y-b.getLocation().y;return x*x+y*y;}
    private static float localStep(ShipAPI ship,float dt){float mult=ship.getMutableStats().getTimeMult().getModifiedValue();
        if(!Float.isFinite(mult)||mult<0)throw new IllegalStateException("Invalid ship-local time multiplier");return dt*mult;}
    private static String slotId(WeaponAPI weapon){return weapon.getSlot()==null?"":weapon.getSlot().getId();}
    private static void blockWeapons(ShipAPI ship){int checked=0;for(WeaponAPI weapon:ship.getAllWeapons())if(++checked<=MAX_WEAPONS&&!weapon.isDecorative())weapon.setForceNoFireOneFrame(true);}
    private void cancelCharge(String reason){if(charge==null)return;ShipAPI source=charge.source;for(TargetSnapshot target:charge.targets)clearLease(target.ship,target.lease);restorePose(charge);charge=null;cancellations++;message=reason;showFeedback(source,"蓄力取消："+reason,true);}
    private static void clearLease(ShipAPI target,Object lease){if(target.getCustomData().get(LEASE_KEY)==lease)target.removeCustomData(LEASE_KEY);}
    private Activation refuse(ShipAPI source,String reason){return refuse(source,reason,false);}
    private Activation refuse(ShipAPI source,String reason,boolean availabilityCondition){
        message=reason;boolean charging=availabilityCondition&&charge!=null&&charge.source==source&&"电子战正在自动蓄力，无需继续按键".equals(reason);
        showFeedback(source,charging?"正在自动蓄力，无需长按":reason,!charging);
        if(feedbackSource==source){feedbackCondition=availabilityCondition?reason:"";feedbackCharging=charging;}
        hud();publish();return new Activation(false,reason);
    }
    private void showFeedback(ShipAPI source,String text,boolean error){if(source!=player()||!eligiblePilot(source))return;
        feedbackSource=source;feedback=text;feedbackError=error;feedbackRemaining=2.5f;feedbackCondition="";feedbackCharging=false;}
    /** A refusal about an earlier condition cannot contradict the current read-only availability.
     * Native failures and other event feedback have no condition tag and retain their normal lifetime. */
    private void reconcileFeedback(Availability view){
        if(feedbackRemaining>0&&feedbackSource==player()&&!feedbackCondition.isEmpty()&&!feedbackCondition.equals(view.reason()))clearFeedback();
    }
    private void clearFeedback(){feedbackRemaining=0;feedback="";feedbackSource=null;feedbackCondition="";feedbackError=false;feedbackCharging=false;}

    // 正常由 UnicornSharedNtdHullmod 在出生时挂上；这里补挂一次（幂等），保证当前驾驶的独角兽一定有键。
    private void refreshBinding(ShipAPI source){Hotkey hotkey=Moci_HotkeySubsystem.attach(source,Hotkey.class,Hotkey::new);
        binding=hotkey==null?Moci_HotkeySubsystem.Binding.none("电子战按键子系统挂载失败"):hotkey.binding();keyClock=.5f;}
    /** 子系统栏「NTD 电子战 [键]」：MagicLib 子系统热键，LunaLib「NTD 电子战键（独角兽）」可改。由 UnicornSharedNtdHullmod 挂载。 */
    public static final class Hotkey extends Moci_HotkeySubsystem {
        public Hotkey(ShipAPI ship){super(ship);}
        @Override protected String keySettingId(){return "Moci_UnicornEwKey";}
        @Override protected String hintText(){return "点按一次：消耗750软幅能发动电子战";}
        @Override public String getDisplayText(){return "NTD 电子战";}
        // 排在喷射背包(9)、NTD(8)之后。
        @Override public int getOrder(){return ORDER_SHIP_MODULAR-1;}
    }
    private void hud(){ShipAPI player=player();if(!eligiblePilot(player))return;
        Availability view=availability(player);reconcileFeedback(view);
        String phase=view.charging()?String.format(Locale.ROOT,"蓄力%.0f%%",Math.min(1,view.chargeElapsed())*100)
                :view.ready()?"就绪":view.cooldownRemaining()>0?String.format(Locale.ROOT,"冷却%.1fs",view.cooldownRemaining()):"未就绪";
        Hotkey hotkey=Moci_HotkeySubsystem.find(player,Hotkey.class);
        if(hotkey!=null)hotkey.setStatus(!view.charging()&&view.cooldownRemaining()<=0&&!view.ntdActive()?"需要NTD":phase,
                view.charging()?Math.min(1,view.chargeElapsed()/CHARGE_SECONDS):view.cooldownRemaining()>0?1-view.cooldownRemaining()/COOLDOWN_SECONDS:1);
        if(!engine.isUIShowingHUD())return;
        String text=view.charging()?String.format(Locale.ROOT,"自动蓄力 %.1f/1秒 · 松键继续 · 本机禁火 · %d目标",Math.min(1,view.chargeElapsed()),charge.targets.size())
                :String.format(Locale.ROOT,"NTD%s · CD%.1fs · 锁定%d/%d · 幅能空间%.0f/750 · 自动蓄力1秒",
                    view.ntdActive()?"开":"未满足",view.cooldownRemaining(),view.validTargetCount(),view.lockedTargetCount(),Math.max(0,view.fluxSpace()));
        engine.maintainStatusForPlayerShip(KEY,ICON,"NTD：电子战 ["+view.keyName()+"] 点按一次 · "+phase,text,!view.ready()&&!view.charging());
        if(feedbackRemaining>0&&feedbackSource==player)engine.maintainStatusForPlayerShip(KEY+".feedback",ICON,
                feedbackError?"电子战未受理":feedbackCharging?"电子战蓄力中":"电子战已受理",feedback,feedbackError);
        else if(!view.ready()&&!view.charging())engine.maintainStatusForPlayerShip(KEY+".condition",ICON,"电子战条件 · 无需长按",view.reason(),true);
    }
    /** Pure pose reassertion only; the caller may invoke this at the late rendering stage. */
    public void renderWorldPose(){if(!closed&&!engine.isPaused()&&charge!=null)applyPose();}
    private void applyPose(){if(charge==null)return;float f=Math.max(0,Math.min(1,charge.elapsed/CHARGE_SECONDS));f=f*f*(3-2*f);
        int applied=0;for(var entry:charge.pose.entrySet()){WeaponAPI weapon=entry.getKey();String slot=slotId(weapon);
            float target=slot.startsWith("ARM_R")?-ARM_ANGLE:ARM_ANGLE;
            weapon.setCurrAngle(normalize(charge.source.getFacing()+entry.getValue()+shortest(target-entry.getValue())*f));applied++;}
        facts.put("poseAppliedSlots",applied);facts.put("poseProgress",f);
    }
    private static void restorePose(Charge state){for(var entry:state.pose.entrySet())entry.getKey().setCurrAngle(normalize(state.source.getFacing()+entry.getValue()));}
    private static float normalize(float angle){float value=angle%360;return value<0?value+360:value;}
    private static float shortest(float angle){float value=normalize(angle);return value>180?value-360:value;}
    private void publish(){facts.put("schema",1);facts.put("closed",closed);facts.put("name","NTD：电子战");facts.put("keyCode",binding.keyCode());facts.put("keyName",binding.keyName());
        facts.put("keyAvailable",binding.available());facts.put("message",message);facts.put("charging",charge!=null);facts.put("chargeElapsed",charge==null?0f:charge.elapsed);
        facts.put("captureCount",captures.size());facts.put("activations",activations);facts.put("cancellations",cancellations);facts.put("overloads",overloads);
        facts.put("captureEntries",captureEntries);facts.put("captureReturns",captureReturns);facts.put("expirationBlasts",expirationBlasts);facts.put("foreignOwnerChanges",foreignOwnerChanges);
        facts.put("lastTarget",lastTarget);facts.put("lastHullSize",lastShape);facts.put("cooldown",cooldowns.getOrDefault(player(),0f));
        facts.put("castTargetIds",List.copyOf(castTargetIds));facts.put("affectedTargetIds",List.copyOf(affectedTargetIds));
        facts.put("acceptedTargetCount",castTargetIds.size());facts.put("chargingTargetCount",charge==null?0:charge.targets.size());facts.put("rejectedTargets",List.copyOf(rejectedTargets));
        facts.put("chargeClock","caster-local");facts.put("cooldownClock","caster-local");facts.put("captureClock","target-local");
        facts.put("captureLeases",captures.values().stream().map(capture->Map.<String,Object>of("sourceId",capture.source.getId(),"targetId",capture.target.getId(),
                "originalOwner",capture.originalOwner,"capturedOwner",capture.capturedOwner,"currentOwner",capture.target.getOwner(),
                "remainingTargetLocalSeconds",Math.max(0,capture.remaining),"durationTargetLocalSeconds",capture.profile.controlSeconds())).toList());
        facts.put("releaseHistory",List.copyOf(releaseHistory));
        Availability view=availability(player());reconcileFeedback(view);Map<String,Object> available=new LinkedHashMap<>();
        available.put("ready",view.ready());available.put("reason",view.reason());available.put("ntdActive",view.ntdActive());
        available.put("nativeNtdState",view.nativeNtdState());available.put("ntdEffectLevel",view.ntdEffectLevel());available.put("ntdAwakened",view.ntdAwakened());
        available.put("keyAvailable",view.keyAvailable());available.put("keyName",view.keyName());available.put("keyCode",view.keyCode());
        available.put("cooldownRemaining",view.cooldownRemaining());available.put("charging",view.charging());available.put("chargeElapsed",view.chargeElapsed());
        available.put("lockedTargetCount",view.lockedTargetCount());available.put("validTargetCount",view.validTargetCount());available.put("fluxSpace",view.fluxSpace());
        available.put("requiredFlux",view.requiredFlux());available.put("validTargetIds",view.validTargetIds());available.put("missingConditions",view.missingConditions());
        available.put("inputMode","tap-once-auto-charge");available.put("chargeSeconds",CHARGE_SECONDS);facts.put("availability",Collections.unmodifiableMap(available));
        facts.put("feedback",feedbackSource==player()&&feedbackRemaining>0?feedback:"");facts.put("feedbackRemaining",feedbackSource==player()?feedbackRemaining:0f);
        facts.put("feedbackIsError",feedbackError);facts.put("feedbackSourceId",feedbackSource==player()&&feedbackSource!=null?shipId(feedbackSource):"");
        facts.put("feedbackCondition",feedbackSource==player()&&feedbackRemaining>0?feedbackCondition:"");
        facts.put("scope","Frozen native/shared target snapshot, at most32; explicit unlock only drops that target; no new target joins a charge. One750-flux cost/10-local-second cooldown per cast. No originalOwner writes or original callback advancement. Pose angles require native draw-stage verification.");}
    public Map<String,Object> status(){publish();return Collections.unmodifiableMap(new LinkedHashMap<>(facts));}
    @Override public void close(){if(closed)return;cancelCharge("战场关闭");for(Capture capture:new ArrayList<>(captures.values()))release(capture,false,"战场关闭");captures.clear();active.clear();cooldowns.clear();closed=true;
        if(engine.getCustomData().get(KEY+".owner")==this)engine.getCustomData().remove(KEY+".owner");publish();}
    private static final class Charge {
        final ShipAPI source;final int sourceOwner;final boolean sharedLocks;final List<TargetSnapshot> targets;
        final Map<WeaponAPI,Float> pose=new IdentityHashMap<>();float elapsed;
        Charge(ShipAPI source,int sourceOwner,boolean sharedLocks,List<TargetSnapshot> targets){
            this.source=source;this.sourceOwner=sourceOwner;this.sharedLocks=sharedLocks;this.targets=new ArrayList<>(targets);
            int checked=0;for(WeaponAPI weapon:source.getAllWeapons())if(++checked<=MAX_WEAPONS&&Set.of("ARM_L","ARM_L_SOURCE","ARM_R","ARM_R_SOURCE","SHIELD_L_COVER").contains(slotId(weapon)))pose.put(weapon,shortest(weapon.getCurrAngle()-source.getFacing()));
        }
    }
    private static final class TargetSnapshot {
        final ShipAPI ship;final int owner;final boolean electronic;final Object lease;
        TargetSnapshot(ShipAPI ship,int owner,boolean electronic,Object lease){this.ship=ship;this.owner=owner;this.electronic=electronic;this.lease=lease;}
    }
    private static final class Capture {
        final ShipAPI source,target;final int originalOwner,capturedOwner;final Object lease;final UnicornNtdScale.Profile profile;
        float remaining;boolean released,justEntered=true;
        Capture(Charge state,TargetSnapshot target,UnicornNtdScale.Profile profile){source=state.source;this.target=target.ship;originalOwner=target.owner;capturedOwner=state.sourceOwner;lease=target.lease;this.profile=profile;remaining=profile.controlSeconds();}
    }
}
