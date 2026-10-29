package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;
import data.scripts.weapons.blade.Moci_BladeMotion;
import data.scripts.weapons.blade.Moci_BladeMotionRegistry;

/** One native SABER weapon callback owns the motion clock. The Actor constructs/observes/closes
 * this object; it must never advance it. Native warmup and the authored burn duration drive the pose;
 * the original Moci callback still owns every damage settlement.
 *
 * 与我方的接缝：本类实现 {@link Moci_BladeMotion}，在自己构造时注册到
 * {@link Moci_BladeMotionRegistry}，由我方的光剑武器/光束回调主动查询并驱动。
 * 我方仍然拥有武器回调、伤害结算、卡肉与火线特效；这里只负责动作编排、姿态与刀光渲染。 */
public final class ZetaSaberMotion implements AutoCloseable, Moci_BladeMotion {
    public static final String KEY="xi.arsenal.zeta.saber",WEAPON="MSZ_006_arm_left_gun_Sabre",SLOT="ARM_L_SOURCE";
    private static final String SWING="MOCI_BLADE_SWING_ACTIVE_LEFT",FLAG_OWNER=KEY+".flagOwner";
    public static final float BODY_SIDE=28f,MAX_SHOULDER_RELATIVE=65f,RECOVERY_SECONDS=.30f;
    public static final float DRAW_FRACTION=.45f,DRAW_BODY=20f,DRAW_BLADE=35f;
    public static final float FINAL_LEFT_BODY=24f,FINAL_SWEEP_END=.97f;
    public enum Stage {IDLE,DRAW,WARMUP,STAB,RIGHT_CUT,LEFT_SLASH,RECOVERY,CLOSED}
    public record Frame(Stage stage,float bodyDegrees,float bladeRelative,float progress){}
    public enum NativeFollowupState {WAITING_NATIVE,PREPARING,STRIKING,RECOVERING,SUCCEEDED,CANCELLED}
    public record FollowupCheck(boolean accepted,String reason,float distance,float range,float angleError){}
    public record FollowupRequest(boolean accepted,String reason,FollowupLease lease){}
    /** A caller owns only this request, never the Mod clock or another action's pose. */
    public final class FollowupLease implements AutoCloseable {
        private final ShipAPI target;private final Vector2f localHitPoint;private final long initialBursts;
        private NativeFollowupState state=NativeFollowupState.WAITING_NATIVE;private boolean released;private float waitingLocal;private String detail="等待真实原生军刀充能";
        private FollowupLease(ShipAPI target,Vector2f worldPoint){this.target=target;localHitPoint=rotate(Vector2f.sub(worldPoint,target.getLocation(),null),-target.getFacing());initialBursts=nativeBursts;}
        public NativeFollowupState state(){requireThread();return state;}
        public boolean done(){requireThread();return released||terminal();}
        public boolean succeeded(){requireThread();return state==NativeFollowupState.SUCCEEDED;}
        public Object armOwner(){return ZetaSaberMotion.this;}
        public void cancel(String why){requireThread();if(followup==this&&!terminal()){ZetaSaberMotion.this.cancel(why==null?"钢索后续军刀已取消":why);suppress();}}
        public Map<String,Object> status(){requireThread();return Map.of("state",state.name(),"done",done(),"succeeded",succeeded(),"reason",detail,"waitingLocal",waitingLocal,"nativeBursts",nativeBursts-initialBursts,"anchor",point(worldPoint()));}
        private boolean terminal(){return state==NativeFollowupState.SUCCEEDED||state==NativeFollowupState.CANCELLED;}
        private Vector2f worldPoint(){return Vector2f.add(target.getLocation(),rotate(localHitPoint,target.getFacing()),null);}
        @Override public void close(){requireThread();if(released)return;if(!terminal())cancel("钢索后续军刀租约已关闭");released=true;if(followup==this)followup=null;}
    }
    private final Thread thread=Thread.currentThread();private final CombatEngineAPI engine;private final ShipAPI ship;private final WeaponAPI blade,follow;
    private ZetaTetherPose pose;private Stage stage=Stage.IDLE;private boolean closed,lightSeen,waitingDark,oldFlagPresent;private Object oldFlag;
    private float cycleLocal,phaseLocal,recoveryLocal,progress,aimRelative,headAim,sideBody,lastBody,lastBlade,startBlade,recoveryProgress,recoverBody,recoverBlade,recoverHead;
    private long weaponSteps,nativeBursts,originalWeaponCalls,originalBeamCalls,mutedCallbacks,cancellations;
    private FollowupLease followup;
    private ZetaSaberBladeRenderer renderer;
    private String reason="原生军刀组开火 · 收刀、前刺、右切、左划砍";

