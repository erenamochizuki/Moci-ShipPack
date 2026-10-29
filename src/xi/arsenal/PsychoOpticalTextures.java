package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.awt.Color;
import java.util.List;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * Per-battle prepared optical masks, shared in design with Xi/Penelope's Magnum flash.
 * Only load() resolves assets. Drawing reads immutable coordinates and prepared texture IDs;
 * it neither loads resources nor changes any shared Sprite wrapper or native attack.
 */
public final class PsychoOpticalTextures implements AutoCloseable {
    public static final String CORE="graphics/fx/Moci_magnum_fx_core.png",
            FRINGE="graphics/fx/Moci_magnum_fx_fringe.png",BLOOM="graphics/fx/hit_glow.png";
    private SpriteAPI core,fringe,bloom;
    private boolean closed;

    private PsychoOpticalTextures(SpriteAPI core,SpriteAPI fringe,SpriteAPI bloom){
        this.core=core;this.fringe=fringe;this.bloom=bloom;
    }
    /** Call on the native combat thread after resource loading, never from render(). */
    public static PsychoOpticalTextures load(){
        SpriteAPI core=Global.getSettings().getSprite(CORE),fringe=Global.getSettings().getSprite(FRINGE),
                bloom=Global.getSettings().getSprite(BLOOM);
        for(SpriteAPI sprite:new SpriteAPI[]{core,fringe,bloom})
            if(sprite==null||sprite.getTextureId()<=0)throw new IllegalStateException("Prepared psycho-optical texture unavailable");
        return new PsychoOpticalTextures(core,fringe,bloom);
    }
    /** Explicitly texture-free instance for API tests; does not pretend assets were loaded. */
    public static PsychoOpticalTextures empty(){return new PsychoOpticalTextures(null,null,null);}
    public boolean isPrepared(){return !closed&&core!=null&&fringe!=null&&bloom!=null;}

    /** Returns actual drawn texture layers. Prefer this before the geometric hot spine. */
    public long renderSegments(List<PsychoOpticalRenderer.Segment> segments,ViewportAPI viewport){
        if(!isPrepared()||segments==null||viewport==null||clamp(viewport.getAlphaMult())<=0)return 0;
        long drawn=0;State guard=null;
        try{
            for(int i=0;i<Math.min(PsychoOpticalRenderer.MAX_SEGMENTS,segments.size());i++){
                var s=segments.get(i);if(!PsychoOpticalRenderer.validSegment(s)||!PsychoOpticalRenderer.visible(s,viewport))continue;
                float alpha=clamp(s.brightness())*clamp(viewport.getAlphaMult());if(alpha<=.001f)continue;
                if(guard==null)guard=new State();
                float width=PsychoOpticalRenderer.visualWidth(s);
                Color primary=s.primary()==null?Color.WHITE:s.primary(),inner=s.inner()==null?Color.WHITE:s.inner();
                // A circular Magnum core stretched into a bolt made a second local bulb.
                // Only soft glow is used along flight, tessellated into the very same
                // connected Gaussian envelope as the hot spine. Magnum core stays at hits.
                segmentMask(bloom,s,width*.94f,primary,alpha*.13f);drawn++;
                segmentMask(bloom,s,width*.42f,inner,alpha*.16f);drawn++;
            }
        }finally{if(guard!=null)guard.close();}
        return drawn;
    }

    /** Returns actual texture layers; prepared masks add heat core,rough plasma and soft bloom. */
    public long renderFlares(List<PsychoOpticalRenderer.Flare> flares,ViewportAPI viewport){
        if(!isPrepared()||flares==null||viewport==null||clamp(viewport.getAlphaMult())<=0)return 0;
        long drawn=0;State guard=null;
        try{
            for(int i=0;i<Math.min(PsychoOpticalRenderer.MAX_FLARES,flares.size());i++){
                var f=flares.get(i);if(!PsychoOpticalRenderer.validFlare(f))continue;
                float radius=Math.min(PsychoOpticalRenderer.MAX_FLARE_RADIUS,f.radius());
                if(!inside(viewport,f.x()-radius,f.y()-radius,f.x()+radius,f.y()+radius))continue;
                float alpha=clamp(f.brightness())*clamp(viewport.getAlphaMult());if(alpha<=.001f)continue;
                if(guard==null)guard=new State();
                Color primary=f.primary()==null?Color.WHITE:f.primary(),inner=f.inner()==null?Color.WHITE:f.inner();
                // Original Magnum artwork supplies fine breakup; RGB is re-tinted from its alpha
                // mask so blue/purple source pixels cannot override a Nightingale gold palette.
                mask(bloom,f.x(),f.y(),radius*1.8f,radius*1.8f,0,primary,alpha*.36f);drawn++;
                mask(fringe,f.x(),f.y(),radius*1.32f,radius*1.32f,f.angle()+17f,inner,alpha*.34f);drawn++;
                mask(core,f.x(),f.y(),radius*.92f,radius*.92f,f.angle()-11f,primary,alpha*.63f);drawn++;
                mask(bloom,f.x(),f.y(),radius*.46f,radius*.46f,0,Color.WHITE,alpha*.92f);drawn++;
                if(f.impact()){
                    mask(fringe,f.x(),f.y(),radius*.86f,radius*.64f,f.angle()+73f,primary,alpha*.18f);drawn++;
                }
            }
        }finally{if(guard!=null)guard.close();}
        return drawn;
    }

