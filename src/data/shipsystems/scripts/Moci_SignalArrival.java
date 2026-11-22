package data.shipsystems.scripts;

import com.fs.starfarer.api.combat.*;
import data.scripts.util.Moci_Util;
import data.scripts.util.Moci_TravelDriveControl.AnimationLock;
import org.lwjgl.util.vector.Vector2f;
import java.util.*;
import data.shipsystems.scripts.Moci_DiamondDrive.Landing;
import data.shipsystems.scripts.Moci_DiamondDrive.Diamond;
import data.shipsystems.scripts.Moci_PetalDrive.Petal;
import static data.shipsystems.scripts.Moci_DiamondDrive.Style.ease;

/** 两种跃迁共用的入场与绘制流程；撤退仍由游戏处理。 */
final class Moci_SignalArrival extends BaseCombatLayeredRenderingPlugin {
    private static final EnumSet<CombatEngineLayers> LAYERS=EnumSet.of(CombatEngineLayers.UNDER_SHIPS_LAYER,CombatEngineLayers.ABOVE_SHIPS_LAYER);
    private static final String HOLD="Moci_signalDrive_staging";
    private final CombatEngineAPI engine;
    private final ShipAPI ship;
    private final Moci_DiamondDrive.Style style;
    private final Vector2f gate;
    private final Random rng;
    private final ArrayList<Part> parts=new ArrayList<>();
    private final float radius,trailLength,phase;
    private final Moci_Util.PetalPulse[] petals;
    private int firstPetal,lastPetal;
    private float time, nextArc;
    private boolean released,expired,burnEnded;
    private Moci_Util.HullEcho echo;
    private AnimationLock controls;

    private static final class Part {
        final ShipAPI s;
        final Vector2f at,velocity;
        final float angle,spin,alpha;
        final CollisionClass collision;
        final boolean hidden;
        final MutableStat[] protection;
        boolean held,detached;
        Part(ShipAPI s) {
            this.s=s; at=new Vector2f(s.getLocation()); velocity=new Vector2f(s.getVelocity());
            angle=s.getFacing(); spin=s.getAngularVelocity(); alpha=s.getExtraAlphaMult();
            collision=s.getCollisionClass(); hidden=s.isDoNotRender();
            var m=s.getMutableStats();
            protection=new MutableStat[]{m.getHullDamageTakenMult(),m.getArmorDamageTakenMult(),m.getShieldDamageTakenMult(),
                    m.getEmpDamageTakenMult(),m.getWeaponDamageTakenMult(),m.getEngineDamageTakenMult()};
        }
        void hold() {
            held=true;
            for(var m:protection) m.modifyMult(HOLD,0f);
            s.setDoNotRender(true); s.setExtraAlphaMult(0); s.setCollisionClass(CollisionClass.NONE); pin();
        }
        void pin() {
            s.getLocation().set(at); s.setFacing(angle); s.setAngularVelocity(0); s.getVelocity().set(0,0);
        }
        void release() {
            if(!held) return;
            held=false; s.setDoNotRender(hidden); s.setExtraAlphaMult(alpha); s.setCollisionClass(collision);
            for(var m:protection) m.unmodify(HOLD);
            if(s.isAlive()) { s.getVelocity().set(velocity); s.setAngularVelocity(spin); }
        }
    }

