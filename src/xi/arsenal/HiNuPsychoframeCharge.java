package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.*;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import data.scripts.weapons.Moci_WingmanSupport;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Earned ship-local charge from simulation time and settled native damage results.
 * One existing owner advances time; this listener neither applies nor modifies damage.
 * This body and its exact attached SHIELD_L share incoming credit. Outgoing Hi-Nu funnel
 * damage additionally requires consistent public mother links; owner or proximity alone never suffice.
 */
public final class HiNuPsychoframeCharge implements DamageListener, DamageTakenModifier, AutoCloseable {
    public static final String KEY="xi.arsenal.hinu.charge";
    public static final float MAX=100f, TIME_PER_SECOND=.5f,
            DEALT_PER_1000=10f, TAKEN_PER_1000=15f;
    private static final Set<String> SHIELD_HULLS=Set.of("Moci_RX_93_v2_shield","Moci_RX_93_v2_shield_hws");
    private final CombatEngineAPI engine;
    private final ShipAPI ship;
    private final CombatListenerManagerAPI listeners;
    private final IdentityHashMap<ShipAPI,ArrayDeque<Boolean>> beforeDamage=new IdentityHashMap<>();
    private float value;
    private double timePoints, dealtPoints, takenPoints, damageDealt, damageTaken;
    private long dealtEvents, takenEvents, consumptions;
    private boolean suspended, closed, registered;

