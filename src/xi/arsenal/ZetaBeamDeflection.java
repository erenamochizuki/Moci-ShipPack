package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.combat.entities.MovingRay;
import java.awt.Color;
import java.util.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Vector2f;

/** Three local seconds of real optical interception. Ray geometry is redirected in place;
 * continuous beams use original-hit cancellation plus one bounded, collision-tested deflected tick.
 * The class never replays an original Mod callback or changes global weapon/hull data. */
public final class ZetaBeamDeflection implements DamageTakenModifier,AutoCloseable {
    public static final String KEY="xi.arsenal.zeta.beamDeflection",ARM_ACTION_KEY="xi.arsenal.zeta.armAction.owner";
    public static final float MAX_SECONDS=3f,ARM_DEGREES=22f;
    private static final int MAX_RAYS=1024,MAX_PENDING=32,MAX_DAMAGE_LEASES=64,MAX_VISUALS=32,MAX_TARGETS=256,MAX_PARTS=8;
    private static final String RAY_MARK=KEY+".rayRedirected";
    private final Thread thread=Thread.currentThread();private final CombatEngineAPI engine;private final ShipAPI ship;
    private final String modifier=KEY+"."+UUID.randomUUID(),reservation="Zeta 光束弹开#"+UUID.randomUUID();
    private final Set<ShipAPI> parts=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<DamageAPI> damageLeases=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> intercepted=Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<Tick> pending=new ArrayDeque<>();private final List<Trace> traces=new ArrayList<>();
    private final Map<WeaponAPI,Arm> arms=new IdentityHashMap<>();private OwnNewtypeBindings.Result binding=new OwnNewtypeBindings.Result(false,List.of(),"需要生物传感器过载");
    private float remaining,blend,keyClock,partClock,lastStep=.0167f;private double clock;private boolean active,closed;private String reason="需要生物传感器过载";
    private long toggles,redirectedRays,cancelledTicks,reflectedHits,budgetSkips,serial;private int rayCursor;
    public record Request(boolean accepted,String reason){}
    /** Native applyDamage parameter. A receiver may absorb once; never reflect again or credit the original deflector twice. */
    public record ReflectedDamage(String defenderId,long serial){}
    private record Tick(Vector2f origin,Vector2f direction,float length,float damage,DamageType type,boolean soft,Color fringe,Color core,float width,double born){}
    private record Trace(Vector2f from,Vector2f to,float width,Color fringe,Color core,double born){}
    private record Hit(ShipAPI target,Vector2f point,float parameter,boolean shield){}
    public ZetaBeamDeflection(CombatEngineAPI engine,ShipAPI ship){
        this.engine=Objects.requireNonNull(engine);this.ship=Objects.requireNonNull(ship);
        if(!ZetaOverdriveStats.supports(ship)||ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Complete unowned Zeta deflection actor required");
        ship.setCustomData(KEY+".owner",this);syncParts();publish();
    }
    public static boolean isReflectedDamage(Object value){return value instanceof ReflectedDamage;}
    public static ZetaBeamDeflection instance(ShipAPI ship){Object own=ship==null?null:ship.getCustomData().get(KEY+".owner");return own instanceof ZetaBeamDeflection d&&!d.closed?d:null;}
    public static boolean active(ShipAPI ship){Object own=ship==null?null:ship.getCustomData().get(KEY+".owner");return own instanceof ZetaBeamDeflection d&&d.active&&!d.closed;}
    /** Human-form charging yields to the defensive arm owner; WR guns remain independent. No shared boolean key is overwritten. */
    public static boolean isBlockingRifle(ShipAPI ship){return active(ship)&&ZetaRifleModes.msForm(ship);}
    public boolean intercepts(Object source){requireThread();return intercepted.contains(source);}
    public Request requestToggle(){
        requireThread();if(closed)return no("光束弹开已关闭");if(active){deactivate("已关闭光束弹开");publish();return new Request(true,reason);}
        if(!canPilot()||!overdrive())return no("仅生物传感器过载期间可开启");
        Object armOwner=ship.getCustomData().get(ARM_ACTION_KEY);if(armOwner!=null&&armOwner!=this)return no("其他技能正在占用双臂");
        ZetaRifleModes rifle=ZetaRifleModes.instance(ship);if(rifle!=null&&ZetaRifleModes.msForm(ship))rifle.cancelCharge("光束弹开占用双臂");
        ship.setCustomData(ARM_ACTION_KEY,this);active=true;remaining=MAX_SECONDS;toggles++;reason="光束弹开 · 最多3秒，再按关闭";publish();return new Request(true,reason);
    }
    private Request no(String message){reason=message;publish();return new Request(false,message);}
    /** One call per actor-local step. Input is real, unconsumed letter-key edges only. */
    public void advance(float localStep,List<InputEventAPI> events){
        requireThread();if(closed)return;if(!Float.isFinite(localStep)||localStep<0)throw new IllegalArgumentException("Invalid deflection local time");
        if(Global.getCombatEngine()!=engine||engine.isCombatOver()||!live(ship)){close();return;}if(engine.isPaused())return;
        float nativeStep=engine.getElapsedInLastFrame();lastStep=Math.min(.1f,Float.isFinite(nativeStep)&&nativeStep>0?nativeStep:localStep);clock+=localStep;releaseDamageLeases();drain();intercepted.clear();traces.removeIf(t->clock-t.born>.14);
        partClock-=localStep;if(partClock<=0){partClock=.1f;syncParts();}
        if(active){remaining=Math.max(0,remaining-localStep);if(remaining<=0||!overdrive()||ship.getCustomData().get(ARM_ACTION_KEY)!=this)deactivate("光束弹开已结束");}
        clearReservation();binding=new OwnNewtypeBindings.Result(false,List.of(),"使用光束弹开武器组");
        processInput(events);
        blend=Math.max(0,Math.min(1,blend+(active?1:-1)*localStep/.15f));
        if(active){redirectRays();if(ZetaRifleModes.msForm(ship))for(WeaponAPI weapon:ship.getAllWeapons())apply(weapon);else restoreArms();}
        else{restoreArms();releaseArmOwnership();}hud();publish();
    }
    /** Input has its own entry point; it never advances a second clock or polls Keyboard state. */
    public void processInput(List<InputEventAPI> events){requireThread();if(events==null||!canPilot())return;WeaponGroupAPI selected=ship.getSelectedGroupAPI();if(selected==null||selected.getWeaponsCopy().stream().noneMatch(w->ZetaDeflectionWeapon.ID.equals(w.getId())))return;
        for(InputEventAPI event:events)if(event!=null&&!event.isConsumed()&&!event.isRepeat()&&event.isControlDownEvent("SHIP_FIRE")){requestToggle();event.consume();}}
    /** Safe for passive-first or deflector-first listener order: true only after this exact damage was cancelled and queued once. */
    public boolean interceptDamage(Object param,CombatEntityAPI target,DamageAPI damage,Vector2f point,boolean shieldHit){
        requireThread();if(damage==null||isReflectedDamage(param))return false;
        if(damageLeases.contains(damage))return true;
        if(!active||closed||!overdrive()||!parts.contains(target)||!(param instanceof BeamAPI beam)||!hostile(beam.getSource())||!finite(point)||!finite(beam.getFrom())||damage.getType()==null)return false;
        if(pending.size()>=MAX_PENDING||damageLeases.size()>=MAX_DAMAGE_LEASES){budgetSkips++;return false;}
        float amount=damage.computeDamageDealt(Math.max(.0001f,lastStep));if(!Float.isFinite(amount)||amount<=0)return false;
        Vector2f direction=reflected(beam.getFrom(),point,ship.getLocation());if(direction==null)return false;
        float range=Math.max(200,Math.min(6000,beam.getWeapon()==null?2000:beam.getWeapon().getRange()));
        pending.add(new Tick(new Vector2f(point),direction,range,Math.min(50000,amount),damage.getType(),damage.isSoftFlux(),safeColor(beam.getFringeColor()),safeColor(beam.getCoreColor()),Math.max(2,Math.min(80,beam.getWidth())),clock));
        damage.getModifier().modifyMult(modifier,0f);damageLeases.add(damage);intercepted.add(param);cancelledTicks++;return true;
    }
    @Override public String modifyDamageTaken(Object param,CombatEntityAPI target,DamageAPI damage,Vector2f point,boolean shieldHit){return interceptDamage(param,target,damage,point,shieldHit)?modifier:null;}
    private void drain(){
        for(int count=0;count<MAX_PENDING&&!pending.isEmpty();count++){
            Tick tick=pending.removeFirst();if(clock-tick.born>.2){budgetSkips++;continue;}
            Vector2f from=new Vector2f(tick.origin.x+tick.direction.x*2,tick.origin.y+tick.direction.y*2),end=new Vector2f(from.x+tick.direction.x*tick.length,from.y+tick.direction.y*tick.length);
            Hit hit=firstHit(from,end);Vector2f stop=hit==null?end:hit.point;
            if(traces.size()>=MAX_VISUALS)traces.remove(0);traces.add(new Trace(from,new Vector2f(stop),tick.width,tick.fringe,tick.core,clock));
            if(hit!=null&&hostile(hit.target)&&!hit.target.isHulk()){
                engine.applyDamage(new ReflectedDamage(Objects.toString(ship.getId(),""),++serial),hit.target,new Vector2f(stop),tick.damage,tick.type,0,false,tick.soft,ship,true);reflectedHits++;
            }
        }
    }
    private void redirectRays(){
        List<DamagingProjectileAPI> all=engine.getProjectiles();if(all==null||all.isEmpty())return;int size=all.size(),limit=Math.min(size,MAX_RAYS);
        float radius=Math.max(20,ship.getCollisionRadius()+50);
        for(int i=0;i<limit;i++){
            DamagingProjectileAPI raw=all.get((rayCursor+i)%size);if(!(raw instanceof MovingRay ray)||ray.didDamage()||ray.isFading()||!live(ray)||!hostile(ray.getSource())||ray.getCustomData().containsKey(RAY_MARK))continue;
            Vector2f from=ray.getFrom(),to=ray.getTo();if(!finite(from)||!finite(to))continue;
            float t=circleEntry(from,to,ship.getLocation(),radius);Vector2f incoming=Vector2f.sub(to,from,null);float length=incoming.length();
            if(length<.001f)continue;
            if(!Float.isFinite(t)){Vector2f velocity=ray.getVelocity();if(!finite(velocity))continue;Vector2f future=new Vector2f(to.x+velocity.x*lastStep,to.y+velocity.y*lastStep);t=circleEntry(to,future,ship.getLocation(),radius);if(!Float.isFinite(t))continue;from=new Vector2f(to);to=future;}
            Vector2f at=lerp(from,to,t),origin=Vector2f.sub(at,incoming,null),out=reflected(origin,at,ship.getLocation());if(out==null)continue;
            // MovingRay's native extender advances from its live from->to direction. Velocity is only an output.
            ray.getFrom().set(at);ray.getTo().set(at.x+out.x*length,at.y+out.y*length);ray.setFacing((float)Math.toDegrees(Math.atan2(out.y,out.x)));
            for(ShipAPI part:parts)ray.addDamagedAlready(part);ray.setCustomData(RAY_MARK,Objects.toString(ship.getId(),""));intercepted.add(ray);redirectedRays++;
        }
        if(size>limit)budgetSkips+=size-limit;rayCursor=(rayCursor+limit)%size;
    }
    private Hit firstHit(Vector2f from,Vector2f to){
        Hit nearest=null;List<ShipAPI> ships=engine.getShips();if(ships==null)return null;int count=0;
        for(ShipAPI target:ships){if(++count>MAX_TARGETS){budgetSkips+=ships.size()-MAX_TARGETS;break;}if(target==ship||parts.contains(target)||!live(target)||target.getCollisionClass()==CollisionClass.NONE||target.isPhased())continue;
            float t=hullEntry(from,to,target);boolean shield=false;ShieldAPI own=target.getShield();
            if(own!=null&&own.isOn()){float st=circleEntry(from,to,own.getLocation(),own.getRadius());if(Float.isFinite(st)&&own.isWithinArc(lerp(from,to,st))&&(!Float.isFinite(t)||st<t)){t=st;shield=true;}}
            if(Float.isFinite(t)&&(nearest==null||t<nearest.parameter))nearest=new Hit(target,lerp(from,to,t),t,shield);
        }return nearest;
    }
    static float hullEntry(Vector2f from,Vector2f to,ShipAPI target){
        BoundsAPI bounds=target.getExactBounds();List<BoundsAPI.SegmentAPI> edges=bounds==null?null:bounds.getOrigSegments();if(edges==null||edges.size()<3||edges.size()>128)return circleEntry(from,to,target.getLocation(),target.getCollisionRadius());
        float best=Float.POSITIVE_INFINITY;double angle=Math.toRadians(target.getFacing());float c=(float)Math.cos(angle),s=(float)Math.sin(angle);Vector2f center=target.getLocation();
        for(BoundsAPI.SegmentAPI edge:edges){Vector2f a=edge.getP1(),b=edge.getP2();if(!finite(a)||!finite(b))continue;Vector2f wa=new Vector2f(center.x+a.x*c-a.y*s,center.y+a.x*s+a.y*c),wb=new Vector2f(center.x+b.x*c-b.y*s,center.y+b.x*s+b.y*c);best=Math.min(best,segmentEntry(from,to,wa,wb));}return best;
    }
    static float segmentEntry(Vector2f a,Vector2f b,Vector2f c,Vector2f d){float rx=b.x-a.x,ry=b.y-a.y,sx=d.x-c.x,sy=d.y-c.y,den=rx*sy-ry*sx;if(Math.abs(den)<.000001f)return Float.POSITIVE_INFINITY;float x=c.x-a.x,y=c.y-a.y,t=(x*sy-y*sx)/den,u=(x*ry-y*rx)/den;return t>=0&&t<=1&&u>=0&&u<=1?t:Float.POSITIVE_INFINITY;}
    static float circleEntry(Vector2f from,Vector2f to,Vector2f center,float radius){if(!finite(from)||!finite(to)||!finite(center)||!Float.isFinite(radius)||radius<0)return Float.POSITIVE_INFINITY;double x=from.x-center.x,y=from.y-center.y,dx=to.x-from.x,dy=to.y-from.y,a=dx*dx+dy*dy,c=x*x+y*y-radius*radius;if(c<=0)return 0;if(a<.0000001)return Float.POSITIVE_INFINITY;double b=2*(x*dx+y*dy),disc=b*b-4*a*c;if(disc<0)return Float.POSITIVE_INFINITY;double t=(-b-Math.sqrt(disc))/(2*a);return t>=0&&t<=1?(float)t:Float.POSITIVE_INFINITY;}
    static Vector2f reflected(Vector2f from,Vector2f contact,Vector2f center){if(!finite(from)||!finite(contact)||!finite(center))return null;Vector2f d=Vector2f.sub(contact,from,null),n=Vector2f.sub(contact,center,null);float len=d.length(),nl=n.length();if(len<.0001f||!Float.isFinite(len))return null;d.scale(1/len);if(nl<.0001f)n.set(-d.x,-d.y);else n.scale(1/nl);float dot=d.x*n.x+d.y*n.y;if(dot>=-.001f)return null;return new Vector2f(d.x-2*dot*n.x,d.y-2*dot*n.y);}
    private static Vector2f lerp(Vector2f a,Vector2f b,float t){return new Vector2f(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t);}
    private static boolean finite(Vector2f p){return p!=null&&Float.isFinite(p.x)&&Float.isFinite(p.y);}
    private static Color safeColor(Color color){return color==null?new Color(205,130,255):color;}
    private boolean hostile(ShipAPI other){return other!=null&&(ship.getOwner()==0||ship.getOwner()==1)&&(other.getOwner()==0||other.getOwner()==1)&&ship.getOwner()!=other.getOwner();}
    private boolean live(CombatEntityAPI value){return value!=null&&!value.isExpired()&&!value.wasRemoved()&&engine.isEntityInPlay(value);}
    private boolean overdrive(){ShipSystemAPI system=ship.getSystem();return live(ship)&&ship.isAlive()&&!ship.isHulk()&&system!=null&&ZetaContent.SYSTEM.equals(system.getId())&&system.getEffectLevel()>=.8f&&!ship.getFluxTracker().isOverloaded()&&!ship.getFluxTracker().isVenting();}
    private boolean canPilot(){return ship==engine.getPlayerShip()&&!engine.isPaused()&&!ship.controlsLocked()&&!engine.isUIShowingDialog()&&(engine.getCombatUI()==null||!engine.getCombatUI().isShowingCommandUI()&&!engine.getCombatUI().isShowingDeploymentDialog());}
    private void syncParts(){Set<ShipAPI> current=Collections.newSetFromMap(new IdentityHashMap<>());current.add(ship);for(ShipAPI child:ship.getChildModulesCopy())if(current.size()<MAX_PARTS&&child.getParentStation()==ship&&live(child))current.add(child);for(ShipAPI old:new ArrayList<>(parts))if(!current.contains(old)){old.removeListener(this);parts.remove(old);}for(ShipAPI fresh:current)if(parts.add(fresh))fresh.addListener(this);}
    private void releaseDamageLeases(){for(DamageAPI damage:damageLeases)damage.getModifier().unmodify(modifier);damageLeases.clear();}
    private void deactivate(String message){active=false;remaining=0;reason=message;if(ship.getCustomData().get(ARM_ACTION_KEY)==this)restoreArms();else arms.clear();releaseArmOwnership();}
    private void releaseArmOwnership(){if(ship.getCustomData().get(ARM_ACTION_KEY)==this)ship.removeCustomData(ARM_ACTION_KEY);}
    public static void applyPose(WeaponAPI weapon){Object own=weapon==null||weapon.getShip()==null?null:weapon.getShip().getCustomData().get(KEY+".owner");if(own instanceof ZetaBeamDeflection d&&!d.closed)d.apply(weapon);}
    private void apply(WeaponAPI weapon){if(!active||ship.getCustomData().get(ARM_ACTION_KEY)!=this||!ZetaRifleModes.msForm(ship)||weapon.getSlot()==null||!Set.of("ARM_L","ARM_R").contains(weapon.getSlot().getId()))return;Arm arm=arms.computeIfAbsent(weapon,Arm::new);float desired=ship.getFacing()+(weapon.getSlot().getId().equals("ARM_L")?ARM_DEGREES:-ARM_DEGREES)*blend;weapon.setCurrAngle(desired);arm.written=weapon.getCurrAngle();arm.applied=true;}
    private final class Arm{final WeaponAPI weapon;final float relative;float written;boolean applied;Arm(WeaponAPI weapon){this.weapon=weapon;relative=weapon.getCurrAngle()-ship.getFacing();}void restore(){if(applied&&Math.abs(shortest(weapon.getCurrAngle()-written))<.01f)weapon.setCurrAngle(ship.getFacing()+relative);}}
    private static float shortest(float a){return ((a%360)+540)%360-180;}
    private void restoreArms(){for(Arm arm:arms.values())arm.restore();arms.clear();}
    private void refreshBinding(){clearReservation();binding=OwnNewtypeBindings.select(engine,ship,binding.available()?binding.keyCode():Keyboard.KEY_K,Map.of(Keyboard.KEY_N,"Moci 子系统显示",Keyboard.KEY_X,"Z变形"));keyClock=.5f;if(!binding.available())return;Object old=engine.getCustomData().get(OwnNewtypeBindings.RESERVATIONS_KEY);if(old!=null&&!(old instanceof Map<?,?>)){binding=new OwnNewtypeBindings.Result(false,List.of(),"扩展键位表类型冲突，未接管字母键");return;}Map<Integer,String> copy=new LinkedHashMap<>();if(old instanceof Map<?,?> previous)for(var e:previous.entrySet()){if(!(e.getKey() instanceof Integer k)||!(e.getValue() instanceof String v)){binding=new OwnNewtypeBindings.Result(false,List.of(),"扩展键位登记格式错误，未接管字母键");return;}copy.put(k,v);}if(copy.containsKey(binding.keyCode())){binding=new OwnNewtypeBindings.Result(false,List.of(),"字母键已被占用");return;}copy.put(binding.keyCode(),reservation);engine.getCustomData().put(OwnNewtypeBindings.RESERVATIONS_KEY,copy);}
    private void clearReservation(){Object old=engine.getCustomData().get(OwnNewtypeBindings.RESERVATIONS_KEY);if(old instanceof Map<?,?> previous&&previous.containsValue(reservation)){Map<Object,Object> copy=new LinkedHashMap<>(previous);copy.entrySet().removeIf(e->reservation.equals(e.getValue()));if(copy.isEmpty())engine.getCustomData().remove(OwnNewtypeBindings.RESERVATIONS_KEY);else engine.getCustomData().put(OwnNewtypeBindings.RESERVATIONS_KEY,copy);}}
    private void hud(){if(ship==engine.getPlayerShip()&&engine.isUIShowingHUD())engine.maintainStatusForPlayerShip(KEY,"graphics/icons/hullsys/fortress_shield.png","光束弹开","光束弹开武器组 · 点按切换 · "+(active?String.format(Locale.ROOT,"剩余 %.1f 秒",remaining):reason),!active);}
    public void renderWorld(ViewportAPI viewport){if(closed||traces.isEmpty())return;List<PsychoOpticalRenderer.Segment> draw=new ArrayList<>();for(Trace t:traces){float alpha=(float)Math.max(0,1-(clock-t.born)/.14);draw.add(new PsychoOpticalRenderer.Segment(t.from.x,t.from.y,t.to.x,t.to.y,t.width,alpha,(float)clock,t.fringe,t.core,true));}PsychoOpticalRenderer.render(draw,viewport);}
    public Map<String,Object> status(){Map<String,Object> result=new LinkedHashMap<>();result.put("active",active);result.put("remaining",remaining);result.put("closed",closed);result.put("key",binding.keyName());result.put("reason",reason);result.put("armsDegrees",ARM_DEGREES);result.put("redirectedRays",redirectedRays);result.put("cancelledNativeBeamTicks",cancelledTicks);result.put("reflectedDamageHits",reflectedHits);result.put("pendingTicks",pending.size());result.put("visualSegments",traces.size());result.put("budgetSkips",budgetSkips);result.put("toggles",toggles);result.put("scope","MovingRay live path redirects; continuous native hit cancellation plus tagged collision-tested own segment; no original callback replay");return Collections.unmodifiableMap(result);}
    private void publish(){ship.setCustomData(KEY,status());}
    private void requireThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Zeta deflection belongs to its battle thread");}
    @Override public void close(){requireThread();if(closed)return;deactivate("光束弹开已关闭");releaseDamageLeases();for(ShipAPI part:parts)part.removeListener(this);parts.clear();pending.clear();traces.clear();intercepted.clear();clearReservation();closed=true;if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");publish();}
}
