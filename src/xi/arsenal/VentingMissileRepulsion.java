package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import java.util.*;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Vector2f;

/** One radial and orientation collision impulse per missile/vent cycle; its original AI then keeps control. */
public final class VentingMissileRepulsion implements AutoCloseable {
    public static final String KEY="xi.arsenal.ventingMissileRepulsion", EXTENSION_TAG="fw_venting_missile_repulsion";
    public static final float EDGE_RANGE=320f,OUTWARD_SPEED=900f,SCAN_SECONDS=.08f;
    public static final int MAX_SOURCES=128,MAX_SOURCE_SCAN=2048,MAX_MISSILE_SCAN=4096,
            MAX_HELD_PER_SOURCE=512,MAX_HELD_TOTAL=4096,MAX_IMPULSES_PER_SCAN=128,MAX_PULSES=64;
    private static final Set<String> HULLS=Set.of("Moci_RX_105","Moci_RX_105_framework_arsenal","Moci_RX_104FF","Moci_RX_104FF_framework_arsenal",
            "Moci_MSN_04_2","Moci_RX_93","Moci_RX_93_v2","Moci_RX_93_v2_HWS");
    private final CombatEngineAPI engine;
    private final Map<ShipAPI,Actor> actors=new IdentityHashMap<>();
    private final Map<String,Object> facts=new LinkedHashMap<>();
    private final List<Map<String,Object>> impulses=new ArrayList<>();
    private final List<Pulse> pulses=new ArrayList<>();
    private float sourceClock,missileClock;private double clock;private int sourceCursor,missileCursor,heldTotal;
    private long nextOrdinal,scans,candidates,pushes,ventStarts,overlapSkips,budgetSkips,invalidVectors,renderedPulses;
    private boolean closed;
    private final class Actor {
        final ShipAPI ship;final long ordinal;final Set<MissileAPI> held=Collections.newSetFromMap(new IdentityHashMap<>());
        boolean venting;long cycle,pushes;double lastPulse=-10;Boundary boundary;
        Actor(ShipAPI ship){this.ship=ship;ordinal=++nextOrdinal;}
        void clear(){heldTotal-=held.size();held.clear();venting=false;}
        Map<String,Object> status(){return Map.of("shipId",id(ship),"hullId",ship.getHullSpec().getHullId(),"venting",venting,"ventCycle",cycle,"heldMissiles",held.size(),"impulses",pushes,"boundary",boundary==null?"not-scanned":boundary.edges.isEmpty()?"native-collision-radius":"copied-original-polygon");}
    }
    private record Pulse(float x,float y,float radius,double born) { }
    public VentingMissileRepulsion(CombatEngineAPI engine){
        this.engine=Objects.requireNonNull(engine);if(engine.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Venting repulsion already owned");
        engine.getCustomData().put(KEY+".owner",this);engine.getCustomData().put(KEY,Collections.unmodifiableMap(facts));publish();
    }
    /** Exact body/base/D-parent identities or explicit extension tag; never a display-name guess. */
    public static boolean supports(ShipAPI ship){
        if(ship==null||ship.isFighter()||ship.isDrone()||ship.isStationModule()||ship.getParentStation()!=null||ship.getHullSpec()==null)return false;
        Set<ShipHullSpecAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());ArrayDeque<ShipHullSpecAPI> queue=new ArrayDeque<>();queue.add(ship.getHullSpec());
        for(int count=0;count<16&&!queue.isEmpty();count++){ShipHullSpecAPI hull=queue.removeFirst();if(!seen.add(hull))continue;
            if(HULLS.contains(Objects.toString(hull.getHullId(),""))||HULLS.contains(Objects.toString(hull.getBaseHullId(),""))||HULLS.contains(Objects.toString(hull.getDParentHullId(),""))
                    ||hull.getTags()!=null&&hull.getTags().contains(EXTENSION_TAG))return true;
            if(hull.getBaseHull()!=null)queue.addLast(hull.getBaseHull());if(hull.getDParentHull()!=null)queue.addLast(hull.getDParentHull());
        }return false;
    }
    public void advance(float amount){
        if(closed)return;if(!Float.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid vent-repulsion frame amount");
        if(engine.isCombatOver()||Global.getCombatEngine()!=engine){close();return;}if(engine.isPaused())return;
        clock+=amount;sourceClock-=amount;missileClock-=amount;pulses.removeIf(p->clock-p.born>.24);
        if(sourceClock<=0){sourceClock=SCAN_SECONDS;discover();}
        boolean newVent=false;
        for(Iterator<Actor> it=actors.values().iterator();it.hasNext();){Actor a=it.next();if(!live(a.ship)||!supports(a.ship)){a.clear();it.remove();continue;}
            int old=a.held.size();a.held.removeIf(m->!live(m));heldTotal-=old-a.held.size();
            boolean vent=a.ship.getFluxTracker()!=null&&a.ship.getFluxTracker().isVenting();
            if(vent&&!a.venting){a.clear();a.venting=true;a.cycle++;ventStarts++;newVent=true;}
            else if(!vent&&a.venting)a.clear();
        }
        if(newVent||missileClock<=0){missileClock=SCAN_SECONDS;repel();}publish();
    }
    private void discover(){List<ShipAPI> ships=engine.getShips();if(ships==null||ships.isEmpty())return;int size=ships.size(),limit=Math.min(size,MAX_SOURCE_SCAN);
        for(int i=0;i<limit;i++){ShipAPI ship=ships.get((sourceCursor+i)%size);if(!live(ship)||!supports(ship)||actors.containsKey(ship))continue;if(actors.size()>=MAX_SOURCES){budgetSkips++;continue;}actors.put(ship,new Actor(ship));}
        if(size>limit)budgetSkips+=size-limit;sourceCursor=(sourceCursor+limit)%size;
    }
    private void repel(){
        List<Actor> active=new ArrayList<>();for(Actor a:actors.values())if(a.venting){a.boundary=Boundary.read(a.ship);active.add(a);}
        active.sort(Comparator.comparing((Actor a)->id(a.ship)).thenComparingLong(a->a.ordinal));if(active.isEmpty())return;
        List<MissileAPI> missiles=engine.getMissiles();if(missiles==null||missiles.isEmpty())return;scans++;
        int size=missiles.size(),limit=Math.min(size,MAX_MISSILE_SCAN),impulseBudget=MAX_IMPULSES_PER_SCAN;
        for(int i=0;i<limit;i++){
            MissileAPI missile=missiles.get((missileCursor+i)%size);candidates++;if(!normalMissile(missile))continue;
            List<Actor> near=new ArrayList<>();Actor chosen=null;float best=Float.POSITIVE_INFINITY;boolean already=false;
            for(Actor a:active){if(!hostile(missile.getOwner(),a.ship.getOwner())||missile.getSource()==a.ship)continue;float distance=a.boundary.distance(missile);
                if(distance>EDGE_RANGE)continue;near.add(a);already|=a.held.contains(missile);if(distance<best){best=distance;chosen=a;}}
            if(chosen==null)continue;
            if(already){overlapSkips++;for(Actor a:near)remember(a,missile);continue;}
            if(impulseBudget<=0||chosen.held.size()>=MAX_HELD_PER_SOURCE||heldTotal>=MAX_HELD_TOTAL){budgetSkips++;continue;}
            Vector2f p=missile.getLocation(),v=missile.getVelocity(),s=chosen.ship.getLocation();
            if(!finite(p)||!finite(v)||!finite(s)){invalidVectors++;continue;}
            double dx=(double)p.x-s.x,dy=(double)p.y-s.y,length=Math.hypot(dx,dy);
            if(length<.0001){double speed=Math.hypot(v.x,v.y);if(speed>.0001){dx=-v.x/speed;dy=-v.y/speed;}else{double angle=Math.toRadians(Float.isFinite(chosen.ship.getFacing())?chosen.ship.getFacing():0);dx=Math.cos(angle);dy=Math.sin(angle);}}
            else{dx/=length;dy/=length;}
            double radial=v.x*dx+v.y*dy,outward=Math.max(radial,OUTWARD_SPEED),nx=v.x+(outward-radial)*dx,ny=v.y+(outward-radial)*dy;
            if(!Double.isFinite(nx)||!Double.isFinite(ny)||Math.abs(nx)>Float.MAX_VALUE||Math.abs(ny)>Float.MAX_VALUE){invalidVectors++;continue;}
            float oldFacing=missile.getFacing(),oldAngular=missile.getAngularVelocity();
            if(!Float.isFinite(oldFacing)||!Float.isFinite(oldAngular)){invalidVectors++;continue;}
            if(!remember(chosen,missile)){budgetSkips++;continue;}for(Actor a:near)remember(a,missile);
            float oldX=v.x,oldY=v.y;
            v.set((float)nx,(float)ny);
            // A pressure collision also turns the missile with its displaced momentum once.
            // Otherwise its still-inward native engine thrust cancels the outward impulse
            // in about0.17s. No later scan holds orientation or suppresses the original AI.
            float pushedFacing=(float)((Math.toDegrees(Math.atan2(ny,nx))+360d)%360d);
            missile.setFacing(pushedFacing);missile.setAngularVelocity(0f);
            impulseBudget--;pushes++;chosen.pushes++;
            if(impulses.size()<64){Map<String,Object> row=new LinkedHashMap<>();row.put("time",clock);row.put("sourceId",id(chosen.ship));row.put("sourceVentCycle",chosen.cycle);row.put("missileSpec",Objects.toString(missile.getProjectileSpecId(),""));row.put("owner",missile.getOwner());row.put("edgeDistance",best);row.put("position",List.of(p.x,p.y));row.put("before",List.of(oldX,oldY));row.put("after",List.of(v.x,v.y));row.put("beforeFacing",oldFacing);row.put("afterFacing",missile.getFacing());row.put("beforeAngularVelocity",oldAngular);row.put("afterAngularVelocity",missile.getAngularVelocity());row.put("outwardSpeed",outward);impulses.add(Collections.unmodifiableMap(row));}
            if(clock-chosen.lastPulse>=.16&&pulses.size()<MAX_PULSES){pulses.add(new Pulse(s.x,s.y,Math.min(130,Math.max(40,chosen.boundary.extent+8)),clock));chosen.lastPulse=clock;}
        }
        if(size>limit)budgetSkips+=size-limit;missileCursor=(missileCursor+limit)%size;
    }
    private boolean remember(Actor a,MissileAPI missile){if(a.held.contains(missile))return true;if(a.held.size()>=MAX_HELD_PER_SOURCE||heldTotal>=MAX_HELD_TOTAL)return false;a.held.add(missile);heldTotal++;return true;}
    private boolean normalMissile(MissileAPI m){return live(m)&&(m.getOwner()==0||m.getOwner()==1)&&m.getDamageAmount()>0&&Float.isFinite(m.getDamageAmount())&&m.getCollisionClass()!=CollisionClass.NONE&&(m.getWeapon()==null||!m.getWeapon().isDecorative());}
    private boolean live(CombatEntityAPI e){return e!=null&&!e.isExpired()&&!e.wasRemoved()&&engine.isEntityInPlay(e)&&(!(e instanceof ShipAPI s)||s.isAlive()&&!s.isHulk());}
    private static boolean hostile(int a,int b){return(a==0||a==1)&&(b==0||b==1)&&a!=b;}
    private static boolean finite(Vector2f v){return v!=null&&Float.isFinite(v.x)&&Float.isFinite(v.y);}
    private static String id(ShipAPI s){return Objects.toString(s.getId(),"");}
    /** Polygon copied in native facing0 coordinates,never update/write the shared native BoundsAPI. */
    private static final class Boundary {
        final float x,y,cos,sin,radius,extent;final List<float[]> edges;
        Boundary(float x,float y,float facing,float radius,float extent,List<float[]> edges){this.x=x;this.y=y;this.radius=radius;this.extent=extent;this.edges=edges;double a=Math.toRadians(facing);cos=(float)Math.cos(a);sin=(float)Math.sin(a);}
        static Boundary read(ShipAPI s){Vector2f p=s.getLocation();float radius=Float.isFinite(s.getCollisionRadius())?Math.max(0,s.getCollisionRadius()):0,extent=radius;List<float[]> edges=new ArrayList<>();BoundsAPI bounds=s.getExactBounds();List<BoundsAPI.SegmentAPI> original=bounds==null?null:bounds.getOrigSegments();
            if(original!=null&&original.size()<=128)for(BoundsAPI.SegmentAPI segment:original){if(segment==null||!finite(segment.getP1())||!finite(segment.getP2()))continue;Vector2f a=segment.getP1(),b=segment.getP2();edges.add(new float[]{a.x,a.y,b.x,b.y});extent=Math.max(extent,(float)Math.max(Math.hypot(a.x,a.y),Math.hypot(b.x,b.y)));}
            if(edges.size()<3)edges.clear();return new Boundary(p.x,p.y,s.getFacing(),radius,extent,edges);}
        float distance(MissileAPI m){Vector2f p=m.getLocation();if(!finite(p)||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(cos)||!Float.isFinite(sin))return Float.POSITIVE_INFINITY;
            float px=(p.x-x)*cos+(p.y-y)*sin,py=-(p.x-x)*sin+(p.y-y)*cos,r=Float.isFinite(m.getCollisionRadius())?Math.max(0,m.getCollisionRadius()):0,gap;
            if(edges.isEmpty())gap=(float)Math.hypot(px,py)-radius-r;else{boolean inside=false;float best=Float.POSITIVE_INFINITY;for(float[] e:edges){if((e[1]>py)!=(e[3]>py)&&px<(e[2]-e[0])*(py-e[1])/(e[3]-e[1])+e[0])inside=!inside;float dx=e[2]-e[0],dy=e[3]-e[1],denom=dx*dx+dy*dy,t=denom<=.000001?0:Math.max(0,Math.min(1,((px-e[0])*dx+(py-e[1])*dy)/denom));float qx=px-e[0]-t*dx,qy=py-e[1]-t*dy;best=Math.min(best,qx*qx+qy*qy);}gap=inside?0:(float)Math.sqrt(best)-r;}
            return Float.isFinite(gap)?Math.max(0,gap):Float.POSITIVE_INFINITY;}
    }
    /** Finite short broken pressure arcs; draw only,never modifies a missile or advances time. */
    public void renderWorld(ViewportAPI viewport){
        if(closed||pulses.isEmpty()||viewport==null)return;int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try{GL20.glUseProgram(0);int units=Math.max(1,GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));for(int i=0;i<units;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glDisable(GL11.GL_TEXTURE_1D);GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL12.GL_TEXTURE_3D);GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);}GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDepthMask(false);GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE);GL11.glShadeModel(GL11.GL_SMOOTH);
            for(Pulse pulse:pulses){float age=(float)Math.max(0,Math.min(1,(clock-pulse.born)/.24)),radius=pulse.radius+45*age,alpha=.14f*(1-age)*Math.max(0,Math.min(1,viewport.getAlphaMult()));if(!viewport.isNearViewport(new Vector2f(pulse.x,pulse.y),radius))continue;
                for(int arc=0;arc<4;arc++){GL11.glBegin(GL11.GL_QUAD_STRIP);try{for(int i=0;i<=10;i++){double angle=Math.toRadians(arc*90+20+i*4);float c=(float)Math.cos(angle),s=(float)Math.sin(angle),a=alpha*(float)Math.sin(Math.PI*i/10);GL11.glColor4f(.75f,.86f,1,a);GL11.glVertex2f(pulse.x+c*(radius-1.2f),pulse.y+s*(radius-1.2f));GL11.glColor4f(.75f,.86f,1,0);GL11.glVertex2f(pulse.x+c*(radius+2.5f),pulse.y+s*(radius+2.5f));}}finally{GL11.glEnd();}}renderedPulses++;
            }
        }finally{GL11.glPopAttrib();GL20.glUseProgram(program);GL13.glActiveTexture(active);}publish();
    }
    public Map<String,Object> status(){return Collections.unmodifiableMap(new LinkedHashMap<>(facts));}
    private void publish(){facts.put("schema",1);facts.put("closed",closed);facts.put("edgeRange",EDGE_RANGE);facts.put("outwardSpeed",OUTWARD_SPEED);facts.put("scanSeconds",SCAN_SECONDS);facts.put("sourceCount",actors.size());facts.put("activeSources",actors.values().stream().filter(a->a.venting).count());facts.put("heldMissiles",heldTotal);facts.put("maxHeld",MAX_HELD_TOTAL);facts.put("scans",scans);facts.put("missileCandidates",candidates);facts.put("impulses",pushes);facts.put("ventStarts",ventStarts);facts.put("overlapSkips",overlapSkips);facts.put("budgetSkips",budgetSkips);facts.put("invalidVectors",invalidVectors);facts.put("renderedPulses",renderedPulses);facts.put("sources",actors.values().stream().sorted(Comparator.comparing(a->id(a.ship))).map(Actor::status).toList());facts.put("recentImpulses",List.copyOf(impulses));facts.put("scope","One radial velocity and orientation collision impulse per missile/vent cycle;near overlapping sources share a held mark;original AI,owner,collision,damage,lifetime and callbacks unchanged;no continuing velocity or facing control");}
    @Override public void close(){if(closed)return;closed=true;for(Actor a:actors.values())a.clear();actors.clear();pulses.clear();if(engine.getCustomData().get(KEY+".owner")==this)engine.getCustomData().remove(KEY+".owner");publish();}
}
