package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import java.awt.Color;
import java.util.*;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

/** Ship-local hand ownership. The installed gun is never removed, repaired or replenished.
 * The one existing psychoframe clock advances this object; native weapon callbacks only gate/pose.
 * A dropped gun has an independent world pose and remains unavailable until an actual close pickup.
 */
public final class HiNuHandCannon implements AutoCloseable {
    public static final String KEY="xi.arsenal.hinu.hand", ARM_ID="Moci_fw_hinu_hand_arm",
            CANNON_ID="Moci_fw_hinu_hand_cannon", CANNON_SLOT="ARM_R_MAIN";
    public static final float PICKUP_RANGE=HiNuPickupPose.CONTACT_TOLERANCE, THROW_SECONDS=.55f, DEPLOY_SECONDS=1.4f,
            RETRACT_SECONDS=1.2f, PICKUP_SECONDS=1.8f, ACTIVE_SECONDS=60f,
            CHARGE_SECONDS=3f, BEAM_SECONDS=2.2f;
    public enum Phase { ATTACHED, THROWING, EMPTY, DEPLOYING, READY, CHARGING, RELEASING, FIRING, RECOVERING, RETRACTING, PICKING_UP, CLOSED }
    public record Request(boolean accepted,String reason){}
    private final ShipAPI ship;
    private final CombatEngineAPI engine;
    private final WeaponAPI arm,held,beam;
    private final HiNuHandGroups groups;
    private final HiNuPsychoframeCharge charge;
    private HiNuPickupPose pickupPose;
    private HiNuCannonChargePose cannonPose;
    private final IdentityHashMap<SpriteAPI,Color> hidden=new IdentityHashMap<>();
    private final Map<String,SpriteAPI> nativeSprites=new HashMap<>();
    private final HiNuHandHud hud=new HiNuHandHud();
    private final List<Part> parts=new ArrayList<>();
    private final Vector2f drop=new Vector2f(),velocity=new Vector2f(),contactOrigin=new Vector2f(),contactVelocity=new Vector2f();
    private Phase phase=Phase.ATTACHED;
    private float timer,throwStartAngle,dropAngle,aimAngle,retractStart=17,activeRemaining,heldCharge,releaseWait,contactTime,contactAngle,pickupSeamError;
    private int frame,detachCount,pickupCount,activationShots,totalShots;
    private long nativeBeamFrames;
    private boolean dropped,fireHeld,closed,beamConfirmed,partsLoaded,localInputFire,pickupTouched,recoverToReady,shotSpent;
    private String reason="右手持枪";

