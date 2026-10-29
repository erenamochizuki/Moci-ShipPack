package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.combat.entities.MovingRay;
import data.scripts.weapons.Moci_WingmanSupport;
import java.awt.Color;
import java.util.*;
import java.util.function.Supplier;
import org.lwjgl.util.vector.Vector2f;

/**
 * Optical finishing only. Samples original rays/beams and settled hits; never replaces a
 * weapon callback, mutates a spec/entity/stat, spawns attacks or advances a native clock.
 * Exact optical weapon identities intentionally exclude bazookas, missiles and launchers.
 */
public final class PsychoArmamentVisuals implements AutoCloseable, data.scripts.combat.Moci_CombatSink {
    public static final String KEY="xi.arsenal.psychoVisuals";
    public static final int MAX_SCAN=8192,MAX_SEGMENTS=192,MAX_FLARES=96,MAX_TRACKED=1024;
    public static final int MAX_PARTICLES_PER_FRAME=24;
    public static final float MAX_PARTICLES_PER_SECOND=160f;
    private static final List<String> FAMILIES=List.of("night","hinu","hws","nu");
    private static final Color PINK=new Color(224,56,142), PURPLE=new Color(155,55,230),
            GREEN=new Color(196,255,70), GOLD=new Color(255,220,65), CYAN=new Color(25,220,255);
    private record Style(String family,Color primary,Color inner,float width,boolean blade) { }
    private record RayState(float firstSeen) { }
    private record Flash(String family,float x,float y,float angle,float radius,float born,float life,
                         Color primary,Color inner,boolean impact,WeaponAPI anchor) { }
    private record Row(String family,PsychoOpticalRenderer.Segment segment) { }
    private final IdentityHashMap<DamagingProjectileAPI,RayState> tracked=new IdentityHashMap<>();
    private final IdentityHashMap<WeaponAPI,Float> beamMuzzles=new IdentityHashMap<>(),beamHits=new IdentityHashMap<>();
    private final IdentityHashMap<DamagingProjectileAPI,Set<CombatEntityAPI>> projectileHits=new IdentityHashMap<>();
    private final Map<String,Map<String,Long>> counters=new LinkedHashMap<>();
    private final List<Flash> flashes=new ArrayList<>();
    private final List<Row> segments=new ArrayList<>();
    private final List<Map<String,Object>> samples=new ArrayList<>();
    private final Set<String> sampleKeys=new HashSet<>();
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private final Random visualRandom=new Random(0x50737963686fL);
    private final Supplier<PsychoOpticalTextures> textureFactory;
    private PsychoOpticalTextures textures;
    private CombatEngineAPI engine;
    private DamageListener damageListener;
    private float now,particleTokens=MAX_PARTICLES_PER_FRAME,pathCountdown;
    private int rayCursor,beamCursor,pathCursor,particlesThisFrame;
    private long budgetSkips,particlesEmitted,particleBudgetSkips,mergedFlares,texturedSegmentLayers,texturedFlareLayers,chargeGlowReports;
    private boolean closed,duplicate;

    public PsychoArmamentVisuals(){this(PsychoOpticalTextures::load);}
    /** Explicit dependency seam for API-only verification; production always prepares real assets. */
    PsychoArmamentVisuals(Supplier<PsychoOpticalTextures> textureFactory){this.textureFactory=Objects.requireNonNull(textureFactory);}

