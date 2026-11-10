package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.*;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.*;
import com.fs.starfarer.combat.entities.MovingRay;
import data.subsystems.Moci_HotkeySubsystem;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Vector2f;

/** Per-deployment Zeta defenses. The existing Actor advances this once; no extra engine/plugin.
 * Native damage listeners, real installed shield launchers and original rifle magazine are used.
 * Original body/transform/weapon callbacks and foreign stats/listeners are never replayed/cleared. */
public final class ZetaDefensiveMechanisms implements AutoCloseable,DamageTakenModifier,DamageListener,DamageDealtModifier {
    public static final String KEY="xi.arsenal.zeta.defenses",COUNTER="Moci_fw_zeta_shield_counter",SHIELD_HULL="Moci_MSZ_006_SHIELD_L";
    public static final String COUNTER_A="ZETA_SHIELD_COUNTER_A",COUNTER_B="ZETA_SHIELD_COUNTER_B";
    public static final float WR_DAMAGE_MULT=.75f,RELOAD_HOLD_SECONDS=.6f,COUNTER_COOLDOWN=1f;
    public static final int DEFAULT_ECAPS=6;
    public static final float MAX_FLUX_DAMAGE_BONUS=.25f,BIO_ENERGY_ROF=1.2f,POINT_FLUX_REMOVAL=90f,BEAM_FLUX_REMOVAL_PER_SECOND=200f;
    private static final String WR_MOD=KEY+".wr",EMP_MOD=KEY+".emp",ABSORB_MOD=KEY+".absorb",FLUX_DAMAGE_MOD=KEY+".fluxDamage",ROF_MOD=KEY+".cooldown";
    private static final int MAX_WEAPONS=256,MAX_TARGETS=512,MAX_GUIDANCE=16,MAX_SHOT_IDENTITIES=512,MAX_DAMAGE_LEASES=512;
    @FunctionalInterface public interface OpticalDeflectionGuard {
        /** True only when this exact native damage has actually been intercepted by the owner. */
        boolean intercept(Object source,CombatEntityAPI target,DamageAPI damage,Vector2f point,boolean shieldHit);
    }
    private final Thread owner=Thread.currentThread();private final CombatEngineAPI engine;private final ShipAPI ship;
    private final Supplier<ZetaRifleModes.ReloadResult> refill;
    /** 换弹键的 MagicLib 子系统；挂载失败时为 null，按「没有键」处理。 */
    private final Hotkey hotkey;
    private final Map<WeaponAPI,FluxLease> fluxLeases=new IdentityHashMap<>();
    private final Map<DamageAPI,DamageLease> damageLeases=new IdentityHashMap<>();
    private static final class DamageLease {final Set<CombatEntityAPI> absorbedTargets=Collections.newSetFromMap(new IdentityHashMap<>());}
    private final Set<DamagingProjectileAPI> seenShots=Collections.newSetFromMap(new IdentityHashMap<>());private final ArrayDeque<DamagingProjectileAPI> shotOrder=new ArrayDeque<>();
    private final Set<WeaponAPI> counterPending=Collections.newSetFromMap(new IdentityHashMap<>());private final Map<MissileAPI,ShipAPI> guidancePending=new IdentityHashMap<>();
    private OpticalDeflectionGuard deflection=(source,target,damage,point,shield)->false;
    private Moci_HotkeySubsystem.Binding binding=Moci_HotkeySubsystem.Binding.none("等待驾驶机体");
    private ShipAPI shield,counterTarget;private List<WeaponAPI> launchers=List.of();
    private boolean launchersGrouped;private BooleanSupplier reloadAuthority;
    private boolean closed,bio,waveRider,reloadHeld,reloadLatched,autoPending,autoUsed,autoAwaiting;
    private float wrDamageMultiplier=WR_DAMAGE_MULT;
    private int ecaps=DEFAULT_ECAPS,heldKey=Keyboard.KEY_NONE;private float hold,bindingDelay,counterCooldown,counterPendingTime,autoConfirmTime,autoRetry,previousHp=1,lastNativeStep=.016f;
    private String reloadReason="长按换弹键更换E-cap",counterReason="等待实体盾受伤",autoReason="首次低于50%结构时自动启动";
    private double absorbedFlux,removedFlux;private long reloads,reloadRefusals,counterTriggers,counterShots,autoAttempts,autoActivations,autoSatisfiedByExisting,leaseConflicts,budgetSkips,absorbEvents,deflectionYields;