    public static boolean supports(ShipAPI ship){return ship!=null&&find(ship,"ARM_R",ARM_ID)!=null&&find(ship,CANNON_SLOT,CANNON_ID)!=null;}
    public static HiNuHandCannon instance(ShipAPI ship){Object o=ship==null?null:ship.getCustomData().get(KEY+".owner");return o instanceof HiNuHandCannon c&&!c.closed?c:null;}
    public HiNuHandCannon(ShipAPI ship){this(ship,null);}
    HiNuHandCannon(ShipAPI ship,HiNuHandGroups.Access groupAccess){
        this.ship=Objects.requireNonNull(ship);engine=Objects.requireNonNull(Global.getCombatEngine());
        arm=Objects.requireNonNull(find(ship,"ARM_R",ARM_ID),"New ordinary Hi-Nu arm required");
        beam=Objects.requireNonNull(find(ship,CANNON_SLOT,CANNON_ID),"Native hand cannon required");
        held=find(ship,"ARM_R_SOURCE",null);aimAngle=arm.getCurrAngle();
        if(ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Hi-Nu hand already owned");
        beam.ensureClonedSpec();
        // Native BaseWeaponSpec.clone copies lists but shares their mutable vectors across ships.
        detachOffsets(beam.getSpec().getHiddenFireOffsets());detachOffsets(beam.getSpec().getTurretFireOffsets());detachOffsets(beam.getSpec().getHardpointFireOffsets());
        groups=groupAccess==null?new HiNuHandGroups(ship,held,beam):new HiNuHandGroups(ship,held,beam,groupAccess);
        charge=new HiNuPsychoframeCharge(engine,ship);
        ship.setCustomData(KEY+".owner",this);guard();publish();
    }
    public HiNuPsychoframeCharge charge(){return charge;}
    private static WeaponAPI find(ShipAPI ship,String slot,String id){for(WeaponAPI w:ship.getAllWeapons())if(w!=null&&w.getSlot()!=null&&slot.equals(w.getSlot().getId())&&(id==null||id.equals(w.getId())))return w;return null;}
    private boolean live(){return ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()&&engine.isEntityInPlay(ship)&&Global.getCombatEngine()==engine;}
    private String controlBlock(){
        if(closed||!live())return "机体或战场失效";
        if(engine.isPaused()||engine.isUIShowingDialog()||ship.controlsLocked())return "当前控制已暂停";
        if(engine.getCombatUI()!=null&&(engine.getCombatUI().isShowingCommandUI()||engine.getCombatUI().isShowingDeploymentDialog()))return "战术界面已打开";
        return "";
    }
    private String fireBlock(){String b=controlBlock();if(!b.isEmpty())return b;
        if(ship.getFluxTracker()!=null&&ship.getFluxTracker().isOverloadedOrVenting())return "过载或排幅中";
        if(beam.isDisabled()||beam.isForceDisabled())return "大炮受损或禁用";
        if(ship.isHoldFire()||ship.isHoldFireOneFrame())return "停火中";
        return "";
    }
    public Request requestDeploy(){
        String block=controlBlock();if(!block.isEmpty())return no(block);
        if(phase!=Phase.ATTACHED&&phase!=Phase.EMPTY)return no("右臂正在执行动作");
        if(!charge.ready())return no("精神感应框架尚未激活完成");
        if(!groups.cannonSelected())return no("请选择呼唤重炮武装组");
        if(held!=null&&phase==Phase.ATTACHED){
            // A previously autofiring hand group must cease firing when the earned summon is selected.
            held.setForceNoFireOneFrame(true);held.stopFiring();
            if(held.isInBurst()||held.isFiring()||!dark(held))return no("等待手持武器本次射击结束");
            throwStartAngle=held.getCurrAngle();aimAngle=throwStartAngle;enter(Phase.THROWING,"向右甩出手持武器");
        }else enter(Phase.DEPLOYING,"空手展开大炮");
        charge.consume();charge.setSuspended(true);activeRemaining=0;
        beam.setAmmo(1);activationShots=0;shotSpent=false;heldCharge=0;releaseWait=0;
        fireHeld=false;beamConfirmed=false;guard();publish();return yes();
    }
    public Request requestRetract(){
        if(closed)return no("右臂机制已关闭");
        if(phase==Phase.ATTACHED||phase==Phase.EMPTY)return no("大炮已经收起");
        if(phase==Phase.PICKING_UP)return no("正在拾取武器");
        fireHeld=false;activeRemaining=0;beam.stopFiring();groups.hideCannon();
        if(cannonPose!=null){recoverToReady=false;enter(Phase.RECOVERING,"重炮收势，随后撤出");guard();publish();return yes();}
        if(phase==Phase.THROWING){frame=0;enter(dropped?Phase.EMPTY:Phase.ATTACHED,"取消甩手");restore();}
        else if(phase!=Phase.RETRACTING){retractStart=frame;enter(Phase.RETRACTING,"等待光束淡出，逆序收炮");}
        guard();publish();return yes();
    }
    public Request requestPickup(){
        String block=controlBlock();if(!block.isEmpty())return no(block);
        if(phase!=Phase.EMPTY||frame!=0||!dropped||held==null)return no("先收起大炮并保持空手");
        Vector2f palm=HiNuGripAnchors.palmLocal(arm,held),grip=dropGrip();
        float radial=Math.abs(Vector2f.sub(grip,HiNuGripAnchors.renderOrigin(arm),null).length()-palm.length());
        if(radial>HiNuPickupPose.CONTACT_TOLERANCE)return no("继续靠近握柄，等待进入右手可触范围");
        pickupTouched=false;pickupSeamError=0;pickupPose=new HiNuPickupPose(ship,arm,PICKUP_SECONDS);enter(Phase.PICKING_UP,"向左侧身，旋转右臂对准握柄");guard();publish();return yes();
    }
    /** Trusted input adapters share this path with the native SHIP_FIRE down/up events. */
    public Request observeFire(boolean down){
        if(!down){fireHeld=false;
            if(phase==Phase.CHARGING){
                if(heldCharge+1e-4f<CHARGE_SECONDS)beginRecovery(true,"蓄力不足，平稳收势后可重试");
                else {heldCharge=CHARGE_SECONDS;releaseWait=0;enter(Phase.RELEASING,"蓄满释放，等待真实原生首亮");}
            }guard();publish();return yes();}
        if(phase==Phase.CHARGING){fireHeld=true;return yes();}
        if(shotSpent||activationShots>0)return no("本次重炮唯一一发已经使用");
        if(phase!=Phase.READY)return no("大炮尚未就绪");
        if(!groups.cannonSelected()||activeRemaining<=0)return no("未选择可用的重炮武装组");
        String block=fireBlock();if(!block.isEmpty())return no(block);
        if(beam.getCooldownRemaining()>.001f&&!beam.isFiring())return no("大炮原生冷却中");
        fireHeld=true;heldCharge=0;cannonPose=new HiNuCannonChargePose(ship,arm);cannonPose.setAim(aimAngle);enter(Phase.CHARGING,"按住蓄力3秒，松手释放决战重炮");guard();publish();return yes();
    }
    public void advance(float dt){
        if(closed)return;if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid Hi-Nu hand dt");
        if(!live()||engine.isCombatOver()){close();return;}
        if(localInputFire&&engine.getPlayerShip()!=ship){localInputFire=false;observeFire(false);}
        if(engine.isPaused())return;
        charge.setSuspended(phase!=Phase.ATTACHED&&phase!=Phase.EMPTY&&phase!=Phase.PICKING_UP);
        charge.advance(dt);
        if((phase==Phase.ATTACHED||phase==Phase.EMPTY)&&charge.ready())groups.showCannon();
        if((phase==Phase.ATTACHED||phase==Phase.EMPTY)&&charge.ready()&&groups.cannonSelected())requestDeploy();
        if(dropped&&!pickupTouched){float decay=(float)Math.exp(-.3f*dt);float travel=(1-decay)/.3f;drop.x+=velocity.x*travel;drop.y+=velocity.y*travel;velocity.scale(decay);}
        observeNativeLight();
        if(!dark(beam))nativeBeamFrames++;
        timer+=dt;
        // The one-minute opportunity is combat time since full deployment,
        // including cancelled charges and their recovery. A confirmed native
        // shot keeps its own illumination/recovery lifetime to finish naturally.
        if(!shotSpent&&(phase==Phase.READY||phase==Phase.CHARGING||phase==Phase.RELEASING||phase==Phase.RECOVERING)){
            activeRemaining=Math.max(0,activeRemaining-dt);
            if(activeRemaining<=0&&(phase!=Phase.RECOVERING||recoverToReady))requestRetract();
        }
        if(phase==Phase.READY&&activeRemaining>0&&(!groups.cannonSelected()||!fireBlock().isEmpty()))observeFire(false);
        switch(phase){
            case THROWING -> {
                float t=Math.min(1,timer/THROW_SECONDS),s=t*t*(3-2*t);
                aimAngle=throwStartAngle+shortest(throwStartAngle,ship.getFacing()-78f)*s;
                if(timer>=THROW_SECONDS&&detach())enter(Phase.DEPLOYING,"武器已脱手，展开大炮");
            }
            case DEPLOYING -> {frame=Math.min(17,(int)(timer/DEPLOY_SECONDS*18));aim();if(timer>=DEPLOY_SECONDS){frame=17;activeRemaining=ACTIVE_SECONDS;enter(Phase.READY,"决战重炮就绪，仅1发；按住蓄力、松开发射");}}
            case READY -> {frame=17;aim();}
            case CHARGING -> {
                frame=17;aim();heldCharge=Math.min(CHARGE_SECONDS,heldCharge+dt);cannonPose.setAim(aimAngle);cannonPose.advance(dt,HiNuCannonChargePose.Stage.CHARGING);
                if(!groups.cannonSelected()||!fireBlock().isEmpty())beginRecovery(true,"蓄力被控制状态中止，平稳收势");
            }
            case RELEASING -> {
                frame=17;aim();releaseWait+=dt;cannonPose.setAim(aimAngle);cannonPose.advance(dt,HiNuCannonChargePose.Stage.CHARGING);
                if(!fireBlock().isEmpty()||!groups.cannonSelected()||releaseWait>1.5f)beginRecovery(true,"原生开火未确认，未消耗唯一弹药");
            }
            case FIRING -> {
                frame=17;aim();cannonPose.setAim(aimAngle);cannonPose.advance(dt,HiNuCannonChargePose.Stage.FIRING);
                if(timer>BEAM_SECONDS+1f||timer>.15f&&dark(beam)&&!beam.isInBurst())beginRecovery(false,"唯一一发照射结束，收势撤炮");
            }
            case RECOVERING -> {
                frame=17;cannonPose.setAim(aimAngle);cannonPose.advance(dt,HiNuCannonChargePose.Stage.RECOVER);
                if(cannonPose.recovered()&&dark(beam)){
                    aimAngle=cannonPose.aimAngle();cannonPose.close();cannonPose=null;
                    if(recoverToReady&&!shotSpent){heldCharge=0;enter(Phase.READY,"已收稳，可重新蓄力唯一一发");}
                    else {activeRemaining=0;groups.hideCannon();retractStart=frame;enter(Phase.RETRACTING,"重炮已用尽，逆序收回");}
                }
            }
            case RETRACTING -> {
                if(!dark(beam)){timer=0;break;}
                frame=Math.max(0,(int)Math.ceil(retractStart*(1-Math.min(1,timer/RETRACT_SECONDS))));
                if(timer>=RETRACT_SECONDS){frame=0;charge.setSuspended(false);enter(dropped||held==null?Phase.EMPTY:Phase.ATTACHED,"大炮已收起，靠近丢枪自动拾取");}
            }
            case EMPTY -> {frame=0;if(dropped&&gripRadialError()<=PICKUP_RANGE)requestPickup();}
            case PICKING_UP -> {
                float t=Math.min(1,timer/PICKUP_SECONDS);
                if(!pickupTouched&&t>=HiNuPickupPose.REACH_END){
                    pickupPose.apply(t,dropGrip());
                    if(!pickupPose.reachReady()){pickupPose.close();pickupPose=null;enter(Phase.EMPTY,"握柄离开手掌可触范围，继续靠近");break;}
                    pickupTouched=true;contactTime=timer;contactOrigin.set(drop);contactVelocity.set(velocity);contactAngle=dropAngle;
                }
                if(timer>=PICKUP_SECONDS){
                    applyPickupPose();
                    pickupSeamError=Vector2f.sub(drop,HiNuGripAnchors.renderOrigin(held),null).length();
                    pickupPose.close();pickupPose=null;dropped=false;parts.clear();partsLoaded=false;restore();
                    pickupCount++;frame=0;boolean restored=groups.restoreHand();enter(Phase.ATTACHED,restored?"原武器与原武器组已恢复":"原武器已拾回；外部分组改动保留，未覆盖");
                }
            }
            default -> {frame=0;}
        }
        guard();applyArmPose();publish();
    }
    private boolean detach(){
        if(held==null||dropped)return true;
        if(!groups.detachHand()){groups.hideCannon();enter(Phase.ATTACHED,"原武器组已被外部改动，取消甩出");return false;}
        drop.set(HiNuGripAnchors.renderOrigin(held));dropAngle=held.getCurrAngle()-90f;
        Vector2f v=ship.getVelocity();velocity.set(v==null?0:v.x,v==null?0:v.y);
        double right=Math.toRadians(ship.getFacing()-90);velocity.x+=(float)Math.cos(right)*115;velocity.y+=(float)Math.sin(right)*115;
        dropped=true;pickupTouched=false;detachCount++;hide();return true;
    }
    private void aim(){Vector2f target=ship==engine.getPlayerShip()?ship.getMouseTarget():ship.getShipTarget()==null?null:ship.getShipTarget().getLocation();aimAngle=target==null?ship.getFacing():angle(arm.getLocation(),target,ship.getFacing());}
    private static float angle(Vector2f from,Vector2f to,float fallback){if(from==null||to==null||Vector2f.sub(to,from,null).lengthSquared()<1)return fallback;return (float)Math.toDegrees(Math.atan2(to.y-from.y,to.x-from.x));}
    private static float shortest(float from,float to){return ((to-from+540)%360+360)%360-180;}
    private void enter(Phase next,String why){phase=next;timer=0;reason=why;}
    private Request yes(){return new Request(true,reason);}
    private Request no(String why){reason=why;publish();return new Request(false,why);}
    private void beginRecovery(boolean ready,String why){fireHeld=false;beam.stopFiring();recoverToReady=ready&&!shotSpent;if(!recoverToReady){activeRemaining=0;groups.hideCannon();}enter(Phase.RECOVERING,why);}
    private void observeNativeLight(){if(phase!=Phase.RELEASING||dark(beam))return;if(shotSpent)throw new IllegalStateException("Duplicate Hi-Nu native shot");shotSpent=true;activationShots++;totalShots++;beamConfirmed=true;fireHeld=false;enter(Phase.FIRING,"决战重炮：唯一原生照射已确认");}
    private boolean holdsGun(){return phase==Phase.ATTACHED||phase==Phase.THROWING;}
    private static boolean dark(WeaponAPI w){List<BeamAPI> beams=w.getBeams();return beams==null||beams.stream().noneMatch(b->b.getBrightness()>.001f);}
    private void guard(){
        if(held!=null&&phase!=Phase.ATTACHED){held.setForceNoFireOneFrame(true);held.stopFiring();}
        if(!canFire())beam.setForceNoFireOneFrame(true);
        if(!holdsGun()&&held!=null)hide();
    }
    // Native barrel/under/glow getters create a fresh API wrapper on EVERY call.
    // Retain one wrapper per actual layer/texture so the original alpha is not lost after hiding.
    private SpriteAPI stable(String layer,SpriteAPI s){if(s==null)return null;return nativeSprites.computeIfAbsent(layer+"/"+s.getTextureId(),k->s);}
    private List<SpriteAPI> sprites(){if(held==null)return List.of();List<SpriteAPI> r=new ArrayList<>();for(SpriteAPI s:new SpriteAPI[]{stable("base",held.getSprite()),stable("barrel",held.getBarrelSpriteAPI()),stable("under",held.getUnderSpriteAPI()),stable("glow",held.getGlowSpriteAPI())})if(s!=null&&!r.contains(s))r.add(s);return r;}
    private void hide(){for(SpriteAPI s:sprites()){Color c=s.getColor();if(!hidden.containsKey(s))hidden.put(s,c);if(c.getAlpha()!=0)s.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),0));}}
    private void restore(){for(var e:hidden.entrySet()){Color c=e.getKey().getColor(),o=e.getValue();if(c.getAlpha()==0&&c.getRed()==o.getRed()&&c.getGreen()==o.getGreen()&&c.getBlue()==o.getBlue())e.getKey().setColor(o);}hidden.clear();}
    /** Called only by the inherited arm's native after callback; never advances a clock. */
    public void applyArmPose(){
        if(closed)return;
        if(arm.getAnimation()!=null){arm.getAnimation().pause();arm.getAnimation().setFrame(frame);}
        if(phase!=Phase.ATTACHED){arm.setCurrAngle(aimAngle);if(held!=null)held.setCurrAngle(aimAngle);}
        if(phase==Phase.PICKING_UP&&pickupPose!=null)applyPickupPose();
        if(cannonPose!=null){cannonPose.setAim(aimAngle);cannonPose.applyAll();}
        if(!holdsGun())hide();alignMuzzle();
    }
    private Vector2f dropGrip(){return HiNuGripAnchors.gripWorld(HiNuGripAnchors.profile(held),HiNuGripAnchors.nativePart(held),drop,dropAngle);}
    private float gripRadialError(){return Math.abs(Vector2f.sub(dropGrip(),HiNuGripAnchors.renderOrigin(arm),null).length()-HiNuGripAnchors.palmLocal(arm,held).length());}
    /** Free gun remains the visible object until the final native carry pose matches it. */
    private void applyPickupPose(){
        float t=Math.min(1,timer/PICKUP_SECONDS);
        if(!pickupTouched){pickupPose.apply(t,dropGrip());return;}
        float elapsed=Math.max(0,timer-contactTime),travel=(1-(float)Math.exp(-.3f*elapsed))/.3f;
        Vector2f baseline=new Vector2f(contactOrigin.x+contactVelocity.x*travel,contactOrigin.y+contactVelocity.y*travel);
        Vector2f baselineGrip=HiNuGripAnchors.gripWorld(HiNuGripAnchors.profile(held),HiNuGripAnchors.nativePart(held),baseline,contactAngle);
        pickupPose.apply(t,baselineGrip);
        float blend=smoother((t-HiNuPickupPose.REACH_END)/(HiNuPickupPose.RETURN_START-HiNuPickupPose.REACH_END));
        Vector2f hand=pickupPose.handWorld();
        Vector2f grip=new Vector2f(baselineGrip.x+(hand.x-baselineGrip.x)*blend,baselineGrip.y+(hand.y-baselineGrip.y)*blend);
        float target=held.getCurrAngle()-90f;
        dropAngle=contactAngle+shortest(contactAngle,target)*blend;
        drop.set(HiNuGripAnchors.originForGrip(HiNuGripAnchors.profile(held),HiNuGripAnchors.nativePart(held),grip,dropAngle));
    }
    private static float smoother(float x){float t=Math.max(0,Math.min(1,x));return t*t*t*(t*(t*6-15)+10);}
    private void alignMuzzle(){
        float actualAngle=cannonPose==null?aimAngle:cannonPose.aimAngle();beam.setCurrAngle(actualAngle);
        // Frame17 nozzle is (128,67) in the unscaled 208x540 canvas: right24, forward203.
        double a=Math.toRadians(actualAngle);float c=(float)Math.cos(a),s=(float)Math.sin(a);
        Vector2f pivot=arm.getLocation(),origin=beam.getLocation();
        Vector2f shift=cannonPose==null?new Vector2f():cannonPose.translation();
        float dx=pivot.x+shift.x+203*c+24*s-origin.x,dy=pivot.y+shift.y+203*s-24*c-origin.y;
        float forward=dx*c+dy*s,left=-dx*s+dy*c;
        setOffsets(beam.getSpec().getHiddenFireOffsets(),forward,left);
        setOffsets(beam.getSpec().getTurretFireOffsets(),forward,left);
        setOffsets(beam.getSpec().getHardpointFireOffsets(),forward,left);beam.updateBeamFromPoints();
    }
    private static void setOffsets(List<Vector2f> offsets,float x,float y){if(offsets!=null)for(Vector2f p:offsets)p.set(x,y);}
    private static void detachOffsets(List<Vector2f> offsets){if(offsets!=null)for(int i=0;i<offsets.size();i++)offsets.set(i,new Vector2f(offsets.get(i)));}
    /** Native beam callback is the only producer of forced native firing; no projectile/damage emulation. */
    // 控制器缺失时走统一判据：Moci_fw_hinu_hand_cannon 在 skins/Moci_fw_hinu_hand.skin 里被
    // weaponSlotChanges 改成 mount HIDDEN（ARM_R_MAIN），AI 无法原生开火、唯一触发源是本控制器，
    // 因此判据结论仍是「保留禁火」——这条路径不会让任何真实武器哑火，只是把无害安全网写明来由。
    // 判据见 XiWeaponControlOwnership。
    public static void beforeCannon(WeaponAPI weapon){HiNuHandCannon own=instance(weapon.getShip());if(own==null){XiWeaponControlOwnership.settleWithoutController(weapon);return;}
        own.alignMuzzle();if(!own.canFire())weapon.setForceNoFireOneFrame(true);
        else if(own.phase==Phase.RELEASING&&!weapon.isForceNoFireOneFrame())weapon.setForceFireOneFrame(true);
    }
    public static void afterCannon(WeaponAPI weapon){HiNuHandCannon own=instance(weapon.getShip());if(own!=null){own.observeNativeLight();own.applyArmPose();}}
    private boolean canFire(){return !closed&&groups.cannonSelected()&&fireBlock().isEmpty()&&
            (phase==Phase.RELEASING&&!shotSpent&&heldCharge>=CHARGE_SECONDS&&cannonPose!=null&&cannonPose.readyForBeam()||phase==Phase.FIRING&&shotSpent&&(beam.isInBurst()||!dark(beam)));}
    public void processInput(List<InputEventAPI> events){
        if(closed||engine.getPlayerShip()!=ship||events==null)return;
        // Releases remain meaningful while paused/in a dialog. Dropping them could restart a beam on resume.
        for(InputEventAPI event:events)if(event!=null&&!event.isRepeat()&&event.isControlUpEvent("SHIP_FIRE")){
            localInputFire=false;observeFire(false);
        }
        if(!controlBlock().isEmpty())return;
        for(InputEventAPI event:events){if(event==null||event.isConsumed()||event.isRepeat())continue;
            if(groups.cannonSelected()&&event.isControlDownEvent("SHIP_FIRE"))localInputFire=observeFire(true).accepted();
            if(event.isControlUpEvent("SHIP_FIRE")){localInputFire=false;observeFire(false);}
        }
    }
    public void present(float dt){if(engine.getPlayerShip()!=ship||closed)return;
        engine.maintainStatusForPlayerShip(KEY+".hud","graphics/icons/hullsys/temporal_shell.png","海牛右臂 · 呼唤重炮武装",reason+(dropped?String.format(Locale.ROOT," · 原武器 %.0f 距离",distance()):""),false);
    }
    public void renderHud(){if(!closed&&engine.getPlayerShip()==ship&&engine.isUIShowingHUD()&&!engine.isUIShowingDialog())hud.render(ship,charge,groups,activeRemaining,heldCharge,phase,shotSpent,reason);}
    private static final class Part {final SpriteAPI sprite;Part(SpriteAPI sprite){this.sprite=sprite;}}
    private void loadParts(){
        if(partsLoaded||held==null)return;partsLoaded=true;
        try{
            JSONObject json=Global.getSettings().loadJSON("data/weapons/"+held.getId()+".wpn");
            String[] names={"turretUnderSprite","turretSprite","turretGunSprite","turretGlowSprite"};
            SpriteAPI[] nativeParts={stable("under",held.getUnderSpriteAPI()),stable("base",held.getSprite()),stable("barrel",held.getBarrelSpriteAPI()),stable("glow",held.getGlowSpriteAPI())};
            for(int i=0;i<names.length;i++){String path=json.optString(names[i],"");SpriteAPI original=nativeParts[i];if(path.isEmpty()||original==null)continue;
                SpriteAPI sprite=Global.getSettings().getSprite(path);sprite.setSize(original.getWidth(),original.getHeight());sprite.setCenter(original.getCenterX(),original.getCenterY());
                sprite.setColor(hidden.getOrDefault(original,original.getColor()));sprite.setNormalBlend();parts.add(new Part(sprite));}
        }catch(Exception e){throw new IllegalStateException("Cannot render the exact dropped right-hand weapon "+held.getId(),e);}
    }
    /** Rendering reads the same world object used by pickup. No animation/time/physics is advanced here. */
    public void render(){if(closed||!dropped)return;loadParts();for(Part p:parts){p.sprite.setAngle(dropAngle);p.sprite.setAlphaMult(1);p.sprite.renderAtCenter(drop.x,drop.y);}}
    private float distance(){return dropped?Vector2f.sub(drop,ship.getLocation(),null).length():0;}
    public Map<String,Object> status(){Map<String,Object> r=new LinkedHashMap<>();r.put("phase",phase.name());r.put("frame",frame);r.put("dropped",dropped);r.put("dropX",drop.x);r.put("dropY",drop.y);r.put("dropAngle",dropAngle);r.put("renderGunX",drop.x);r.put("renderGunY",drop.y);r.put("renderGunAngle",dropAngle);if(held!=null){Vector2f g=dropGrip();r.put("dropGripX",g.x);r.put("dropGripY",g.y);r.put("pickupRadius",HiNuGripAnchors.palmLocal(arm,held).length());}r.put("dropDistance",distance());r.put("pickupRange",PICKUP_RANGE);r.put("pickupSeamError",pickupSeamError);r.put("ammo",held==null?0:held.getAmmo());r.put("currentWeaponId",held==null?"":held.getId());r.put("detachCount",detachCount);r.put("pickupCount",pickupCount);r.put("beamConfirmed",beamConfirmed);r.put("nativeBeamFrames",nativeBeamFrames);r.put("heldCharge",heldCharge);r.put("chargeSeconds",CHARGE_SECONDS);r.put("activationShots",activationShots);r.put("totalShots",totalShots);r.put("shotSpent",shotSpent);r.put("cannonAmmo",beam.getAmmo());r.put("cannonPose",cannonPose==null?Map.of():cannonPose.status());r.put("activeRemaining",activeRemaining);r.put("charge",charge.status());r.put("groups",groups.status());r.put("pickupTouched",pickupTouched);r.put("pickupAttached",!dropped);r.put("pickupPose",pickupPose==null?Map.of():pickupPose.status());r.put("reason",reason);r.put("closed",closed);return Collections.unmodifiableMap(r);}
    private void publish(){ship.setCustomData(KEY,status());}
    @Override public void close(){if(closed)return;closed=true;fireHeld=false;beam.setForceNoFireOneFrame(true);beam.stopFiring();restore();dropped=false;parts.clear();phase=Phase.CLOSED;
        if(pickupPose!=null){pickupPose.close();pickupPose=null;}if(cannonPose!=null){cannonPose.close();cannonPose=null;}charge.close();groups.close();
        if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");publish();}
}