    private static void mask(SpriteAPI sprite,float x,float y,float width,float height,float angle,Color color,float alpha){
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,sprite.getTextureId());
        GL11.glColor4f(color.getRed()/255f,color.getGreen()/255f,color.getBlue()/255f,clamp(alpha)*color.getAlpha()/255f);
        float u0=sprite.getTexX(),v0=sprite.getTexY(),u1=u0+sprite.getTexWidth(),v1=v0+sprite.getTexHeight();
        double a=Math.toRadians(angle);float c=(float)Math.cos(a),s=(float)Math.sin(a);
        float rx=c*width*.5f,ry=s*width*.5f,ux=-s*height*.5f,uy=c*height*.5f;
        GL11.glBegin(GL11.GL_QUADS);
        try{
            GL11.glTexCoord2f(u0,v0);GL11.glVertex2f(x-rx-ux,y-ry-uy);
            GL11.glTexCoord2f(u1,v0);GL11.glVertex2f(x+rx-ux,y+ry-uy);
            GL11.glTexCoord2f(u1,v1);GL11.glVertex2f(x+rx+ux,y+ry+uy);
            GL11.glTexCoord2f(u0,v1);GL11.glVertex2f(x-rx+ux,y-ry+uy);
        }finally{GL11.glEnd();}
    }
    private static void segmentMask(SpriteAPI sprite,PsychoOpticalRenderer.Segment segment,float width,Color color,float alpha){
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,sprite.getTextureId());
        float u0=sprite.getTexX(),v0=sprite.getTexY(),uw=sprite.getTexWidth(),vh=sprite.getTexHeight();
        float dx=segment.toX()-segment.fromX(),dy=segment.toY()-segment.fromY();
        float inverse=1f/(float)Math.hypot(dx,dy),nx=-dy*inverse,ny=dx*inverse;
        for(int band=0;band<12;band++){
            float side0=-1f+band/6f,side1=-1f+(band+1)/6f;
            GL11.glBegin(GL11.GL_QUAD_STRIP);
            try{
                for(int i=0;i<=24;i++){
                    float t=i/24f,half=width*.5f*PsychoOpticalRenderer.longitudinalSpread(t,segment.sustained());
                    float x=segment.fromX()+dx*t,y=segment.fromY()+dy*t;
                    float along=alpha*PsychoOpticalRenderer.longitudinalProfile(t,segment.sustained());
                    segmentVertex(color,along,side0,x,y,nx,ny,half,u0,uw,v0+vh*t);
                    segmentVertex(color,along,side1,x,y,nx,ny,half,u0,uw,v0+vh*t);
                }
            }finally{GL11.glEnd();}
        }
    }
    private static void segmentVertex(Color color,float alpha,float side,float x,float y,float nx,float ny,float half,float u0,float uw,float v){
        GL11.glColor4f(color.getRed()/255f,color.getGreen()/255f,color.getBlue()/255f,
                clamp(alpha)*PsychoOpticalRenderer.transverseProfile(side)*color.getAlpha()/255f);
        GL11.glTexCoord2f(u0+uw*(side+1)*.5f,v);GL11.glVertex2f(x+nx*half*side,y+ny*half*side);
    }
    private static boolean inside(ViewportAPI viewport,float left,float bottom,float right,float top){
        float x=viewport.getLLX(),y=viewport.getLLY(),width=viewport.getVisibleWidth(),height=viewport.getVisibleHeight();
        return Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(width)&&Float.isFinite(height)&&width>0&&height>0
                &&right>=x&&left<=x+width&&top>=y&&bottom<=y+height;
    }
    private static float clamp(float value){return Float.isFinite(value)?Math.max(0,Math.min(1,value)):0;}
    @Override public void close(){if(closed)return;closed=true;core=null;fringe=null;bloom=null;}

    /** Keeps atlas/shared texture parameters untouched; only temporary unit enable/env state changes. */
    private static final class State implements AutoCloseable {
        private final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),activeTexture=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        State(){
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);GL20.glUseProgram(0);
            int units=Math.max(1,GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));
            for(int i=0;i<units;i++){
                GL13.glActiveTexture(GL13.GL_TEXTURE0+i);
                GL11.glDisable(GL11.GL_TEXTURE_1D);GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL12.GL_TEXTURE_3D);GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
                GL11.glDisable(GL11.GL_TEXTURE_GEN_S);GL11.glDisable(GL11.GL_TEXTURE_GEN_T);GL11.glDisable(GL11.GL_TEXTURE_GEN_R);GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_MODE,GL13.GL_COMBINE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_COMBINE_RGB,GL11.GL_REPLACE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE0_RGB,GL13.GL_PREVIOUS);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND0_RGB,GL11.GL_SRC_COLOR);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_COMBINE_ALPHA,GL11.GL_MODULATE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE0_ALPHA,GL11.GL_TEXTURE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_SOURCE1_ALPHA,GL13.GL_PREVIOUS);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND0_ALPHA,GL11.GL_SRC_ALPHA);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL13.GL_OPERAND1_ALPHA,GL11.GL_SRC_ALPHA);
            GL11.glTexEnvf(GL11.GL_TEXTURE_ENV,GL13.GL_RGB_SCALE,1);GL11.glTexEnvf(GL11.GL_TEXTURE_ENV,GL11.GL_ALPHA_SCALE,1);
            GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_FOG);
            GL11.glDepthMask(false);GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK,GL11.GL_FILL);
            GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE);GL11.glShadeModel(GL11.GL_SMOOTH);
        }
        @Override public void close(){try{GL11.glPopAttrib();}finally{GL20.glUseProgram(program);GL13.glActiveTexture(activeTexture);}}
    }
}
