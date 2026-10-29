package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.awt.Color;
import java.util.*;
import org.lwjgl.util.vector.Vector2f;

/** Native EMP visuals only. Advance once from the charge mount's advanceAfter, never from render. */
public final class NativeRifleEmp implements AutoCloseable {
    public static final float INTERVAL=.12f, LIFETIME=.18f;
    public static final int MAX_PER_BURST=3, MAX_LIVE=6, MAX_BEAMS=8;
    public static final Color FRINGE=new Color(65,150,255,210), CORE=new Color(245,252,255,240);
    private static final class Owned {
        final EmpArcEntityAPI arc;
        float age;
        Owned(EmpArcEntityAPI arc){this.arc=arc;}
    }
    private final Thread owner=Thread.currentThread();
    private final List<Owned> live=new ArrayList<>();
    private CombatEngineAPI engine;
    private WeaponAPI source,charge;
    private double elapsed,nextEmission;
    private long spawned,removed,bursts,chargeArcs,beamArcs,lockoutArcs;
    private int peakLive,lastBurst;
    private boolean closed;
    private String phase="IDLE";
    private Vector2f muzzle,gunBody,rightArm,lastBeamFrom,lastBeamTo;
    private Map<String,Object> facts=Map.of("spawned",0L,"live",0,"phase","IDLE","closed",false);

