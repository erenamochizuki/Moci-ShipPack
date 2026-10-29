package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import java.awt.Color;
import java.util.List;
import org.lwjgl.opengl.*;
import org.lwjgl.util.vector.Vector2f;

/** Bounded additive presentation, sampled from the real muzzle and native beam.
 * No clocks advance in render; no particles/entities/damage/sounds are emitted. */
public final class RifleChargeOptics {
    public static final int MAX_RAYS = 8, GRAINS = 7;
    public record Cues(float gatheringRadius, float gatheringLight, float firstLight, float shockRadius) { }
    public record ChargeEnvelope(float coreRadius, float haloRadius, float ringRadius, float blueMix) { }
    public static final Color ELECTRIC = new Color(76, 158, 255), ELECTRIC_CORE = new Color(188, 225, 255);
    private RifleChargeOptics() { }

    public static Cues cues(float charge, float firedSeconds) {
        float q = clamp(charge);
        float first = Float.isFinite(firedSeconds) && firedSeconds >= 0 && firedSeconds < .22f
                ? (float)Math.exp(-firedSeconds / .065f) * (1 - smooth(firedSeconds / .22f)) : 0;
        float spread = first > 0 ? 14 + 56 * smooth(firedSeconds / .22f) : 0;
        return new Cues(30 + 12 * q, q > 0 ? .20f + .56f * q : 0, first, spread);
    }
    /** Slow bounded growth continues after full normal power, independently of damage caps. */
    public static ChargeEnvelope envelope(float heldSeconds) {
        float held = Float.isFinite(heldSeconds) ? Math.max(0, heldSeconds) : 0;
        float normal = smooth(held / 3), longHold = smooth((held - 3) / 7);
        float excess = Math.max(0, held - 10), limit = excess / (excess + 4);
        return new ChargeEnvelope(14 + 12 * normal + 4 * longHold + 24 * limit,
                24 + 14 * normal + 7 * longHold + 37 * limit,
                13 + 9 * normal + 7 * longHold + 31 * limit,
                excess > 0 ? .26f + .47f * limit : 0);
    }
    public static float armElectric(float lockoutAge) {
        return Float.isFinite(lockoutAge) && lockoutAge >= 0 && lockoutAge < 5 ? 1 : 0;
    }
    public static Color mix(Color base, Color target, float fraction) {
        float q = clamp(fraction);
        return new Color(Math.round(base.getRed()*(1-q)+target.getRed()*q),
                Math.round(base.getGreen()*(1-q)+target.getGreen()*q),
                Math.round(base.getBlue()*(1-q)+target.getBlue()*q));
    }
    public static float empPulse(float age) {
        return Float.isFinite(age) && age >= 0 && age < .45f
                ? (float)Math.exp(-age/.11f)*(1-smooth(age/.45f)) : 0;
    }

