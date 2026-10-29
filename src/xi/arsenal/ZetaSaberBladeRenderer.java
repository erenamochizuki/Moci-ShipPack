package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import java.awt.Color;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.util.vector.Vector2f;

/** Visual skin of the SAME installed native blade. Never changes its endpoints, width,
 * range, damage, collision or callback count. The opaque native tube/large trail is made
 * transparent; an untextured tapered mesh follows the actual collision ray exactly. */
public final class ZetaSaberBladeRenderer extends BaseCombatLayeredRenderingPlugin {
    private final CombatEngineAPI engine;private final WeaponAPI weapon;private BeamAPI beam;
    /** true 之后引擎会把本渲染器从分层队列移除（动作层关闭时释放）。 */
    private boolean released;
    private Color core=new Color(255,238,250),fringe=new Color(224,108,250);
    ZetaSaberBladeRenderer(CombatEngineAPI engine,WeaponAPI weapon){this.engine=Objects.requireNonNull(engine);this.weapon=Objects.requireNonNull(weapon);}
    /** Only color alpha is owned. Native dimensions and endpoints remain read-only. */
    void observeAndHide(BeamAPI current){if(current.getWeapon()!=weapon)throw new IllegalArgumentException("Native saber visual changed weapon identity");beam=current;
        Color c=current.getCoreColor(),f=current.getFringeColor();if(c!=null&&c.getAlpha()>0)core=c;if(f!=null&&f.getAlpha()>0)fringe=f;
        current.setCoreColor(transparent(core));current.setFringeColor(transparent(fringe));
    }
        /** 释放：还原被隐藏的原生光束颜色，并让引擎移除本渲染器（否则阵亡帧会留下隐形空刀）。 */
    void release(){if(beam!=null){try{beam.setCoreColor(core);beam.setFringeColor(fringe);}catch(RuntimeException ignored){}beam=null;}released=true;}
    private static Color transparent(Color c){return new Color(c.getRed(),c.getGreen(),c.getBlue(),0);}
    /** A narrow root, gently narrowing shaft and one continuous pointed tip; no rectangular halo. */
    public static float profile(float t){if(!Float.isFinite(t)||t<0||t>1)throw new IllegalArgumentException("Finite native blade fraction required");return(float)Math.pow(Math.max(0,1-t),.58);}
    /** Giant blade: root and middle are three times the previous visual thickness. The
     * final 28% smoothly returns to the original narrow tip; endpoints and hit width stay native. */
    public static float widthProfile(float t,boolean giant){float base=profile(t);if(!giant)return base;float q=Math.max(0,Math.min(1,(t-.72f)/.28f));float smooth=q*q*(3-2*q);return base*(3-2*smooth);}
    public static float rootHalfWidth(float nativeWidth){return Float.isFinite(nativeWidth)&&nativeWidth>0?Math.max(.2f,nativeWidth*.105f):.2f;}
    @Override public EnumSet<CombatEngineLayers> getActiveLayers(){return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);}
    @Override public float getRenderRadius(){return Math.max(500,weapon.getRange()+100);}
    @Override public boolean isExpired(){return released||weapon.getShip()==null||!weapon.getShip().isAlive()||weapon.getShip().wasRemoved()||Global.getCombatEngine()!=engine;}
    @Override public void advance(float dt){if(entity!=null)entity.getLocation().set(weapon.getLocation());}
    @Override public void render(CombatEngineLayers layer,ViewportAPI viewport){if(isExpired()||beam==null||!ZetaSaberMotion.nativeBladeVisible(weapon)||weapon.getShip().isPhased())return;
        Vector2f from=beam.getFrom(),to=beam.getTo();float length=Vector2f.sub(to,from,null).length(),alpha=clamp(beam.getBrightness())*clamp(viewport.getAlphaMult());
        if(!Float.isFinite(length)||length<=.001f||alpha<=.001f||!viewport.isNearViewport(from,length+25))return;
        float half=rootHalfWidth(beam.getWidth());boolean giant=ZetaOverdriveLifecycle.immune(weapon.getShip());
        try(State ignored=new State()){
            // Close additive glow: outer edge is transparent at each cross-section, not a quad.
            mesh(from,to,half*1.85f,half*.72f,alpha*.20f,new Color(220,110,247),giant);
            mesh(from,to,half*1.30f,half*.58f,alpha*.35f,new Color(247,156,232),giant);
            mesh(from,to,half,half*.27f,alpha*.88f,new Color(255,204,237),giant);
            mesh(from,to,half*.50f,0,alpha*.94f,new Color(255,247,254),giant);
        }
    }
    /** Smooth side-alpha strips share a continuously tapered contour and exact native tip. */
    private static void mesh(Vector2f from,Vector2f to,float outer,float inner,float alpha,Color color,boolean giant){float dx=to.x-from.x,dy=to.y-from.y,inverse=1f/(float)Math.hypot(dx,dy),nx=-dy*inverse,ny=dx*inverse;
        for(int side:new int[]{-1,1}){GL11.glBegin(GL11.GL_TRIANGLE_STRIP);try{for(int i=0;i<=64;i++){float t=i/64f,w=widthProfile(t,giant),x=from.x+dx*t,y=from.y+dy*t,rootFade=Math.min(1,t*40+.55f);
            GL11.glColor4f(color.getRed()/255f,color.getGreen()/255f,color.getBlue()/255f,alpha*rootFade);GL11.glVertex2f(x+nx*inner*w*side,y+ny*inner*w*side);
            GL11.glColor4f(color.getRed()/255f,color.getGreen()/255f,color.getBlue()/255f,0);GL11.glVertex2f(x+nx*outer*w*side,y+ny*outer*w*side);
        }}finally{GL11.glEnd();}}
    }
    private static float clamp(float f){return Float.isFinite(f)?Math.max(0,Math.min(1,f)):0;}
    private static final class State implements AutoCloseable {
        private final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        State(){GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);GL20.glUseProgram(0);int units=Math.max(1,GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));for(int i=0;i<units;i++){GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glDisable(GL11.GL_TEXTURE_1D);GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL12.GL_TEXTURE_3D);GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);GL11.glDisable(GL11.GL_TEXTURE_GEN_S);GL11.glDisable(GL11.GL_TEXTURE_GEN_T);GL11.glDisable(GL11.GL_TEXTURE_GEN_R);GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);}GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_FOG);GL11.glDepthMask(false);GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE);GL11.glShadeModel(GL11.GL_SMOOTH);}
        @Override public void close(){try{GL11.glPopAttrib();}finally{GL20.glUseProgram(program);GL13.glActiveTexture(active);}}
    }
}