    /** amount is the weapon callback's already-local step; do not multiply it by the ship time stat again. */
    public Map<String,Object> advance(float amount,CombatEngineAPI engine,WeaponAPI sourceWeapon,
            WeaponAPI chargeWeapon,Map<String,Object> ownStatus) {
        checkThread();
        if(!Float.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid native EMP local step");
        if(closed)return facts;
        Objects.requireNonNull(engine,"engine");
        if(this.engine!=null&&this.engine!=engine)throw new IllegalStateException("Native EMP cannot cross battle engines");
        if(engine.isPaused()||amount==0)return facts;
        bind(engine,sourceWeapon,chargeWeapon);
        elapsed+=amount;lastBurst=0;
        expire(amount,false);
        Map<String,Object> status=ownStatus==null?Map.of():ownStatus;
        ShipAPI ship=source.getShip();
        if(!ship.isAlive()||ship.isHulk()||ship.wasRemoved()||ship.isPhased()||Boolean.TRUE.equals(status.get("closed"))){phase="IDLE";publish();return facts;}

        float lockAge=number(status,"lockoutAge",-1),held=number(status,"heldLocal",0);
        String ownPhase=String.valueOf(status.getOrDefault("phase","IDLE"));
        boolean overlimit=Boolean.TRUE.equals(status.get("overlimit"))||number(status,"overlimitPower",0)>0;
        List<BeamAPI> rays=brightRays(ship);
        if(lockAge>=0&&lockAge<5)phase="LOCKOUT";
        else if(overlimit&&!rays.isEmpty())phase="BEAM";
        else if(held>10&&(ownPhase.equals("HOLDING")||ownPhase.equals("PRESENTING")||ownPhase.equals("RELEASING")))phase="CHARGE";
        else phase="IDLE";
        if(phase.equals("IDLE")){publish();return facts;}

        sampleAnchors(ship);
        if(elapsed+1e-7>=nextEmission){
            // No catch-up loop: even a long frame emits only one bounded batch.
            nextEmission=elapsed+INTERVAL;bursts++;
            if(phase.equals("BEAM"))emitBeam(rays);
            else if(phase.equals("LOCKOUT"))emitLockout(ship);
            else emitCharge(ship);
        }
        publish();return facts;
    }

    private void bind(CombatEngineAPI engine,WeaponAPI sourceWeapon,WeaponAPI chargeWeapon){
        Objects.requireNonNull(sourceWeapon,"source rifle");Objects.requireNonNull(chargeWeapon,"charge rifle");
        if(source!=null){if(source!=sourceWeapon||charge!=chargeWeapon)throw new IllegalStateException("Native EMP cannot be shared by weapon instances");return;}
        if(!BeamRiflePalette.isOwnRifle(sourceWeapon.getId())||!BeamRifleModes.isCharge(chargeWeapon)
                ||sourceWeapon.getShip()==null||sourceWeapon.getShip()!=chargeWeapon.getShip())
            throw new IllegalArgumentException("Native EMP requires the owned rifle pair on one ship");
        this.engine=engine;source=sourceWeapon;charge=chargeWeapon;
    }

    private void sampleAnchors(ShipAPI ship){
        muzzle=BeamRifleMount.visibleMuzzleWorld(source);
        SpriteAPI sprite=BeamRifleMount.visibleGunSprite(source);
        // Same PNG-to-world convention as visibleMuzzleWorld, evaluated at the actual bitmap centre.
        // The live sprite centre already includes recoil/pose translation; adding it again would double it.
        float right=sprite.getWidth()*.5f-sprite.getCenterX(),up=sprite.getHeight()*.5f-sprite.getCenterY();
        double a=Math.toRadians(source.getCurrAngle());
        gunBody=new Vector2f(source.getLocation().x+up*(float)Math.cos(a)+right*(float)Math.sin(a),
                source.getLocation().y+up*(float)Math.sin(a)-right*(float)Math.cos(a));
        rightArm=null;
        int checked=0;for(WeaponAPI part:ship.getAllWeapons()){
            if(++checked>256)break;
            if(part!=null&&part.getSlot()!=null&&"ARM_R".equals(part.getSlot().getId())){
                rightArm=Vector2f.add(part.getLocation(),RifleFiringPose.gunTranslation(source),null);break;
            }
        }
    }

    private List<BeamAPI> brightRays(ShipAPI ship){
        List<BeamAPI> result=new ArrayList<>();int checked=0;
        for(BeamAPI ray:charge.getBeams()){
            if(++checked>MAX_BEAMS)break;
            if(ray!=null&&ray.getWeapon()==charge&&ray.getSource()==ship&&ray.getBrightness()>.001f
                    &&Float.isFinite(ray.getWidth())&&ray.getWidth()>=0&&finite(ray.getFrom())&&finite(ray.getTo())
                    &&distanceSquared(ray.getFrom(),ray.getTo())>1e-8f)result.add(ray);
        }
        return result;
    }

    private void emitCharge(ShipAPI ship){
        SpriteAPI sprite=BeamRifleMount.visibleGunSprite(source);
        float across=Math.max(5,Math.min(14,sprite.getWidth()*.11f));
        emit(offset(muzzle,-3,-across),ship,offset(muzzle,4,across),ship,4.5f);
        emit(offset(gunBody,-10,-across*.5f),ship,offset(gunBody,11,across*.5f),ship,4f);
        emit(offset(gunBody,6,-across),ship,offset(muzzle,-9,across*.35f),ship,4f);
    }

    private void emitLockout(ShipAPI ship){
        emit(offset(gunBody,-12,-5),ship,offset(gunBody,12,6),ship,5f);
        if(rightArm!=null){
            emit(offset(rightArm,-7,-9),ship,offset(rightArm,8,9),ship,5f);
            emit(offset(rightArm,5,4),ship,offset(gunBody,-5,-3),ship,4.5f);
        }else{
            emit(offset(gunBody,-7,8),ship,offset(muzzle,-8,-3),ship,4.5f);
            emit(offset(gunBody,4,-10),ship,offset(gunBody,-9,9),ship,4f);
        }
    }

    private void emitBeam(List<BeamAPI> rays){
        for(int n=0;n<MAX_PER_BURST;n++){
            BeamAPI ray=rays.get(n%rays.size());
            Vector2f from=new Vector2f(ray.getFrom()),to=new Vector2f(ray.getTo());
            lastBeamFrom=new Vector2f(from);lastBeamTo=new Vector2f(to);
            // Short native arcs travel inside the actual hit-clipped segment. A long beam must not
            // become three thousand-unit wires, and the muzzle-side segment must remain visible.
            float length=(float)Math.sqrt(distanceSquared(from,to));
            float span=Math.min(n==0?220:420,length*(n==0?.2f:.25f));
            float start=n==0?Math.min(24,length*.04f)
                    :(float)((elapsed*.55+n*.37)%1)*Math.max(0,length-span);
            emit(lerp(from,to,start/length),null,lerp(from,to,Math.min(1,(start+span)/length)),null,
                    Math.max(4,Math.min(9,ray.getWidth()*.055f)));
        }
    }

    private void emit(Vector2f from,CombatEntityAPI fromAnchor,Vector2f to,CombatEntityAPI toAnchor,float thickness){
        if(lastBurst>=MAX_PER_BURST||live.size()>=MAX_LIVE||!finite(from)||!finite(to)||distanceSquared(from,to)<=1e-8f)return;
        EmpArcEntityAPI.EmpArcParams parameters=new EmpArcEntityAPI.EmpArcParams();
        parameters.flamesOutMissiles=false;
        parameters.zigZagReductionFactor=.25f;parameters.maxZigZagMult=.4f;
        parameters.glowSizeMult=.28f;parameters.glowAlphaMult=.35f;
        parameters.glowColorOverride=new Color(100,180,255,145);
        parameters.movementDurOverride=.12f;
        EmpArcEntityAPI arc=engine.spawnEmpArcVisual(new Vector2f(from),fromAnchor,new Vector2f(to),toAnchor,
                thickness,FRINGE,CORE,parameters);
        lastBurst++;
        if(arc==null)return;
        live.add(new Owned(arc));spawned++;peakLive=Math.max(peakLive,live.size());
        if(phase.equals("CHARGE"))chargeArcs++;else if(phase.equals("BEAM"))beamArcs++;else if(phase.equals("LOCKOUT"))lockoutArcs++;
        arc.setSingleFlickerMode(true);
        arc.setRenderGlowAtStart(true);arc.setRenderGlowAtEnd(true);
        arc.setUpdateFromOffsetEveryFrame(true);
    }

    private void expire(float step,boolean all){
        RuntimeException first=null;
        for(Iterator<Owned> iterator=live.iterator();iterator.hasNext();){
            Owned item=iterator.next();item.age+=step;
            if(item.arc.isExpired()||item.arc.wasRemoved()){iterator.remove();continue;}
            if(!all&&item.age+1e-7<LIFETIME)continue;
            try{engine.removeEntity(item.arc);iterator.remove();removed++;}
            catch(RuntimeException failure){if(first==null)first=failure;else first.addSuppressed(failure);}
        }
        if(first!=null)throw first;
    }

    private Vector2f offset(Vector2f point,float forward,float left){double a=Math.toRadians(source.getCurrAngle());return new Vector2f(point.x+forward*(float)Math.cos(a)-left*(float)Math.sin(a),point.y+forward*(float)Math.sin(a)+left*(float)Math.cos(a));}
    private static Vector2f lerp(Vector2f from,Vector2f to,float t){return new Vector2f(from.x+(to.x-from.x)*t,from.y+(to.y-from.y)*t);}
    private static float distanceSquared(Vector2f a,Vector2f b){float x=a.x-b.x,y=a.y-b.y;return x*x+y*y;}
    private static boolean finite(Vector2f point){return point!=null&&Float.isFinite(point.x)&&Float.isFinite(point.y);}
    private static float number(Map<String,Object> status,String key,float fallback){Object raw=status.get(key);return raw instanceof Number n&&Float.isFinite(n.floatValue())?n.floatValue():fallback;}
    private static List<Float> point(Vector2f point){return point==null?List.of():List.of(point.x,point.y);}
    private void publish(){
        Map<String,Object> result=new LinkedHashMap<>();result.put("phase",phase);result.put("spawned",spawned);result.put("live",live.size());result.put("removed",removed);result.put("bursts",bursts);result.put("lastBurst",lastBurst);result.put("peakLive",peakLive);result.put("chargeArcs",chargeArcs);result.put("beamArcs",beamArcs);result.put("lockoutArcs",lockoutArcs);result.put("localElapsed",elapsed);result.put("intervalLocal",INTERVAL);result.put("lifetimeLocal",LIFETIME);result.put("muzzleWorld",point(muzzle));result.put("gunBodyWorld",point(gunBody));result.put("rightArmWorld",point(rightArm));result.put("nativeBeamFrom",point(lastBeamFrom));result.put("nativeBeamTo",point(lastBeamTo));result.put("closed",closed);result.put("scope","Native spawnEmpArcVisual only; no damage/EMP damage/audio and no render-time emission");facts=Collections.unmodifiableMap(result);
    }
    public Map<String,Object> snapshot(){checkThread();return facts;}
    private void checkThread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native EMP belongs to its combat owner thread");}
    @Override public void close(){checkThread();closed=true;phase="CLOSED";try{if(engine!=null)expire(0,true);}finally{publish();}}
}