    /** Returns actually submitted visible effect groups, rather than a health/compatibility claim. */
    public static int render(Vector2f muzzle, float angle, float time, float heldSeconds, boolean gathering,
            float firedSeconds, float overlimitPower, Vector2f gunPart, Vector2f armPart, float lockoutAge,
            List<BeamAPI> beams, BeamRiflePalette palette, ViewportAPI viewport) {
        if (muzzle == null || palette == null || viewport == null || !finite(muzzle.x, muzzle.y, angle, time)) return 0;
        float alpha = clamp(viewport.getAlphaMult()); if (alpha <= .001f) return 0;
        float charge=heldSeconds/3; Cues cue = cues(charge, firedSeconds);
        ChargeEnvelope volume=envelope(heldSeconds);
        int drawn = 0;float limit=clamp(overlimitPower),emp=empPulse(lockoutAge),locked=armElectric(lockoutAge);
        Color primary=mix(palette.primary,ELECTRIC,volume.blueMix),inner=mix(palette.inner,ELECTRIC_CORE,volume.blueMix*.7f);
        if (!gathering && cue.firstLight <= .001f && locked <= .001f && beams.stream().noneMatch(b -> b != null && b.getBrightness() > .001f)) return 0;
        try (State ignored = new State()) {
            if (gathering && viewport.isNearViewport(muzzle, 100)) {
                float q = clamp(charge), radius = volume.haloRadius;
                dot(muzzle.x, muzzle.y, radius, primary, alpha * cue.gatheringLight * .19f);
                dot(muzzle.x, muzzle.y, volume.coreRadius, primary, alpha * cue.gatheringLight * .56f);
                dot(muzzle.x, muzzle.y, volume.coreRadius*.46f, inner, alpha * cue.gatheringLight * .88f);
                dot(muzzle.x, muzzle.y, volume.coreRadius*.21f, Color.WHITE, alpha * cue.gatheringLight);
                // Growing broken crescents have feathered ends, never an opaque disc or rectangular bloom.
                arc(muzzle, angle + time*65, volume.ringRadius, 145, 1.7f, primary, alpha*.30f*(.35f+q));
                arc(muzzle, angle - time*83 + 160, volume.ringRadius*.73f, 100, 1.3f, inner, alpha*.36f*(.35f+q));
                for (int i=0; i<GRAINS; i++) {
                    float travel = fract(time * (1.5f + q*.55f) + i*.618034f);
                    float light = (float)Math.sin(Math.PI*travel) * (.22f + q*.38f) * alpha;
                    double a = Math.toRadians(i*137.5f + time*31 + travel*24);
                    float r = 5 + radius * (1-travel)*(1-travel);
                    float x=muzzle.x+(float)Math.cos(a)*r, y=muzzle.y+(float)Math.sin(a)*r;
                    dot(x,y,1.4f+q, palette.inner, light);
                    streak(x,y,x+(float)Math.cos(a)*6,y+(float)Math.sin(a)*6,1.4f,palette.primary,light*.55f);
                }
                if(limit>0) {
                    arc(muzzle,angle-time*105,volume.ringRadius*1.1f,120,2.4f,ELECTRIC,alpha*.65f);
                }
                drawn++;
            }
            if (cue.firstLight > .001f && viewport.isNearViewport(muzzle, 115)) {
                float flash=cue.firstLight*alpha;
                dot(muzzle.x,muzzle.y,(42*cue.firstLight+18)*(1+.45f*limit),primary,flash*.36f);
                dot(muzzle.x,muzzle.y,(18*cue.firstLight+8)*(1+.45f*limit),inner,flash*.8f);
                dot(muzzle.x,muzzle.y,7*cue.firstLight+4,Color.WHITE,flash);
                // A short recoil collar, not an ever-expanding halo along the whole weapon.
                arc(muzzle,angle+55,cue.shockRadius,70,2.8f,palette.inner,flash*.54f);
                arc(muzzle,angle+235,cue.shockRadius,70,2.8f,palette.primary,flash*.4f);
                double a=Math.toRadians(angle); float dx=(float)Math.cos(a),dy=(float)Math.sin(a);
                streak(muzzle.x-dx*16,muzzle.y-dy*16,muzzle.x+dx*35,muzzle.y+dy*35,9,palette.inner,flash*.6f);
                drawn++;
            }
            // EMP arcs belong to NativeRifleEmp's native spawnEmpArcVisual lifecycle, not this GL renderer.
            int checked=0;
            for (BeamAPI beam : beams) {
                if (++checked>MAX_RAYS) break;
                if (beam==null || beam.getBrightness()<=.001f) continue;
                Vector2f from=beam.getFrom(),to=beam.getTo();
                if (from==null||to==null||!finite(from.x,from.y,to.x,to.y)) continue;
                float dx=to.x-from.x,dy=to.y-from.y,length=(float)Math.hypot(dx,dy);
                if (length<8) continue; dx/=length;dy/=length;
                float light=clamp(beam.getBrightness())*alpha;
                if(limit>0) {
                    // Native-width hot core and blue electrical ribbons follow the exact hit-clipped ray.
                    overlimitSpine(from,to,beam.getWidth(),light,primary,inner);
                    drawn++;
                }
                // Fine energy moving inside the existing continuous beam, never beyond its actual hit.
                for (int i=0;i<3;i++) {
                    float p=fract(time*.85f+i/3f),half=Math.min(48,length*.09f);
                    float at=p*length,lo=Math.max(0,at-half),hi=Math.min(length,at+half);
                    Vector2f middle=new Vector2f(from.x+dx*at,from.y+dy*at);
                    if (hi-lo>2 && viewport.isNearViewport(middle,half+20)) {
                        streak(from.x+dx*lo,from.y+dy*lo,from.x+dx*hi,from.y+dy*hi,
                                4+beam.getWidth()*.12f,palette.inner,light*.23f);
                        drawn++;
                    }
                }
                if (beam.getDamageTarget()!=null && viewport.isNearViewport(to,65)) {
                    for (int i=0;i<5;i++) {
                        float p=fract(time*2.8f+i*.381966f);
                        double a=Math.atan2(-dy,-dx)+(i-2)*.30;
                        float c=(float)Math.cos(a),s=(float)Math.sin(a),r=3+24*p;
                        streak(to.x+c*r,to.y+s*r,to.x+c*(r+9),to.y+s*(r+9),1.6f,
                                i%2==0?palette.inner:palette.spark,light*(1-p)*.46f);
                    }
                    drawn++;
                }
            }
        }
        return drawn;
    }

