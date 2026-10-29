package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import data.scripts.weapons.Moci_MechDecoWeaponMutexControl;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Physical left-shield pose adapter. The native per-weapon advanceAfter is its only clock owner.
 * It never advances Moci callbacks, teleports modules, changes collision/damage or simulates a shield hit.
 */
public final class AdaptiveShieldGuard {
    public static final String KEY="xi.arsenal.adaptiveShieldGuard";
    public static final String DECLARATIONS="data/config/adaptive_shield_guard.json";
    private static final String STATE=KEY+".state",CONFIG=KEY+".config";
    private static final int MAX_OBJECTS=4096,MAX_ENEMIES=64,MAX_WEAPONS=96;
    private static final float HORIZON=1.2f,SCAN_SECONDS=.06f,HOLD_SECONDS=.18f,SCAN_RADIUS=2600f;
    private static final float TURN_SPEED=220f;
    private static final Set<String> JOINT_SLOTS=Set.of("ARM_L","ARM_L_SOURCE","ARM_L_FOLLOW","SHIELD_L");
    private AdaptiveShieldGuard() { }

    /** Call at the end of the one native arm-pose chain, after higher-priority actions. */
    public static void applyAfter(WeaponAPI arm) {
        if(arm==null||arm.getSlot()==null||!"ARM_L".equals(arm.getSlot().getId()))return;
        CombatEngineAPI engine=Global.getCombatEngine();ShipAPI ship=arm.getShip();
        if(engine==null||ship==null)return;
        if(engine.isCombatOver()||!ship.isAlive()||ship.isHulk()){AdaptiveShieldVisualLayer.releaseParent(engine,ship);return;}
        if(engine.isPaused())return;
        State state=state(engine,ship,arm);
        float stamp=engine.getTotalElapsedTime(false);
        if(state.lastFrame==stamp)return;state.lastFrame=stamp;
        float dt=state.pendingStep>=0?state.pendingStep:engine.getElapsedInLastFrame()*Math.max(0,ship.getMutableStats().getTimeMult().getModifiedValue());
        state.pendingStep=-1;
        if(!Float.isFinite(dt)||dt<0)throw new IllegalStateException("Invalid adaptive shield native step");
        dt=Math.min(.1f,dt);state.clock+=dt;state.scan-=dt;
        state.ordinaryPhase=false;
        Mount mount=state.declaration==null?null:state.declaration.mount(ship);
        ShipAPI shield=physicalShield(ship,mount);
        if(mount==null||shield==null){if(!ensureDskShieldLayer(engine,ship))AdaptiveShieldVisualLayer.releaseParent(engine,ship);state.release("No declared live ARM_L / SHIELD_L structure");return;}
        if(AdaptiveShieldVisualLayer.foldedForTransform(ship)){AdaptiveShieldVisualLayer.ensure(engine,ship,shield);state.release("Zeta transform owns the folded physical shield");return;}
        AdaptiveShieldVisualLayer.ensure(engine,ship,shield);
        String blocked=blocked(engine,ship,arm);
        if(blocked!=null){state.release(blocked);return;}
        if(!state.resolveJoint(mount)){state.release(state.jointFailure);return;}
        state.ordinaryPhase=true;
        // The original fixed-defense controller may itself leave -50 on a ±45 slot.
        // Fence every ordinary frame, including no-target / expired / rejected paths.
        float inherited=shortest(arm.getCurrAngle()-ship.getFacing());
        if(!state.range.contains(inherited)){
            writeJointPose(ship,arm,shield,state.range.project(inherited));state.fenceWrites++;
        }
        if(state.scan<=0){state.scan=SCAN_SECONDS;state.choose(engine,ship,shield,mount);}
        if(state.pick==null||state.clock-state.lastThreat>HOLD_SECONDS){state.release("No predicted incoming hit or aimed hostile weapon");return;}
        float desired=jointTarget((float)state.pick.angle(),ship.getFacing(),mount.normalDegrees,state.range);
        if(!Float.isFinite(desired)){state.release("Incoming direction is outside this left arm's physical articulation");return;}
        // Project an inherited native/previous action angle BEFORE the first write.
        // This is a bounded joint, not a circular turret: never take a wraparound shortcut.
        state.local=guardStep(state.local,shortest(arm.getCurrAngle()-ship.getFacing()),state.active,desired,TURN_SPEED*dt,state.range);state.active=true;
        writeJointPose(ship,arm,shield,state.local);
        state.reason="Physical shield follows a predicted threat";state.writes++;
        state.publish(mount,shield,desired);
    }
    private static void writeJointPose(ShipAPI ship,WeaponAPI arm,ShipAPI shield,float local) {
        float world=normalize(ship.getFacing()+local);
        arm.setCurrAngle(world);
        int checked=0;
        for(WeaponAPI weapon:ship.getAllWeapons()) {
            if(++checked>MAX_WEAPONS)break;
            if(weapon.getSlot()==null)continue;String slot=weapon.getSlot().getId();
            if("ARM_L_SOURCE".equals(slot)||"ARM_L_FOLLOW".equals(slot)||("SHIELD_L_COVER".equals(slot)&&weapon.isDecorative()))weapon.setCurrAngle(world);
        }
        // The original parent-slot binding remains authoritative for location, collision and damage.
        shield.ensureClonedStationSlotSpec();shield.getStationSlot().setAngle(shortest(world-ship.getFacing()));
        shield.setFacing(world);
    }
    static void recordFrame(float amount,CombatEngineAPI engine,WeaponAPI arm) {
        if(engine==null||arm==null||arm.getShip()==null||arm.getSlot()==null||!"ARM_L".equals(arm.getSlot().getId()))return;
        if(!Float.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid native shield callback amount");
        state(engine,arm.getShip(),arm).pendingStep=amount;
    }
    /** Pose owners may keep the declared native shield foreground while guard steering is blocked. No clock advances. */
    public static void ensureVisualLayer(ShipAPI ship) {
        CombatEngineAPI engine=Global.getCombatEngine();
        if(engine==null||ship==null)return;
        if(!ship.isAlive()||ship.isHulk()||engine.isCombatOver()){AdaptiveShieldVisualLayer.releaseParent(engine,ship);return;}
        for(WeaponAPI arm:ship.getAllWeapons())if(arm.getSlot()!=null&&"ARM_L".equals(arm.getSlot().getId())) {
            Declaration declaration=config(engine).byWeapon.get(arm.getId());Mount mount=declaration==null?null:declaration.mount(ship);ShipAPI shield=physicalShield(ship,mount);
            if(shield!=null)AdaptiveShieldVisualLayer.ensure(engine,ship,shield);else if(!ensureDskShieldLayer(engine,ship))AdaptiveShieldVisualLayer.releaseParent(engine,ship);
            return;
        }
        AdaptiveShieldVisualLayer.releaseParent(engine,ship);
    }
    /** DSK has the same physical shield as Xi but is a distinct skin absent
     * from the ordinary guard declarations. Lease only its native visual layer;
     * do not enable or steer the ordinary shield guard for this skin. */
    private static boolean ensureDskShieldLayer(CombatEngineAPI engine,ShipAPI ship){
        if(!DskRifleCurveV2.isDsk(ship))return false;
        for(ShipAPI child:ship.getChildModulesCopy())if(child!=null&&child.getParentStation()==ship&&child.getStationSlot()!=null
                &&"SHIELD_L".equals(child.getStationSlot().getId())&&child.isAlive()&&!child.isHulk()
                &&matchesHull(child,"Moci_RX_105_SHIELD_L")){
            AdaptiveShieldVisualLayer.ensure(engine,ship,child);return true;
        }
        return false;
    }
    static String originalEffect(CombatEngineAPI engine,String weaponId) {
        Declaration declaration=config(engine).byWeapon.get(weaponId);
        if(declaration==null||!declaration.wrapper)throw new IllegalStateException("Missing explicit original callback declaration for "+weaponId);
        return declaration.effect;
    }
    public static Map<String,Object> status(ShipAPI ship) {
        if(ship==null)return Map.of("active",false,"reason","No ship");
        Object found=ship.getCustomData().get(KEY);return found instanceof Map<?,?> map?stringMap(map):Map.of("active",false,"reason","No shield callback observed");
    }
    private static Map<String,Object> stringMap(Map<?,?> input) {Map<String,Object> result=new LinkedHashMap<>();input.forEach((k,v)->{if(k instanceof String key)result.put(key,v);});return Collections.unmodifiableMap(result);}
    private static State state(CombatEngineAPI engine,ShipAPI ship,WeaponAPI arm) {
        Object existing=ship.getCustomData().get(STATE);
        if(existing instanceof State state&&state.arm==arm)return state;
        State created=new State(ship,arm,config(engine).byWeapon.get(arm.getId()));ship.setCustomData(STATE,created);return created;
    }
    private static ShipAPI physicalShield(ShipAPI ship,Mount mount) {
        if(mount==null)return null;
        for(ShipAPI module:ship.getChildModulesCopy())if(module!=null&&module.getParentStation()==ship&&module.getStationSlot()!=null
                &&"SHIELD_L".equals(module.getStationSlot().getId())&&module.isAlive()&&!module.isHulk()
                &&matchesHull(module,mount.moduleHull))return module;
        return null;
    }
    private static boolean matchesHull(ShipAPI ship,String id) {return id.equals(ship.getHullSpec().getHullId())||id.equals(ship.getHullSpec().getBaseHullId());}
    private static String blocked(CombatEngineAPI engine,ShipAPI ship,WeaponAPI arm) {
        if(ship.controlsLocked())return "Native controls are locked";
        if(FullSalvoPose.isBlockingRifle(ship))return "Full salvo pose has priority";
        if(RifleBracedPose.blocksGuard(ship))return "Two-hand rifle support owns the left arm";
        if(ship.getCustomData().containsKey("xi.arsenal.zeta.armAction.owner"))return "Zeta exclusive arm action has priority";
        Map<String,Object> riflePose=RifleFiringPose.status(ship);
        if(Boolean.TRUE.equals(riflePose.get("blocksGuard"))||((Number)riflePose.getOrDefault("blend",0f)).floatValue()>.00001f)return "Rifle release/closing pose has priority";
        if(Boolean.TRUE.equals(ship.getCustomData().get("xi.arsenal.pose.blockShield")))return "Declared higher-priority pose";
        if(Moci_MechDecoWeaponMutexControl.isBladeSwingActive(arm))return "Original left blade animation has priority";
        if(Boolean.TRUE.equals(ship.getCustomData().get("MOCI_MSZ_006_TRANSFORMED"))||Boolean.TRUE.equals(ship.getCustomData().get("MOCI_MSZ_006_PARTS_HIDDEN"))
                ||Boolean.TRUE.equals(ship.getCustomData().get("MOCI_MSZ_006_TRANSFORM_PREPARE")))return "Native transformation has priority";
        Object transform=ship.getCustomData().get("MOCI_MSZ_006_TRANSFORM_STATE");
        if(transform!=null&&!"NORMAL".equals(String.valueOf(transform)))return "Native transformation is in progress";
        Object skill=engine.getCustomData().get(UnicornNtdOverloadSkill.KEY+".owner");
        if(skill instanceof UnicornNtdOverloadSkill electronic&&electronic.availability(ship).charging())return "NTD electronic-warfare arm pose has priority";
        int count=0;for(WeaponAPI weapon:ship.getAllWeapons())if(++count<=MAX_WEAPONS&&weapon.getSlot()!=null
                &&Set.of("ARM_L_SOURCE","ARM_L_FOLLOW").contains(weapon.getSlot().getId())&&!weapon.isDecorative()
                &&(weapon.isFiring()||weapon.getChargeLevel()>.001f))return "The left arm's native weapon action has priority";
        return null;
    }

    /** Pure relative-motion input for prediction contracts; independent of native singleton state. */
    public record Motion(double x,double y,double vx,double vy,double radius) {
        public Motion {if(!finite(x,y,vx,vy,radius)||radius<0)throw new IllegalArgumentException("Invalid defender motion");}
    }
    public record Threat(String id,double x,double y,double vx,double vy,double radius,double damage) {
        public Threat {if(id==null||id.length()>128||!finite(x,y,vx,vy,radius,damage)||radius<0||damage<0)throw new IllegalArgumentException("Invalid projectile threat");}
    }
    public record Intercept(String id,double angle,double seconds,double missDistance,double score,String kind) { }
    /** First entry into the defender's swept safety circle, not nearest-distance targeting. */
    public static Intercept predict(Motion own,Threat threat,double horizon,double padding) {
        if(!finite(horizon,padding)||horizon<=0||horizon>5||padding<0)throw new IllegalArgumentException("Invalid prediction bounds");
        double x=threat.x-own.x,y=threat.y-own.y,vx=threat.vx-own.vx,vy=threat.vy-own.vy;
        double vv=vx*vx+vy*vy,dot=x*vx+y*vy;
        if(vv<.0001||dot>=0)return null;
        double t=-dot/vv;if(t<0)return null;
        double mx=x+vx*t,my=y+vy*t,miss=Math.hypot(mx,my),radius=own.radius+threat.radius+padding;
        if(miss>radius)return null;
        double discriminant=dot*dot-vv*(x*x+y*y-radius*radius);
        double first=Math.max(0,(-dot-Math.sqrt(Math.max(0,discriminant)))/vv);
        if(first>horizon)return null;
        // The plate normal opposes the incoming relative velocity. A near-grazing hit's
        // contact point on the safety circle can jump sideways with tiny dispersion;
        // facing that point would visibly flap the shield despite parallel incoming shots.
        double angle=Math.toDegrees(Math.atan2(-vy,-vx));
        double score=2+1/(.08+first)+Math.log1p(Math.min(100000,threat.damage))*.13;
        return new Intercept(threat.id,angle,first,miss,score,"projectile");
    }
    public static boolean aimedAt(Motion own,double x,double y,double facing,double range) {
        if(!finite(x,y,facing,range)||range<=0)return false;
        double dx=own.x-x,dy=own.y-y,r=Math.toRadians(facing),forward=dx*Math.cos(r)+dy*Math.sin(r);
        return forward>0&&forward<=range+own.radius&&Math.abs(dx*Math.sin(r)-dy*Math.cos(r))<=own.radius+12;
    }
    private static boolean finite(double... values){for(double value:values)if(!Double.isFinite(value))return false;return true;}
    private static float normalize(float degrees){float v=degrees%360;return v<0?v+360:v;}
    private static float shortest(float degrees){float v=normalize(degrees);return v>180?v-360:v;}
    public static float approach(float current,float desired,float maximum) {
        if(!Float.isFinite(current)||!Float.isFinite(desired)||!Float.isFinite(maximum)||maximum<0)throw new IllegalArgumentException("Invalid guard angular step");
        return current+Math.max(-maximum,Math.min(maximum,shortest(desired-current)));
    }
    /** A contiguous, unwrapped authored joint interval; zero-width joints are allowed. */
    public record JointRange(float minimum,float maximum) {
        public JointRange {if(!finite(minimum,maximum)||minimum>maximum||maximum-minimum>=360||Math.abs(minimum)>720||Math.abs(maximum)>720)throw new IllegalArgumentException("Invalid shield joint interval");}
        float center(){return (minimum+maximum)*.5f;}
        float equivalent(float angle){return center()+shortest(angle-center());}
        public boolean contains(float angle){float local=equivalent(angle);return local>=minimum&&local<=maximum;}
        float project(float angle){return Math.max(minimum,Math.min(maximum,equivalent(angle)));}
    }
    public record JointSector(String id,float angle,float arc) {
        public JointSector {if(id==null||!finite(angle,arc)||arc<0||arc>360)throw new IllegalArgumentException("Invalid authored shield sector");}
    }
    /** Native slots are relative to SHIP, not BODY. 360-degree decorative slots impose no joint limit. */
    public static JointRange authoredJointRange(Collection<JointSector> sectors) {
        JointRange result=null;
        for(JointSector sector:sectors)if(JOINT_SLOTS.contains(sector.id)&&sector.arc<360){
            float center=shortest(sector.angle);JointRange next=new JointRange(center-sector.arc*.5f,center+sector.arc*.5f);
            if(result==null)result=next;else {result=intersect(result,next);if(result==null)return null;}
        }
        return result;
    }
    public static JointRange intersect(JointRange first,JointRange second) {
        if(first==null||second==null)return null;
        float shift=360f*Math.round((first.center()-second.center())/360f);
        float minimum=Math.max(first.minimum,second.minimum+shift),maximum=Math.min(first.maximum,second.maximum+shift);
        return minimum<=maximum?new JointRange(minimum,maximum):null;
    }
    /** BODY policy shifts into SHIP coordinates before intersecting native hard traverse. */
    public static JointRange jointEnvelope(JointRange nativeRange,boolean hasNativeLimit,JointRange bodyRange,float bodyOffset) {
        if(!Float.isFinite(bodyOffset))throw new IllegalArgumentException("Invalid BODY joint reference");
        if(hasNativeLimit&&nativeRange==null)return null;
        JointRange shifted=bodyRange==null?null:new JointRange(bodyRange.minimum+bodyOffset,bodyRange.maximum+bodyOffset);
        return hasNativeLimit?(shifted==null?nativeRange:intersect(nativeRange,shifted)):shifted;
    }
    /** NaN means unreachable; never clamp an out-of-sector incoming threat into a fake block. */
    public static float jointTarget(float incomingWorld,float shipFacing,float plateNormal,JointRange range) {
        if(range==null||!finite(incomingWorld,shipFacing,plateNormal))return Float.NaN;
        float local=range.equivalent(incomingWorld-shipFacing-plateNormal);
        return local>=range.minimum&&local<=range.maximum?local:Float.NaN;
    }
    public static float advanceJoint(float current,float desired,float maximum,JointRange range) {
        if(range==null||!finite(current,desired,maximum)||maximum<0||!range.contains(desired))throw new IllegalArgumentException("Invalid bounded shield step");
        float start=range.project(current),end=range.equivalent(desired);
        return Math.max(range.minimum,Math.min(range.maximum,start+Math.max(-maximum,Math.min(maximum,end-start))));
    }
    /** Native callbacks can rewrite their rest angle every frame; active motor progress remains ours. */
    public static float guardStep(float tracked,float observed,boolean active,float desired,float maximum,JointRange range) {
        return advanceJoint(active?tracked:observed,desired,maximum,range);
    }
    private record Mount(String hull,String moduleHull,float normalDegrees,JointRange bodyRange,String jointEvidence) { }
    private record Declaration(String weapon,String effect,boolean wrapper,List<Mount> mounts) {
        Mount mount(ShipAPI ship){for(Mount value:mounts)if(matchesHull(ship,value.hull))return value;return null;}
    }
    private record Configuration(Map<String,Declaration> byWeapon) { }
    private static Configuration config(CombatEngineAPI engine) {
        if(engine==null)throw new IllegalStateException("Shield declarations require the native callback engine");
        Object existing=engine.getCustomData().get(CONFIG);if(existing instanceof Configuration configuration)return configuration;
        try {
            JSONObject json=Global.getSettings().loadJSON(DECLARATIONS);if(json.getInt("schema")!=1)throw new IllegalArgumentException("Unknown shield declaration schema");
            Map<String,Declaration> result=new LinkedHashMap<>();JSONArray rows=json.getJSONArray("weapons");
            if(rows.length()>128)throw new IllegalArgumentException("Excessive shield declarations");
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);List<Mount> mounts=new ArrayList<>();JSONArray entries=row.getJSONArray("mounts");
                if(entries.length()>128)throw new IllegalArgumentException("Excessive shield mounts");
                for(int j=0;j<entries.length();j++){JSONObject mount=entries.getJSONObject(j);float normal=(float)mount.getDouble("normalDegrees");
                    if(!Float.isFinite(normal))throw new IllegalArgumentException("Invalid physical shield normal");
                    JointRange joint=null;String evidence="";
                    if(mount.has("guardJointMinimumDegrees")||mount.has("guardJointMaximumDegrees")){
                        joint=new JointRange((float)mount.getDouble("guardJointMinimumDegrees"),(float)mount.getDouble("guardJointMaximumDegrees"));
                        evidence=mount.getString("guardJointEvidence");if(evidence.isBlank())throw new IllegalArgumentException("Missing declared BODY joint evidence");}
                    mounts.add(new Mount(mount.getString("hullId"),mount.getString("moduleHullId"),normal,joint,evidence));}
                Declaration value=new Declaration(row.getString("weaponId"),row.getString("originalEffect"),row.getBoolean("wrapper"),List.copyOf(mounts));
                if(result.putIfAbsent(value.weapon,value)!=null)throw new IllegalArgumentException("Duplicate shield weapon declaration");
            }
            Configuration configuration=new Configuration(Map.copyOf(result));engine.getCustomData().put(CONFIG,configuration);return configuration;
        }catch(Exception error){throw new IllegalStateException("Cannot read exact physical left-shield declarations",error);}
    }
    private static final class State {
        final ShipAPI ship;final WeaponAPI arm;final Declaration declaration;
        final Map<String,Object> facts=new LinkedHashMap<>();
        float lastFrame=Float.NaN,pendingStep=-1,clock,scan,lastThreat,local;boolean active,ordinaryPhase;long writes,scans,truncated,unreachable,fenceWrites;
        ShipHullSpecAPI authoredHull;JointRange nativeRange,range;boolean hasNativeLimit;WeaponAPI body;float bodyNeutral,bodyOffset;
        List<JointSector> nativeSectors=List.of();
        Intercept pick;String reason="Awaiting native shield callback",jointFailure="No authored finite traverse or declared BODY joint policy";
        State(ShipAPI ship,WeaponAPI arm,Declaration declaration){this.ship=ship;this.arm=arm;this.declaration=declaration;facts.put("schema",1);ship.setCustomData(KEY,Collections.unmodifiableMap(facts));}
        void release(String why){active=false;pick=null;reason=why;publish(null,null,0);}
        boolean resolveJoint(Mount mount) {
            if(authoredHull!=ship.getHullSpec()){
                authoredHull=ship.getHullSpec();List<JointSector> sectors=new ArrayList<>();body=null;bodyNeutral=0;hasNativeLimit=false;
                for(WeaponSlotAPI slot:authoredHull.getAllWeaponSlotsCopy()){
                    if(JOINT_SLOTS.contains(slot.getId())){JointSector sector=new JointSector(slot.getId(),slot.getAngle(),slot.getArc());sectors.add(sector);if(sector.arc<360)hasNativeLimit=true;}
                    if("BODY".equals(slot.getId()))bodyNeutral=slot.getAngle();
                }
                nativeSectors=List.copyOf(sectors);nativeRange=authoredJointRange(nativeSectors);
                for(WeaponAPI weapon:ship.getAllWeapons())if(weapon.getSlot()!=null&&"BODY".equals(weapon.getSlot().getId())){body=weapon;break;}
            }
            if(mount.bodyRange!=null&&body==null){range=null;jointFailure="Declared BODY joint reference is absent";return false;}
            bodyOffset=body==null?0:shortest(body.getCurrAngle()-bodyNeutral-ship.getFacing());
            range=jointEnvelope(nativeRange,hasNativeLimit,mount.bodyRange,bodyOffset);
            if(range==null)jointFailure=hasNativeLimit?"Authored native left-arm traverse / BODY joint ranges conflict":"No authored finite traverse or declared BODY joint policy";
            facts.put("jointEvidence",mount.bodyRange==null?"Intersection of exact merged HullSpec finite native slots":mount.jointEvidence);
            facts.put("jointReference","SHIP native traverse intersect declared BODY-neutral envelope");
            facts.put("nativeJointSectors",nativeSectors.stream().map(s->Map.of("id",s.id,"angle",s.angle,"arc",s.arc)).toList());
            facts.put("bodyNeutralOffset",bodyOffset);return range!=null;
        }
        void choose(CombatEngineAPI engine,ShipAPI own,ShipAPI shield,Mount mount) {
            scans++;Motion motion=new Motion(own.getLocation().x,own.getLocation().y,own.getVelocity().x,own.getVelocity().y,own.getCollisionRadius());
            Intercept best=null,previous=null;int objects=0,enemies=0;
            Iterator<?> iterator=engine.getAllObjectGrid()!=null?engine.getAllObjectGrid().getCheckIterator(own.getLocation(),SCAN_RADIUS*2,SCAN_RADIUS*2):engine.getProjectiles().iterator();
            Set<Object> visited=Collections.newSetFromMap(new IdentityHashMap<>());
            while(iterator.hasNext()&&objects++<MAX_OBJECTS) {
                Object object=iterator.next();if(!visited.add(object))continue;
                Intercept candidate=null;
                if(object instanceof DamagingProjectileAPI projectile&&projectile.getOwner()>=0&&projectile.getOwner()!=own.getOwner()
                        &&!projectile.didDamage()&&!projectile.isFading()&&projectile.getCollisionClass()!=CollisionClass.NONE) {
                    if(!finite(projectile.getLocation().x,projectile.getLocation().y,projectile.getVelocity().x,projectile.getVelocity().y,projectile.getCollisionRadius(),projectile.getDamageAmount()))continue;
                    String spec=Objects.toString(projectile.getProjectileSpecId(),"unknown");
                    String id="projectile:"+spec.substring(0,Math.min(80,spec.length()))+":"+Integer.toUnsignedString(System.identityHashCode(projectile));
                    candidate=predict(motion,new Threat(id,projectile.getLocation().x,projectile.getLocation().y,projectile.getVelocity().x,projectile.getVelocity().y,
                            Math.max(0,projectile.getCollisionRadius()),Math.max(0,projectile.getDamageAmount())),HORIZON,8);
                }else if(object instanceof ShipAPI enemy&&enemies++<MAX_ENEMIES&&enemy!=own&&enemy.getOwner()>=0&&enemy.getOwner()!=own.getOwner()&&enemy.isAlive()&&!enemy.isHulk()) {
                    int count=0;for(WeaponAPI weapon:enemy.getAllWeapons()){
                        if(++count>MAX_WEAPONS)break;
                        if(weapon.isDecorative()||weapon.isDisabled()||(!weapon.isFiring()&&weapon.getChargeLevel()<=.02f))continue;
                        if(aimedAt(motion,weapon.getLocation().x,weapon.getLocation().y,weapon.getCurrAngle(),weapon.getRange())) {
                            String id="aim:"+Objects.toString(enemy.getId(),"")+":"+(weapon.getSlot()==null?weapon.getId():weapon.getSlot().getId());
                            candidate=new Intercept(id,Math.toDegrees(Math.atan2(weapon.getLocation().y-own.getLocation().y,weapon.getLocation().x-own.getLocation().x)),.8,0,.5,"weapon-aim");break;
                        }
                    }
                }
                if(candidate==null)continue;
                if(!Float.isFinite(jointTarget((float)candidate.angle,own.getFacing(),mount.normalDegrees,range))){unreachable++;continue;}
                if(pick!=null&&pick.id.equals(candidate.id))previous=candidate;
                if(best==null||candidate.score>best.score||(candidate.score==best.score&&candidate.id.compareTo(best.id)<0))best=candidate;
            }
            if(iterator.hasNext())truncated++;
            if(previous!=null&&best!=null&&best.score<previous.score*1.22)best=previous;
            if(best!=null){pick=best;lastThreat=clock;}
        }
        void publish(Mount mount,ShipAPI shield,float desired) {
            facts.put("active",active);facts.put("reason",reason);facts.put("weaponId",Objects.toString(arm.getId(),""));facts.put("shipId",Objects.toString(ship.getId(),""));
            facts.put("scans",scans);facts.put("scanBudgetTruncations",truncated);facts.put("poseWrites",writes);facts.put("localAngle",local);facts.put("desiredLocalAngle",desired);
            facts.put("actualLocalAngle",shortest(arm.getCurrAngle()-ship.getFacing()));
            facts.put("unreachableThreats",unreachable);
            facts.put("ordinaryJointPhase",ordinaryPhase);facts.put("jointFenceWrites",fenceWrites);
            if(range!=null){facts.put("minimumLocalAngle",range.minimum);facts.put("maximumLocalAngle",range.maximum);}else{facts.remove("minimumLocalAngle");facts.remove("maximumLocalAngle");}
            facts.put("turnDegreesPerLocalSecond",TURN_SPEED);
            facts.put("targetId",pick==null?"":pick.id);facts.put("threatKind",pick==null?"":pick.kind);facts.put("predictedEntrySeconds",pick==null?0:pick.seconds);
            facts.put("normalDegrees",mount==null?0:mount.normalDegrees);facts.put("shieldHull",shield==null?"":shield.getHullSpec().getHullId());
            facts.put("scope","Native left-arm late pose; original animation/collision/damage retained; physical normal inferred from declared shield bounds, requires native visual acceptance");
        }
    }
}
