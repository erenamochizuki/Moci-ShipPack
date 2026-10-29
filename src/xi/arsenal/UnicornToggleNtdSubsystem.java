package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;
import data.hullmods.Moci_MobileSuitRepairTracker;
import data.subsystems.Moci_NTDSubsystem;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

/** Native Moci transformation/effects with an owned toggle policy; MagicLib alone advances time. */
public class UnicornToggleNtdSubsystem extends Moci_NTDSubsystem implements AutoCloseable {
    public static final float UPKEEP_SOFT_FLUX_PER_SECOND=20f;
    private static final AtomicLong SHUTDOWNS=new AtomicLong();
    public record Shutdown(long sequence,float onLocalSeconds,String reason) { }
    private boolean resonanceLocked,fatigueLocked,retired,deathCleaned,cycleOpen,keyHeld;
    private int resonanceCount;
    private float onLocalSeconds;
    private long cycles,shutoffs;
    private String nextOffReason="manual-toggle",lastOffReason="";
    private Shutdown pendingShutdown;

    public UnicornToggleNtdSubsystem(ShipAPI ship){super(ship);}
    @Override public boolean isToggle(){return true;}
    @Override public float getBaseActiveDuration(){return 0f;}
    @Override public int getMaxCharges(){return 0;}
    @Override public boolean usesChargesOnActivate(){return false;}
    @Override public float getBaseCooldownDuration(){return 0;}
    @Override public float getFluxCostFlatPerSecondWhileActive(){return UPKEEP_SOFT_FLUX_PER_SECOND;}
    @Override public boolean isHardFluxPerSecondWhileActive(){return false;}
    @Override public boolean canUseWhileVenting(){return true;}
    @Override public boolean canUseWhileOverloaded(){return false;}
    @Override public float getStateCompleteRatio(){
        return stateInterval==null?0:stateInterval.getIntervalDuration()<=0?1:super.getStateCompleteRatio();
    }
    /** Original canActivate hard-rejects venting, so preserve its other checks explicitly. */
    @Override public boolean canActivate(){
        if(retired||!ship.isAlive()||ship.isHulk()||ship.wasRemoved()
                ||Moci_MobileSuitRepairTracker.isShipRepairing(ship)||ship.getFluxTracker().isOverloaded())return false;
        if(fatigueLocked||pendingShutdown!=null)return false;
        return state!=State.ACTIVE||!resonanceLocked;
    }
    /** One physical down edge; a held native subsystem key cannot toggle repeatedly. */
    @Override public boolean isKeyDown(){boolean down=readNativeKeyDown(),edge=down&&!keyHeld;keyHeld=down;return edge;}
    protected boolean readNativeKeyDown(){return super.isKeyDown();}
    @Override public void activate(){if(canActivate()&&canActivateInternal())super.activate();}
    @Override public boolean shouldActivateAI(float amount){
        // Original NTD was a timed burst; its old AI would otherwise toggle off every frame.
        return state==State.READY&&canActivate()&&super.shouldActivateAI(amount);
    }
    @Override public void onActivate(){if(state==State.READY)activateOriginal();}
    protected void activateOriginal(){super.onActivate();}
    @Override public void setState(MagicSubsystem.State next){
        State previous=state;
        super.setState(next);
        if(next==State.IN&&previous!=State.IN&&previous!=State.ACTIVE){
            cycleOpen=true;onLocalSeconds=0;cycles++;lastOffReason="";
        }else if(cycleOpen&&previous!=next&&next!=State.IN&&next!=State.ACTIVE){
            cycleOpen=false;
            if(!retired&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()){
                lastOffReason=next==State.OUT?nextOffReason:"native-stop";
                pendingShutdown=new Shutdown(SHUTDOWNS.incrementAndGet(),onLocalSeconds,lastOffReason);shutoffs++;
            }
            nextOffReason="manual-toggle";
        }
    }
    @Override public void advance(float amount,boolean paused){
        if(retired)return;
        if(!paused){
            if(!Float.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid local NTD step");
            // MagicSubsystemsCombatPlugin already supplies amount*ship.timeMult exactly once.
            if(cycleOpen&&(state==State.IN||state==State.ACTIVE))onLocalSeconds+=amount;
            forceOutForOverload();
        }
        advanceOriginal(amount,paused); // exactly one original effect/animation advance, native-owned
    }
    protected void advanceOriginal(float amount,boolean paused){super.advance(amount,paused);}
    @Override public void onFinished(){finishOriginal();}
    protected void finishOriginal(){super.onFinished();}
    @Override public void onShipDeath(){
        cycleOpen=false;pendingShutdown=null;fatigueLocked=true;deathCleaned=true;deathOriginal();
    }
    protected void deathOriginal(){super.onShipDeath();}
    public void setRuntimeLocks(boolean resonance,boolean fatigue,int count){
        resonanceLocked=resonance;fatigueLocked=fatigue;resonanceCount=Math.max(0,count);
    }
    public boolean requestResonanceOn(){
        if(!resonanceLocked||state!=State.READY||!canActivate()||!canActivateInternal())return false;
        activate();return state==State.IN;
    }
    public boolean forceOutForOverload(){
        if((state!=State.IN&&state!=State.ACTIVE)||!ship.getFluxTracker().isOverloaded())return false;
        nextOffReason="overload";setState(State.OUT);return true;
    }
    public Shutdown pollShutdown(){Shutdown result=pendingShutdown;pendingShutdown=null;return result;}
    public float onLocalSeconds(){return onLocalSeconds;}
    public boolean resonanceLocked(){return resonanceLocked;}
    public boolean fatigueLocked(){return fatigueLocked||pendingShutdown!=null;}
    public int resonanceCount(){return resonanceCount;}
    public long cycles(){return cycles;}
    public long shutoffs(){return shutoffs;}
    public String lastOffReason(){return lastOffReason;}
    public String blockReason(){
        if(retired)return "NTD已退役";
        if(!ship.isAlive()||ship.isHulk())return "机体失效";
        if(ship.getFluxTracker().isOverloaded())return "过载：强制解除NTD";
        if(fatigueLocked())return "反噬未结束，无法开启";
        if(resonanceLocked&&state==State.ACTIVE)return "共振锁定，不能手动关闭";
        if(Moci_MobileSuitRepairTracker.isShipRepairing(ship))return "修理状态阻断";
        if(state==State.IN||state==State.OUT)return "变形进行中";
        if(state==State.READY&&!canActivateInternal())return "幅能空间不足以支付启动500";
        return "";
    }
    @Override public String getBriefText(){return "常驻开关 · 20软幅能/秒";}
    @Override public String getStateText(){
        if(fatigueLocked())return "反噬锁定";
        if(resonanceLocked&&state==State.ACTIVE)return "共振锁定";
        return super.getStateText();
    }
    /** Teardown is not a gameplay shutdown and never creates structure cost/backlash. */
    @Override public void close(){
        if(retired)return;retired=true;cycleOpen=false;pendingShutdown=null;
        List<MagicSubsystem> systems=MagicSubsystemsManager.getSubsystemsForShipCopy(ship);
        boolean owns=systems!=null&&systems.stream().anyMatch(s->s==this);
        if(owns){
            if(!deathCleaned&&(state==State.IN||state==State.ACTIVE||state==State.OUT))finishOriginal();
            MagicSubsystemsManager.removeSubsystemFromShip(ship,getClass());
        }
    }
}
