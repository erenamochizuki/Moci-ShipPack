package data.shipsystems.scripts.travel;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import data.scripts.util.Moci_Util;
import java.awt.Color;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;
import static data.shipsystems.scripts.travel.Moci_NebulaGateMotion.*;

final class Moci_NebulaCloudFlash implements Moci_Util.NebulaPuffObserver {
    private static final float INTERVAL = .60f, JITTER = .09f, ECHO_DELAY = .15f, LATE_MARGIN = .10f;
    private static final float MAIN_LIFE = .21f, ECHO_LIFE = .17f, GOLDEN_ANGLE = 2.399963f;
    private final Vector2f gate;
    private final float radius, seed, speed;
    private final boolean ribbon;
    private final Puff[] bank = new Puff[48];
    private final Puff active = new Puff();
    private int cursor, beat;
    private float batchTime, lastExpiry, nextMain = SMOKE, nextEcho = Float.POSITIVE_INFINITY;
    private float outwardX, outwardY, strength;

    Moci_NebulaCloudFlash(Vector2f gate,float radius,float seed,float speed) {
        this(gate,radius,seed,speed,false);
    }

    Moci_NebulaCloudFlash(Vector2f gate,float radius,float seed,float speed,boolean ribbon) {
        this.gate=gate; this.radius=radius; this.seed=seed; this.speed=speed; this.ribbon=ribbon;
    }

    private static final class Puff {
        final Vector2f origin=new Vector2f(), drift=new Vector2f();
        float born, expires, size;
        Color tint;

        void set(Vector2f position,Vector2f velocity,float diameter,float birth,float end,Color color) {
            origin.set(position); drift.set(velocity); size=diameter; born=birth; expires=end; tint=color;
        }
    }

    boolean beginSmokeBatch(float battleTime) {
        batchTime=battleTime;
        return Float.isFinite(battleTime) && battleTime>=0f;
    }

    @Override public void emitted(Vector2f position,Vector2f velocity,float size,float lifetime,Color color) {
        if (!(lifetime>0f && size>0f) || color.getAlpha()==0) return;
        Puff puff=bank[cursor];
        if (puff==null) bank[cursor]=puff=new Puff();
        cursor=(cursor+1)%bank.length;
        puff.set(position,velocity,size,batchTime,batchTime+lifetime,color);
        lastExpiry=Math.max(lastExpiry,puff.expires);
    }

    private float battleAt(float animation) {
        return ribbon ? Moci_FluxRibbonDrive.TIMING.battleAt(animation) : animation/speed;
    }

    private float animationAt(float battle) {
        if (!ribbon) return battle*speed;
        var timing=Moci_FluxRibbonDrive.TIMING;
        float end=timing.battleAt(END);
        return battle<=end ? timing.animationAt(battle) : END+(battle-end)*timing.rate(3);
    }

    private boolean chooseCloud(float battle,float animation,float heading) {
        float aimX=(float)Math.cos(heading), aimY=(float)Math.sin(heading);
        float targetX=gate.x+aimX*radius*.72f, targetY=gate.y+aimY*radius*.72f;
        float endNeeded=battleAt(animation+ECHO_DELAY+ECHO_LIFE+.045f);
        float best=Float.POSITIVE_INFINITY;
        Puff chosen=null;
        for (Puff puff:bank) {
            if (puff==null || puff.expires<endNeeded || (puff.born==active.born && puff.origin.equals(active.origin))) continue;
            float elapsed=battle-puff.born;
            float deltaX=puff.origin.x+puff.drift.x*elapsed-targetX, deltaY=puff.origin.y+puff.drift.y*elapsed-targetY;
            float vitality=ease((puff.expires-battle)/(puff.expires-puff.born)/.65f);
            float score=deltaX*deltaX+deltaY*deltaY+radius*radius*.28f*(1f-vitality);
            if (score<best) { best=score; chosen=puff; }
        }
        if (chosen==null) return false;
        active.set(chosen.origin,chosen.drift,chosen.size,chosen.born,chosen.expires,chosen.tint);
        outwardX=aimX; outwardY=aimY;
        float closure=battleAt(CLOSED);
        if (battle<=closure) strength=.65f+.35f*ease((animation-SMOKE)/.6f);
        else {
            float remaining=Math.max(0f,1f-(battle-closure)/Math.max(.001f,lastExpiry-closure));
            strength=remaining*ease(remaining);
        }
        return strength>.015f;
    }

    void advance(CombatEngineAPI engine,float battle,boolean visible) {
        float animation=animationAt(battle);
        if (!visible) nextEcho=Float.POSITIVE_INFINITY;
        if (animation>=nextMain) {
            float due=nextMain;
            float heading=seed+beat*GOLDEN_ANGLE;
            float spacing=INTERVAL+JITTER*(float)Math.sin(seed+beat*1.83f);
            beat++;
            boolean timely=animation-due<=LATE_MARGIN;
            nextMain=(timely ? due : animation)+spacing;
            nextEcho=Float.POSITIVE_INFINITY;
            if (visible && timely && chooseCloud(battle,animation,heading)) {
                nextEcho=animation+ECHO_DELAY;
                lobe(engine,battle,animation,0f,0f,1.38f,MAIN_LIFE,.64f,2.5f);
                lobe(engine,battle,animation,-.29f,.11f,1.03f,.19f,.49f,2.05f);
                lobe(engine,battle,animation,.17f,-.25f,.77f,.165f,.52f,1.85f);
            }
        } else if (animation>=nextEcho) {
            float due=nextEcho;
            nextEcho=Float.POSITIVE_INFINITY;
            if (visible && animation-due<=LATE_MARGIN && active.expires>=battleAt(animation+ECHO_LIFE)) {
                lobe(engine,battle,animation,.54f,.12f,1.06f,ECHO_LIFE,.48f,1.48f);
                lobe(engine,battle,animation,.38f,.32f,.79f,.15f,.36f,1.15f);
            }
        }
    }

    private void lobe(CombatEngineAPI engine,float battle,float animation,float across,float outward,float scale,float life,float highlight,float alpha) {
        float elapsed=battle-active.born;
        Vector2f position=new Vector2f(active.origin.x+active.drift.x*elapsed+active.size*(outward*outwardX-across*outwardY),
                active.origin.y+active.drift.y*elapsed+active.size*(outward*outwardY+across*outwardX));
        float alphaScale=Math.min(alpha,255f/active.tint.getAlpha())*strength;
        engine.addNebulaParticle(position,active.drift,active.size*scale,1.18f,.12f,.24f,battleAt(animation+life)-battle,
                Moci_NebulaSmokePalette.toward(active.tint,255,highlight,alphaScale));
    }

    void finishTransit(CombatEngineAPI engine,ShipAPI ship,float renderRadius,float battle) {
        if (battle>=lastExpiry) return;
        boolean visible=Moci_NebulaGateTransit.inView(engine,ship,gate,renderRadius);
        advance(engine,battle,visible);
        engine.addPlugin(new BaseEveryFrameCombatPlugin() {
            private float elapsed=battle;

            @Override public void advance(float amount,List<InputEventAPI> events) {
                if (!ship.isAlive() || ship.isRetreating() || !engine.isEntityInPlay(ship)) { engine.removePlugin(this); return; }
                if (!(amount>0f) || engine.isPaused()) return;
                elapsed+=amount;
                if (elapsed>=lastExpiry) { engine.removePlugin(this); return; }
                Moci_NebulaCloudFlash.this.advance(engine,elapsed,Moci_NebulaGateTransit.inView(engine,ship,gate,renderRadius));
            }
        });
    }
}