    public static boolean supports(ShipAPI ship){return ship!=null&&ZetaMobility.supports(ship)&&ship.getAllWeapons().stream().anyMatch(w->w.getSlot()!=null&&SLOT.equals(w.getSlot().getId())&&WEAPON.equals(w.getId())&&w.isBeam());}
    public ZetaSaberMotion(CombatEngineAPI engine,ShipAPI ship){
        this.engine=Objects.requireNonNull(engine);this.ship=Objects.requireNonNull(ship);if(!supports(ship))throw new IllegalArgumentException("Original complete Zeta left saber required");
        blade=ZetaRifleMount.slot(ship,SLOT);follow=ZetaRifleMount.slot(ship,"ARM_L_FOLLOW");
        if(ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Saber motion already owned");
        blade.ensureClonedSpec();ship.setCustomData(KEY+".owner",this);publish();
        // 注册到我方接缝：此后由我方光剑武器/光束回调主动驱动本动作层。
        Moci_BladeMotionRegistry.register(blade,this);
    }
    public static ZetaSaberMotion instance(ShipAPI ship){Object owner=ship==null?null:ship.getCustomData().get(KEY+".owner");return owner instanceof ZetaSaberMotion s&&!s.closed?s:null;}
    /** Read-only preflight. Only the exact outgoing arm token may be ignored before handoff.
     * Request performs the strict check again after that caller has returned its pose/rig/token. */
    public FollowupCheck canNativeFollowup(ShipAPI target,Vector2f worldPoint,Object outgoingArmOwner){requireThread();String why=closed?"军刀控制器已关闭":pose!=null||followup!=null&&!followup.terminal()?"军刀动作正在执行":blocked(outgoingArmOwner);if(!why.isEmpty())return new FollowupCheck(false,why,0,Math.max(0,blade.getRange()),0);return followupGeometry(target,worldPoint,true);}
    public FollowupRequest requestNativeFollowup(ShipAPI target,Vector2f worldPoint){requireThread();FollowupCheck check=canNativeFollowup(target,worldPoint,null);if(!check.accepted())return new FollowupRequest(false,check.reason(),null);
        FollowupLease next=new FollowupLease(target,new Vector2f(worldPoint));followup=next;lightSeen=false;ship.setCustomData(ZetaRifleModes.ARM_ACTION_KEY,this);
        // The real installed weapon pays its native charge/flux/cooldown and owns all callbacks.
        // No SELECT_GROUP, onFire replay, extra beam or scripted damage is involved.
        if(!blade.isForceNoFireOneFrame())blade.setForceFireOneFrame(true);reason="钢索命中已拉入真实刀距，准备原生三刀";publish();return new FollowupRequest(true,reason,next);
    }
    private FollowupCheck followupGeometry(ShipAPI target,Vector2f worldPoint,boolean requireDark){float range=blade.getRange();if(!Float.isFinite(range)||range<=0)return new FollowupCheck(false,"原生军刀射程不可用",0,0,0);
        if(target==null||target==ship||!target.isAlive()||target.isHulk()||target.wasRemoved()||target.isPhased()||!engine.isEntityInPlay(target)||target.getOwner()==ship.getOwner())return new FollowupCheck(false,"钢索目标不再是可命中的敌舰",0,range,0);
        if(worldPoint==null||!finite(worldPoint.x,worldPoint.y))return new FollowupCheck(false,"缺少真实钢索挂接点",0,range,0);
        float distance=Vector2f.sub(worldPoint,blade.getFirePoint(0),null).length(),angle=Math.abs(shortest(angleFromShip(worldPoint)-ship.getFacing()));
        if(distance>range+.01f)return new FollowupCheck(false,"尚未拉入原生军刀有效距离",distance,range,angle);
        if(angle>halfArc()+.01f||blade.distanceFromArc(worldPoint)>.01f)return new FollowupCheck(false,"钢索挂点超出真实军刀射界",distance,range,angle);
        if(requireDark&&(blade.getCooldownRemaining()>.0001f||blade.isInBurst()||blade.isFiring()||blade.getChargeLevel()>.0001f||!dark()))return new FollowupCheck(false,"原生军刀上次攻击或冷却未结束",distance,range,angle);
        if(blade.usesAmmo()&&blade.getAmmo()<1)return new FollowupCheck(false,"原生军刀弹药不足",distance,range,angle);
        float cost=blade.getFluxCostToFire();FluxTrackerAPI flux=ship.getFluxTracker();if(Float.isFinite(cost)&&cost>0&&flux!=null&&flux.getMaxFlux()-flux.getCurrFlux()<cost)return new FollowupCheck(false,"原生军刀所需幅能不足",distance,range,angle);
        return new FollowupCheck(true,"挂接点在真实军刀距离和射界内",distance,range,angle);
    }
    /** The cable follows the live visible wrist through either owner's pose, without storing it. */
    public static Vector2f currentLeftAperture(ShipAPI ship){WeaponAPI arm=ship==null?null:ZetaRifleMount.slot(ship,"ARM_L");if(arm==null||arm.getSprite()==null)throw new IllegalStateException("Actual Zeta left wrist is unavailable");return ZetaWristMount.visibleMuzzleWorld(arm,true,0);}
    private void nativeStep(float dt){
        requireThread();if(closed||engine.isPaused())return;if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid native saber step");weaponSteps++;
        String blocked=blocked();if(!blocked.isEmpty()){if(pose!=null||followup!=null&&!followup.terminal())cancel(blocked);else reason=blocked;suppress();publish();return;}
        if(followup!=null&&!followup.terminal()&&!lightSeen){Vector2f hook=followup.worldPoint();FollowupCheck reachable=followupGeometry(followup.target,hook,false);if(!reachable.accepted()){cancel(reachable.reason());suppress();publish();return;}
            // Tether continues moving both hulls during native preparation. Aim at the same
            // target-local hook's live world position, never the old launch point or mouse.
            aimRelative=clampAngle(shortest(angleFromShip(hook)-ship.getFacing()),halfArc());headAim=ship.getFacing()+aimRelative;sideBody=-Math.min(BODY_SIDE,Math.max(0,MAX_SHOULDER_RELATIVE-Math.max(0,aimRelative)));}
        boolean charged=blade.isFiring()&&blade.getChargeLevel()>.0001f;
        if(waitingDark){if(dark()&&blade.getChargeLevel()<=.0001f)waitingDark=false;else{suppress();publish();return;}}
        if(pose==null){if(!charged){if(followup!=null&&!followup.terminal()){followup.waitingLocal+=dt;if(followup.waitingLocal>1.5f){cancel("原生军刀充能未在期限内开始");suppress();}else if(!blade.isForceNoFireOneFrame())blade.setForceFireOneFrame(true);}publish();return;}if(!begin()){if(followup!=null&&!followup.terminal())cancel(reason);suppress();publish();return;}}
        cycleLocal+=dt;phaseLocal+=dt;
        if(cycleLocal>8){cancel("原生军刀周期超时，归还动作");suppress();publish();return;}
        boolean actualLight=blade.getBeams().stream().anyMatch(b->b!=null&&b.getWeapon()==blade&&b.getSource()==ship&&b.getBrightness()>.05f);
        if(!lightSeen&&actualLight){lightSeen=true;nativeBursts++;phaseLocal=0;}
        if(!lightSeen){
            // A single real native trigger commits one melee action. Keep that same weapon's
            // native charge alive after a short click; never synthesize a beam or replay a callback.
            // Articulation follows actual charge progress, so ROF/time modifiers cannot make the
            // blade ignite before the torso has completed its draw-to-thrust transition.
            if(!blade.isForceNoFireOneFrame())blade.setForceFireOneFrame(true);
            float charge=clamp(blade.getChargeLevel());Frame frame=preparation(charge,sideBody,aimRelative,halfArc(),startBlade);
            setStage(frame.stage);lastBody=frame.bodyDegrees;lastBlade=frame.bladeRelative;
            // preparation already interpolates from the real starting blade angle. A second
            // arm-weight blend would combine BODY rotation twice and can cross the slot limit.
            pose.advance(dt,lastBody,24,0,ship.getFacing(),ship.getFacing()+lastBlade,headAim);
            reason=frame.stage==Stage.DRAW?"向左侧身收刀，军刀向右回收":"转向右侧身，提刀前刺";
            if(cycleLocal>3&&!charged&&dark())cancel("原生军刀未能完成准备，已归还动作");
        }else if(stage!=Stage.RECOVERY){
            float duration=Math.max(.001f,blade.getSpec().getBurstDuration()),remaining=blade.getBurstFireTimeRemaining();
            // A real burst may last expose progress .977... before the native tracker returns
            // OFF and resets getBurstFireTimeRemaining to its next-cycle duration (.9 here).
            // Observe the actual ended cycle instead of waiting for that reset counter to hit0.
            boolean nativeEnded=!blade.isFiring()&&blade.getChargeLevel()<=.0001f&&dark();
            progress=nativeEnded?1:Math.max(progress,clamp(1-(Float.isFinite(remaining)?Math.max(0,remaining):0)/duration));Frame frame=sequence(progress,sideBody,aimRelative,halfArc());setStage(frame.stage);lastBody=frame.bodyDegrees;lastBlade=frame.bladeRelative;
            reason=switch(frame.stage){case STAB->"前刺";case RIGHT_CUT->"身体回正，向右切";case LEFT_SLASH->"向左大范围划砍";default->reason;};
            pose.advance(dt,lastBody,24,0,ship.getFacing(),ship.getFacing()+lastBlade,headAim);
            // Keep the final native pose while the original .66 chargedown is still visibly damaging.
            if(nativeEnded){setStage(Stage.RECOVERY);recoveryLocal=recoveryProgress=0;recoverBody=lastBody;recoverBlade=lastBlade;recoverHead=headAim;reason="原生军刀周期已结束，身体与左臂同步收势";}
        }else{
            recoveryLocal+=dt;float q=smooth(clamp(recoveryLocal/RECOVERY_SECONDS));recoveryProgress=q;lastBody=mix(recoverBody,0,q);lastBlade=mix(recoverBlade,startBlade,q);
            // One authored q controls BODY and the actual left-arm angle. Keeping arm weight1
            // avoids a second blend against the already rotating torso and the old snap to0.
            pose.advance(dt,lastBody,24,0,ship.getFacing(),ship.getFacing()+lastBlade,recoverHead+shortest(ship.getFacing()-recoverHead)*q);
            if(q>=1){releasePose();stage=Stage.IDLE;phaseLocal=0;reason="原生三段动作结束，等待原生冷却";if(followup!=null&&!followup.terminal()){followup.state=nativeBursts-followup.initialBursts==1?NativeFollowupState.SUCCEEDED:NativeFollowupState.CANCELLED;followup.detail=followup.state==NativeFollowupState.SUCCEEDED?"真实原生三刀和收势已完成":"原生军刀周期数量异常";}}
        }
        reassert();publish();
    }
    private boolean begin(){
        if(ship.getCustomData().containsKey(LeftArmAttachments.KEY)){reason="前一动作收势未结束，等待左臂附件归还";return false;}
        Object arms=ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY);if(arms!=null&&!(arms==this&&followup!=null&&!followup.terminal()))return false;
        Object flag=ship.getCustomData().get(SWING);if(Boolean.TRUE.equals(flag)){reason="另一个原生刀动作占用左臂";return false;}
        float aim=aimAngle();aimRelative=clampAngle(shortest(aim-ship.getFacing()),halfArc());headAim=ship.getFacing()+aimRelative;
        sideBody=-Math.min(BODY_SIDE,Math.max(0,MAX_SHOULDER_RELATIVE-Math.max(0,aimRelative)));
        WeaponAPI arm=ZetaRifleMount.slot(ship,"ARM_L");startBlade=clampAngle(shortest((arm==null?blade:arm).getCurrAngle()-ship.getFacing()),halfArc());
        oldFlagPresent=ship.getCustomData().containsKey(SWING);oldFlag=flag;ship.setCustomData(ZetaRifleModes.ARM_ACTION_KEY,this);
        try{pose=new ZetaTetherPose(ship,this);ship.setCustomData(FLAG_OWNER,this);ship.setCustomData(SWING,Boolean.TRUE);}
        catch(RuntimeException failure){releasePose();throw failure;}
        cycleLocal=phaseLocal=recoveryLocal=recoveryProgress=progress=0;lightSeen=false;stage=Stage.DRAW;reason="收刀后前刺、右切、左划砍";return true;
    }
    private String blocked(){return blocked(null);}
    private String blocked(Object outgoingArmOwner){
        if(!ship.isAlive()||ship.isHulk()||ship.wasRemoved()||!engine.isEntityInPlay(ship)||engine.isCombatOver())return "机体或战场失效";
        if(!ZetaRifleModes.msForm(ship))return "仅完整MS形态使用军刀";
        if(ship.controlsLocked()||ship.getFluxTracker().isOverloadedOrVenting()||ship.isHoldFire()||ship.isHoldFireOneFrame())return "机体控制、过载或排幅阻止军刀";
        if(blade.isDisabled()||blade.isForceDisabled())return "真实左刀部位失效";
        Object arms=ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY);boolean handingOff=outgoingArmOwner!=null&&arms==outgoingArmOwner;
        if(arms!=null&&arms!=this&&!handingOff)return "其他技能占用双臂";
        if(pose!=null&&arms!=this)return "军刀动作租约已失效";
        // COOLDOWN is a weapon state, not proof that the previous presentation released its
        // left-hand attachment. Native autofire can reach this callback during CLOSING.
        // The previous owner alone restores/removes that lease; do not steal or clear it.
        if(pose==null&&ship.getCustomData().containsKey(LeftArmAttachments.KEY)&&!handingOff)return "前一动作收势未结束，等待左臂附件归还";
        ZetaRifleModes rifle=ZetaRifleModes.instance(ship);if(pose==null&&rifle!=null&&!Set.of("IDLE","COOLDOWN").contains(String.valueOf(rifle.status().get("phase"))))return "步枪动作尚未归还";
        return "";
    }
    private float aimAngle(){Vector2f to=followup!=null&&!followup.terminal()?followup.worldPoint():null;if(to==null&&engine.getPlayerShip()==ship&&ship.getMouseTarget()!=null&&Vector2f.sub(ship.getMouseTarget(),ship.getLocation(),null).lengthSquared()>1)to=ship.getMouseTarget();ShipAPI target=ship.getShipTarget();if(to==null&&target!=null&&target.isAlive()&&!target.isHulk())to=target.getLocation();if(to==null)return ship.getFacing();return angleFromShip(to);}
    private float angleFromShip(Vector2f point){return(float)Math.toDegrees(Math.atan2(point.y-ship.getLocation().y,point.x-ship.getLocation().x));}
    private float halfArc(){return Math.max(1,Math.min(47.5f,blade.getSlot().getArc()*.5f));}
    /** Native charging: first turn torso left while drawing the blade right, then flow into
     * the existing right-side thrust. Endpoints stay inside both the real slot and shoulder arcs. */
    public static Frame preparation(float charge,float side,float aim,float halfArc,float start){
        if(!finite(charge,side,aim,halfArc,start)||halfArc<=0||halfArc>47.5f||side>0||side< -BODY_SIDE)throw new IllegalArgumentException("Finite native saber preparation required");
        float c=clamp(charge),target=clampAngle(aim,halfArc),draw=clampAngle(target-DRAW_BLADE,halfArc);
        float body=Math.min(DRAW_BODY,Math.max(0,MAX_SHOULDER_RELATIVE+draw));
        if(c<DRAW_FRACTION){float q=smooth(c/DRAW_FRACTION);return new Frame(Stage.DRAW,body*q,mix(clampAngle(start,halfArc),draw,q),c);}
        float q=smooth((c-DRAW_FRACTION)/(1-DRAW_FRACTION));return new Frame(Stage.WARMUP,mix(body,side,q),mix(draw,target,q),c);
    }
    /** Front stab, torso-led right cut, then a longer BODY-led left sweep and short final hold. */
    public static Frame sequence(float progress,float side,float aim,float halfArc){
        if(!finite(progress,side,aim,halfArc)||halfArc<=0||halfArc>47.5f||side>0||side< -BODY_SIDE)throw new IllegalArgumentException("Finite original saber articulation required");float p=clamp(progress),start=clampAngle(aim,halfArc);
        if(p<.22f)return new Frame(Stage.STAB,side,start,p);
        if(p<.57f){float q=smooth((p-.22f)/.35f);return new Frame(Stage.RIGHT_CUT,side*(1-q),mix(start,-halfArc,q),p);}
        if(p<.63f)return new Frame(Stage.RIGHT_CUT,0,-halfArc,p);
        float q=smooth((p-.63f)/(FINAL_SWEEP_END-.63f));return new Frame(Stage.LEFT_SLASH,FINAL_LEFT_BODY*q,mix(-halfArc,halfArc,q),p);
    }
    private void setStage(Stage next){if(stage!=next){stage=next;phaseLocal=0;}}
    private void suppress(){blade.setForceNoFireOneFrame(true);blade.stopFiring();}
    private boolean dark(){return blade.getBeams().stream().noneMatch(b->b!=null&&b.getBrightness()>.05f);}
    private boolean validDamage(){return !closed&&pose!=null&&ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY)==this&&blocked().isEmpty();}
    static boolean nativeBladeVisible(WeaponAPI weapon){ZetaSaberMotion own=instance(weapon==null?null:weapon.getShip());return own!=null&&weapon==own.blade&&own.validDamage();}
    /** The original Moci delegate keeps one per-target paid-damage map for the whole burst.
     * Expose a cumulative ceiling, rather than spending its entire budget on the first stab.
     * Missed early contacts reserve nothing: the final real contact can still pay the full
     * original budget. This never adds damage or resets the delegate's private records. */
    public static float cumulativeDamageFraction(Stage phase){return switch(Objects.requireNonNull(phase)){case STAB->.25f;case RIGHT_CUT->.60f;case LEFT_SLASH->1f;default->0f;};}
    // ===== Moci_BladeMotion 实现：我方插件查询这些方法，而不是被本类包裹 =====
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon){
        if(weapon!=blade)return;nativeStep(amount);reassert();originalWeaponCalls++;
    }
    @Override public void beamFrame(CombatEngineAPI engine,BeamAPI beam){
        if(beam==null||beam.getWeapon()!=blade)return;
        if(renderer==null){renderer=new ZetaSaberBladeRenderer(engine,blade);engine.addLayeredRenderingPlugin(renderer);}
        renderer.observeAndHide(beam);reassert();
        if(validDamage())originalBeamCalls++;else mutedCallbacks++;
    }
    @Override public void lateReassert(WeaponAPI weapon){if(weapon==blade)reassert();}
    @Override public boolean ownsAim(WeaponAPI weapon){return weapon==blade&&pose!=null;}
    @Override public boolean isActionActive(WeaponAPI weapon){return weapon==blade&&validDamage();}
    @Override public float damageBudgetFraction(WeaponAPI weapon){return weapon==blade&&validDamage()?cumulativeDamageFraction(stage):0f;}
    @Override public int rayPhase(WeaponAPI weapon){return weapon==blade&&validDamage()&&lightSeen&&cumulativeDamageFraction(stage)>0f?1:0;}
    @Override public float collisionProgress(WeaponAPI weapon){return weapon==blade?progress:0f;}
    /** Pure shared bone reconciliation: never advances either original callback or a second clock. */
    public static void reassert(WeaponAPI weapon){
        // Another action may own the shared left-hand attachment even when this saber has no pose.
        // Its original callback must not pull the small visible handle back to the unposed wrist.
        LeftArmAttachments.apply(weapon);
        ZetaSaberMotion own=instance(weapon==null?null:weapon.getShip());if(own!=null)own.reassert();
    }
    private void reassert(){if(pose==null||ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY)!=this)return;ZetaTetherPose.apply(blade);if(follow!=null){follow.setForceNoFireOneFrame(true);follow.stopFiring();ZetaTetherPose.apply(follow);}WeaponAPI arm=ZetaRifleMount.slot(ship,"ARM_L");if(arm!=null)ZetaTetherPose.apply(arm);WeaponAPI cover=ZetaRifleMount.slot(ship,"SHIELD_L_COVER");if(cover!=null)ZetaTetherPose.apply(cover);}
    public void cancel(String why){requireThread();if(closed)return;boolean request=followup!=null&&!followup.terminal();if(pose!=null||request){cancellations++;releasePose();waitingDark=true;}if(request){followup.state=NativeFollowupState.CANCELLED;followup.detail=why;}stage=Stage.IDLE;phaseLocal=0;reason=why;publish();}
    private void releasePose(){try{if(pose!=null)pose.close();}finally{pose=null;if(ship.getCustomData().get(FLAG_OWNER)==this){if(Boolean.TRUE.equals(ship.getCustomData().get(SWING))){if(oldFlagPresent)ship.setCustomData(SWING,oldFlag);else ship.removeCustomData(SWING);}ship.removeCustomData(FLAG_OWNER);}if(ship.getCustomData().get(ZetaRifleModes.ARM_ACTION_KEY)==this)ship.removeCustomData(ZetaRifleModes.ARM_ACTION_KEY);}}
    public Map<String,Object> status(){Map<String,Object> r=new LinkedHashMap<>();r.put("closed",closed);r.put("stage",stage.name());r.put("active",pose!=null);r.put("clockOwner","native ARM_L_SOURCE weapon callback only; Actor never advances");r.put("weaponSteps",weaponSteps);r.put("cycleLocal",cycleLocal);r.put("phaseLocal",phaseLocal);r.put("burstProgress",progress);r.put("nativeBursts",nativeBursts);r.put("nativeFollowup",followup==null?Map.of():followup.status());r.put("originalWeaponCallbacks",originalWeaponCalls);r.put("originalBeamCallbacks",originalBeamCalls);r.put("mutedCallbacks",mutedCallbacks);r.put("cancellations",cancellations);r.put("bodyDegrees",lastBody);r.put("recoveryProgress",recoveryProgress);r.put("recoveryStartBody",recoverBody);r.put("recoveryStartBlade",recoverBlade);r.put("restBlade",startBlade);r.put("finalLeftBody",FINAL_LEFT_BODY);r.put("finalSweepEnd",FINAL_SWEEP_END);r.put("bladeRelative",lastBlade);r.put("sourceAngle",blade.getCurrAngle());r.put("headAim",headAim);r.put("firepoint",point(blade.getFirePoint(0)));r.put("nativeCharge",blade.getChargeLevel());r.put("nativeChargeup",blade.getSpec().getBeamChargeupTime());r.put("drawFraction",DRAW_FRACTION);r.put("nativeBurstRemaining",blade.getBurstFireTimeRemaining());r.put("nativeBurstDuration",blade.getSpec().getBurstDuration());r.put("nativeArc",blade.getSlot().getArc());r.put("reason",reason);r.put("pose",pose==null?Map.of():pose.status());r.put("damageBudgetFraction",cumulativeDamageFraction(stage));r.put("damageBudgetPolicy","Original per-target cumulative ceiling: STAB25%, RIGHT_CUT60%, LEFT_SLASH100%; no extra damage");r.put("damageScope","One authored native1.1-second beam burst; original Moci per-target settlement pays only each stage ceiling minus damage already paid; original total DPS/EMP/range unchanged");return Collections.unmodifiableMap(r);}
    private void publish(){if(followup!=null&&!followup.terminal()){followup.state=pose==null?NativeFollowupState.WAITING_NATIVE:stage==Stage.RECOVERY?NativeFollowupState.RECOVERING:lightSeen?NativeFollowupState.STRIKING:NativeFollowupState.PREPARING;followup.detail=reason;}ship.setCustomData(KEY,status());}
    private void requireThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Saber motion requires native battle thread");}
    @Override public void close(){requireThread();if(closed)return;if(renderer!=null){renderer.release();renderer=null;}Moci_BladeMotionRegistry.unregister(blade,this);if(followup!=null&&!followup.terminal()){followup.state=NativeFollowupState.CANCELLED;followup.detail="军刀控制器已关闭";}releasePose();closed=true;stage=Stage.CLOSED;if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");publish();}
    private static Vector2f rotate(Vector2f point,float degrees){double r=Math.toRadians(degrees);return new Vector2f((float)(point.x*Math.cos(r)-point.y*Math.sin(r)),(float)(point.x*Math.sin(r)+point.y*Math.cos(r)));}
    private static float clamp(float n){return Float.isFinite(n)?Math.max(0,Math.min(1,n)):0;}private static float smooth(float n){float t=clamp(n);return t*t*(3-2*t);}private static float mix(float a,float b,float t){return a+(b-a)*t;}private static float shortest(float a){return((a%360)+540)%360-180;}private static float clampAngle(float n,float half){return Math.max(-half,Math.min(half,n));}private static boolean finite(float... values){for(float v:values)if(!Float.isFinite(v))return false;return true;}private static List<Float> point(Vector2f p){return p==null?List.of():List.of(p.x,p.y);}
}