    public HiNuPsychoframeCharge(CombatEngineAPI engine, ShipAPI ship) {
        this.engine=Objects.requireNonNull(engine,"Combat engine");
        this.ship=Objects.requireNonNull(ship,"Hi-Nu owner");
        listeners=Objects.requireNonNull(engine.getListenerManager(),"Native combat listener manager");
        if(ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Hi-Nu charge already owned");
        ship.setCustomData(KEY+".owner",this);
        try { listeners.addListener(this);registered=true;publish(); }
        catch(RuntimeException|Error failure) {
            if(registered)try { listeners.removeListener(this); } catch(RuntimeException|Error cleanup) { failure.addSuppressed(cleanup); }
            if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");
            throw failure;
        }
    }
    private boolean live(ShipAPI target) {
        return target!=null&&target.isAlive()&&!target.isHulk()&&!target.wasRemoved()&&engine.isEntityInPlay(target);
    }
    private boolean usable() {
        return !closed&&Global.getCombatEngine()==engine&&!engine.isCombatOver()&&live(ship);
    }
    public void advance(float seconds) {
        if(closed)return;
        if(!Float.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid Hi-Nu charge step");
        if(!usable()||ship.getCustomData().get(KEY+".owner")!=this){close();return;}
        if(engine.isPaused()||suspended||ready())return;
        beforeDamage.clear(); // Native pre/post callbacks are synchronous; no old-frame witness can be reused.
        double added=add((double)seconds*TIME_PER_SECOND);
        timePoints+=added;publish();
    }
    private double add(double points) {
        if(!Double.isFinite(points)||points<=0)return 0;
        float before=value;value=(float)Math.min(MAX,(double)value+points);
        return value-before;
    }
    public boolean ready() { return !closed&&value>=MAX; }
    /** Called only when the earned cannon activation is actually committed. */
    public boolean consume() {
        if(!ready())return false;
        value=0;consumptions++;publish();return true;
    }
    public void setSuspended(boolean suspended) {
        if(closed)return;this.suspended=suspended;publish();
    }
    private static ShipAPI sourceShip(Object source) {
        if(source instanceof ShipAPI s)return s;
        if(source instanceof DamagingProjectileAPI p)return p.getSource();
        if(source instanceof BeamAPI b)return b.getSource();
        return null;
    }
    private boolean ownedPart(ShipAPI target) {
        if(target==ship)return true;
        if(target==null||target.getParentStation()!=ship||target.getStationSlot()==null
                ||!"SHIELD_L".equals(target.getStationSlot().getId())||target.getOwner()!=ship.getOwner())return false;
        ShipHullSpecAPI hull=target.getHullSpec();
        if(hull==null||!SHIELD_HULLS.contains(hull.getHullId())&&!SHIELD_HULLS.contains(hull.getBaseHullId()))return false;
        List<ShipAPI> children=ship.getChildModulesCopy();
        if(children==null)return false;
        int checked=0;for(ShipAPI child:children){if(checked++>=32)break;if(child==target)return true;}
        return false;
    }
    private boolean ownedFunnel(ShipAPI drone) {
        if(!drone.isDrone()||drone.getOwner()!=ship.getOwner()||drone.getHullSpec()==null)return false;
        ShipHullSpecAPI hull=drone.getHullSpec();
        if(!"Moci_RX_93_v2_bit".equals(hull.getHullId())&&!"Moci_RX_93_v2_bit".equals(hull.getBaseHullId()))return false;
        Object flag=!PsychoframeHiNuSupport.reliableNativeFlag(engine,drone)||drone.getAIFlags()==null?null:drone.getAIFlags().getCustom(AIFlags.DRONE_MOTHERSHIP);
        ShipAPI found=null;
        for(ShipAPI candidate:new ShipAPI[]{PsychoframeHiNuSupport.motherFor(drone),drone.getDroneSource(),
                Moci_WingmanSupport.getTrackedMothership(drone),flag instanceof ShipAPI s?s:null}) {
            if(candidate==null)continue;
            ShipAPI root=Moci_WingmanSupport.resolveRootMothership(candidate);
            if(root==null||candidate.getOwner()!=drone.getOwner()||root.getOwner()!=drone.getOwner()||found!=null&&found!=root)return false;
            found=root;
        }
        return found==ship;
    }
    private static boolean hostile(ShipAPI attacker, ShipAPI target) {
        int from=attacker.getOwner(),to=target.getOwner();
        return (from==0||from==1)&&(to==0||to==1)&&from!=to;
    }
    private static double positive(float amount) { return Float.isFinite(amount)&&amount>0?amount:0; }
    /** Observation only: preserve whether this exact native hit began on a live ship. The
     * native engine applies hull death before its post-damage report. Returning null changes no damage. */
    @Override public String modifyDamageTaken(Object source,CombatEntityAPI entity,DamageAPI damage,Vector2f point,boolean shieldHit) {
        if(!usable()||engine.isPaused()||suspended||ready()||!(entity instanceof ShipAPI target))return null;
        ArrayDeque<Boolean> stack=beforeDamage.get(target);
        if(stack==null){if(beforeDamage.size()>=512)return null;stack=new ArrayDeque<>();beforeDamage.put(target,stack);}
        if(stack.size()>=16){beforeDamage.remove(target);return null;}
        stack.addLast(live(target));return null;
    }
    private boolean consumeWitness(ShipAPI target) {
        ArrayDeque<Boolean> stack=beforeDamage.get(target);if(stack==null||stack.isEmpty())return false;
        boolean wasLive=stack.removeLast();if(stack.isEmpty())beforeDamage.remove(target);return wasLive;
    }
    @Override public void reportDamageApplied(Object source, CombatEntityAPI entity, ApplyDamageResultAPI result) {
        if(!(entity instanceof ShipAPI target))return;
        boolean beganAlive=consumeWitness(target);
        if(!usable()||engine.isPaused()||suspended||ready()||result==null||!live(target)&&!beganAlive)return;
        ShipAPI attacker=sourceShip(source);
        if(attacker==null||!live(attacker)||!hostile(attacker,target))return;
        boolean dealt=ownedPart(attacker)||ownedFunnel(attacker),taken=ownedPart(target);
        if(dealt==taken)return;
        // Primary armor and over-max shield values overlap these settled totals and are not added twice.
        double actual=positive(result.getDamageToHull())+positive(result.getTotalDamageToArmor())+positive(result.getDamageToShields());
        if(actual<=0)return;
        if(dealt) {
            damageDealt+=actual;dealtEvents++;
            dealtPoints+=add(actual*DEALT_PER_1000/1000d);
        } else {
            damageTaken+=actual;takenEvents++;
            takenPoints+=add(actual*TAKEN_PER_1000/1000d);
        }
        publish();
    }
    public Map<String,Object> status() {
        Map<String,Object> state=new LinkedHashMap<>();
        state.put("label","精神感应框架激活中");state.put("value",value);state.put("max",MAX);
        state.put("progress",value);state.put("ratio",value/MAX);state.put("ready",ready());state.put("suspended",suspended);state.put("closed",closed);
        state.put("timePoints",timePoints);state.put("dealtPoints",dealtPoints);state.put("takenPoints",takenPoints);
        state.put("damageDealt",damageDealt);state.put("damageTaken",damageTaken);
        state.put("dealtEvents",dealtEvents);state.put("takenEvents",takenEvents);state.put("consumptions",consumptions);
        state.put("timePerSecond",TIME_PER_SECOND);state.put("dealtPer1000",DEALT_PER_1000);state.put("takenPer1000",TAKEN_PER_1000);
        return Collections.unmodifiableMap(state);
    }
    private void publish() { ship.setCustomData(KEY,status()); }
    @Override public void close() {
        if(closed)return;closed=true;suspended=true;beforeDamage.clear();
        if(registered){listeners.removeListener(this);registered=false;}
        if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");
        publish();
    }
}
