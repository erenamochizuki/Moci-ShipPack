package xi.arsenal;

import data.scripts.weapons.Moci_MSN04Wingman_Effect;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.combat.ai.BasicShipAI;
import com.fs.starfarer.combat.entities.Ship;
import data.scripts.weapons.Moci_WingmanSupport;
import java.util.*;
import java.util.function.DoubleSupplier;
import org.lwjgl.util.vector.Vector2f;

/**
 * Owned attack controller for the original MSN04 FX funnel only. The engine calls this ShipAI once;
 * a manager must NEVER advance it as well. During owned combat we do not also advance BasicShipAI:
 * its movement/flocking engine would compete with these real commands. Original weapon effects,
 * native weapon advancement and Moci's independent lifecycle listener continue normally.
 *
 * The original MSN04 effect has no timed docking/return phase: it deletes the oldest drone at its
 * cap, and the lifecycle listener removes drones when their mother is invalid. This owned extension
 * adds two confirmed shots followed by command-driven return and native landing. Ammo is restored
 * only by the launch effect's exact-origin, finished-landing, once-only contract. External native
 * landing/wing return/retreat/explicit handoff still restores the exact original AI.
 * A caller owns close() for installed instances; do not register hundreds of per-drone resources.
 */
public final class NightShootMoveAI implements ShipAIPlugin,AutoCloseable {
    public static final String HULL_ID="Moci_MSN_04_2_bit",KEY="xi.arsenal.nightShootMove",RETURN_KEY=KEY+".returnRequested";
    public static final float CRUISE_SPEED=580f,ACCEL_MULT=24f,BRAKE_MULT=32f,TURN_MULT=3.2f,TURN_ACCEL_MULT=16f;
    /** Historical public multiplier; the current baseline uses an additive580 adjustment. */
    public static final float MOVE_SPEED_MULT=1f;
    public static final float ARRIVE_RADIUS=28f,SETTLE_SPEED=30f,AIM_ERROR_DEGREES=5f,ARC_STEP_DEGREES=32f;
    public static final float MIN_RELOCATION=170f,FIRE_TIMEOUT_SECONDS=4f,NO_SHOT_RETRY_SECONDS=.15f;
    public static final int SORTIE_SHOTS=2;
    private static final int MAX_WEAPONS=16,MAX_MOTHER_WEAPONS=512,MAX_MODULES=64,MAX_AUTO_CANDIDATES=512;
    private static final Set<String> LAUNCHERS=Set.of("Moci_MSN_04_2_wing_left","Moci_MSN_04_2_wing_right");
    public enum Phase { WAITING_TARGET,MOVE,SETTLE,FIRE_ONCE,RECOVER,RETURN,LANDING,RETURNED,HANDED_BACK,CLOSED }
    @FunctionalInterface interface ReturnCompletion { boolean complete(CombatEngineAPI engine,ShipAPI drone,ShipAPI mother); }
    private final CombatEngineAPI engine;
    private final ShipAPI drone,mother;
    private final ShipAIPlugin original;
    private final WeaponAPI gun;
    private final WeaponGroupAPI group;
    private final int groupIndex;
    private final boolean originalAuto;
    private final long ordinal;
    private final DoubleSupplier random;
    private final ReturnCompletion completion;
    private final Thread owner=Thread.currentThread();
    private final String modifier=KEY+"."+Integer.toHexString(System.identityHashCode(this));
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private final Map<String,Object> factsView=Collections.unmodifiableMap(facts);
    private Phase phase=Phase.WAITING_TARGET;
    private ShipAPI target,routedTarget;
    private float orbitAngle,orbitRadius,phaseSeconds,settledSeconds,cachedLauncherRange,rangeClock,noFireDelay;
    private final Vector2f waypoint=new Vector2f();
    private int previousAmmo;
    private float previousCooldown,previousBrightness;
    private boolean shotArmed,closed,routerAssigned,returnPending,completionAttempted;
    private boolean autonomySuspended,directApproach;
    private long clearEpochSeen;
    private float targetSearchAt,maximumObservedSpeed;
    private ShipAPI autoCandidate;
    private String targetingMode="unassigned";
    private float elapsed,lastShotTime,returnStartedAt=-1,landingStartedAt=-1,returnedAt=-1;
    private final Vector2f lastShotPosition=new Vector2f();
    private final List<Map<String,Object>> shotPositions=new ArrayList<>();
    private long shots,moves,fireRequests,nativeHandoffs,shotTimeouts,extraAmmoDrops;
    private String lastShot="",handoffReason="";

