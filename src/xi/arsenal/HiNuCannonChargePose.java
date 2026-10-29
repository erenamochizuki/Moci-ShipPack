package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.lwjgl.util.vector.Vector2f;

/** Hi-Nu adapter for the authored rifle-sniper skeleton action.
 * The hand controller owns the charge, native beam and only clock. This object
 * owns a single pose lease and never advances a Moci callback or adds a plugin.
 */
public final class HiNuCannonChargePose implements AutoCloseable {
    public static final float RECOVER_SECONDS=.65f;
    public static final float RECOIL_DEGREES=3.5f,RECOIL_ATTACK_SECONDS=.065f,RECOIL_RETURN_SECONDS=.42f;
    /** FIRING begins only after the native beam's first visible light. */
    public enum Stage { CHARGING,FIRING,RECOVER }

    private final Thread owner=Thread.currentThread();
    private final ShipAPI ship;
    private final WeaponAPI arm;
    private final AutoCloseable lease;
    private final float initialArmRelative;
    private Stage stage=Stage.CHARGING;
    private float nativeAim,chargingElapsed,firingElapsed,recoveryElapsed,recoveryRecoil;
    private boolean closed,firstLight;

    public HiNuCannonChargePose(ShipAPI ship,WeaponAPI visibleArm){
        this.ship=Objects.requireNonNull(ship,"Hi-Nu charge ship");
        arm=Objects.requireNonNull(visibleArm,"Hi-Nu visible cannon arm");
        if(arm.getShip()!=ship||arm.getSlot()==null||!"ARM_R".equals(arm.getSlot().getId()))
            throw new IllegalArgumentException("Hi-Nu charge requires its own real ARM_R bone");
        nativeAim=arm.getCurrAngle();initialArmRelative=shortest(nativeAim-ship.getFacing());
        lease=RifleFiringPose.attach(ship,new RifleReleaseRig(){
            public ShipAPI ship(){return HiNuCannonChargePose.this.ship;}
            public WeaponAPI source(){return arm;}
            public float presentationPower(){return 1f;}
            public boolean useQuinticMotionCurve(){return true;}
            public float recoverySeconds(){return RECOVER_SECONDS;}
            // Moci_MechBodyController_RX93.DEFAULT_FRAME_TARGET=4, nine real leg frames.
            public int standingLegFrame(){return 4;}
        });
        try{RifleFiringPose.advance(ship,RifleFiringPose.Stage.PRESENTING,0);applyAll();}
        catch(RuntimeException|Error failure){try{lease.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }

    /** Supply the unmodified target angle before applying the late arm pose. */
    public void setAim(float angle){requireOwner();if(!Float.isFinite(angle))throw new IllegalArgumentException("Invalid Hi-Nu cannon aim");nativeAim=angle;}

    public void advance(float dt,Stage wanted){
        requireOwner();Objects.requireNonNull(wanted,"Hi-Nu charge stage");
        if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid Hi-Nu charge pose step");
        if(closed)return;
        if(stage==Stage.RECOVER&&wanted!=Stage.RECOVER)throw new IllegalStateException("Recovering Hi-Nu pose cannot be restarted");
        if(firstLight&&wanted==Stage.CHARGING)throw new IllegalStateException("A fired Hi-Nu pose cannot restart its charge");
        if(wanted==Stage.RECOVER&&stage!=Stage.RECOVER)recoveryRecoil=stage==Stage.FIRING?recoilDegrees(firingElapsed):0;
        if(wanted==Stage.FIRING&&!firstLight){
            if(!readyForBeam())throw new IllegalStateException("Hi-Nu cannon illuminated before its sniper pose settled");
            RifleFiringPose.onNativeFirstLight(ship);firstLight=true;firingElapsed=0;
        }
        stage=wanted;
        switch(wanted){
            case CHARGING -> {chargingElapsed+=dt;RifleFiringPose.advance(ship,RifleFiringPose.Stage.PRESENTING,dt);}
            case FIRING -> {firingElapsed+=dt;RifleFiringPose.advance(ship,RifleFiringPose.Stage.FIRING,dt);}
            case RECOVER -> {recoveryElapsed=Math.min(RECOVER_SECONDS,recoveryElapsed+dt);RifleFiringPose.advance(ship,RifleFiringPose.Stage.CLOSING,dt);}
        }
        applyAll();
    }

    /** Reapplied by the inherited native arm's late callback; no clock advances here. */
    public void applyAll(){
        requireOwner();if(closed||recovered())return;
        arm.setCurrAngle(aimAngle());RifleFiringPose.applyAll(ship);
    }

    /** Actual rendered angle, also used for the hidden native beam's aperture. */
    public float aimAngle(){
        requireOwner();
        if(recovered())return ship.getFacing()+initialArmRelative;
        float aimed=RifleFiringPose.gunAimAngle(arm,nativeAim);
        return aimed+currentRecoil();
    }

    /** Exact world shift of the visible ARM_R shoulder orbit; no artificial extension. */
    public Vector2f translation(){requireOwner();return closed?new Vector2f():RifleFiringPose.armTranslation(ship,false);}
    public boolean readyForBeam(){requireOwner();return !closed&&stage==Stage.CHARGING&&blockedReason().isEmpty()&&RifleFiringPose.readyForBeam(ship);}
    public boolean recovered(){return stage==Stage.RECOVER&&recoveryElapsed>=RECOVER_SECONDS;}
    public String blockedReason(){requireOwner();return closed?"重炮蓄力姿态已关闭":RifleFiringPose.blockedReason(ship);}

    public Map<String,Object> status(){
        requireOwner();Map<String,Object> result=new LinkedHashMap<>();
        result.put("closed",closed);result.put("stage",stage.name());result.put("chargingElapsed",chargingElapsed);
        result.put("firstLight",firstLight);result.put("firingElapsed",firingElapsed);result.put("recoveryElapsed",recoveryElapsed);
        result.put("readyForBeam",readyForBeam());result.put("recovered",recovered());result.put("aimAngle",aimAngle());
        result.put("recoilDegrees",currentRecoil());
        result.put("motionCurve","QUINTIC");result.put("limbTranslationPolicy","BODY_PIVOT_ORBIT_ONLY");
        result.put("riflePose",RifleFiringPose.status(ship));return Collections.unmodifiableMap(result);
    }

    @Override public void close(){
        requireOwner();if(closed)return;
        try{lease.close();closed=true;}catch(Exception failure){throw new IllegalStateException("Hi-Nu cannon pose cleanup failed",failure);}
    }

    /** Joint-angle recoil preserves the real shoulder pivot and cannon length. */
    static float recoilDegrees(float age){
        if(age<0||age>=RECOIL_ATTACK_SECONDS+RECOIL_RETURN_SECONDS)return 0;
        return age<RECOIL_ATTACK_SECONDS?RECOIL_DEGREES*RifleFiringPose.quintic(age/RECOIL_ATTACK_SECONDS)
                :RECOIL_DEGREES*(1-RifleFiringPose.quintic((age-RECOIL_ATTACK_SECONDS)/RECOIL_RETURN_SECONDS));
    }
    private float currentRecoil(){return stage==Stage.FIRING?recoilDegrees(firingElapsed):stage==Stage.RECOVER?recoveryRecoil*(1-RifleFiringPose.quintic(recoveryElapsed/RECOVER_SECONDS)):0;}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Hi-Nu cannon pose belongs to its native battle thread");}
    private static float shortest(float value){float result=((value%360)+360)%360;return result>180?result-360:result;}
}
