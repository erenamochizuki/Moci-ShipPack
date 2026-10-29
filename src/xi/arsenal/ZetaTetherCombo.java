package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import java.awt.Color;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** One native-onHit-confirmed cable combination. The outer Zeta actor alone advances this clock.
 * Native launcher, native rifle reservation/first light and native wrist burst pay their own costs. */
public final class ZetaTetherCombo implements AutoCloseable {
    public static final String KEY="xi.arsenal.zeta.tether",COMMAND="Moci_fw_zeta_tether_command",LAUNCHER="Moci_fw_zeta_tether_launcher",PROJECTILE="Moci_fw_zeta_tether_dart",
            COMMAND_SLOT="ZETA_TETHER_COMMAND",LAUNCHER_SLOT="ZETA_TETHER_LAUNCHER";
    public enum Phase{IDLE,LEFT_PRESENT,LAUNCH,WAIT_HIT,RETURNING,RIGHT_PRESENT,TRACTOR_FIRE,SABER_FOLLOWUP,RECOVER,COOLDOWN,CLOSED}
    public record Request(boolean accepted,String reason){}
    private final Thread thread=Thread.currentThread();private final CombatEngineAPI engine;private final ShipAPI ship;private final ZetaRifleModes rifle;
    private final WeaponAPI command,launcher,wrist;private final ZetaTetherCable cable;
    private Phase phase=Phase.IDLE;private ShipAPI requested,anchorEntity,forceBody;private Vector2f requestedPoint,localAnchor;private DamagingProjectileAPI projectile;
    private ZetaTetherPose pose;private ZetaRifleModes.ComboLease lease;private WeaponGroupAPI wristGroup;private boolean wristAuto,closed,wristStarted,releaseRequested;
    private ZetaSaberMotion.FollowupLease saberFollowup;private long saberStarted,saberCompleted,saberSkipped;private String saberReason="";private float saberDistance,saberRange,saberAngle;
    private boolean piercingCast;private ZetaTetherMissileAI flightAi;private boolean returnRecovered;private String returnReason="";private Vector2f returnedAt,returnedWrist;private float returnAimAngle,returnAimRelative;
    private long returnStarted,returned,returnFailures;private String dartIdentity="";private float returnStartWorld;
    private float phaseTime,totalTime,cooldown,empCarry,firstLightWait,recoverBody=-28;private long castSerial,casts,nativeLaunches,nativeHits,nativeEmp,wristShots;private int wristAmmo;
    private String reason="准星瞄准前方，点按钢索弹组发射",followupReason="";private float lastPullAcceleration,lastSourceAcceleration,lastDesiredClosing,lastBioForceMultiplier=1,lastBioSpeedMultiplier=1,lastSourceDeltaV,lastTargetDeltaV,lastSourcePullScale=1;private boolean anchorShieldHit,lockWasNative;private final Set<DamagingProjectileAPI> hitOnce=Collections.newSetFromMap(new IdentityHashMap<>());
    public ZetaTetherCombo(CombatEngineAPI engine,ShipAPI ship,ZetaRifleModes rifle){
        this.engine=Objects.requireNonNull(engine);this.ship=Objects.requireNonNull(ship);this.rifle=Objects.requireNonNull(rifle);
        if(!supports(ship)||ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Complete unowned Zeta tether loadout required");
        command=slot(COMMAND_SLOT);launcher=slot(LAUNCHER_SLOT);wrist=slot("ZETA_WRIST_R");if(wrist==null)throw new IllegalStateException("Actual right wrist grenade launcher missing");
        cable=new ZetaTetherCable();ship.setCustomData(KEY+".owner",this);publish();
    }
    public static boolean supports(ShipAPI ship){return ship!=null&&ship.getAllWeapons().stream().anyMatch(w->COMMAND.equals(w.getId())&&w.getSlot()!=null&&COMMAND_SLOT.equals(w.getSlot().getId()))&&ship.getAllWeapons().stream().anyMatch(w->LAUNCHER.equals(w.getId())&&w.getSlot()!=null&&LAUNCHER_SLOT.equals(w.getSlot().getId()));}
    public static ZetaTetherCombo instance(ShipAPI ship){Object value=ship==null?null:ship.getCustomData().get(KEY+".owner");return value instanceof ZetaTetherCombo own&&!own.closed?own:null;}
    public Request request(ShipAPI target){
        check();return requestAim(target==null?ship.getMouseTarget():target.getLocation(),target);
    }
    /** The real command callback starts preparation. Free aim follows the live mouse until
     * native onLaunch fixes the outgoing ray; a valid explicit lock remains optional assistance. */
    public Request requestAim(Vector2f worldPoint,ShipAPI optionalLock){
        check();ShipAPI locked=priorityLock(optionalLock);worldPoint=locked==null?worldPoint:locked.getLocation();String block=startBlock(worldPoint);if(!block.isEmpty())return no(block);if(locked!=null&&Vector2f.sub(locked.getLocation(),ship.getLocation(),null).length()>launcher.getRange())return no("锁定目标超出钢索真实射程");
        requestedPoint=new Vector2f(worldPoint);Vector2f ray=Vector2f.sub(requestedPoint,ship.getLocation(),null);float distance=ray.length();if(distance>launcher.getRange()){ray.scale(launcher.getRange()/distance);requestedPoint=Vector2f.add(ship.getLocation(),ray,null);}
        requested=locked;lockWasNative=requested!=null&&requested==ship.getShipTarget();
        phase=Phase.LEFT_PRESENT;phaseTime=totalTime=empCarry=0;wristStarted=releaseRequested=returnRecovered=anchorShieldHit=false;projectile=null;flightAi=null;dartIdentity="";returnedAt=returnedWrist=null;returnReason=followupReason="";anchorEntity=forceBody=null;localAnchor=null;hitOnce.clear();castSerial++;
        ship.setCustomData(ZetaRifleModes.ARM_ACTION_KEY,this);
        try{
            // Starting the arm action cancels an ordinary held/releasing shot through its owner.
            // A real already-emitted shot keeps its normal expenditure; no ammunition is invented.
            rifle.cancelCharge("钢索弹接管双臂");
            // cancelCharge may leave an independently owned native closing animation alive.
            // Decline this new action normally; never steal/clear a foreign geometry lease.
            if(ship.getCustomData().containsKey(LeftArmAttachments.KEY)||ship.getCustomData().containsKey(RifleFiringPose.KEY)||ship.getCustomData().containsKey(ZetaTetherPose.KEY)){releaseOwned();phase=Phase.IDLE;return no("上一动作仍在收势，等待双臂附件归还后再发射钢索");}
            pose=new ZetaTetherPose(ship,this);
            var accepted=rifle.acquireCombo(new ZetaRifleModes.ComboRig(){public Object token(){return ZetaTetherCombo.this;}public boolean valid(){return active()&&validSource()&&ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY)==ZetaTetherCombo.this;}public boolean readyForBeam(){return phase==Phase.TRACTOR_FIRE;}public float gunAimAngle(float nativeAim){return pose==null?aim():pose.rightAimAngle();}public Vector2f gunTranslation(){return pose==null?new Vector2f():pose.rightTranslation();}public void onNativeFirstLight(){if(pose!=null)pose.firstLight();}});
            if(accepted.accepted())lease=accepted.lease();else followupReason="步枪后续不可用："+accepted.reason();
            if(!wristReady())followupReason+=(followupReason.isEmpty()?"":"；")+"右腕榴弹后续不可用";
            wristGroup=ship.getWeaponGroupFor(wrist);wristAuto=wristGroup!=null&&wristGroup.isAutofiring();if(wristAuto)wristGroup.toggleOff();
            // A command may arrive after the actor's step and before rendering. Establish the
            // neutral standing pose atomically; the next actor step alone advances presentation.
            pose.advance(0,0,0,0,aim());
            casts++;reason="向右侧身，左臂前伸瞄准钢索";publish();return new Request(true,reason);
        }catch(RuntimeException failure){releaseOwned();phase=Phase.IDLE;throw failure;}
    }
    private String startBlock(Vector2f point){if(closed||engine!=Global.getCombatEngine()||engine.getPlayerShip()!=ship||!validSource()||engine.isPaused())return "需要当前可操作的人形Z高达";if(phase!=Phase.IDLE||cooldown>0)return "钢索弹尚未冷却";
        if(ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY)!=null||ZetaBeamDeflection.isBlockingRifle(ship))return "其他动作正在占用双臂";
        if(point==null||!Float.isFinite(point.x)||!Float.isFinite(point.y)||Vector2f.sub(point,ship.getLocation(),null).lengthSquared()<1)return "请把准星移至机体前方";
        if(Math.abs(shortest(angleTo(point)-ship.getFacing()))>45)return "准星超出左腕机械射界：机体前方±45度";
        if(launcher.isDisabled()||launcher.isForceDisabled()||launcher.getAmmo()<1||launcher.getCooldownRemaining()>0)return "钢索弹药或发射器尚未就绪";
        return "";}
    public void advance(float dt){
        check();if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid tether local step");if(engine.isPaused())return;
        if(phase==Phase.COOLDOWN){cooldown=Math.max(0,cooldown-dt);if(cooldown<=0){phase=Phase.IDLE;reason="钢索连携就绪";}publish();return;}
        if(!active()){publish();return;}phaseTime+=dt;totalTime+=dt;
        if(!validSource()||!ownsCurrentArms()){cancel("钢索机体、停火或双臂控制失效");return;}
        if(requested!=null&&!enemy(requested)&&phase!=Phase.WAIT_HIT)requested=null;
        if((phase==Phase.LEFT_PRESENT||phase==Phase.LAUNCH)&&!refreshPrelaunchAim()){cancel("实时准星已离开机体前方±45度，取消未发钢索");return;}
        if(phase==Phase.WAIT_HIT&&(requested!=null&&!enemy(requested)||Math.abs(shortest(aim()-ship.getFacing()))>47)){beginReturn("目标规避或离开钢索射界，沿原路回收");}
        else if(phase!=Phase.RETURNING&&!returnRecovered&&(forceBody!=null&&!enemy(forceBody)||totalTime>(phase==Phase.SABER_FOLLOWUP?16:8.5f))){cancel("钢索条件失效或超时");return;}
        if(phase!=Phase.RETURNING&&phase!=Phase.SABER_FOLLOWUP&&!returnRecovered&&Math.abs(shortest(aim()-ship.getFacing()))>47){cancel("目标离开真实步枪前方射界");return;}
        suppressUnrelated();
        switch(phase){
            case LEFT_PRESENT->{float q=smooth(phaseTime/ZetaTetherPose.LEFT_PRESENT_SECONDS);pose.advance(dt,-28*q,24*q,0,aim());if(phaseTime>=ZetaTetherPose.LEFT_PRESENT_SECONDS){phase=Phase.LAUNCH;phaseTime=0;reason="真实左腕发射钢索";}}
            case LAUNCH->{pose.advance(dt,-28,24,0,aim());if(phaseTime>.5f)cancel("钢索原生发射未确认");}
            case WAIT_HIT->{pose.advance(dt,-28,24,0,aim());if(!liveDart()){returnFailures++;cancel("钢索飞弹已被击落或失效，无法回收");}else if(phaseTime>2.6f||Vector2f.sub(projectile.getLocation(),pose.leftAperture(),null).length()>launcher.getRange()+100)beginReturn("钢索未命中或超过射程，沿原飞行路径回收");}
            case RETURNING->{if(!liveDart()||flightAi==null||flightAi.lost()||engine.getTotalElapsedTime(false)-returnStartWorld>ZetaTetherMissileAI.RETURN_SECONDS+1){returnFailures++;cancel("钢索原路回收失败：原生飞弹失效或超时");break;}
                returnAimAngle=ship.getFacing()+returnAimRelative;
                pose.advance(dt,-28,24,0,returnAimAngle);
                if(flightAi.atWrist()&&Vector2f.sub(projectile.getLocation(),pose.leftAperture(),null).length()<=ZetaTetherMissileAI.RECOVERY_RADIUS){returnedAt=new Vector2f(projectile.getLocation());returnedWrist=pose.leftAperture();engine.removeEntity(projectile);projectile=null;returned++;returnRecovered=true;phase=Phase.RECOVER;phaseTime=0;recoverBody=-28;reason="钢索沿原路到腕，收势";}}
            case RIGHT_PRESENT->{float q=smooth(phaseTime/ZetaTetherPose.RIGHT_PRESENT_SECONDS);pose.advance(dt,-28+56*q,24*(1-q),24*q,aim());pull(dt);if(!active())return;if(phaseTime>=ZetaTetherPose.RIGHT_PRESENT_SECONDS){phase=Phase.TRACTOR_FIRE;phaseTime=firstLightWait=0;reason="牵引中，释放真实步枪照射与右腕榴弹";}}
            case TRACTOR_FIRE->{pose.advance(dt,28,0,24,aim());pull(dt);if(!active())return;if(!releaseRequested){if(lease!=null){var fired=lease.releaseCharge();if(!fired.accepted())skipRifle(fired.reason());}releaseRequested=true;wristAmmo=wrist.getAmmo();}
                String gunPhase=String.valueOf(rifle.status().get("phase"));if(lease==null||gunPhase.equals("FIRING"))fireWrist();
                boolean gunDone=lease==null&&phaseTime>=.65f||lease!=null&&gunPhase.equals("COOLDOWN"),grenadeDone=!wristReady()||wristStarted&&!wrist.isInBurst()&&!wrist.isFiring()||phaseTime>1.2f&&!wrist.isInBurst();
                if(gunDone&&grenadeDone&&nativeBeamSettled())beginSaberOrRecover();
                else if(gunDone&&phaseTime>6)skipSaber("原生照射尚未熄灭，禁止军刀抢占");
                else if(lease!=null&&!gunPhase.equals("FIRING")&&!gunPhase.equals("COOLDOWN")&&(firstLightWait+=dt)>.8f){skipRifle("真实照射未能首亮");reason="钢索牵引继续，跳过不可用步枪";}}
            case SABER_FOLLOWUP->{if(saberFollowup==null){finish("军刀后续租约失效，松开钢索",12);break;}if(saberFollowup.done()){boolean success=saberFollowup.succeeded();if(success)saberCompleted++;saberReason=success?"真实三刀及收势已完成":"军刀后续取消或未能完成";finish(saberReason+"，松开钢索",12);break;}if(phaseTime>8){cancel("原生军刀后续超时，松开钢索");break;}pull(dt);reason="钢索保持挂接 · 原生军刀"+saberFollowup.state();}
            case RECOVER->{float q=1-smooth(phaseTime/ZetaTetherPose.RECOVER_SECONDS);pose.advance(dt,recoverBody*q,returnRecovered?24*q:0,returnRecovered?0:24*q,returnRecovered?returnAimAngle:aim());if(phaseTime>=ZetaTetherPose.RECOVER_SECONDS)finish(returnRecovered?"钢索已沿原路回收":"钢索连携完成",returnRecovered?6:12);}
            default->{}
        }
        if(active()&&bio()&&anchorEntity!=null){empCarry+=dt;if(empCarry>=.45f){empCarry%=.45f;emp();}}
        publish();
    }
    private boolean ownsCurrentArms(){Object owner=ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY);return phase==Phase.SABER_FOLLOWUP?saberFollowup!=null&&(saberFollowup.done()||owner==saberFollowup.armOwner()):owner==this;}
    private boolean nativeBeamSettled(){for(WeaponAPI weapon:ship.getAllWeapons())if(ZetaRifleModes.SNIPER.equals(weapon.getId())&&weapon.getSlot()!=null&&ZetaRifleModes.SNIPER_SLOT.equals(weapon.getSlot().getId())){if(weapon.isFiring()||weapon.getChargeLevel()>.0001f)return false;for(BeamAPI beam:weapon.getBeams())if(beam!=null&&beam.getWeapon()==weapon&&beam.getSource()==ship&&beam.getBrightness()>.05f)return false;}return true;}
    private void beginSaberOrRecover(){ZetaSaberMotion sword=ZetaSaberMotion.instance(ship);if(sword==null){skipSaber("未安装可用的原生军刀控制器");return;}var check=sword.canNativeFollowup(anchorEntity,attached(),this);saberDistance=check.distance();saberRange=check.range();saberAngle=check.angleError();if(!check.accepted()){skipSaber(check.reason());return;}
        // Return the outgoing rig first. The sword repeats its strict physical checks after
        // restoration and receives its own typed lease; no second Pose can overlap this one.
        if(lease!=null){lease.close();lease=null;}if(pose!=null){pose.close();pose=null;}if(ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY)==this)ship.removeCustomData(ZetaRifleModes.ARM_ACTION_KEY);
        var request=sword.requestNativeFollowup(anchorEntity,attached());if(!request.accepted()){saberSkipped++;saberReason=request.reason();finish("军刀交权后未满足真实距离/射界："+saberReason,12);return;}
        saberFollowup=request.lease();saberStarted++;saberReason="真实刀距与射界已满足，已交给原生军刀";phase=Phase.SABER_FOLLOWUP;phaseTime=0;reason=saberReason;
    }
    private void skipSaber(String why){saberSkipped++;saberReason=why;phase=Phase.RECOVER;phaseTime=0;recoverBody=28;reason="后续军刀跳过："+why+"，钢索收势";}
    /** The real native launcher callback confirms the projectile. It never pays for a synthetic missile. */
    void onLaunch(DamagingProjectileAPI shot){if(closed){if(shot!=null)engine.removeEntity(shot);return;}check();if(phase!=Phase.LAUNCH||!validSource()||shot==null||shot.getWeapon()!=launcher||shot.getSource()!=ship){if(shot!=null)engine.removeEntity(shot);return;}
        projectile=shot;dartIdentity=Integer.toHexString(System.identityHashCode(shot));shot.setCustomData(KEY+".owner",this);shot.setCustomData(KEY+".serial",castSerial);nativeLaunches++;phase=Phase.WAIT_HIT;phaseTime=0;
        if(shot instanceof MissileAPI missile){piercingCast=ZetaOverdriveLifecycle.immune(ship);flightAi=new ZetaTetherMissileAI(engine,missile,requested,requestedPoint);if(piercingCast){missile.setCollisionClass(CollisionClass.NONE);flightAi.enableOverdrivePiercing((hit,point)->{onHit(missile,hit,point,false);if(engine.isEntityInPlay(missile))engine.removeEntity(missile);});}missile.setMissileAI(flightAi);}else throw new IllegalStateException("Tether projectile must be a real native missile");
        if(lease!=null){var held=lease.beginCharge();if(!held.accepted())skipRifle(held.reason());}reason=lease==null?"钢索飞行，命中后自动牵引；"+followupReason:"钢索飞行，命中后自动牵引与照射";publish();
    }
    /** Optional public recall action; the normal miss/phase/timeout path invokes the same real return. */
    public Request recall(){check();if(phase!=Phase.WAIT_HIT||!validSource())return no("当前没有可召回的在途钢索");return beginReturn("主动沿原路回收钢索")?new Request(true,reason):new Request(false,reason);}
    private boolean beginReturn(String why){if(phase!=Phase.WAIT_HIT||!liveDart()||flightAi==null){returnFailures++;cancel("原生钢索飞弹已消失，无法伪造回收");return false;}
        rifle.cancelCharge("钢索正在原路回收，取消未发蓄力");phase=Phase.RETURNING;phaseTime=0;returnStartWorld=engine.getTotalElapsedTime(false);returnReason=why;reason=why;returnAimRelative=Math.max(-45,Math.min(45,shortest(aim()-ship.getFacing())));returnAimAngle=ship.getFacing()+returnAimRelative;returnStarted++;flightAi.beginReturn(()->pose==null?new Vector2f(ship.getLocation()):pose.leftAperture(),ship::getVelocity);publish();return true;}
    private boolean liveDart(){return projectile instanceof MissileAPI missile&&engine.isEntityInPlay(projectile)&&!missile.isFading()&&!missile.isFizzling();}
    void onHit(DamagingProjectileAPI shot,CombatEntityAPI target,Vector2f point,boolean shield){if(closed)return;check();if(shot!=projectile||phase!=Phase.WAIT_HIT||!hitOnce.add(shot)||!(target instanceof ShipAPI hit))return;
        ShipAPI main=hit;Set<ShipAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());for(int i=0;i<8&&main.getParentStation()!=null&&seen.add(main);i++)main=main.getParentStation();
        if(!enemy(main)){cancel("钢索没有命中有效敌舰");return;}anchorEntity=hit;forceBody=main;anchorShieldHit=shield;localAnchor=rotate(Vector2f.sub(point,hit.getLocation(),null),-hit.getFacing());nativeHits++;phase=Phase.RIGHT_PRESENT;phaseTime=0;reason=shield?"钢索已抓住护盾接触点，牵引整舰":"钢索已挂接，反向侧身展开右臂";if(bio())emp();publish();
    }
    public static void beforeWeapon(WeaponAPI weapon){beforeWeapon(weapon,Global.getCombatEngine());}
    public static void beforeWeapon(WeaponAPI weapon,CombatEngineAPI engine){ZetaTetherCombo own=instance(weapon.getShip());if(own==null){if(COMMAND.equals(weapon.getId())||LAUNCHER.equals(weapon.getId())){ZetaTetherEntryProbe.guard(engine,weapon,"NO_TETHER_ACTOR");weapon.setForceNoFireOneFrame(true);}return;}
        if(!own.validSource()&&own.active())own.cancel("停火或原生机体控制已阻止钢索");
        if(COMMAND.equals(weapon.getId())){boolean ready=own.phase==Phase.IDLE&&own.validSource();ZetaTetherEntryProbe.guard(engine,weapon,ready?"READY":"BLOCKED_"+own.phase.name());if(!ready)weapon.setForceNoFireOneFrame(true);return;}
        if(LAUNCHER.equals(weapon.getId())){boolean ready=own.phase==Phase.LAUNCH&&own.pose!=null;if(ready&&!own.refreshPrelaunchAim()){own.cancel("发射前实时准星超出前方±45度");ready=false;}ZetaTetherEntryProbe.guard(engine,weapon,ready?"NATIVE_LAUNCH":"WAIT_FOR_COMMAND_AND_LEFT_POSE");if(ready){weapon.setCurrAngle(own.aim());ZetaRifleMount.setFirePoint(weapon,own.pose.leftAperture());if(!weapon.isForceNoFireOneFrame())weapon.setForceFireOneFrame(true);}else weapon.setForceNoFireOneFrame(true);}
    }
    private ShipAPI priorityLock(ShipAPI supplied){ShipAPI nativeTarget=ship.getShipTarget();return enemy(nativeTarget)&&!nativeTarget.isFighter()?nativeTarget:enemy(supplied)&&!supplied.isFighter()?supplied:null;}
    private Vector2f readyAimPoint(){ShipAPI lock=priorityLock(null);return lock==null?ship.getMouseTarget():lock.getLocation();}
    private String readyBlock(){ShipAPI lock=priorityLock(null);if(lock!=null&&Vector2f.sub(lock.getLocation(),ship.getLocation(),null).length()>launcher.getRange())return "锁定目标超出钢索真实射程";return startBlock(readyAimPoint());}
    private boolean refreshPrelaunchAim(){ShipAPI current=ship.getShipTarget();if(enemy(current)&&!current.isFighter()){requested=current;lockWasNative=true;}else if(lockWasNative)requested=null;Vector2f point=requested==null?ship.getMouseTarget():requested.getLocation();if(point==null||!Float.isFinite(point.x)||!Float.isFinite(point.y)||Vector2f.sub(point,ship.getLocation(),null).lengthSquared()<1||Math.abs(shortest(angleTo(point)-ship.getFacing()))>45)return false;Vector2f offset=Vector2f.sub(point,ship.getLocation(),null);float distance=offset.length();if(requested!=null&&distance>launcher.getRange())return false;if(distance>launcher.getRange())offset.scale(launcher.getRange()/distance);requestedPoint=Vector2f.add(ship.getLocation(),offset,null);return true;}
    private void suppressUnrelated(){for(WeaponAPI weapon:ship.getAllWeapons()){if(weapon.isDecorative()||weapon==launcher||ZetaRifleModes.SNIPER.equals(weapon.getId()))continue;if(phase==Phase.SABER_FOLLOWUP&&ZetaSaberMotion.WEAPON.equals(weapon.getId()))continue;if(weapon==wrist&&phase==Phase.TRACTOR_FIRE)continue;weapon.setForceNoFireOneFrame(true);}}
    private void pull(float dt){if(anchorEntity==null||!anchorEntity.isAlive()||anchorEntity.isHulk()||!engine.isEntityInPlay(anchorEntity)){cancel("钢索附着部位失效");return;}Vector2f at=forceBody.getLocation(),from=ship.getLocation();float stop=ship.getCollisionRadius()+forceBody.getCollisionRadius()+35;
        float pulse=phase==Phase.RIGHT_PRESENT?(float)Math.sin(Math.PI*Math.max(0,Math.min(1,phaseTime/ZetaTetherPose.RIGHT_PRESENT_SECONDS))):0;
        float sourceScale=1;ShipEngineControllerAPI engines=ship.getEngineController();if(engines!=null&&engines.isAcceleratingBackwards()&&!engines.isDisabled()&&!engines.isFlamedOut()){Vector2f toward=Vector2f.sub(at,from,null);float distance=toward.length();double facing=Math.toRadians(ship.getFacing());if(distance>1&&(toward.x*Math.cos(facing)+toward.y*Math.sin(facing))/distance>.2)sourceScale=0;}
        var impulse=ZetaTetherPhysics.pairImpulse(from,at,ship.getVelocity(),forceBody.getVelocity(),Math.max(1,ship.getMass()),Math.max(1,forceBody.getMass()),forceBody.getHullSize(),stop,dt,phase==Phase.RIGHT_PRESENT?1+.5f*pulse:.9f,bioEffect(),sourceScale);
        Vector2f.add(ship.getVelocity(),impulse.sourceDelta(),ship.getVelocity());if(!ZetaOverdriveLifecycle.immune(forceBody))Vector2f.add(forceBody.getVelocity(),impulse.targetDelta(),forceBody.getVelocity());lastPullAcceleration=impulse.targetAcceleration();lastSourceAcceleration=impulse.sourceAcceleration();lastDesiredClosing=impulse.desiredClosing();lastBioForceMultiplier=impulse.forceMultiplier();lastBioSpeedMultiplier=impulse.speedMultiplier();lastSourceDeltaV=impulse.sourceDelta().length();lastTargetDeltaV=impulse.targetDelta().length();lastSourcePullScale=sourceScale;}
    private Vector2f leftAperture(){return pose!=null?pose.leftAperture():phase==Phase.SABER_FOLLOWUP&&saberFollowup!=null?ZetaSaberMotion.currentLeftAperture(ship):null;}
    private void emp(){Vector2f wristPoint=leftAperture();if(anchorEntity==null||wristPoint==null||ZetaOverdriveLifecycle.immune(anchorEntity))return;if(piercingCast)engine.spawnEmpArcPierceShields(ship,wristPoint,ship,anchorEntity,DamageType.ENERGY,0,400,2000,null,1,new Color(175,95,255,105),new Color(230,215,255,160));else engine.spawnEmpArc(ship,wristPoint,ship,anchorEntity,DamageType.ENERGY,0,120,2000,null,1,new Color(175,95,255,105),new Color(230,215,255,160));nativeEmp++;}
    /** Exactly the coordinates consumed by render, exposed for read-only endpoint diagnostics. */
    public List<Vector2f> cableEndpoints(){if(!active())return List.of();Vector2f from=leftAperture();if(from==null)return List.of();if(anchorEntity!=null)return ZetaTetherCable.liveEndpoints(from,attached());return projectile!=null&&engine.isEntityInPlay(projectile)&&flightAi!=null?flightAi.cablePath(from):List.of();}
    public void render(ViewportAPI viewport){List<Vector2f> ends=cableEndpoints();if(ends.size()==2)cable.render(ends.get(0),ends.get(1),engine.getTotalElapsedTime(false),anchorEntity!=null,bio(),viewport);}
    private Vector2f attached(){return Vector2f.add(anchorEntity.getLocation(),rotate(localAnchor,anchorEntity.getFacing()),null);}
    private boolean bio(){ShipSystemAPI system=ship.getSystem();return system!=null&&ZetaContent.SYSTEM.equals(system.getId())&&system.getEffectLevel()>.1f;}
    private float bioEffect(){ShipSystemAPI system=ship.getSystem();return system!=null&&ZetaContent.SYSTEM.equals(system.getId())&&Float.isFinite(system.getEffectLevel())?Math.max(0,Math.min(1,system.getEffectLevel())):0;}
    private boolean validSource(){return !closed&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship)&&ZetaRifleModes.msForm(ship)&&!ship.controlsLocked()&&!ship.getFluxTracker().isOverloadedOrVenting()&&!ship.isHoldFire()&&!ship.isHoldFireOneFrame()&&engine.getPlayerShip()==ship;}
    private boolean enemy(ShipAPI target){return target!=null&&target!=ship&&target.isAlive()&&!target.isHulk()&&!target.wasRemoved()&&engine.isEntityInPlay(target)&&target.getOwner()!=ship.getOwner()&&(target.getOwner()==0||target.getOwner()==1)&&!target.isPhased();}
    private float aim(){ShipAPI target=forceBody==null?requested:forceBody;return target==null?(requestedPoint==null?ship.getFacing():angleTo(requestedPoint)):angleTo(target.getLocation());}
    private boolean wristReady(){return !wrist.isDisabled()&&!wrist.isForceDisabled()&&wrist.getAmmo()>=2;}
    private void fireWrist(){if(wristStarted||!validSource())return;if(wrist.isInBurst()||wrist.getAmmo()<wristAmmo){wristStarted=true;wristShots++;return;}if(!wristReady())return;wrist.setCurrAngle(aim());wrist.setForceFireOneFrame(true);}
    private void skipRifle(String why){if(lease!=null){lease.close();lease=null;}followupReason="步枪后续跳过："+why;}
    private float angleTo(Vector2f point){return (float)Math.toDegrees(Math.atan2(point.y-ship.getLocation().y,point.x-ship.getLocation().x));}
    public boolean active(){return !closed&&phase!=Phase.IDLE&&phase!=Phase.COOLDOWN&&phase!=Phase.CLOSED;}
    public void cancel(String why){check();if(!active())return;finish(why==null?"钢索已取消":why,6);}
    private void finish(String why,float seconds){releaseOwned();phase=Phase.COOLDOWN;cooldown=seconds;reason=why;publish();}
    private void releaseOwned(){Throwable failure=null;
        // Attempt every owned restoration even if an earlier native object was removed mid-frame.
        for(AutoCloseable item:new AutoCloseable[]{saberFollowup,lease,pose,
                ()->{if(projectile!=null&&engine.isEntityInPlay(projectile)&&!projectile.didDamage())engine.removeEntity(projectile);},
                ()->{if(wristAuto&&wristGroup!=null&&!wristGroup.isAutofiring())wristGroup.toggleOn();},
                ()->{if(ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY)==this)ship.removeCustomData(ZetaRifleModes.ARM_ACTION_KEY);}})
            if(item!=null)try{item.close();}catch(Throwable error){if(error instanceof VirtualMachineError fatal)throw fatal;if(failure==null)failure=error;else failure.addSuppressed(error);}
        saberFollowup=null;lease=null;pose=null;projectile=null;anchorEntity=forceBody=null;localAnchor=null;wristAuto=false;wristGroup=null;
        if(failure!=null)throw new IllegalStateException("Tether cleanup failed",failure);}
    private WeaponAPI slot(String id){return ZetaRifleMount.slot(ship,id);}
    public Map<String,Object> status(){Map<String,Object> row=new LinkedHashMap<>();row.put("hullPiercing",piercingCast);row.put("phase",phase.name());String readyBlock=phase==Phase.IDLE?readyBlock():phase==Phase.COOLDOWN?"钢索弹冷却中":"正在执行钢索动作";row.put("ready",readyBlock.isEmpty());row.put("unavailableReason",readyBlock);row.put("active",active());row.put("reason",reason);row.put("phaseLocal",phaseTime);row.put("totalLocal",totalTime);row.put("cooldown",cooldown);row.put("casts",casts);row.put("nativeLaunches",nativeLaunches);row.put("nativeHits",nativeHits);row.put("nativeEmp",nativeEmp);row.put("wristBursts",wristShots);row.put("pullAcceleration",lastPullAcceleration);row.put("sourcePullAcceleration",lastSourceAcceleration);row.put("sourcePullDeltaV",lastSourceDeltaV);row.put("targetPullDeltaV",lastTargetDeltaV);row.put("sourcePullScale",lastSourcePullScale);row.put("desiredClosingSpeed",lastDesiredClosing);row.put("bioForceMultiplier",lastBioForceMultiplier);row.put("bioSpeedMultiplier",lastBioSpeedMultiplier);row.put("shieldAnchor",anchorShieldHit);row.put("attached",anchorEntity!=null);row.put("freeAim",requested==null);row.put("launchAimPoint",requestedPoint==null?List.of():point(requestedPoint));row.put("optionalLock",requested!=null);row.put("followupReason",followupReason);row.put("rifleFollowup",lease!=null);row.put("wristFollowup",wristReady());row.put("nativeBeamSettled",nativeBeamSettled());row.put("saberFollowupStarted",saberStarted);row.put("saberFollowupCompleted",saberCompleted);row.put("saberFollowupSkipped",saberSkipped);row.put("saberGateReason",saberReason);row.put("saberGateDistance",saberDistance);row.put("saberGateRange",saberRange);row.put("saberGateAngle",saberAngle);row.put("saberFollowup",saberFollowup==null?Map.of():saberFollowup.status());row.put("pose",pose==null?Map.of():pose.status());row.put("returning",phase==Phase.RETURNING);row.put("returnStarted",returnStarted);row.put("returned",returned);row.put("returnFailures",returnFailures);row.put("returnReason",returnReason);row.put("nativeDartIdentity",dartIdentity);row.put("flightSamples",flightAi==null?0:flightAi.sampleCount());row.put("flightPath",flightAi==null?List.of():flightAi.flightSamples().stream().map(ZetaTetherCombo::point).toList());row.put("flightState",flightAi==null?"":flightAi.flight().name());row.put("outbound",flightAi==null?Map.of():flightAi.outboundStatus());row.put("returnWaypointIndex",flightAi==null?-1:flightAi.waypointIndex());row.put("returnWaypointsVisited",flightAi==null?0:flightAi.waypointsVisited());row.put("maxReturnWaypointError",flightAi==null?0:flightAi.maxWaypointError());row.put("returnTime",flightAi==null?0:flightAi.returnTime());Map<String,Object> reel=flightAi==null?Map.of():flightAi.reelStatus();row.put("reel",reel);row.put("lastReelDeltaV",reel.getOrDefault("lastImpulse",0f));row.put("lastReelStep",reel.getOrDefault("lastForceStep",0f));row.put("reelAcceleration",ZetaTetherMissileAI.WINCH_ACCELERATION);row.put("reelSpeedLimit",ZetaTetherMissileAI.MAX_REEL_SPEED);row.put("reelVelocityBefore",reel.getOrDefault("velocityBefore",List.of()));row.put("reelVelocityAfter",reel.getOrDefault("velocityAfter",List.of()));row.put("dartPosition",projectile==null?List.of():point(projectile.getLocation()));row.put("leftAperture",leftAperture()==null?List.of():point(leftAperture()));row.put("returnDistance",projectile!=null&&pose!=null?Vector2f.sub(projectile.getLocation(),pose.leftAperture(),null).length():null);row.put("returnedAt",returnedAt==null?List.of():point(returnedAt));row.put("returnedWrist",returnedWrist==null?List.of():point(returnedWrist));int index=commandGroupIndex();row.put("groupIndex",index);row.put("groupVisible",index>=0);if(index>=0)row.put("groupNumber",index+1);row.put("scope","Native missile onHit, bounded velocity pull, paid native rifle firstlight and native wrist burst");return Collections.unmodifiableMap(row);}
    private Request no(String why){reason=why;publish();return new Request(false,why);}
    private int commandGroupIndex(){List<WeaponGroupAPI> groups=ship.getWeaponGroupsCopy();WeaponGroupAPI group=ship.getWeaponGroupFor(command);for(int i=0;i<groups.size();i++)if(groups.get(i)==group&&group!=null)return i;return -1;}
    private void publish(){ship.setCustomData(KEY,status());if(!closed&&engine.getPlayerShip()==ship){int index=commandGroupIndex();
        String readyBlock=phase==Phase.IDLE?readyBlock():"";
        String detail=phase==Phase.COOLDOWN?String.format(Locale.ROOT,"冷却 %.1f秒",cooldown):phase==Phase.IDLE?(readyBlock.isEmpty()?"就绪 · 准星±45° · 组 "+(index<0?"暂不可选":index+1)+" 点按 · 命中自动连携":"未就绪："+readyBlock):reason;
        if(active()&&!followupReason.isEmpty())detail+=" · "+followupReason;
        engine.maintainStatusForPlayerShip(KEY,"graphics/icons/hullsys/high_energy_focus.png","钢索弹 · "+launcher.getAmmo()+"发",detail,phase==Phase.COOLDOWN||!readyBlock.isEmpty()||!ZetaRifleModes.msForm(ship));}}
    private void check(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Tether requires combat owner thread");if(closed)throw new IllegalStateException("Tether closed");}
    @Override public void close(){if(closed)return;check();try{releaseOwned();}finally{closed=true;phase=Phase.CLOSED;if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");publish();}}
    private static List<Float> point(Vector2f value){return List.of(value.x,value.y);}
    private static float shortest(float value){float a=(value%360+360)%360;return a>180?a-360:a;}
    private static float smooth(float value){float p=Math.max(0,Math.min(1,value));return p*p*(3-2*p);}
    private static Vector2f rotate(Vector2f point,float degrees){double r=Math.toRadians(degrees);return new Vector2f((float)(point.x*Math.cos(r)-point.y*Math.sin(r)),(float)(point.x*Math.sin(r)+point.y*Math.cos(r)));}
}