    Moci_SignalArrival(CombatEngineAPI engine,ShipAPI ship,Moci_DiamondDrive.Style style) {
        this(engine,ship,style,style.petals ? java.util.concurrent.ThreadLocalRandom.current().nextLong() : 0L);
    }
    Moci_SignalArrival(CombatEngineAPI engine,ShipAPI ship,Moci_DiamondDrive.Style style,long petalSeed) {
        this.engine=engine; this.ship=ship; this.style=style; gate=new Vector2f(ship.getLocation());
        rng=new Random(ship.getId().hashCode()); phase=rng.nextFloat()*360f;
        petals=style.petals ? Moci_PetalDrive.makePetals(petalSeed,style.charge) : null;
        parts.add(new Part(ship));
        for(var child:ship.getChildModulesCopy()) if(child.getParentStation()==ship && child.isAlive() && engine.isEntityInPlay(child)) parts.add(new Part(child));
        var bounds=Moci_Util.measureTravelHull(ship,engine);
        var sp=ship.getSpriteAPI();
        float solo=Math.max(ship.getCollisionRadius(),sp==null?0:.5f*Math.max(sp.getWidth(),sp.getHeight()));
        radius=Math.max(24f,bounds.hasModules()?Math.max(solo,bounds.radius()):solo);
        trailLength=((float)Math.hypot(engine.getMapWidth(),engine.getMapHeight())+radius*4)*Landing.TRAIL_LENGTH;
        nextArc=style.charge-Petal.ARC_LEAD;
    }
    void start() {
        if (released || controls!=null) return;
        controls=new AnimationLock(engine,ship);
        for(var p:parts) p.hold();
    }
    void continueBurn() { burnEnded=false; }
    void endBurn() { burnEnded=true; if(expired) forget(); }
    private void forget() { if(ship.getCustomData().get(Moci_DiamondDrive.EVENT)==this) ship.removeCustomData(Moci_DiamondDrive.EVENT); }
    private void release() {
        if(released) return;
        if(controls!=null) controls.release();
        for(var p:parts) p.release();
        released=true;
    }
    @Override public void cleanup() { release(); expired=true; echo=null; if(burnEnded) forget(); }
    @Override public boolean isExpired() { return expired; }
    @Override public EnumSet<CombatEngineLayers> getActiveLayers() { return LAYERS; }
    @Override public float getRenderRadius() { return released?Math.max(trailLength,4f*radius):4f*radius; }
    private boolean visible() {
        ShipAPI player=engine.getPlayerShip(); int side=player==null || player.getOwner()==100 ? 0 : player.getOwner();
        FogOfWarAPI fog=engine.getFogOfWar(side);
        return !parts.get(0).hidden && (ship.getOwner()==side || fog==null || fog.isVisible(gate));
    }
    @Override public void advance(float dt) {
        if(expired) return;
        if(!ship.isAlive() || !engine.isEntityInPlay(ship) || ship.isRetreating()) { cleanup(); return; }
        if(engine.isPaused() || !(dt>0)) return;
        time+=dt;
        float post=time-style.charge;
        if(petals!=null && post<0) {
            while(lastPetal<petals.length && petals[lastPetal].birth()<=time) lastPetal++;
            while(firstPetal<lastPetal && petals[firstPetal].end()<=time) firstPetal++;
        }
        boolean emit=visible() && engine.getViewport().isNearViewport(gate,4*radius);
        if(!released) {
            if(post<0 && controls!=null) controls.advance();
            for(var p:parts) {
                if(p.s!=ship && (!p.s.isAlive() || p.s.getParentStation()!=ship || !engine.isEntityInPlay(p.s))) { p.release(); p.detached=true; }
                if(p.held) p.pin();
            }
            if(style.petals && time>=nextArc && post<0) {
                if(emit) for(int i=0;i<Petal.ARCS_PER_BURST;i++) {
                    double a=(i+rng.nextFloat())*Math.PI*2/Petal.ARCS_PER_BURST;
                    float r=radius*(Petal.ARC_REACH+Petal.ARC_REACH_RANDOM*rng.nextFloat());
                    Moci_Util.spawnVisualLightning(engine,gate,new Vector2f(gate.x+r*(float)Math.cos(a),gate.y+r*(float)Math.sin(a)),
                            Math.max(Petal.ARC_MIN_WIDTH,radius*Petal.ARC_WIDTH),style.signal,Landing.FLASH,radius*Petal.ARC_SPEED); // 纯视觉电弧，无伤害
                }
                nextArc=time+Petal.ARC_INTERVAL+Petal.ARC_INTERVAL_RANDOM*rng.nextFloat();
            }
            if(post>=0) {
                release();
                if(emit && style.particles>0) Moci_Util.spawnRadialLights(engine,gate,radius,style.particles,style.signal,rng,Landing.SPARKS);
            }
        }
        if(post>=Math.max(style.petals ? Petal.LENS_TAIL : 0,Math.max(style.ring,Math.max(style.fade,style.echoEnd)))) { cleanup(); return; }
        if(post<0) return;
        float glow=1-ease(post/style.fade);
        for(var p:parts) if(!p.hidden && !p.detached && p.s.isAlive() && p.s.getEngineController()!=null) {
            p.s.getEngineController().extendFlame(this,Landing.ENGINE_LENGTH*glow,Landing.ENGINE_WIDTH*glow,Landing.ENGINE_GLOW*glow);
        }
    }

