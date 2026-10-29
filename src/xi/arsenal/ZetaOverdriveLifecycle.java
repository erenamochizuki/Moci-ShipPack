package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.*;
import java.awt.Color;
import java.util.*;

/** Per-deployment biosensor clock. The Actor is its sole caller; kills come from real native
 * damage results, never the number of nearby wrecks. Only our own modifiers are restored. */
public final class ZetaOverdriveLifecycle implements DamageListener,AutoCloseable {
    public static final String KEY="xi.arsenal.zeta.overdrive.lifecycle",IMMUNITY="xi.arsenal.controlImmunity";
    public static final float DURATION=40,KILL_EXTENSION=10,BACKLASH=10,KILL_BACKLASH=1,BACKLASH_EMP=1800;
    private static final String STUN=KEY+".backlash",MOBILITY=KEY+".mobility";
    private static final ShipCommand[] BLOCKED={ShipCommand.ACCELERATE,ShipCommand.ACCELERATE_BACKWARDS,ShipCommand.DECELERATE,ShipCommand.STRAFE_LEFT,ShipCommand.STRAFE_RIGHT,ShipCommand.TURN_LEFT,ShipCommand.TURN_RIGHT,ShipCommand.USE_SYSTEM};
    private final CombatEngineAPI engine;private final ShipAPI ship;
    private final Map<ShipAPI,Float> lastHits=new IdentityHashMap<>();
    private final Set<ShipAPI> credited=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String,Object> immunity=new LinkedHashMap<>();
    private float remaining,backlashRemaining,backlashTotal=BACKLASH;private boolean running,closed,pendingEmp;
    private long activations,kills,immuneOverloads,backlashes;private String reason="就绪 · F 开启40秒生物传感器过载";
    public ZetaOverdriveLifecycle(CombatEngineAPI engine,ShipAPI ship){this.engine=Objects.requireNonNull(engine);this.ship=Objects.requireNonNull(ship);
        if(ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Zeta overdrive lifecycle already owned");
        ship.setCustomData(KEY+".owner",this);engine.getListenerManager().addListener(this);publish();}
    public static boolean immune(ShipAPI target){ShipAPI part=target;for(int i=0;part!=null&&i<16;i++){
        Object own=part.getCustomData().get(KEY+".owner");if(own instanceof ZetaOverdriveLifecycle life)return life.running&&!life.closed;
        ShipAPI parent=part.getParentStation();if(parent==part)break;part=parent;}return false;}
    public static ZetaOverdriveLifecycle instance(ShipAPI ship){Object own=ship==null?null:ship.getCustomData().get(KEY+".owner");return own instanceof ZetaOverdriveLifecycle life&&!life.closed?life:null;}
    /** Actual native IN->ACTIVE drives entry; forceState holds only this installed system's
     * ACTIVE progress so that an earned extension cannot be lost to its fixed CSV duration. */
    public void advance(float localSeconds){if(closed)return;if(!Float.isFinite(localSeconds)||localSeconds<0)throw new IllegalArgumentException("Invalid overdrive step");
        if(engine.isPaused())return;ShipSystemAPI system=ship.getSystem();
        if(!ship.isAlive()||ship.isHulk()||ship.wasRemoved()){close();return;}
        if(system==null||!ZetaContent.SYSTEM.equals(system.getId()))throw new IllegalStateException("Zeta biosensor system missing");
        if(backlashRemaining>0){backlashRemaining=Math.max(0,backlashRemaining-localSeconds);suppressBacklash();if(backlashRemaining<=0){clearBacklash();reason="瘫痪结束，等待系统冷却";}publish();return;}
        if(!running&&system.isStateActive()){running=true;remaining=DURATION;backlashTotal=BACKLASH;activations++;lastHits.clear();credited.clear();reason="超载武装已就绪";}
        if(running){settleKills();remaining=Math.max(0,remaining-localSeconds);
            if(remaining<=0){finish(system);publish();return;}
            // Full flux/hostile overload cannot end this mode; venting remains a native action.
            if(ship.getFluxTracker().isOverloaded()){ship.getFluxTracker().stopOverload();immuneOverloads++;}
            system.forceState(ShipSystemAPI.SystemState.ACTIVE,0);
            immunity.put("schema",1);immunity.put("owner",KEY);immunity.put("expiresAt",engine.getTotalElapsedTime(false)+.25f);ship.setCustomData(IMMUNITY,immunity);
            for(MutableStat stat:mobility())stat.modifyMult(MOBILITY,1.25f);
            if(ship.getEngineController()!=null)ship.getEngineController().extendFlame(MOBILITY,.5f,.5f,.25f);
        }publish();}
    private MutableStat[] mobility(){MutableShipStatsAPI s=ship.getMutableStats();return new MutableStat[]{s.getMaxSpeed(),s.getAcceleration(),s.getDeceleration(),s.getMaxTurnRate(),s.getTurnAcceleration()};}
    private static ShipAPI source(Object from){return from instanceof ShipAPI s?s:from instanceof DamagingProjectileAPI p?p.getSource():from instanceof BeamAPI b?b.getSource():null;}
    @Override public void reportDamageApplied(Object from,CombatEntityAPI entity,ApplyDamageResultAPI result){if(closed||!running||!(entity instanceof ShipAPI target)||target==ship||target.isFighter()||target.isStationModule()||target.getParentStation()!=null||target.getOwner()==ship.getOwner()||result==null||result.getDamageToHull()<=0)return;
        ShipAPI attacker=source(from);if(attacker==ship){if(lastHits.size()<512||lastHits.containsKey(target))lastHits.put(target,engine.getTotalElapsedTime(false));}else lastHits.remove(target);}
    private void settleKills(){float now=engine.getTotalElapsedTime(false);for(Iterator<Map.Entry<ShipAPI,Float>> it=lastHits.entrySet().iterator();it.hasNext();){var hit=it.next();ShipAPI target=hit.getKey();
        if(target.isHulk()||target.getHitpoints()<=0){if(credited.size()<1024&&credited.add(target)){remaining+=KILL_EXTENSION;backlashTotal+=KILL_BACKLASH;kills++;reason="击沉确认 · 持续+10秒 / 最终瘫痪+1秒";}it.remove();}
        else if(now-hit.getValue()>1||target.wasRemoved())it.remove();}}
    private void finish(ShipSystemAPI system){running=false;remaining=0;clearActive();system.forceState(ShipSystemAPI.SystemState.COOLDOWN,0);system.setCooldownRemaining(Math.max(20,system.getCooldownRemaining()));
        backlashRemaining=backlashTotal;backlashes++;reason="生物传感器反噬 · 机体瘫痪";
        pendingEmp=true;suppressBacklash();}
    /** Called after the old defense and optical-stat leases have been removed this frame. */
    public void afterEffects(){if(closed||!pendingEmp)return;pendingEmp=false;
        engine.spawnEmpArcPierceShields(ship,ship.getLocation(),ship,ship,DamageType.ENERGY,0,BACKLASH_EMP,500,null,14,new Color(150,65,245,210),new Color(240,200,255));
        ship.getFluxTracker().beginOverloadWithTotalBaseDuration(backlashTotal);suppressBacklash();}
    private void suppressBacklash(){for(MutableStat stat:mobility())stat.modifyMult(STUN,0);
        for(ShipCommand command:BLOCKED)ship.blockCommandForOneFrame(command);
        ship.setHoldFireOneFrame(true);for(WeaponAPI w:ship.getAllWeapons())if(!w.isDecorative()){w.setForceNoFireOneFrame(true);w.stopFiring();}
        if(ship.getEngineController()!=null)for(ShipEngineControllerAPI.ShipEngineAPI drive:ship.getEngineController().getShipEngines())ship.getEngineController().setFlameLevel(drive.getEngineSlot(),0);}
    private void clearActive(){for(MutableStat stat:mobility())stat.unmodify(MOBILITY);if(ship.getCustomData().get(IMMUNITY)==immunity)ship.removeCustomData(IMMUNITY);lastHits.clear();}
    private void clearBacklash(){for(MutableStat stat:mobility())stat.unmodify(STUN);}
    public boolean active(){return running&&!closed;}public boolean stunned(){return backlashRemaining>0&&!closed;}
    public Map<String,Object> status(){return Map.ofEntries(Map.entry("active",active()),Map.entry("closed",closed),Map.entry("remaining",remaining),Map.entry("backlashRemaining",backlashRemaining),Map.entry("finalBacklashSeconds",backlashTotal),Map.entry("kills",kills),Map.entry("activations",activations),Map.entry("backlashes",backlashes),Map.entry("blockedNativeOverloads",immuneOverloads),Map.entry("mobilityMult",active()?1.25f:1),Map.entry("engineLengthMult",active()?1.5f:1),Map.entry("engineWidthMult",active()?1.5f:1),Map.entry("reason",reason));}
    private void publish(){ship.setCustomData(KEY,status());if(ship==engine.getPlayerShip())engine.maintainStatusForPlayerShip(KEY,"graphics/icons/hullsys/high_energy_focus.png","生物传感器超载",running?String.format(Locale.ROOT,"剩余 %.1f 秒 · 击沉 %d · 反噬 %.0f 秒",remaining,kills,backlashTotal):backlashRemaining>0?String.format(Locale.ROOT,"瘫痪 %.1f 秒",backlashRemaining):reason,backlashRemaining>0);}
    @Override public void close(){if(closed)return;running=false;clearActive();clearBacklash();closed=true;engine.getListenerManager().removeListener(this);if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");publish();}
}
