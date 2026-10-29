package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.awt.Color;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.util.vector.Vector2f;

/** Read-only coating on the actual visible assembly, using its real animated texture alpha. */
public final class ZetaOverdriveCoating {
    private static final int MAX_PARTS=160,MAX_SHIPS=16;
    public record Surface(int texture,float x,float y,float angle,float width,float height,float centerX,float centerY,
            float texX,float texY,float texWidth,float texHeight,float alpha) { }
    private ZetaOverdriveCoating(){}
    public static List<Surface> snapshot(ShipAPI ship){
        List<Surface> out=new ArrayList<>();collect(ship,out,Collections.newSetFromMap(new IdentityHashMap<>()),0);return List.copyOf(out);
    }
    private static void collect(ShipAPI ship,List<Surface> out,Set<ShipAPI> seen,int depth){
        if(ship==null||depth>3||seen.size()>=MAX_SHIPS||out.size()>=MAX_PARTS||!seen.add(ship)||ship.isDoNotRender())return;
        float alpha=clamp(ship.getCombinedAlphaMult());if(alpha<=0)return;
        Vector2f offset=ship.getRenderOffset();float dx=offset==null?0:offset.x,dy=offset==null?0:offset.y;
        if(!ship.isDoNotRenderSprite())add(out,ship.getSpriteAPI(),ship.getLocation().x+dx,ship.getLocation().y+dy,ship.getFacing()-90,alpha);
        List<WeaponAPI> weapons=ship.getAllWeapons();
        if(!ship.isDoNotRenderWeapons())for(int i=0;i<Math.min(512,weapons.size())&&out.size()<MAX_PARTS;i++){
            WeaponAPI weapon=weapons.get(i);
            if(weapon==null||weapon.getLocation()==null)continue;float x=weapon.getLocation().x+dx,y=weapon.getLocation().y+dy,angle=weapon.getCurrAngle()-90;
            add(out,weapon.getUnderSpriteAPI(),x,y,angle,alpha);add(out,weapon.getSprite(),x,y,angle,alpha);add(out,weapon.getBarrelSpriteAPI(),x,y,angle,alpha);
        }
        for(ShipAPI child:ship.getChildModulesCopy())collect(child,out,seen,depth+1);
    }
    private static void add(List<Surface> out,SpriteAPI s,float x,float y,float angle,float alpha){
        if(out.size()>=MAX_PARTS||s==null||s.getTextureId()<=0||s.getWidth()<=0||s.getHeight()<=0)return;
        Color tint=s.getColor();alpha*=clamp(s.getAlphaMult())*(tint==null?1:tint.getAlpha()/255f);
        if(alpha<=0||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(angle))return;
        out.add(new Surface(s.getTextureId(),x,y,angle,s.getWidth(),s.getHeight(),s.getCenterX(),s.getCenterY(),s.getTexX(),s.getTexY(),s.getTexWidth(),s.getTexHeight(),alpha));
    }
    /** Full central mask also covers dark armor. No original sprite/texture/animation writes. */
    public static int render(ShipAPI ship,float level,float viewportAlpha,float time){
        float effect=clamp(level)*clamp(viewportAlpha);if(effect<=0)return 0;
        List<Surface> surfaces=snapshot(ship);if(surfaces.isEmpty())return 0;
        int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),matrix=GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try{
            GL20.glUseProgram(0);
            for(int unit=1;unit<Math.min(8,GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));unit++){
                GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glDisable(GL11.GL_TEXTURE_2D);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE);GL11.glShadeModel(GL11.GL_SMOOTH);GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_MODE,GL13.GL_COMBINE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_COMBINE_RGB,GL11.GL_REPLACE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE0_RGB,GL13.GL_PRIMARY_COLOR);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND0_RGB,GL11.GL_SRC_COLOR);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_COMBINE_ALPHA,GL11.GL_MODULATE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE0_ALPHA,GL11.GL_TEXTURE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE1_ALPHA,GL13.GL_PRIMARY_COLOR);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND0_ALPHA,GL11.GL_SRC_ALPHA);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND1_ALPHA,GL11.GL_SRC_ALPHA);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_RGB_SCALE,1);GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL11.GL_ALPHA_SCALE,1);
            float breathe=.94f+.06f*(float)Math.sin(time*2.2f);
            for(Surface s:surfaces){
                quad(s,0,0,effect*s.alpha*.32f*breathe);
                quad(s,1.2f,0,effect*s.alpha*.045f);quad(s,-1.2f,0,effect*s.alpha*.045f);
                quad(s,0,1.2f,effect*s.alpha*.045f);quad(s,0,-1.2f,effect*s.alpha*.045f);
            }
        }finally{GL11.glPopAttrib();GL11.glMatrixMode(matrix);GL20.glUseProgram(program);GL13.glActiveTexture(active);}
        return surfaces.size();
    }
    private static void quad(Surface s,float dx,float dy,float alpha){
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,s.texture);GL11.glPushMatrix();
        try{
            GL11.glTranslatef(s.x+dx,s.y+dy,0);GL11.glRotatef(s.angle,0,0,1);
            float l=-s.centerX,b=-s.centerY,r=l+s.width,t=b+s.height;
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glColor4f(.65f,.12f,1,alpha*.88f);GL11.glTexCoord2f(s.texX,s.texY);GL11.glVertex2f(l,b);
            GL11.glColor4f(.65f,.12f,1,alpha*.88f);GL11.glTexCoord2f(s.texX+s.texWidth,s.texY);GL11.glVertex2f(r,b);
            GL11.glColor4f(.84f,.28f,1,alpha);GL11.glTexCoord2f(s.texX+s.texWidth,s.texY+s.texHeight);GL11.glVertex2f(r,t);
            GL11.glColor4f(.84f,.28f,1,alpha);GL11.glTexCoord2f(s.texX,s.texY+s.texHeight);GL11.glVertex2f(l,t);GL11.glEnd();
        }finally{GL11.glPopMatrix();}
    }
    private static float clamp(float value){return Float.isFinite(value)?Math.max(0,Math.min(1,value)):0;}
}