    /** 光学收尾的宿主：由用了 xi 光学武器的每帧回调惰性安装，不再是全局插件。 */
    public static PsychoArmamentVisuals ensure(CombatEngineAPI engine){
        if (Global.getCurrentState() != com.fs.starfarer.api.GameState.COMBAT) return null;
        if(engine==null)return null;Object owner=engine.getCustomData().get(KEY+".owner");
        if(owner instanceof PsychoArmamentVisuals own&&!own.closed)return own;
        PsychoArmamentVisuals created=new PsychoArmamentVisuals();created.start(engine);
        return created.closed||created.duplicate?null:created;
    }
    void start(CombatEngineAPI engine){
        if(closed)throw new IllegalStateException("Closed optical overlay cannot be reused");
        if(this.engine!=null)throw new IllegalStateException("Optical overlay already initialized");
        this.engine=Objects.requireNonNull(engine);
        if(engine.getCustomData().containsKey(KEY+".owner")){duplicate=true;return;}
        engine.getCustomData().put(KEY+".owner",this);
        for(String family:FAMILIES){Map<String,Long> row=new LinkedHashMap<>();
            for(String name:List.of("projectileObservations","beamObservations","muzzleFlares","impactFlares","renderedSegments","renderedFlares","ionParticles"))row.put(name,0L);
            counters.put(family,row);}
        try{
            // 无引擎外资源：textures 只是 Global.getSettings().getSprite() 的缓存引用，
            // PsychoOpticalTextures.close() 仅置 null；damageListener 与渲染插件都在引擎内，
            // 收尾链路由本类 advance() 的 combat-over 守卫 + Moci_CombatBus.closeAll() 覆盖。
            textures=Objects.requireNonNull(textureFactory.get(),"Optical texture preparation returned null");
            damageListener=this::settledHit;
            engine.getListenerManager().addListener(damageListener);
            engine.getCustomData().put(KEY,Collections.unmodifiableMap(facts));publish();
            data.scripts.combat.Moci_CombatBus.register(this);
        }catch(RuntimeException|Error failure){
            try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    public static Map<String,Object> status(CombatEngineAPI engine){
        Object raw=engine==null?null:engine.getCustomData().get(KEY);
        if(!(raw instanceof Map<?,?> map))return Map.of();
        Map<String,Object> copy=new LinkedHashMap<>();map.forEach((k,v)->copy.put(String.valueOf(k),v));
        return Collections.unmodifiableMap(copy);
    }
    /** Called once by the exact native rifle visual callback; never advances a weapon or clock. */
    public static void nativeChargeGlow(CombatEngineAPI engine,WeaponAPI weapon,float charge){
        if(engine==null||weapon==null||!NightRifleChargeEffect.WEAPON_ID.equals(weapon.getId())
                ||!Float.isFinite(charge)||charge<.1f||engine.isPaused())return;
        Object owner=engine.getCustomData().get(KEY+".owner");
        if(!(owner instanceof PsychoArmamentVisuals own)||own.closed||own.duplicate||own.engine!=engine)return;
        Style style=style(weapon);Vector2f muzzle=weapon.getFirePoint(0);if(style==null||!finite(muzzle))return;
        own.now=engine.getTotalElapsedTime(false);own.chargeGlowReports++;
        own.flash(style,muzzle,weapon.getCurrAngle(),22f+20f*Math.min(1,charge),.14f,false,weapon);
    }
    @Override public void advance(float amount,List<InputEventAPI> events){
        if(closed||duplicate)return;
        if(Global.getCombatEngine()!=engine||engine.isCombatOver()){close();return;}
        if(!Float.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid optical frame amount");
        if(engine.isPaused())return;
        now=engine.getTotalElapsedTime(false);
        particlesThisFrame=0;
        particleTokens=Math.min(MAX_PARTICLES_PER_FRAME,particleTokens+amount*MAX_PARTICLES_PER_SECOND);
        flashes.removeIf(f->now-f.born>=f.life);
        tracked.keySet().removeIf(p->!engine.isEntityInPlay(p));
        projectileHits.keySet().removeIf(p->!engine.isEntityInPlay(p));
        beamMuzzles.keySet().removeIf(w->!live(w.getShip()));
        beamHits.keySet().removeIf(w->!live(w.getShip()));
        segments.clear();
        sampleRays();sampleBeams();
        pathCountdown-=amount;if(pathCountdown<=0){pathCountdown=.06f;pathMotes();}
        publish();
    }
    private void sampleRays(){
        List<DamagingProjectileAPI> all=engine.getProjectiles();int size=all.size(),limit=Math.min(size,MAX_SCAN);
        for(int i=0;i<limit;i++){
            DamagingProjectileAPI p=all.get((rayCursor+i)%Math.max(1,size));
            if(p==null||p instanceof MissileAPI||p.getWeapon()==null)continue;
            Style style=style(p.getWeapon());if(style==null)continue;
            if(!tracked.containsKey(p)){
                if(tracked.size()>=MAX_TRACKED){budgetSkips++;continue;}
                tracked.put(p,new RayState(now));inc(style.family,"projectileObservations");
                // The native projectile's birth point, not a hull/engine pivot or an estimated offset.
                if(p.getElapsed()<.25f){flash(style,p.getSpawnLocation(),p.getFacing(),muzzleSize(style),.23f,false,p.getWeapon());
                    scatter(style.family,style.primary,p.getSpawnLocation(),p.getFacing(),p.getSource()==null?null:p.getSource().getVelocity(),4,false);}
                sample(style,p.getWeapon(),"projectile",p.getSpawnLocation(),p.getLocation(),
                        p.getDamage()==null?0:p.getDamage().getDamage(),p.getMoveSpeed(),p.getProjectileSpecId());
            }
            if(!(p instanceof MovingRay ray)||ray.isExpired())continue;
            addSegment(style,ray.getFrom(),ray.getTo(),style.width,ray.getBrightness(),p.getElapsed(),false);
        }
        if(size>limit)budgetSkips+=size-limit;rayCursor=size==0?0:(rayCursor+limit)%size;
    }
    private void sampleBeams(){
        List<BeamAPI> all=engine.getBeams();int size=all.size(),limit=Math.min(size,MAX_SCAN);
        for(int i=0;i<limit;i++){
            BeamAPI b=all.get((beamCursor+i)%Math.max(1,size));Style style=b==null?null:style(b.getWeapon());
            if(style==null||b.getBrightness()<=.005f)continue;
            if(addSegment(style,b.getFrom(),b.getTo(),Math.max(2,b.getWidth()),b.getBrightness(),now,true))
                inc(style.family,"beamObservations");
            if(!style.blade&&allow(beamMuzzles,b.getWeapon(),.14f))
                flash(style,b.getFrom(),angle(b.getFrom(),b.getTo()),muzzleSize(style)*.85f,.23f,false,b.getWeapon());
            sample(style,b.getWeapon(),"beam",b.getFrom(),b.getTo(),
                    b.getDamage()==null?0:b.getDamage().getDamage(),0,"");
        }
        if(size>limit)budgetSkips+=size-limit;beamCursor=size==0?0:(beamCursor+limit)%size;
    }
    private boolean addSegment(Style style,Vector2f from,Vector2f to,float width,float brightness,float phase,boolean sustained){
        if(!finite(from)||!finite(to)||!Float.isFinite(width)||!Float.isFinite(brightness)||brightness<=.005f)return false;
        if(segments.size()>=MAX_SEGMENTS){budgetSkips++;return false;}
        segments.add(new Row(style.family,new PsychoOpticalRenderer.Segment(from.x,from.y,to.x,to.y,width,
                Math.min(1,brightness),phase,style.primary,style.inner,sustained)));return true;
    }
    private void settledHit(Object attacker,CombatEntityAPI target,ApplyDamageResultAPI result){
        if(closed||duplicate||engine.isPaused()||!(target instanceof ShipAPI hit)||result==null)return;
        now=engine.getTotalElapsedTime(false);
        float total=result.getDamageToHull()+result.getTotalDamageToArmor()+result.getDamageToShields();
        if(!Float.isFinite(total)||total<=0)return;
        // Native DAMAGE is already settled. The native parameter is still readable in this callback.
        Object param=hit.getParamAboutToApplyDamage();Style style;Vector2f point;float facing;
        if(param instanceof BeamAPI b){
            style=style(b.getWeapon());if(style==null||!allow(beamHits,b.getWeapon(),.11f))return;
            point=b.getTo();facing=angle(b.getFrom(),point);
        }else if(param instanceof DamagingProjectileAPI p){
            if(p instanceof MissileAPI||p.getWeapon()==null)return;
            style=style(p.getWeapon());if(style==null)return;
            if(projectileHits.size()>=MAX_TRACKED&&!projectileHits.containsKey(p)){budgetSkips++;return;}
            Set<CombatEntityAPI> victims=projectileHits.computeIfAbsent(p,k->Collections.newSetFromMap(new IdentityHashMap<>()));
            if(victims.size()>=32||!victims.add(target))return;
            point=p instanceof MovingRay ray?ray.getTo():p.getLocation();facing=p.getFacing();
        }else return;
        WeaponAPI weapon=param instanceof BeamAPI b?b.getWeapon():((DamagingProjectileAPI)param).getWeapon();
        flash(style,point,facing,style.blade?38:Math.min(110,55+style.width*.35f),.31f,true,weapon);
        scatter(style.family,style.primary,point,facing+180,target.getVelocity(),style.blade?3:6,true);
    }
    private boolean allow(IdentityHashMap<WeaponAPI,Float> clock,WeaponAPI weapon,float interval){
        Float previous=clock.get(weapon);if(previous!=null&&now-previous<interval)return false;
        if(previous==null&&clock.size()>=MAX_TRACKED){budgetSkips++;return false;}
        clock.put(weapon,now);return true;
    }
    private void flash(Style style,Vector2f point,float angle,float radius,float life,boolean impact,WeaponAPI anchor){
        if(!finite(point))return;
        // A continuous native beam refreshes one local halo instead of piling identical
        // bright disks at the same aperture/contact every .14 seconds.
        for(int i=0;i<flashes.size();i++){Flash old=flashes.get(i);
            float dx=old.x-point.x,dy=old.y-point.y;
            if(old.anchor==anchor&&old.impact==impact&&dx*dx+dy*dy<144f){
                flashes.set(i,new Flash(style.family,point.x,point.y,angle,radius,now,life,style.primary,style.inner,impact,anchor));
                mergedFlares++;return;
            }
        }
        if(flashes.size()>=MAX_FLARES){budgetSkips++;return;}
        flashes.add(new Flash(style.family,point.x,point.y,angle,radius,now,life,style.primary,style.inner,impact,anchor));
        inc(style.family,impact?"impactFlares":"muzzleFlares");
    }
    /** Native finite particles are visual output only, never damaging entities or clock owners. */
    private void scatter(String family,Color color,Vector2f point,float angle,Vector2f inherited,int count,boolean impact){
        if(!finite(point)||engine.getViewport()==null||!engine.getViewport().isNearViewport(point,120))return;
        for(int i=0;i<count;i++){
            double a=Math.toRadians(angle+(random()*2-1)*(impact?115:65));
            float speed=(impact?45:28)+random()*(impact?105:65);
            Vector2f velocity=new Vector2f((finite(inherited)?inherited.x*.35f:0)+(float)Math.cos(a)*speed,
                    (finite(inherited)?inherited.y*.35f:0)+(float)Math.sin(a)*speed);
            particle(family,point,velocity,(impact?4:3)+random()*4,.2f+random()*.2f,
                    i==0?new Color(255,245,255,195):new Color(color.getRed(),color.getGreen(),color.getBlue(),150));
        }
    }
    private void pathMotes(){
        if(segments.isEmpty())return;int count=Math.min(8,segments.size());
        for(int i=0;i<count;i++){
            Row row=segments.get(Math.floorMod(pathCursor+i,segments.size()));PsychoOpticalRenderer.Segment s=row.segment;
            float t=.12f+random()*.76f,dx=s.toX()-s.fromX(),dy=s.toY()-s.fromY(),length=(float)Math.hypot(dx,dy);
            if(length<1)continue;float nx=-dy/length,ny=dx/length,offset=(random()-.5f)*Math.min(18,s.width()*.22f);
            Vector2f point=new Vector2f(s.fromX()+dx*t+nx*offset,s.fromY()+dy*t+ny*offset);
            if(engine.getViewport()==null||!engine.getViewport().isNearViewport(point,40))continue;
            float drift=(random()-.5f)*35;
            particle(row.family,point,new Vector2f(nx*drift,ny*drift),2.5f+random()*3,.18f+random()*.16f,
                    new Color(s.primary().getRed(),s.primary().getGreen(),s.primary().getBlue(),140));
        }
        pathCursor=(pathCursor+count)%segments.size();
    }
    private void particle(String family,Vector2f at,Vector2f velocity,float size,float life,Color color){
        if(particlesThisFrame>=MAX_PARTICLES_PER_FRAME||particleTokens<1){particleBudgetSkips++;return;}
        particleTokens-=1;particlesThisFrame++;particlesEmitted++;inc(family,"ionParticles");
        engine.addSmoothParticle(new Vector2f(at),new Vector2f(velocity),size,.7f,Math.min(.45f,life),color);
    }
    private float random(){return visualRandom.nextFloat();}
    @Override public void renderInWorldCoords(ViewportAPI viewport){
        if(closed||duplicate||viewport==null)return;
        List<PsychoOpticalRenderer.Segment> lines=new ArrayList<>();for(Row row:segments)lines.add(row.segment);
        if(textures!=null)texturedSegmentLayers+=textures.renderSegments(lines,viewport);
        PsychoOpticalRenderer.render(lines,viewport,i->inc(segments.get(i).family,"renderedSegments"));
        List<PsychoOpticalRenderer.Flare> glows=new ArrayList<>();
        for(Flash f:flashes){
            float t=Math.max(0,Math.min(1,(now-f.born)/f.life));
            float alpha=(float)Math.pow(1-t,1.35);float size=f.radius*(f.impact?(.68f+.32f*t):(.88f+.12f*t));
            glows.add(new PsychoOpticalRenderer.Flare(f.x,f.y,f.angle,size,alpha,f.primary,f.inner,f.impact));
        }
        PsychoOpticalRenderer.renderFlares(glows,viewport,i->inc(flashes.get(i).family,"renderedFlares"));
        if(textures!=null)texturedFlareLayers+=textures.renderFlares(glows,viewport);
        // Counters record completed real draw calls; no effect age or game state advances in render.
        publish();
    }
    private void sample(Style style,WeaponAPI weapon,String kind,Vector2f from,Vector2f to,float damage,float speed,String projectile){
        String key=style.family+"/"+weapon.getId()+"/"+kind;
        if(samples.size()>=64||!sampleKeys.add(key)||!finite(from)||!finite(to))return;
        Map<String,Object> row=new LinkedHashMap<>();row.put("family",style.family);row.put("weaponId",weapon.getId());
        row.put("sourceId",weapon.getShip()==null?"":Objects.toString(weapon.getShip().getId(),""));
        row.put("kind",kind);row.put("from",List.of(from.x,from.y));row.put("to",List.of(to.x,to.y));
        row.put("damage",damage);row.put("speed",speed);row.put("range",weapon.getRange());row.put("projectileId",projectile);
        samples.add(Collections.unmodifiableMap(row));
    }
    /** Explicit native optical identities. No guessed ENERGY category, substring or family-wide reskin. */
    private static Style style(WeaponAPI weapon){
        if(weapon==null)return null;String id=weapon.getId();if(id==null)return null;
        String family;Color color;float width;boolean blade=false;
        switch(id){
            case "RX_93_arm_right_gun_rifle" -> {family="nu";color=PINK;width=32;}
            case "RX_93_v2_arm_right_gun_rifle" -> {family="hinu";color=PINK;width=32;}
            case "RX_93_v2_arm_right_gun_hws_rifle" -> {family="hws";color=PINK;width=100;}
            case "MSN_04_2_arm_right_rifle" -> {family="night";color=GREEN;width=100;}
            case "MSN_04_2_body_beam" -> {family="night";color=GREEN;width=20;}
            case "MSN_04_2_body_beam_improved" -> {family="night";color=CYAN;width=30;}
            case "Moci_MSN_04_2_bit_beam","Moci_MSN_04_2_bit_blaster" -> {family="night";color=GOLD;width=25;}
            case "Moci_RX_93_v2_bit_shot" -> {family=bitFamily(weapon.getShip());color=PINK;width=24;}
            case "Moci_RX_93_v2_bit_shortbeam","Moci_RX_93_v2_bit_beam" -> {family=bitFamily(weapon.getShip());color=PURPLE;width=20;}
            case "RX_93_v2_shield_cannon","RX_93_v2_superweapon_inarm" -> {family="hws";color=PURPLE;width=60;}
            case "RX_93_arm_left_gun_Sabre" -> {family="nu";color=PURPLE;width=30;blade=true;}
            case "RX_93_v2_arm_left_gun_Sabre","RX_93_v2_arm_right_gun_Sabre" -> {family="hinu";color=PURPLE;width=30;blade=true;}
            case "MSN_04_2_arm_right_Sabre","MSN_04_2_arm_right_Axe","MSN_04_2_leg_left_Sabre","MSN_04_2_leg_right_Sabre" -> {family="night";color=GOLD;width=30;blade=true;}
            default -> {return null;}
        }
        return new Style(family,color,inner(color),width,blade);
    }
    private static String bitFamily(ShipAPI ship){
        if(ship==null)return "hinu";
        ShipAPI source=PsychoframeHiNuSupport.motherFor(ship);
        if(source==null)source=ship.getDroneSource();
        if(source==null)source=Moci_WingmanSupport.getTrackedMothership(ship);
        if(source==null&&ship.getAIFlags()!=null){Object flag=ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.DRONE_MOTHERSHIP);if(flag instanceof ShipAPI s)source=s;}
        if(source!=null&&source.getHullSpec()!=null&&"Moci_RX_93_v2_HWS".equals(source.getHullSpec().getHullId()))return "hws";
        if(ship.getHullSpec()!=null&&"Moci_RX_93_bit".equals(ship.getHullSpec().getHullId()))return "nu";
        return "hinu";
    }
    private static Color inner(Color c){return new Color((c.getRed()+510)/3,(c.getGreen()+510)/3,(c.getBlue()+510)/3);}
    private static float muzzleSize(Style s){return Math.min(80,36+s.width*.3f);}
    private static float angle(Vector2f a,Vector2f b){return finite(a)&&finite(b)?(float)Math.toDegrees(Math.atan2(b.y-a.y,b.x-a.x)):0;}
    private static boolean finite(Vector2f p){return p!=null&&Float.isFinite(p.x)&&Float.isFinite(p.y);}
    private boolean live(ShipAPI s){return s!=null&&s.isAlive()&&!s.wasRemoved()&&engine.isEntityInPlay(s);}
    private void inc(String family,String name){inc(family,name,1);}
    private void inc(String family,String name,long n){Map<String,Long> row=counters.get(family);row.put(name,row.get(name)+n);}
    private void publish(){
        facts.put("schema",1);facts.put("closed",closed);facts.put("currentSegments",segments.size());facts.put("currentFlares",flashes.size());
        facts.put("presentationVersion",2);facts.put("textureAssetsPrepared",textures!=null&&textures.isPrepared());
        facts.put("texturedSegmentLayers",texturedSegmentLayers);facts.put("texturedFlareLayers",texturedFlareLayers);
        facts.put("particlesEmitted",particlesEmitted);facts.put("particlesThisFrame",particlesThisFrame);
        facts.put("particleBudgetSkips",particleBudgetSkips);facts.put("mergedFlares",mergedFlares);
        facts.put("nightChargeGlowReports",chargeGlowReports);
        facts.put("maxParticlesPerFrame",MAX_PARTICLES_PER_FRAME);facts.put("maxParticlesPerSecond",MAX_PARTICLES_PER_SECOND);
        facts.put("maxSegments",MAX_SEGMENTS);facts.put("maxFlares",MAX_FLARES);facts.put("budgetSkips",budgetSkips);
        Map<String,Object> families=new LinkedHashMap<>();for(String f:FAMILIES)families.put(f,Map.copyOf(counters.get(f)));
        facts.put("familyStats",Collections.unmodifiableMap(families));facts.put("samples",List.copyOf(samples));
        for(String metric:List.of("projectileObservations","beamObservations","muzzleFlares","impactFlares","renderedSegments","renderedFlares"))
            facts.put(metric,counters.values().stream().mapToLong(m->m.get(metric)).sum());
        facts.put("scope","Exact Nightingale/Nu/Hi-Nu/HWS optical identities; real native endpoints and settled damage only; original callbacks and attack stats untouched");
    }
    @Override public void close(){
        if(closed||duplicate)return;closed=true;
        if(engine!=null&&damageListener!=null)engine.getListenerManager().removeListener(damageListener);
        if(textures!=null)textures.close();
        tracked.clear();projectileHits.clear();beamMuzzles.clear();beamHits.clear();segments.clear();flashes.clear();
        data.scripts.combat.Moci_CombatBus.unregister(this);
        if(engine!=null&&engine.getCustomData().get(KEY+".owner")==this)engine.getCustomData().remove(KEY+".owner");
        if(engine!=null)publish();
    }
}