    @Override public void render(CombatEngineLayers layer,ViewportAPI view) {
        if(expired || !visible() || (released && ship.isDoNotRender())) return;
        float post=time-style.charge, opacity=view.getAlphaMult();
        boolean near=view.isNearViewport(gate,4*radius), hullNear=view.isNearViewport(ship.getLocation(),Math.max(3f,style.echoPeak)*radius);
        if(!near && !(released && (hullNear || layer==CombatEngineLayers.UNDER_SHIPS_LAYER && post<style.fade))) return;
        try(var c=new Moci_Util.LightCanvas()) {
            if(layer==CombatEngineLayers.UNDER_SHIPS_LAYER) {
                if(near) drawLanding(c,post,opacity);
                if(released && post<style.fade) drawTrails(c,(1-ease(post/style.fade)),opacity);
            } else {
                if(style.petals && near) c.lensBloom(gate,radius*Petal.LENS_SIZE,style.wave,opacity*Petal.LENS_POWER*Moci_PetalDrive.lensPower(post));
                if(!released || !hullNear || post>=Math.max(style.echoEnd,style.fade)) return;
                // 主舰已绘制，此时捕捉动画帧及子模块；虚影和贴合舰体的闪白各按自己的时间消退。
                if(echo==null || echo.isEmpty()) echo=new Moci_Util.HullEcho(ship);
                if(post<style.echoEnd) c.echo(echo,ship.getLocation(),ship.getFacing(),style.echoScale(post),Landing.FLASH,style.echoAlpha(post)*opacity);
                if(post<style.fade) c.echo(echo,ship.getLocation(),ship.getFacing(),1,Landing.FLASH,Landing.HULL_GLOW*(1-ease(post/style.fade))*opacity);
            }
        }
    }
    private void drawLanding(Moci_Util.LightCanvas c,float post,float opacity) {
        float t=ease(time/style.charge), r=radius;
        if(post<0 && !style.petals) {
            float d=ease(time/(style.charge*Diamond.MARKER_CHARGE)), ring=r*(Diamond.MARKER_RADIUS+Diamond.MARKER_EXPAND*d);
            c.band(gate,ring*(1-Diamond.MARKER_EDGE),ring,ring*(1+Diamond.MARKER_EDGE),4,style.signal,opacity*Diamond.MARKER_ALPHA*(1-d));
            c.band(gate,0,0,r*(Diamond.SPOT_RADIUS+Diamond.SPOT_EXPAND*t),72,Landing.HALO,opacity*Diamond.SPOT_ALPHA*ease((t-Diamond.SPOT_START)/(1-Diamond.SPOT_START)));
        } else if(post<0) {
            if(time<Petal.MARKER_TIME) {
                float d=time/Petal.MARKER_TIME, mark=r*(Petal.MARKER_RADIUS+Petal.MARKER_EXPAND*ease(d));
                c.band(gate,mark*(1-Petal.MARKER_EDGE),mark,mark*(1+Petal.MARKER_EDGE),4,style.wave,opacity*Petal.MARKER_ALPHA*ease(d/Petal.MARKER_FADE_IN)*(1-ease((d-Petal.MARKER_FADE_OUT_START)/(1-Petal.MARKER_FADE_OUT_START))));
            }
            c.pulsingPetals(gate,petals,firstPetal,lastPetal,phase,time,r*Petal.SIZE,style.signal,
                    opacity*(Petal.GROUP_ALPHA+Petal.GROUP_BRIGHTEN*t)*ease((time-Petal.GROUP_START)/Petal.GROUP_FADE_IN));
            c.band(gate,0,0,r*(Petal.SPOT_RADIUS+Petal.SPOT_EXPAND*t),72,style.signal,opacity*t*Petal.SPOT_ALPHA);
            float core=ease((time-Petal.CORE_START)/Petal.CORE_RISE);
            c.band(gate,0,0,r*(Petal.CORE_RADIUS+Petal.CORE_EXPAND*core),72,Landing.FLASH,opacity*Petal.CORE_ALPHA*core);
            float wavePhase=Moci_PetalDrive.contractingRingPhase(time,style.charge);
            if(wavePhase>=0) {
                float ring=r*(Petal.WAVE_RADIUS-Petal.WAVE_CONTRACT*wavePhase);
                c.band(gate,ring-r*Petal.WAVE_EDGE,ring,ring+r*Petal.WAVE_EDGE,72,Petal.CONTRACTING_RING_COLOR,opacity*(Petal.WAVE_ALPHA+Petal.WAVE_BRIGHTEN*t)*(float)Math.sin(wavePhase*Math.PI));
            }
            if(post>=-Petal.ARC_LEAD) {
                float q=ease(1+post/Petal.ARC_LEAD),fast=ease((Petal.ARC_LEAD+post)/Petal.OUTER_RISE);
                float outer=r*(Petal.OUTER_RADIUS+Petal.OUTER_EXPAND*fast),inner=r*(Petal.INNER_RADIUS+Petal.INNER_EXPAND*q);
                c.band(gate,outer-r*Petal.OUTER_IN_EDGE,outer,outer+r*Petal.OUTER_OUT_EDGE,72,style.wave,opacity*Petal.OUTER_ALPHA*(1-fast));
                c.band(gate,inner-r*Petal.INNER_IN_EDGE,inner,inner+r*Petal.INNER_OUT_EDGE,72,style.wave,opacity*Petal.INNER_ALPHA);
            }
        } else {
            if(post>=style.ring) return;
            float q=ease(post/style.ring), wave=r*(Landing.WAVE_RADIUS+Landing.WAVE_EXPAND*q), inner=r*Landing.HOLE_EXPAND*q;
            // 中心挖空向外扩张，最后才消去光斑的外缘。
            c.band(gate,inner,inner+r*Landing.HOLE_EDGE,Landing.SPOT_RADIUS*r,72,Landing.HALO,opacity*Landing.SPOT_ALPHA*(1-q));
            c.band(gate,wave-r*Landing.WAVE_IN_EDGE,wave,wave+r*Landing.WAVE_OUT_EDGE,72,style.wave,opacity*Landing.WAVE_ALPHA*(1-q));
        }
    }
    private void drawTrails(Moci_Util.LightCanvas c,float k,float opacity) {
        for(var p:parts) {
            var s=p.s; if(p.hidden || p.detached || !s.isAlive() || s.isDoNotRender() || s.getEngineController()==null) continue;
            double a=Math.toRadians(s.getFacing()); float dx=-(float)Math.cos(a),dy=-(float)Math.sin(a);
            for(var nozzle:s.getEngineController().getShipEngines()) {
                var slot=nozzle.getEngineSlot();
                if(slot==null || nozzle.isDisabled() || !nozzle.isActive()) continue;
                double diff=Math.toRadians(slot.computeMidArcAngle(s.getFacing())-s.getFacing());
                if(Math.cos(diff)>-.45) continue; // aft-facing propulsion, not manoeuvring jets
                c.ribbon(nozzle.getLocation(),dx,dy,trailLength,slot.getWidth()*Landing.TRAIL_WIDTH*k,style.trail,opacity*Landing.TRAIL_ALPHA*k);
            }
        }
    }
}