    public ZetaDefensiveMechanisms(CombatEngineAPI engine,ShipAPI ship,ZetaRifleModes rifle){
        this(engine,ship,Objects.requireNonNull(rifle)::refillMagazine);
    }
    ZetaDefensiveMechanisms(CombatEngineAPI engine,ShipAPI ship,Supplier<ZetaRifleModes.ReloadResult> refill){
        this.engine=Objects.requireNonNull(engine);this.ship=Objects.requireNonNull(ship);this.refill=Objects.requireNonNull(refill);
        if(!ZetaOverdriveStats.supports(ship))throw new IllegalArgumentException("Complete Zeta required");
        if(ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Zeta defenses already owned");
        hotkey=Moci_HotkeySubsystem.attach(ship,Hotkey.class,Hotkey::new);
        ship.setCustomData(KEY+".owner",this);ship.addListener(this);refreshShield();publish();
    }
    public void setWaveRiderDamageReduction(float fraction){check();if(!Float.isFinite(fraction)||fraction<0||fraction>1)throw new IllegalArgumentException("WR damage reduction must be0..1");wrDamageMultiplier=1-fraction;}
    public void setOpticalDeflectionGuard(OpticalDeflectionGuard guard){check();deflection=Objects.requireNonNull(guard);}
    /** Existing actor supplies actual native WR state and actual F effect; call only once per frame. */
    public void advance(float localSeconds,boolean completeWr,float effect){
        check();if(!Float.isFinite(localSeconds)||localSeconds<0)throw new IllegalArgumentException("Invalid Zeta defense dt");
        restoreDamageLeases(); // Native beams may reuse DamageAPI across ticks and across Bio transitions.
        if(engine.isPaused())return;if(!live(ship)){close();return;}
        float nativeStep=engine.getElapsedInLastFrame();if(Float.isFinite(nativeStep)&&nativeStep>0)lastNativeStep=nativeStep;
        waveRider=completeWr;bio=Float.isFinite(effect)&&effect>0;
        refreshShield();applyStats(ship);if(shield!=null)applyStats(shield);
        updateFluxLeases();drainActualBeams(localSeconds);
        counterCooldown=Math.max(0,counterCooldown-localSeconds);counterPendingTime=Math.max(0,counterPendingTime-localSeconds);
        if(counterPendingTime<=0&&!counterPending.isEmpty()){counterPending.clear();counterReason="反制发射未确认，已取消本次排队";}
        updateGuidance();autoLowHp(localSeconds);
        if(engine.getPlayerShip()!=ship&&(reloadAuthority==null||!reloadAuthority.getAsBoolean()))releaseReload();
        else{
            if(engine.getPlayerShip()==ship){bindingDelay-=localSeconds;if(bindingDelay<=0){refreshBinding();bindingDelay=.5f;}}
            if(reloadHeld&&!reloadLatched){if(!inputAllowed()){releaseReload();reloadReason="操作界面或机体状态阻止换弹";}else{hold+=localSeconds;if(hold>=RELOAD_HOLD_SECONDS){reloadLatched=true;tryRefill();}}}
            hud();
        }publish();
    }
    private void applyStats(ShipAPI part){MutableShipStatsAPI stats=part.getMutableStats();
        for(MutableStat stat:new MutableStat[]{stats.getHullDamageTakenMult(),stats.getArmorDamageTakenMult(),stats.getShieldDamageTakenMult()}){
            if(waveRider)stat.modifyMult(WR_MOD,wrDamageMultiplier);else stat.unmodify(WR_MOD);
        }
        // Native EMP and component-damage prevention. This never clears foreign modifier IDs,
        // repairs disabled weapons, forces engines on, or pretends to cancel arbitrary Mod jams.
        for(MutableStat stat:new MutableStat[]{stats.getEmpDamageTakenMult(),stats.getWeaponDamageTakenMult(),stats.getEngineDamageTakenMult()}){
            if(bio)stat.modifyMult(EMP_MOD,0);else stat.unmodify(EMP_MOD);
        }
        if(part==ship){if(bio)stats.getEnergyRoFMult().modifyMult(ROF_MOD,BIO_ENERGY_ROF);else stats.getEnergyRoFMult().unmodify(ROF_MOD);}
    }
    public static boolean isOpticalDamageSource(Object source){return source instanceof BeamAPI||source instanceof MovingRay||ZetaBeamDeflection.isReflectedDamage(source);}
    private ShipAPI sourceShip(Object source){return source instanceof ShipAPI nativeSource?nativeSource:source instanceof BeamAPI beam?beam.getSource():source instanceof DamagingProjectileAPI p?p.getSource():null;}
    /** 1 incoming observed native damage ->1 soft flux, capacity-clamped, before own immunity. */
    @Override public String modifyDamageTaken(Object source,CombatEntityAPI target,DamageAPI damage,Vector2f point,boolean shieldHit){
        checkThread();if(damage==null)return null;
        damage.getModifier().unmodify(ABSORB_MOD); // Before every early return, including Bio off.
        if(closed||!ownedPart(target)||!isOpticalDamageSource(source))return null;
        if(deflection.intercept(source,target,damage,point,shieldHit)){deflectionYields++;return null;}
        if(!bio)return null;
        DamageLease lease=damageLease(damage);if(lease==null)return null;
        if(lease.absorbedTargets.add(target)){
            float incoming=damage.computeDamageDealt(lastNativeStep);if(!Float.isFinite(incoming)||incoming<0)incoming=0;
            FluxTrackerAPI flux=ship.getFluxTracker();float before=flux.getCurrFlux(),add=Math.min(incoming,Math.max(0,flux.getMaxFlux()-before));
            if(add>0)flux.increaseFlux(add,false);
            absorbedFlux+=Math.max(0,flux.getCurrFlux()-before);absorbEvents++;
        }
        damage.getModifier().modifyMult(ABSORB_MOD,0);return ABSORB_MOD;
    }
    public static float fluxDamageMultiplier(float fluxLevel){return 1+MAX_FLUX_DAMAGE_BONUS*clamp(fluxLevel);}
    @Override public String modifyDamageDealt(Object source,CombatEntityAPI target,DamageAPI damage,Vector2f point,boolean shieldHit){
        checkThread();if(damage==null)return null;
        damage.getModifier().unmodify(FLUX_DAMAGE_MOD); // A persistent native beam cannot keep a former Bio bonus.
        if(closed||!bio||sourceShip(source)!=ship||!isOpticalDamageSource(source)||damageLease(damage)==null)return null;
        damage.getModifier().modifyMult(FLUX_DAMAGE_MOD,fluxDamageMultiplier(ship.getFluxTracker().getFluxLevel()));return FLUX_DAMAGE_MOD;
    }
    private DamageLease damageLease(DamageAPI damage){DamageLease lease=damageLeases.get(damage);if(lease!=null)return lease;
        if(damageLeases.size()>=MAX_DAMAGE_LEASES){budgetSkips++;return null;}
        lease=new DamageLease();damageLeases.put(damage,lease);return lease;
    }
    /** Only this owner's two keys are restored; foreign multipliers remain untouched. */
    private void restoreDamageLeases(){for(DamageAPI damage:damageLeases.keySet()){damage.getModifier().unmodify(ABSORB_MOD);damage.getModifier().unmodify(FLUX_DAMAGE_MOD);}damageLeases.clear();}
    /** Only actual nonzero native shield-module damage arms the two installed launchers. */
    @Override public void reportDamageApplied(Object source,CombatEntityAPI target,ApplyDamageResultAPI result){
        checkThread();if(closed||target!=shield||result==null||counterCooldown>0||!live(shield))return;
        float actual=positive(result.getDamageToHull())+positive(result.getTotalDamageToArmor())+positive(result.getDamageToShields());if(actual<=0)return;
        ShipAPI attacker=sourceShip(source);if(!enemy(attacker))attacker=nearestEnemy();
        if(attacker==null){counterReason="实体盾受伤，但没有合法敌舰目标";return;}
        if(launchers.size()!=2||!launchersGrouped){counterReason="实体盾缺少两门原生反制发射器或内置组，未伪造弹体";return;}
        counterTarget=attacker;counterPending.clear();counterPending.addAll(launchers);counterCooldown=COUNTER_COOLDOWN;counterPendingTime=.75f;counterTriggers++;counterReason="已排队两枚原生反制导弹";
    }
    private void refreshShield(){ShipAPI next=null;int count=0;for(ShipAPI part:ship.getChildModulesCopy()){
        if(count++>=32){budgetSkips++;break;}if(part!=null&&part.getStationSlot()!=null&&"SHIELD_L".equals(part.getStationSlot().getId())&&part.getHullSpec()!=null&&(SHIELD_HULL.equals(part.getHullSpec().getHullId())||SHIELD_HULL.equals(part.getHullSpec().getBaseHullId()))){next=part;break;}
    }
        if(next!=shield){if(shield!=null){shield.removeListener(this);clearStats(shield);}shield=next;counterPending.clear();launchers=List.of();if(shield!=null)shield.addListener(this);}
        if(shield==null)return;List<WeaponAPI> found=new ArrayList<>();count=0;for(WeaponAPI weapon:shield.getAllWeapons()){
            if(count++>=32){budgetSkips++;break;}if(weapon!=null&&COUNTER.equals(weapon.getId())&&weapon.getSlot()!=null&&Set.of(COUNTER_A,COUNTER_B).contains(weapon.getSlot().getId()))found.add(weapon);
        }
        found.sort(Comparator.comparing(w->w.getSlot().getId()));launchers=List.copyOf(found);launchersGrouped=launchers.size()==2&&launchers.stream().allMatch(w->shield.getWeaponGroupFor(w)!=null);
    }
    private boolean ownedPart(CombatEntityAPI target){return target==ship||target==shield;}
    private boolean enemy(ShipAPI target){return target!=null&&target!=ship&&target!=shield&&target.getOwner()>=0&&target.getOwner()<=1&&ship.getOwner()>=0&&ship.getOwner()<=1&&target.getOwner()!=ship.getOwner()&&live(target)&&!target.isPhased();}
    private ShipAPI nearestEnemy(){ShipAPI best=null;float d=Float.POSITIVE_INFINITY;int count=0;for(ShipAPI target:engine.getShips()){
        if(count++>=MAX_TARGETS){budgetSkips++;break;}if(!enemy(target))continue;float value=Vector2f.sub(target.getLocation(),ship.getLocation(),new Vector2f()).lengthSquared();if(value<d){d=value;best=target;}
    }return best;}
    private static ZetaDefensiveMechanisms ownerFor(ShipAPI part){ShipAPI root=part;for(int i=0;root!=null&&i<16;i++){
        Object value=root.getCustomData().get(KEY+".owner");if(value instanceof ZetaDefensiveMechanisms own&&!own.closed)return own;
        ShipAPI parent=root.getParentStation();if(parent==root)break;root=parent;
    }return null;}
    /** Invoked by this NEW launcher's one native every-frame callback; never advances this owner. */
    // 控制器缺失时走统一判据：Moci_fw_zeta_shield_counter 的两门槽（ZETA_SHIELD_COUNTER_A/B）在
    // hulls/Moci_MSZ_006_SHIELD_L.ship 里都是 mount HIDDEN，AI 无法原生开火，唯一开火路径就是本类
    // 在 reportDamageApplied 之后用 setForceFireOneFrame 触发，所以判据结论仍是「保留禁火」。
    // 判据见 XiWeaponControlOwnership。
    public static void guardCounterWeapon(WeaponAPI weapon){ZetaDefensiveMechanisms own=ownerFor(weapon==null?null:weapon.getShip());
        if(own==null){XiWeaponControlOwnership.settleWithoutController(weapon);return;}own.checkThread();
        if(!own.launchers.contains(weapon)||!own.counterPending.contains(weapon)||!own.enemy(own.counterTarget)||own.engine.isPaused()||weapon.isDisabled()||weapon.isForceDisabled()){
            weapon.setForceNoFireOneFrame(true);return;
        }
        Vector2f from=weapon.getFirePoint(0),to=own.counterTarget.getLocation();weapon.setCurrAngle((float)Math.toDegrees(Math.atan2(to.y-from.y,to.x-from.x)));
        weapon.getShip().setShipTarget(own.counterTarget); // module only; player's root target is untouched.
        weapon.setForceFireOneFrame(true);
    }
    /** Only the actual native OnFire confirms a missile; requesting fire does not count. */
    public static void counterShotFired(DamagingProjectileAPI projectile,WeaponAPI weapon){ZetaDefensiveMechanisms own=ownerFor(weapon==null?null:weapon.getShip());
        if(own==null||projectile==null||projectile.getSource()!=weapon.getShip())return;own.checkThread();if(!own.counterPending.remove(weapon))return;own.counterShots++;
        if(projectile instanceof MissileAPI missile&&own.enemy(own.counterTarget)){
            if(missile.getMissileAI() instanceof GuidedMissileAI guided)guided.setTarget(own.counterTarget);
            else if(own.guidancePending.size()<MAX_GUIDANCE)own.guidancePending.put(missile,own.counterTarget);
        }
        if(own.counterPending.isEmpty())own.counterReason="两枚原生反制导弹已确认发射";
    }
    private void updateGuidance(){for(Iterator<Map.Entry<MissileAPI,ShipAPI>> it=guidancePending.entrySet().iterator();it.hasNext();){var e=it.next();
        if(!engine.isEntityInPlay(e.getKey())||!enemy(e.getValue())){it.remove();continue;}if(e.getKey().getMissileAI() instanceof GuidedMissileAI ai){ai.setTarget(e.getValue());it.remove();}
    }}
    private void updateFluxLeases(){Set<WeaponAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());int count=0;
        if(bio)for(WeaponAPI weapon:ship.getAllWeapons()){
            if(count++>=MAX_WEAPONS){budgetSkips++;break;}if(!ZetaOverdriveStats.isOpticalWeapon(weapon))continue;seen.add(weapon);
            FluxLease lease=fluxLeases.get(weapon);if(lease!=null&&lease.spec!=weapon.getSpec()){lease.restore();fluxLeases.remove(weapon);lease=null;}
            if(lease==null){weapon.ensureClonedSpec();WeaponSpecAPI local=weapon.getSpec();if(local==weapon.getOriginalSpec()||local==null){leaseConflicts++;continue;}lease=new FluxLease(local);fluxLeases.put(weapon,lease);}lease.zero();
        }
        for(Iterator<Map.Entry<WeaponAPI,FluxLease>> it=fluxLeases.entrySet().iterator();it.hasNext();){var e=it.next();if(!seen.contains(e.getKey())){e.getValue().restore();it.remove();}}
    }
    private final class FluxLease {
        final WeaponSpecAPI spec;final float shot,second;boolean written,yielded;
        FluxLease(WeaponSpecAPI spec){this.spec=spec;shot=spec instanceof ProjectileWeaponSpecAPI p?p.getEnergyPerShot():0;second=spec instanceof BeamWeaponSpecAPI b?b.getEnergyPerSecond():spec instanceof ProjectileWeaponSpecAPI p?p.getEnergyPerSecond():0;}
        void zero(){if(yielded)return;float a=spec instanceof ProjectileWeaponSpecAPI p?p.getEnergyPerShot():0,b=spec instanceof BeamWeaponSpecAPI beam?beam.getEnergyPerSecond():spec instanceof ProjectileWeaponSpecAPI p?p.getEnergyPerSecond():0;
            if(written&&(a!=0||b!=0)){yielded=true;leaseConflicts++;return;}if(spec instanceof ProjectileWeaponSpecAPI p){p.setEnergyPerShot(0);p.setEnergyPerSecond(0);}if(spec instanceof BeamWeaponSpecAPI beam)beam.setEnergyPerSecond(0);written=true;
        }
        void restore(){if(!written)return;if(spec instanceof ProjectileWeaponSpecAPI p){if(p.getEnergyPerShot()==0)p.setEnergyPerShot(shot);if(p.getEnergyPerSecond()==0)p.setEnergyPerSecond(second);}if(spec instanceof BeamWeaponSpecAPI beam&&beam.getEnergyPerSecond()==0)beam.setEnergyPerSecond(second);}
    }
    /** Actual native projectile emission hook; misses consume flux too, no on-hit dependency. */
    public static void opticalShotFired(DamagingProjectileAPI projectile,WeaponAPI weapon){ZetaDefensiveMechanisms own=ownerFor(weapon==null?null:weapon.getShip());
        if(own==null||!own.bio||projectile==null||projectile.getSource()!=own.ship||!ZetaOverdriveStats.isOpticalWeapon(weapon)||weapon.isBeam()||!own.neutralizedCost(weapon))return;own.checkThread();
        if(!own.seenShots.add(projectile))return;own.shotOrder.addLast(projectile);if(own.shotOrder.size()>MAX_SHOT_IDENTITIES)own.seenShots.remove(own.shotOrder.removeFirst());own.removeFlux(POINT_FLUX_REMOVAL);
    }
    private void drainActualBeams(float dt){if(!bio||dt<=0)return;int count=0;for(WeaponAPI weapon:ship.getAllWeapons()){
        if(count++>=MAX_WEAPONS){budgetSkips++;break;}if(!ZetaOverdriveStats.isOpticalWeapon(weapon)||!neutralizedCost(weapon)||!weapon.isBeam()||!weapon.isFiring()||weapon.getChargeLevel()<.999f)continue;
        float brightness=0;int beams=0;for(BeamAPI beam:weapon.getBeams()){if(beams++>=32){budgetSkips++;break;}if(beam==null||beam.getSource()!=ship||beam.getWeapon()!=weapon||beam.getFrom()==null||beam.getTo()==null)continue;
            if(Vector2f.sub(beam.getTo(),beam.getFrom(),new Vector2f()).lengthSquared()>1)brightness=Math.max(brightness,clamp(beam.getBrightness()));
        }
        if(brightness>0)removeFlux(BEAM_FLUX_REMOVAL_PER_SECOND*dt*brightness);
    }}
    private boolean neutralizedCost(WeaponAPI weapon){FluxLease lease=fluxLeases.get(weapon);return lease!=null&&lease.written&&!lease.yielded;}
    private void removeFlux(float amount){FluxTrackerAPI flux=ship.getFluxTracker();float before=flux.getCurrFlux();flux.decreaseFlux(Math.min(Math.max(0,before),Math.max(0,amount)));removedFlux+=Math.max(0,before-flux.getCurrFlux());}
    private void autoLowHp(float dt){float hp=ship.getMaxHitpoints()>0?clamp(ship.getHitpoints()/ship.getMaxHitpoints()):1;
        if(hp<.5f&&previousHp>=.5f&&!autoUsed)autoPending=true;previousHp=hp;
        ShipSystemAPI system=ship.getSystem();if(autoUsed||!autoPending||system==null)return;
        if(system.isOn()||system.isChargeup()||system.isActive()){if(autoAwaiting)autoActivations++;else autoSatisfiedByExisting++;autoUsed=true;autoPending=autoAwaiting=false;autoReason="低结构生物传感器已由原生系统启动，不续时";return;}
        autoRetry=Math.max(0,autoRetry-dt);
        if(autoAwaiting){autoConfirmTime+=dt;if(autoConfirmTime<.5f)return;autoAwaiting=false;autoRetry=.5f;autoReason="等待原生系统确认后重试";}
        if(hp>=.5f||autoRetry>0)return;
        if(system.getSpecAPI()==null||!ZetaOverdriveStats.SYSTEM_ID.equals(system.getSpecAPI().getId())){autoReason="没有安装本机生物传感器系统";return;}
        if(system.getCooldownRemaining()>0||system.isCoolingDown()){autoReason="低结构触发已记录，等待原生冷却";return;}
        if(ship.isShipSystemDisabled()||ship.controlsLocked()||ship.getFluxTracker().isOverloadedOrVenting()||ship.getFluxTracker().getMaxFlux()-ship.getFluxTracker().getCurrFlux()<system.getFluxPerUse()){autoReason="低结构触发等待机体状态允许";return;}
        ship.useSystem();autoAwaiting=true;autoConfirmTime=0;autoAttempts++;autoReason="已请求原生生物传感器启动";
    }
    public void processInput(List<InputEventAPI> events){check();if(events==null)return;
        for(InputEventAPI event:events){if(event==null||event.isConsumed()||!event.isKeyboardEvent())continue;
            if(event.isKeyUpEvent()&&event.getEventValue()==heldKey){releaseReload();if(!event.isConsumed())event.consume();continue;}
            if(event.isConsumed())continue;
            if(!event.isKeyDownEvent()||event.isRepeat()||engine.getPlayerShip()!=ship||!inputAllowed())continue;
            if(!binding.available()||event.getEventValue()==binding.keyCode())refreshBinding();if(binding.available()&&event.getEventValue()==binding.keyCode()){requestReloadHeld(true);heldKey=binding.keyCode();event.consume();}
        }
    }
    /** Semantic hold action for an already-authorized external controller; no raw key injection. */
    public void bindReloadAuthority(BooleanSupplier valid){check();reloadAuthority=Objects.requireNonNull(valid);releaseReload();}
    public void releaseReloadAuthority(){check();reloadAuthority=null;releaseReload();}
    public void requestReloadHeld(boolean down){check();if(!down){releaseReload();return;}if(!reloadHeld){reloadHeld=true;reloadLatched=false;hold=0;reloadReason="保持0.6秒更换E-cap";}}
    private void releaseReload(){reloadHeld=reloadLatched=false;hold=0;heldKey=Keyboard.KEY_NONE;}
    private void tryRefill(){if(ecaps<=0){reloadReason="E-cap已用尽";reloadRefusals++;return;}
        ZetaRifleModes.ReloadResult result=refill.get();if(result.accepted()&&result.added()>0){ecaps--;reloads++;reloadReason="已补满步枪弹匣，剩余E-cap "+ecaps;}else{reloadRefusals++;reloadReason=result.reason();}
    }
    private boolean inputAllowed(){return !closed&&!engine.isPaused()&&live(ship)&&!ship.controlsLocked()&&(engine.getPlayerShip()!=ship||!engine.isUIShowingDialog()&&(engine.getCombatUI()==null||!engine.getCombatUI().isShowingCommandUI()&&!engine.getCombatUI().isShowingDeploymentDialog()));}
    private void refreshBinding(){int previousKey=binding.keyCode();
        binding=hotkey==null?Moci_HotkeySubsystem.Binding.none("换弹按键子系统挂载失败"):hotkey.binding();
        if(!binding.available()||binding.keyCode()!=previousKey)releaseReload();
    }
    /** 子系统栏「E-cap 换弹 [键]」：MagicLib 子系统热键，LunaLib「E-cap 换弹键（Z高达）」可改。 */
    public static final class Hotkey extends Moci_HotkeySubsystem {
        public Hotkey(ShipAPI ship){super(ship);}
        @Override protected String keySettingId(){return "Moci_ZetaReloadKey";}
        @Override protected String hintText(){return "长按0.6秒更换E-cap";}
        @Override public String getDisplayText(){return "E-cap 换弹";}
        // 排在喷射背包(9)之后、生物传感器压制(6)之前。
        @Override public int getOrder(){return ORDER_SHIP_MODULAR-1;}
    }
    private void hud(){if(engine.getPlayerShip()!=ship)return;
        boolean holding=reloadHeld&&!reloadLatched;
        if(hotkey!=null)hotkey.setStatus(holding?"换弹中":"E-cap "+ecaps+"/"+DEFAULT_ECAPS,holding?hold/RELOAD_HOLD_SECONDS:(float)ecaps/DEFAULT_ECAPS);
        if(!engine.isUIShowingHUD())return;String key=binding.available()?binding.keyName():"未分配键";
        engine.maintainStatusForPlayerShip(KEY,"graphics/icons/hullsys/ammo_feeder.png","Z防御 / E-cap "+ecaps+"/"+DEFAULT_ECAPS,key+" 长按0.6秒 · "+(binding.available()?reloadReason:binding.error()),false);
        if(bio)engine.maintainStatusForPlayerShip(KEY+".bio","graphics/icons/hullsys/high_energy_focus.png","生物传感器 · 光束吸收 / 原生EMP免疫",String.format(Locale.ROOT,"幅能光束伤害 ×%.2f · 实际开火降低幅能",fluxDamageMultiplier(ship.getFluxTracker().getFluxLevel())),false);
    }
    private boolean live(ShipAPI part){return part!=null&&part.isAlive()&&!part.isHulk()&&!part.wasRemoved()&&engine.isEntityInPlay(part);}
    private static float clamp(float n){return Float.isFinite(n)?Math.max(0,Math.min(1,n)):0;}
    private static float positive(float n){return Float.isFinite(n)?Math.max(0,n):0;}
    public Map<String,Object> status(){Map<String,Object> r=new LinkedHashMap<>();r.put("closed",closed);r.put("waveRider",waveRider);r.put("wrDamageMult",wrDamageMultiplier);r.put("bioActive",bio);r.put("ecaps",ecaps);r.put("reloadHeld",reloadHeld);r.put("reloadLatched",reloadLatched);r.put("holdSeconds",hold);r.put("key",binding.keyCode());r.put("keyName",binding.keyName());r.put("bindingError",binding.error());r.put("reloadReason",reloadReason);r.put("reloads",reloads);r.put("reloadRefusals",reloadRefusals);
        r.put("shieldPresent",shield!=null);r.put("launchers",launchers.size());r.put("launchersGrouped",launchersGrouped);r.put("counterPending",counterPending.size());r.put("counterTriggers",counterTriggers);r.put("counterActualShots",counterShots);r.put("counterCooldown",counterCooldown);r.put("counterReason",counterReason);r.put("autoPending",autoPending);r.put("autoUsed",autoUsed);r.put("autoAttempts",autoAttempts);r.put("autoActivations",autoActivations);r.put("autoSatisfiedByExisting",autoSatisfiedByExisting);r.put("autoReason",autoReason);
        r.put("absorbedSoftFlux",absorbedFlux);r.put("fluxRemovedOnFire",removedFlux);r.put("absorbEvents",absorbEvents);r.put("deflectionYields",deflectionYields);r.put("fluxDamageMult",bio?fluxDamageMultiplier(ship.getFluxTracker().getFluxLevel()):1);r.put("fluxLeases",fluxLeases.size());r.put("damageLeases",damageLeases.size());r.put("leaseConflicts",leaseConflicts);r.put("budgetSkips",budgetSkips);r.put("immunityScope","Native EMP and component damage; optical damage absorption. Foreign Mod debuffs are not erased.");return Collections.unmodifiableMap(r);
    }
    private void publish(){ship.setCustomData(KEY,status());}
    private void checkThread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Zeta defenses require battle owner thread");}
    private void check(){checkThread();if(closed)throw new IllegalStateException("Zeta defenses closed");}
    private void clearStats(ShipAPI part){MutableShipStatsAPI stats=part.getMutableStats();for(MutableStat s:new MutableStat[]{stats.getHullDamageTakenMult(),stats.getArmorDamageTakenMult(),stats.getShieldDamageTakenMult()})s.unmodify(WR_MOD);for(MutableStat s:new MutableStat[]{stats.getEmpDamageTakenMult(),stats.getWeaponDamageTakenMult(),stats.getEngineDamageTakenMult()})s.unmodify(EMP_MOD);if(part==ship)stats.getEnergyRoFMult().unmodify(ROF_MOD);}
    @Override public void close(){checkThread();if(closed)return;closed=true;restoreDamageLeases();releaseReload();ship.removeListener(this);clearStats(ship);if(shield!=null){shield.removeListener(this);clearStats(shield);}for(FluxLease lease:fluxLeases.values())lease.restore();fluxLeases.clear();seenShots.clear();shotOrder.clear();guidancePending.clear();counterPending.clear();
        if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");publish();
    }
}