    /** Returns null for an unsupported/foreign controller; never replaces arbitrary fighter AI. */
    public static NightShootMoveAI install(CombatEngineAPI engine,ShipAPI drone,ShipAPI mother,long stableOrdinal){
        if(engine==null||drone==null||mother==null||!isExactHull(drone)||!drone.isDrone()
                ||drone.getShipAI()==null||drone.getShipAI().getClass()!=BasicShipAI.class)return null;
        if(!drone.isAlive()||drone.isHulk()||!mother.isAlive()||mother.isHulk()||drone.isLanding()||drone.isFinishedLanding()
                ||drone.isRetreating()||drone.getWing()!=null&&drone.getWing().isReturning(drone)||engine.isCombatOver()
                ||Boolean.TRUE.equals(drone.getCustomData().get(RETURN_KEY)))return null;
        if(!linkedMother(drone,mother)||drone.getOwner()!=mother.getOwner()||findGun(drone)==null)return null;
        WeaponAPI candidate=findGun(drone);if(drone.getWeaponGroupFor(candidate)==null||readLauncherRange(mother)<=0)return null;
        NightShootMoveAI own=new NightShootMoveAI(engine,drone,mother,drone.getShipAI(),stableOrdinal,Math::random);
        drone.setShipAI(own);return own;
    }
    /** Deterministic injection for API contracts; the public installer still requires exact native AI. */
    NightShootMoveAI(CombatEngineAPI engine,ShipAPI drone,ShipAPI mother,ShipAIPlugin original,long stableOrdinal,DoubleSupplier random){
        this(engine,drone,mother,original,stableOrdinal,random,Moci_MSN04Wingman_Effect::completeReturn);
    }
    NightShootMoveAI(CombatEngineAPI engine,ShipAPI drone,ShipAPI mother,ShipAIPlugin original,long stableOrdinal,DoubleSupplier random,ReturnCompletion completion){
        this.engine=Objects.requireNonNull(engine);this.drone=Objects.requireNonNull(drone);this.mother=Objects.requireNonNull(mother);
        this.original=Objects.requireNonNull(original);this.random=Objects.requireNonNull(random);this.ordinal=stableOrdinal;
        this.completion=Objects.requireNonNull(completion);
        if(!isExactHull(drone)||!drone.isDrone()||drone.getOwner()!=mother.getOwner()||!linkedMother(drone,mother))throw new IllegalArgumentException("Not a linked original MSN04 funnel");
        if(drone.getCustomData().containsKey(KEY))throw new IllegalStateException("Night funnel controller already owned");
        WeaponAPI found=findGun(drone);
        if(found==null)throw new IllegalArgumentException("MSN04 real WS0001 gun not found");
        gun=found;group=drone.getWeaponGroupFor(gun);List<WeaponGroupAPI> groups=drone.getWeaponGroupsCopy();
        groupIndex=identityIndex(groups,group);if(group==null||groupIndex<0)throw new IllegalArgumentException("MSN04 gun group not found");
        originalAuto=group.isAutofiring();
        // This single FX gun is controlled by ordinary SELECT_GROUP/FIRE commands, not its PD
        // autofire selector, which could otherwise choose a missile during our one-shot window.
        cachedLauncherRange=launcherRange();clearEpochSeen=readClearEpoch();
        if(cachedLauncherRange<=0)throw new IllegalArgumentException("Original MSN04 mother launchers not found");
        if(originalAuto)group.toggleOff();
        applyMovement();drone.setCustomData(KEY,this);drone.setCustomData(KEY+".status",factsView);sampleWeapon();publish();
    }
    public static boolean isExactHull(ShipAPI ship){return ship!=null&&ship.getHullSpec()!=null&&HULL_ID.equals(ship.getHullSpec().getHullId());}
    /** Native setShipAI wraps API-only plugins; unwrap only that exact public native wrapper. */
    public static NightShootMoveAI fromNative(ShipAIPlugin ai){
        for(int depth=0;ai!=null&&depth<4;depth++){
            if(ai instanceof NightShootMoveAI own)return own;
            if(ai.getClass()!=Ship.ShipAIWrapper.class)return null;
            ShipAIPlugin next=((Ship.ShipAIWrapper)ai).getAI();if(next==ai)return null;ai=next;
        }return null;
    }
    public boolean ownsNativeAi(){requireOwner();return fromNative(drone.getShipAI())==this;}
    private static WeaponAPI findGun(ShipAPI drone){int checked=0;
        for(WeaponAPI weapon:drone.getAllWeapons()){
            if(checked++>=MAX_WEAPONS)break;
            if(weapon!=null&&!weapon.isDecorative()&&"WS0001".equals(slot(weapon))
                    &&("Moci_MSN_04_2_bit_beam".equals(weapon.getId())||"Moci_MSN_04_2_bit_blaster".equals(weapon.getId())))return weapon;
        }return null;
    }
    private static boolean linkedMother(ShipAPI ship,ShipAPI mother){
        ShipAPI tracked=Moci_WingmanSupport.getTrackedMothership(ship);
        Object flagged=ship.getAIFlags()==null?null:ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.DRONE_MOTHERSHIP);
        ShipAPI nativeSource=ship.getDroneSource();ShipAPI root=Moci_WingmanSupport.resolveRootMothership(mother);
        boolean confirmed=false;
        for(ShipAPI candidate:new ShipAPI[]{tracked,flagged instanceof ShipAPI s?s:null,nativeSource})if(candidate!=null){
            if(Moci_WingmanSupport.resolveRootMothership(candidate)!=root)return false;confirmed=true;
        }
        return confirmed&&root==mother;
    }
    @Override public void advance(float dt){
        requireOwner();if(closed)return;if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid night funnel dt");
        if(!ownsNativeAi()){handoff("external-AI-owner",false);return;}
        if(serviceLandingCompletion())return;if(closed)return;
        String returnReason=returnReason();if(returnReason!=null){handoff(returnReason,true);return;}
        if(engine.isPaused())return;phaseSeconds+=dt;elapsed+=dt;noFireDelay=Math.max(0,noFireDelay-dt);
        maximumObservedSpeed=Math.max(maximumObservedSpeed,speed());
        long epoch=readClearEpoch();
        if(epoch>=0&&clearEpochSeen>=0&&epoch>clearEpochSeen&&!returnPending){autonomySuspended=true;autoCandidate=null;}
        clearEpochSeen=epoch;
        if(!ContentTargetLocks.snapshot(engine,mother).isEmpty())autonomySuspended=false;
        if(original.getAIFlags()!=null)original.getAIFlags().advance(dt);
        rangeClock+=dt;if(rangeClock>=.25f){rangeClock=0;cachedLauncherRange=launcherRange();}
        boolean visible=brightness()>.001f,cycle=visible||gun.getChargeLevel()>.001f||gun.isInBurst();
        if(phase==Phase.FIRE_ONCE&&shotArmed&&detectShot()){
            shots++;lastShotPosition.set(drone.getLocation());lastShotTime=elapsed;
            shotPositions.add(Map.of("sequence",shots,"x",lastShotPosition.x,"y",lastShotPosition.y,"time",elapsed,"targetId",Objects.toString(target.getId(),"")));
            if(shots>=SORTIE_SHOTS)returnPending=true;
            shotArmed=false;transition(Phase.RECOVER);denyFire();
        }
        if(phase==Phase.RETURN){advanceReturn();sampleWeapon();publish();return;}
        if(phase==Phase.LANDING){denyFire();sampleWeapon();publish();return;}
        if(!returnPending&&routerAssigned&&routedTarget!=target&&!cycle
                &&(phase==Phase.MOVE||phase==Phase.SETTLE||phase==Phase.WAITING_TARGET)){
            target=pickTarget();if(target==null)transition(Phase.WAITING_TARGET);else newOrbit();
        }
        boolean legal=permittedTarget();
        if(!returnPending&&!legal&&phase!=Phase.RECOVER){
            if(cycle){transition(Phase.RECOVER);shotArmed=false;}
            else{target=pickTarget();if(target!=null)newOrbit();else transition(Phase.WAITING_TARGET);}
        }
        switch(phase){
            case WAITING_TARGET->{
                denyFire();target=pickTarget();
                if(target!=null)newOrbit();else{Vector2f escort=new Vector2f(mother.getLocation());escort.x+=120f+(ordinal%5)*30f;move(escort,mother.getFacing());}
            }
            case MOVE->{
                denyFire();Vector2f goal=orbitPoint(orbitAngle);
                if(directApproach&&distance(drone.getLocation(),target.getLocation())<=orbitRadius+100)directApproach=false;
                Vector2f next=directApproach?goal:arcWaypoint();waypoint.set(goal);
                move(next,angle(drone.getLocation(),next));
                if(distance(drone.getLocation(),goal)<=ARRIVE_RADIUS){settledSeconds=0;transition(Phase.SETTLE);}
            }
            case SETTLE->{
                denyFire();drone.giveCommand(ShipCommand.DECELERATE,null,0);Vector2f aim=aimPoint();turn(angle(drone.getLocation(),aim));
                if(shots>0&&distance(drone.getLocation(),lastShotPosition)<MIN_RELOCATION&&!cycle){newOrbit();break;}
                boolean ready=speed()<=SETTLE_SPEED&&Math.abs(delta(angle(drone.getLocation(),aim)-drone.getFacing()))<=AIM_ERROR_DEGREES
                        &&gun.distanceFromArc(aim)<=.001f&&!cycle&&!gun.isDisabled()&&gun.getCooldownRemaining()<=.001f&&(!gun.usesAmmo()||gun.getAmmo()>0);
                settledSeconds=ready?settledSeconds+dt:0;
                if(settledSeconds>=.04f){sampleWeapon();shotArmed=true;transition(Phase.FIRE_ONCE);}
            }
            case FIRE_ONCE->{
                drone.giveCommand(ShipCommand.DECELERATE,null,0);Vector2f aim=aimPoint();turn(angle(drone.getLocation(),aim));
                if(permittedTarget()&&noFireDelay<=0&&speed()<=SETTLE_SPEED+8
                        &&Math.abs(delta(angle(drone.getLocation(),aim)-drone.getFacing()))<=AIM_ERROR_DEGREES+2){
                    drone.setShipTarget(target);drone.giveCommand(ShipCommand.SELECT_GROUP,null,groupIndex);
                    drone.giveCommand(ShipCommand.FIRE,aim,groupIndex);fireRequests++;
                }else denyFire();
                if(phaseSeconds>FIRE_TIMEOUT_SECONDS){shotTimeouts++;shotArmed=false;transition(Phase.RECOVER);denyFire();}
            }
            case RECOVER->{
                denyFire();drone.giveCommand(ShipCommand.DECELERATE,null,0);
                if(!cycle&&phaseSeconds>=NO_SHOT_RETRY_SECONDS){
                    if(returnPending)beginReturn();
                    else{target=pickTarget();if(target!=null)newOrbit();else transition(Phase.WAITING_TARGET);}
                }
            }
            default->{ }
        }
        sampleWeapon();publish();
    }
    private boolean permittedTarget(){
        if(!ContentTargetLocks.legal(engine,mother,target)||!withinPursuit(target))return false;
        if(routerAssigned&&routedTarget==null)return false;
        if(ContentTargetLocks.managed(engine,mother)){
            List<ShipAPI> pool=ContentTargetLocks.snapshot(engine,mother);
            if(!pool.isEmpty())return contains(pool,target);
            return readClearEpoch()>=0&&!autonomySuspended;
        }
        return !autonomySuspended;
    }
    private String returnReason(){
        if(!drone.isAlive()||drone.isHulk()||drone.wasRemoved()||!engine.isEntityInPlay(drone))return "drone-invalid";
        if(!mother.isAlive()||mother.isHulk()||!engine.isEntityInPlay(mother))return "original-mother-lifecycle";
        if(drone.getOwner()!=mother.getOwner()||!linkedMother(drone,mother))return "mother-or-owner-changed";
        if(phase!=Phase.LANDING&&(drone.isLanding()||drone.isFinishedLanding()))return "native-landing";
        if(drone.isRetreating()||drone.getWing()!=null&&drone.getWing().isReturning(drone)||original.needsRefit())return "native-return-or-refit";
        if(!returnPending&&Boolean.TRUE.equals(drone.getCustomData().get(RETURN_KEY)))return "explicit-return-handoff";
        if(engine.isCombatOver())return "combat-over";
        return null;
    }
    private ShipAPI pickTarget(){
        float launchRange=cachedLauncherRange;
        if(routerAssigned)return ContentTargetLocks.legal(engine,mother,routedTarget)&&withinPursuit(routedTarget)
                &&(!ContentTargetLocks.managed(engine,mother)||contains(ContentTargetLocks.snapshot(engine,mother),routedTarget))?routedTarget:null;
        if(ContentTargetLocks.managed(engine,mother)){
            List<ShipAPI> declared=ContentTargetLocks.snapshot(engine,mother);
            if(!declared.isEmpty()){
                List<ShipAPI> pool=declared.stream().filter(s->ContentTargetLocks.legal(engine,mother,s)&&withinPursuit(s)).toList();
                targetingMode="shared-locks";return pool.isEmpty()?null:pool.get((int)Math.floorMod(ordinal+shots,(long)pool.size()));
            }
            // Old bridges lack a clear-generation distinction. Keep their strict empty-pool
            // contract; modern host bridges allow a fresh sortie to be an explicit attack order.
            if(readClearEpoch()<0||autonomySuspended){targetingMode="explicit-hold";return null;}
        }
        if(autonomySuspended){targetingMode="explicit-hold";return null;}
        ShipAPI focus=mother.getShipTarget();
        if(ContentTargetLocks.legal(engine,mother,focus)&&withinPursuit(focus)){targetingMode="native-focus";return focus;}
        if(elapsed<targetSearchAt)return ContentTargetLocks.legal(engine,mother,autoCandidate)&&withinPursuit(autoCandidate)?autoCandidate:null;
        targetSearchAt=elapsed+.2f;
        List<ShipAPI> candidates=new ArrayList<>();List<ShipAPI> ships=engine.getShips();
        for(int i=0;i<Math.min(MAX_AUTO_CANDIDATES,ships.size());i++){
            ShipAPI candidate=ships.get(i);
            if(!ContentTargetLocks.legal(engine,mother,candidate)||candidate.isFighter()||candidate.isDrone()
                    ||!ContentTargetLocks.inRange(mother,candidate,launchRange))continue;
            FogOfWarAPI fog=engine.getFogOfWar(mother.getOwner());if(fog!=null&&!fog.isVisible(candidate))continue;
            candidates.add(candidate);
        }
        candidates.sort(Comparator.comparingDouble((ShipAPI s)->distance(mother.getLocation(),s.getLocation()))
                .thenComparing(s->Objects.toString(s.getId(),""))
                .thenComparingDouble(s->s.getLocation().x).thenComparingDouble(s->s.getLocation().y));
        if(candidates.size()>8)candidates=new ArrayList<>(candidates.subList(0,8));
        autoCandidate=candidates.isEmpty()?null:candidates.get((int)Math.floorMod(ordinal-1,(long)candidates.size()));
        targetingMode=autoCandidate==null?"no-visible-enemy":"autonomous-sortie";return autoCandidate;
    }
    private boolean withinPursuit(ShipAPI enemy){return enemy!=null&&ContentTargetLocks.inRange(mother,enemy,cachedLauncherRange*1.75f);}
    /** -1 marks a legacy bridge whose explicit empty pool must remain authoritative. */
    private long readClearEpoch(){
        Object raw=engine.getCustomData().get(ContentTargetLocks.KEY);
        if(!(raw instanceof Map<?,?> bridge)||!(bridge.get("schema") instanceof Number schema)||schema.intValue()!=1
                ||!(bridge.get("clearEpoch") instanceof Map<?,?> epochs))return -1;
        Object value=epochs.get(mother);return value instanceof Number n?Math.max(0,n.longValue()):0;
    }
    private float launcherRange(){return readLauncherRange(mother);}
    private static float readLauncherRange(ShipAPI mother){
        float range=0;int scanned=0,modules=0;ArrayDeque<ShipAPI> pending=new ArrayDeque<>();pending.add(mother);
        Set<ShipAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(!pending.isEmpty()&&modules++<MAX_MODULES&&scanned<MAX_MOTHER_WEAPONS){ShipAPI ship=pending.removeFirst();if(!seen.add(ship))continue;
            for(WeaponAPI weapon:ship.getAllWeapons()){if(scanned++>=MAX_MOTHER_WEAPONS)break;if(weapon!=null&&weapon.getId()!=null&&LAUNCHERS.contains(weapon.getId()))range=Math.max(range,weapon.getRange());}
            List<ShipAPI> children=ship.getChildModulesCopy();if(children!=null)for(ShipAPI child:children)if(child!=null&&pending.size()<MAX_MODULES&&!seen.contains(child))pending.addLast(child);
        }
        return Float.isFinite(range)?range:0;
    }
    private void newOrbit(){
        float minimum=Math.max(target.getCollisionRadius()+drone.getCollisionRadius()+85f,100f);
        float maximum=Math.max(minimum+60,target.getCollisionRadius()+Math.min(580f,gun.getRange()*.65f));
        float chosenAngle=orbitAngle,chosenRadius=minimum;
        Vector2f previousShot=shots>0?lastShotPosition:drone.getLocation();float requiredDistance=MIN_RELOCATION+2*ARRIVE_RADIUS+20;
        for(int attempt=0;attempt<8;attempt++){
            chosenAngle=(float)(sampleRandom()*360);chosenRadius=minimum+(maximum-minimum)*(float)sampleRandom();
            Vector2f candidate=point(target.getLocation(),chosenAngle,chosenRadius);
            if(distance(candidate,previousShot)>=requiredDistance&&Math.abs(delta(chosenAngle-orbitAngle))>=55)break;
        }
        if(distance(point(target.getLocation(),chosenAngle,chosenRadius),previousShot)<requiredDistance||Math.abs(delta(chosenAngle-orbitAngle))<55){
            chosenAngle=(orbitAngle+137.508f)%360;chosenRadius=(minimum+maximum)*.5f;
            if(distance(point(target.getLocation(),chosenAngle,chosenRadius),previousShot)<requiredDistance)chosenAngle=(chosenAngle+180)%360;
        }
        if(shots==0&&distance(drone.getLocation(),target.getLocation())>maximum+100){
            chosenAngle=angle(target.getLocation(),drone.getLocation())+((ordinal%5)-2)*8f;
            chosenRadius=minimum+(maximum-minimum)*.28f;
        }
        orbitAngle=chosenAngle;orbitRadius=chosenRadius;
        directApproach=distance(drone.getLocation(),target.getLocation())>orbitRadius+160;
        waypoint.set(orbitPoint(orbitAngle));moves++;transition(Phase.MOVE);
        drone.setShipTarget(target);
    }
    private double sampleRandom(){double value=random.getAsDouble();if(!Double.isFinite(value)||value<0||value>=1)throw new IllegalStateException("Night funnel random must be in [0,1)");return value;}
    private Vector2f orbitPoint(float degrees){return point(target.getLocation(),degrees,orbitRadius);}
    private Vector2f arcWaypoint(){
        float bearing=angle(target.getLocation(),drone.getLocation());float change=delta(orbitAngle-bearing);
        return orbitPoint(bearing+Math.max(-ARC_STEP_DEGREES,Math.min(ARC_STEP_DEGREES,change)));
    }
    private void move(Vector2f goal,float facing){
        turn(facing);float dx=goal.x-drone.getLocation().x,dy=goal.y-drone.getLocation().y,dist=(float)Math.hypot(dx,dy);
        float desired=Math.min(drone.getMaxSpeed(),(float)Math.sqrt(2*Math.max(1,drone.getDeceleration())*Math.max(0,dist-ARRIVE_RADIUS*.45f)));
        if(dist<ARRIVE_RADIUS*.6f){drone.giveCommand(ShipCommand.DECELERATE,null,0);return;}
        float desiredX=dx/Math.max(1,dist)*desired,desiredY=dy/Math.max(1,dist)*desired;
        float ex=desiredX-drone.getVelocity().x,ey=desiredY-drone.getVelocity().y;
        double a=Math.toRadians(drone.getFacing());float forward=(float)(ex*Math.cos(a)+ey*Math.sin(a)),left=(float)(-ex*Math.sin(a)+ey*Math.cos(a));
        if(forward>8)drone.giveCommand(ShipCommand.ACCELERATE,null,0);else if(forward< -8)drone.giveCommand(ShipCommand.ACCELERATE_BACKWARDS,null,0);
        if(left>8)drone.giveCommand(ShipCommand.STRAFE_LEFT,null,0);else if(left< -8)drone.giveCommand(ShipCommand.STRAFE_RIGHT,null,0);
    }
    private void turn(float wanted){
        float error=delta(wanted-drone.getFacing());float limit=Math.max(1,drone.getMaxTurnRate());
        float desired=Math.copySign(Math.min(limit,(float)Math.sqrt(2*Math.max(1,drone.getTurnDeceleration())*Math.abs(error))),error);
        if(desired-drone.getAngularVelocity()>2)drone.giveCommand(ShipCommand.TURN_LEFT,null,0);
        else if(desired-drone.getAngularVelocity()< -2)drone.giveCommand(ShipCommand.TURN_RIGHT,null,0);
    }
    private Vector2f aimPoint(){
        Vector2f aim=engine.getAimPointWithLeadForAutofire(drone,1f,target,Math.max(1,gun.getProjectileSpeed()));
        return aim==null?new Vector2f(target.getLocation()):new Vector2f(aim);
    }
    private boolean detectShot(){
        float bright=brightness(),cool=gun.getCooldownRemaining();int ammo=gun.getAmmo();
        // This original MSN04 launcher uses a beam. Commit its shot only after a real beam is
        // bright, not merely an ammo deduction during charge-up; then let its native pulse finish.
        if(gun.isBeam()){
            if(bright>.001f&&previousBrightness<=.001f){lastShot="native-beam-bright-edge";return true;}
            return false;
        }
        if(gun.usesAmmo()&&ammo<previousAmmo){int drop=previousAmmo-ammo;if(drop>1)extraAmmoDrops+=drop-1;lastShot="native-ammo-edge";return true;}
        if(!gun.isBeam()&&cool>previousCooldown+.0001f&&(gun.isFiring()||gun.isInBurst())){lastShot="native-cooldown-firing-edge";return true;}
        return false;
    }
    private float brightness(){float max=0;for(BeamAPI beam:gun.getBeams())if(beam!=null)max=Math.max(max,beam.getBrightness());return max;}
    private void sampleWeapon(){previousAmmo=gun.getAmmo();previousCooldown=gun.getCooldownRemaining();previousBrightness=brightness();}
    private void denyFire(){int checked=0;for(WeaponAPI weapon:drone.getAllWeapons()){if(checked++>=MAX_WEAPONS)break;if(weapon!=null&&!weapon.isDecorative())weapon.setForceNoFireOneFrame(true);}}
    private void applyMovement(){MutableShipStatsAPI stats=drone.getMutableStats();stats.getMaxSpeed().modifyFlat(modifier,CRUISE_SPEED-stats.getMaxSpeed().getBaseValue());stats.getAcceleration().modifyMult(modifier,ACCEL_MULT);
        stats.getDeceleration().modifyMult(modifier,BRAKE_MULT);stats.getMaxTurnRate().modifyMult(modifier,TURN_MULT);stats.getTurnAcceleration().modifyMult(modifier,TURN_ACCEL_MULT);}
    private void clearMovement(){MutableShipStatsAPI stats=drone.getMutableStats();stats.getMaxSpeed().unmodify(modifier);stats.getAcceleration().unmodify(modifier);
        stats.getDeceleration().unmodify(modifier);stats.getMaxTurnRate().unmodify(modifier);stats.getTurnAcceleration().unmodify(modifier);}
    private void transition(Phase next){phase=next;phaseSeconds=0;}
    private void beginReturn(){returnPending=true;drone.setCustomData(RETURN_KEY,Boolean.TRUE);returnStartedAt=elapsed;shotArmed=false;transition(Phase.RETURN);denyFire();}
    private void advanceReturn(){
        denyFire();waypoint.set(mother.getLocation());
        if(distance(drone.getLocation(),mother.getLocation())<=mother.getCollisionRadius()+20){
            drone.giveCommand(ShipCommand.DECELERATE,null,0);clearMovement();
            drone.beginLandingAnimation(mother);landingStartedAt=elapsed;transition(Phase.LANDING);
        }else move(new Vector2f(mother.getLocation()),angle(drone.getLocation(),mother.getLocation()));
    }
    /** Does not advance the AI. A coordinator should call before retiring a naturally removed FX drone. */
    public boolean serviceLandingCompletion(){
        requireOwner();if(closed)return phase==Phase.RETURNED;
        if(phase!=Phase.LANDING||!drone.isFinishedLanding()||completionAttempted||!ownsNativeAi())return false;
        completionAttempted=true;
        if(!drone.isAlive()||drone.isHulk()||!mother.isAlive()||mother.isHulk()||!engine.isEntityInPlay(mother)
                ||drone.getOwner()!=mother.getOwner()||!linkedMother(drone,mother)||gun.isDisabled()
                ||drone.getEngineController()!=null&&drone.getEngineController().isDisabled()){
            handoff("landing-invalid-or-disabled-no-refund",true);return false;
        }
        if(!completion.complete(engine,drone,mother)){handoff("landing-without-origin-proof",true);return false;}
        // complete() has checked actual onFire provenance and once-only refund. Only now remove
        // this finished owned FX entity; never create a replacement or refund from a guessed slot.
        clearMovement();if(drone.getCustomData().get(KEY)==this)drone.removeCustomData(KEY);
        if(drone.getCustomData().get(KEY+".status")==factsView)drone.removeCustomData(KEY+".status");
        returnedAt=elapsed;closed=true;phase=Phase.RETURNED;if(engine.isEntityInPlay(drone))engine.removeEntity(drone);publish();return true;
    }
    /** Router hint only; a charging/visible pulse finishes at its current target before changing. */
    public void setAssignedTarget(ShipAPI assigned){requireOwner();if(closed)return;routerAssigned=true;routedTarget=assigned;}
    public void clearAssignedTarget(){requireOwner();if(closed)return;routerAssigned=false;routedTarget=null;}
    public ShipAPI currentTarget(){requireOwner();return target;}
    public long confirmedShots(){requireOwner();return shots;}
    public boolean inNativeWeaponCycle(){requireOwner();return brightness()>.001f||gun.getChargeLevel()>.001f||gun.isInBurst();}
    private void handoff(String reason,boolean restoreOriginal){
        if(closed)return;handoffReason=reason;nativeHandoffs++;boolean own=ownsNativeAi();
        clearMovement();if(own&&restoreOriginal){if(originalAuto&&!group.isAutofiring())group.toggleOn();drone.setShipAI(original);original.forceCircumstanceEvaluation();}
        if(drone.getCustomData().get(KEY)==this)drone.removeCustomData(KEY);
        if(drone.getCustomData().get(KEY+".status")==factsView)drone.removeCustomData(KEY+".status");closed=true;phase=Phase.HANDED_BACK;publish();
    }
    public void requestNativeHandoff(){requireOwner();drone.setCustomData(RETURN_KEY,Boolean.TRUE);handoff("explicit-handoff",true);}
    @Override public void close(){requireOwner();if(closed)return;handoff("battle-owner-close",true);phase=Phase.CLOSED;publish();}
    public ShipAIPlugin originalAI(){return original;}
    public Map<String,Object> status(){requireOwner();publish();return Collections.unmodifiableMap(new LinkedHashMap<>(facts));}
    private void publish(){facts.put("phase",phase.name());facts.put("closed",closed);facts.put("ordinal",ordinal);facts.put("shots",shots);facts.put("moves",moves);facts.put("fireRequests",fireRequests);
        facts.put("lastShot",lastShot);facts.put("shotTimeouts",shotTimeouts);facts.put("extraAmmoDrops",extraAmmoDrops);facts.put("nativeHandoffs",nativeHandoffs);facts.put("handoffReason",handoffReason);
        facts.put("targetId",target==null?"":Objects.toString(target.getId(),""));facts.put("waypointX",waypoint.x);facts.put("waypointY",waypoint.y);
        facts.put("motherId",Objects.toString(mother.getId(),""));facts.put("originalAI",original.getClass().getName());facts.put("nativeTimedReturnExists",false);facts.put("sourceLauncherRange",cachedLauncherRange);
        facts.put("routerAssigned",routerAssigned);facts.put("routerTargetId",routedTarget==null?"":Objects.toString(routedTarget.getId(),""));
        facts.put("targetingMode",targetingMode);facts.put("autonomySuspended",autonomySuspended);facts.put("clearEpoch",clearEpochSeen);
        facts.put("directApproach",directApproach);facts.put("configuredBaseSpeed",CRUISE_SPEED);facts.put("nativeMaxSpeed",drone.getMaxSpeed());
        facts.put("actualSpeed",speed());facts.put("maximumObservedSpeed",maximumObservedSpeed);
        facts.put("nativeAcceleration",drone.getAcceleration());facts.put("nativeDeceleration",drone.getDeceleration());
        facts.put("phaseSeconds",phaseSeconds);facts.put("lastShotX",lastShotPosition.x);facts.put("lastShotY",lastShotPosition.y);facts.put("lastShotTime",lastShotTime);facts.put("shotPositions",List.copyOf(shotPositions));
        facts.put("returnPending",returnPending);facts.put("sortieShotLimit",SORTIE_SHOTS);facts.put("returnStartedAt",returnStartedAt);facts.put("landingStartedAt",landingStartedAt);facts.put("returnedAt",returnedAt);
        facts.put("nativeBeamBrightness",brightness());facts.put("nativeCharge",gun.getChargeLevel());facts.put("nativeAmmo",gun.getAmmo());facts.put("nativeCooldownRemaining",gun.getCooldownRemaining());
    }
    private float speed(){return drone.getVelocity().length();}
    private static float distance(Vector2f a,Vector2f b){return(float)Math.hypot(a.x-b.x,a.y-b.y);}
    private static float angle(Vector2f a,Vector2f b){return(float)Math.toDegrees(Math.atan2(b.y-a.y,b.x-a.x));}
    private static float delta(float degrees){float result=degrees%360;if(result>180)result-=360;if(result< -180)result+=360;return result;}
    private static Vector2f point(Vector2f center,float degrees,float radius){double a=Math.toRadians(degrees);return new Vector2f(center.x+(float)Math.cos(a)*radius,center.y+(float)Math.sin(a)*radius);}
    private static String slot(WeaponAPI weapon){return weapon.getSlot()==null?"":weapon.getSlot().getId();}
    private static int identityIndex(List<WeaponGroupAPI> list,WeaponGroupAPI wanted){for(int i=0;i<Math.min(16,list.size());i++)if(list.get(i)==wanted)return i;return -1;}
    private static boolean contains(List<ShipAPI> list,ShipAPI target){for(ShipAPI candidate:list)if(candidate==target)return true;return false;}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Night funnel AI requires the battle thread");}
    @Override public void setDoNotFireDelay(float amount){requireOwner();if(!Float.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid no-fire delay");noFireDelay=Math.max(noFireDelay,amount);original.setDoNotFireDelay(amount);}
    @Override public void forceCircumstanceEvaluation(){original.forceCircumstanceEvaluation();}
    @Override public boolean needsRefit(){return original.needsRefit();}
    @Override public ShipwideAIFlags getAIFlags(){return original.getAIFlags();}
    @Override public void cancelCurrentManeuver(){requireOwner();if(closed||returnPending)return;original.cancelCurrentManeuver();shotArmed=false;transition(brightness()>.001f||gun.isInBurst()?Phase.RECOVER:Phase.WAITING_TARGET);denyFire();}
    @Override public ShipAIConfig getConfig(){return original.getConfig();}
    @Override public void setTargetOverride(ShipAPI target){original.setTargetOverride(target);}
}
