package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.combat.entities.MovingRay;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.util.vector.Vector2f;

/** Exact native short-ray observer. Original Magnum textures/width; no generated attack or fake beam. */
public final class UnicornMagnumPulseEffect implements EveryFrameWeaponEffectPluginWithAdvanceAfter,WeaponEffectPluginWithInit,AutoCloseable {
    public static final String WEAPON_ID="Moci_fw_unicorn_magnum_pulse",PROJECTILE_ID="Moci_fw_unicorn_magnum_pulse_shot";
    private WeaponAPI bound;private CombatEngineAPI engine;private boolean installed,closed;
    private float flashAge=1f;
    @Override public void init(WeaponAPI weapon){
        if(bound!=null&&bound!=weapon)throw new IllegalStateException("Magnum pulse callback reused");
        if(!WEAPON_ID.equals(weapon.getId()))throw new IllegalArgumentException("Exact owned Magnum pulse required");bound=weapon;
        weapon.setForceNoFireOneFrame(true);
    }
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon){
        if(bound==null)init(weapon);if(bound!=weapon)throw new IllegalStateException("Magnum pulse identity changed");
        if(closed||engine==null||engine.isPaused())return;
        UnicornMagnumModes.beforeWeapon(weapon);
        if(!Float.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid Magnum visual step");
        flashAge+=amount;
        if(!installed){this.engine=engine;engine.addLayeredRenderingPlugin(new Visuals());OwnBattleResources.register(engine,this);installed=true;}
    }
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon){if(!closed&&engine!=null&&!engine.isPaused())UnicornMagnumModes.afterWeapon(weapon);}
    static void nativeShot(WeaponAPI weapon){
        if(!(weapon.getEffectPlugin() instanceof UnicornMagnumPulseEffect effect))throw new IllegalStateException("Missing native Magnum pulse callback");effect.flashAge=0;
    }
    private final class Visuals extends BaseCombatLayeredRenderingPlugin {
        final Masks masks=new Masks();
        @Override public EnumSet<CombatEngineLayers> getActiveLayers(){return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);}
        @Override public float getRenderRadius(){return 3500;}
        @Override public boolean isExpired(){return closed||bound.getShip()==null||!bound.getShip().isAlive()||bound.getShip().wasRemoved()||Global.getCombatEngine()!=engine;}
        @Override public void advance(float dt){if(entity!=null)entity.getLocation().set(bound.getLocation());}
        @Override public void render(CombatEngineLayers layer,ViewportAPI viewport){
            if(isExpired()||bound.getShip().isPhased())return;
            try(Masks.State guard=new Masks.State()){
                int scanned=0,drawn=0;
                for(DamagingProjectileAPI p:engine.getProjectiles()){
                    if(scanned++>=4096||drawn>=64)break;
                    if(p.getWeapon()!=bound||p.getSource()!=bound.getShip()||!PROJECTILE_ID.equals(p.getProjectileSpecId())||!(p instanceof MovingRay ray)||ray.isExpired())continue;
                    Vector2f from=ray.getFrom(),to=ray.getTo();float length=Vector2f.sub(to,from,null).length();
                    if(!Float.isFinite(length)||length<=.001f||!viewport.isNearViewport(ray.getLocation(),250))continue;
                    float alpha=clamp(ray.getBrightness())*clamp(viewport.getAlphaMult());
                    masks.segment(from,to,150f,alpha);drawn++;
                }
                if(flashAge<.22f){Vector2f at=bound.getFirePoint(0);if(viewport.isNearViewport(at,160))masks.muzzle(at,bound.getCurrAngle(),clamp((.22f-flashAge)/.14f)*clamp(viewport.getAlphaMult()),1f);}
            }
        }
    }
    static float clamp(float f){return Float.isFinite(f)?Math.max(0,Math.min(1,f)):0;}
    /** Prepared own wrappers are read through texture IDs; no shared Sprite or native ray writes. */
    static final class Masks {
        final SpriteAPI beamCore=load("graphics/fx/Moci_magnum_beam_core.png"),beamFringe=load("graphics/fx/Moci_magnum_beam_fringe.png"),
                core=load("graphics/fx/Moci_magnum_fx_core.png"),fringe=load("graphics/fx/Moci_magnum_fx_fringe.png");
        static SpriteAPI load(String path){SpriteAPI sprite=Global.getSettings().getSprite(path);if(sprite==null||sprite.getTextureId()<=0)throw new IllegalStateException("Missing original Magnum texture "+path);return sprite;}
        void segment(Vector2f from,Vector2f to,float width,float alpha){strip(beamFringe,from,to,width,alpha*.8f);strip(beamCore,from,to,width,alpha);}
        private void strip(SpriteAPI sprite,Vector2f from,Vector2f to,float width,float alpha){
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,sprite.getTextureId());float dx=to.x-from.x,dy=to.y-from.y,inverse=1f/(float)Math.hypot(dx,dy),nx=-dy*inverse,ny=dx*inverse;
            float u=sprite.getTexX(),v=sprite.getTexY(),uw=sprite.getTexWidth(),vh=sprite.getTexHeight();
            GL11.glBegin(GL11.GL_QUAD_STRIP);
            try{for(int i=0;i<=24;i++){float t=i/24f,cap=(float)Math.pow(Math.max(0,Math.sin(Math.PI*t)),.30),half=width*.5f*cap;
                float x=from.x+dx*t,y=from.y+dy*t;GL11.glColor4f(1,1,1,alpha*cap);
                GL11.glTexCoord2f(u+uw*t,v);GL11.glVertex2f(x-nx*half,y-ny*half);
                GL11.glTexCoord2f(u+uw*t,v+vh);GL11.glVertex2f(x+nx*half,y+ny*half);
            }}finally{GL11.glEnd();}
        }
        void muzzle(Vector2f at,float angle,float alpha,float scale){mask(fringe,at,115*scale,angle+35,alpha*.85f);mask(core,at,76*scale,angle-20,alpha);}
        private void mask(SpriteAPI sprite,Vector2f at,float size,float angle,float alpha){
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,sprite.getTextureId());GL11.glColor4f(1,1,1,clamp(alpha));
            double a=Math.toRadians(angle);float rx=(float)Math.cos(a)*size*.5f,ry=(float)Math.sin(a)*size*.5f,ux=-ry,uy=rx;
            float u=sprite.getTexX(),v=sprite.getTexY(),uw=sprite.getTexWidth(),vh=sprite.getTexHeight();GL11.glBegin(GL11.GL_QUADS);
            try{GL11.glTexCoord2f(u,v);GL11.glVertex2f(at.x-rx-ux,at.y-ry-uy);GL11.glTexCoord2f(u+uw,v);GL11.glVertex2f(at.x+rx-ux,at.y+ry-uy);GL11.glTexCoord2f(u+uw,v+vh);GL11.glVertex2f(at.x+rx+ux,at.y+ry+uy);GL11.glTexCoord2f(u,v+vh);GL11.glVertex2f(at.x-rx+ux,at.y-ry+uy);}finally{GL11.glEnd();}
        }
        static final class State implements AutoCloseable {
            final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            State(){GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);GL20.glUseProgram(0);int units=Math.max(1,GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));for(int i=0;i<units;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glDisable(GL11.GL_TEXTURE_1D);GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL12.GL_TEXTURE_3D);GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);GL11.glDisable(GL11.GL_TEXTURE_GEN_S);GL11.glDisable(GL11.GL_TEXTURE_GEN_T);GL11.glDisable(GL11.GL_TEXTURE_GEN_R);GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);}GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glEnable(GL11.GL_TEXTURE_2D);GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_MODE,GL11.GL_MODULATE);GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_FOG);GL11.glDepthMask(false);GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE);GL11.glShadeModel(GL11.GL_SMOOTH);}
            @Override public void close(){try{GL11.glPopAttrib();}finally{GL20.glUseProgram(program);GL13.glActiveTexture(active);}}
        }
    }
    @Override public void close(){closed=true;}
}