    private static void overlimitSpine(Vector2f from,Vector2f to,float width,float alpha,Color primary,Color inner) {
        float dx=to.x-from.x,dy=to.y-from.y,length=(float)Math.hypot(dx,dy);if(length<1)return;
        float nx=-dy/length,ny=dx/length;
        float safe=Math.max(1,Math.min(160,width));
        for(int layer=0;layer<3;layer++) {
            float half=safe*(layer==0?.68f:layer==1?.30f:.18f);
            Color color=layer==0?primary:layer==1?inner:Color.WHITE;
            for(int band=0;band<10;band++) {
                float side0=-1+band*.2f,side1=side0+.2f;
                GL11.glBegin(GL11.GL_QUAD_STRIP);
                try { for(int i=0;i<=20;i++) {
                    float p=i/20f,taper=smooth(p/.05f)*smooth((1-p)/.08f);
                    float x=from.x+dx*p,y=from.y+dy*p;
                    rgba(color,alpha*taper*(float)Math.exp(-side0*side0*5)*(layer==0?.19f:.48f));
                    GL11.glVertex2f(x+nx*half*side0,y+ny*half*side0);
                    rgba(color,alpha*taper*(float)Math.exp(-side1*side1*5)*(layer==0?.19f:.48f));
                    GL11.glVertex2f(x+nx*half*side1,y+ny*half*side1);
                }} finally {GL11.glEnd();}
            }
        }
    }

    private static void arc(Vector2f at,float angle,float radius,float span,float width,Color color,float alpha) {
        for (int i=0;i<18;i++) {
            float t=(i+.5f)/18f;
            double a=Math.toRadians(angle-span*.5f+span*i/18f),b=Math.toRadians(angle-span*.5f+span*(i+1)/18f);
            streak(at.x+(float)Math.cos(a)*radius,at.y+(float)Math.sin(a)*radius,
                    at.x+(float)Math.cos(b)*radius,at.y+(float)Math.sin(b)*radius,width,color,
                    alpha*(float)Math.sin(Math.PI*t));
        }
    }
    private static void dot(float x,float y,float radius,Color color,float alpha) {
        if (radius<=0 || alpha<=.001f) return;
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        try { rgba(color,alpha);GL11.glVertex2f(x,y);
            for(int i=0;i<=20;i++){double a=i*Math.PI*2/20;rgba(color,0);GL11.glVertex2f(x+(float)Math.cos(a)*radius,y+(float)Math.sin(a)*radius);}
        } finally { GL11.glEnd(); }
    }
    private static void streak(float x,float y,float tx,float ty,float width,Color color,float alpha) {
        float dx=tx-x,dy=ty-y,length=(float)Math.hypot(dx,dy);if(length<.01f||alpha<=.001f)return;
        float nx=-dy/length,ny=dx/length;
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        try { rgba(color,alpha);GL11.glVertex2f((x+tx)*.5f,(y+ty)*.5f);
            rgba(color,0);GL11.glVertex2f(x,y);
            GL11.glVertex2f((x+tx)*.5f+nx*width*.5f,(y+ty)*.5f+ny*width*.5f);
            GL11.glVertex2f(tx,ty);
            GL11.glVertex2f((x+tx)*.5f-nx*width*.5f,(y+ty)*.5f-ny*width*.5f);GL11.glVertex2f(x,y);
        } finally { GL11.glEnd(); }
    }
    private static float fract(float value){return value-(float)Math.floor(value);}
    private static float clamp(float value){return Float.isFinite(value)?Math.max(0,Math.min(1,value)):0;}
    private static float smooth(float value){float v=clamp(value);return v*v*(3-2*v);}
    private static boolean finite(float... v){for(float x:v)if(!Float.isFinite(x))return false;return true;}
    private static void rgba(Color color,float a){GL11.glColor4f(color.getRed()/255f,color.getGreen()/255f,color.getBlue()/255f,clamp(a)*color.getAlpha()/255f);}
    private static final class State implements AutoCloseable {
        final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        State(){GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);GL20.glUseProgram(0);
            int units=Math.max(1,GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));
            for(int i=0;i<units;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glDisable(GL11.GL_TEXTURE_1D);GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL12.GL_TEXTURE_3D);GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);GL11.glDisable(GL11.GL_TEXTURE_GEN_S);GL11.glDisable(GL11.GL_TEXTURE_GEN_T);GL11.glDisable(GL11.GL_TEXTURE_GEN_R);GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);}
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_FOG);GL11.glDepthMask(false);GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK,GL11.GL_FILL);GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE);GL11.glShadeModel(GL11.GL_SMOOTH);}
        @Override public void close(){try{GL11.glPopAttrib();}finally{GL20.glUseProgram(program);GL13.glActiveTexture(active);}}
    }
}
