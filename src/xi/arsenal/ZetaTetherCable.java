package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.util.vector.Vector2f;

/** Own, neutral-metal braided wire. Arc-length UVs keep its pitch continuous from wrist to dart/anchor.
 * Pure presentation only: the caller still owns actual endpoints, hit attachment and return motion. */
public final class ZetaTetherCable {
    public static final String TEXTURE="graphics/fx/fw_zeta_tether_cable.png";
    public static final float WIRE_WIDTH=2.6f,TILE_WORLD_LENGTH=12f,MAX_RENDER_LENGTH=12000f;
    public static final int MAX_TILES=1200;
    private final SpriteAPI metal;
    /** Actor constructs this on the battle thread after content staging. Native Settings owns
     * the shared texture cache; requesting a Sprite wrapper alone does not upload this custom PNG. */
    public ZetaTetherCable(){metal=loadMaterial(Global.getSettings());}
    static SpriteAPI loadMaterial(SettingsAPI settings){
        if(settings==null)throw new IllegalStateException("Cable material initialization has no native Settings: "+TEXTURE);
        try{
            settings.loadTexture(TEXTURE);SpriteAPI sprite=settings.getSprite(TEXTURE);
            if(sprite==null||sprite.getTextureId()<=0)throw new IllegalStateException("Native texture was not uploaded: "+TEXTURE);
            return sprite;
        }catch(java.io.IOException|RuntimeException failure){throw new IllegalStateException("Failed to initialize metallic cable material "+TEXTURE+" before native rendering",failure);}
    }
    private long draws,tilesDrawn,skipped;private String reason="Awaiting actual cable endpoints";
    public record Edge(float x,float y,float leftX,float leftY,float rightX,float rightY,float distance){}
    public record Tile(Edge from,Edge to,float u0,float u1){}
    /** Independent snapshots, never aliases of a moving source, projectile or target position. */
    public static List<Vector2f> liveEndpoints(Vector2f from,Vector2f to){
        if(from==null||to==null||!finite(from.x,from.y,to.x,to.y))throw new IllegalArgumentException("Finite live cable endpoints required");
        return List.of(new Vector2f(from),new Vector2f(to));
    }
    public static List<Vector2f> points(Vector2f from,Vector2f to,float seconds,boolean taut){
        if(from==null||to==null||!finite(from.x,from.y,to.x,to.y,seconds))throw new IllegalArgumentException("Finite cable endpoints required");
        float dx=to.x-from.x,dy=to.y-from.y,length=(float)Math.hypot(dx,dy);if(!Float.isFinite(length))throw new IllegalArgumentException("Cable distance overflow");if(length<.001f)return List.of(new Vector2f(from),new Vector2f(to));
        int segments=Math.max(2,Math.min(96,(int)Math.ceil(length/16)));float sag=(taut?.008f:.055f)*Math.min(1000,length)*(1+.12f*(float)Math.sin(seconds*4)),cx=(from.x+to.x)*.5f-dy/length*sag,cy=(from.y+to.y)*.5f+dx/length*sag;
        List<Vector2f> result=new ArrayList<>(segments+1);for(int i=0;i<=segments;i++){float t=(float)i/segments,u=1-t;result.add(new Vector2f(u*u*from.x+2*u*t*cx+t*t*to.x,u*u*from.y+2*u*t*cy+t*t*to.y));}return List.copyOf(result);
    }
    /** Immutable ribbon geometry. Each UV cycle covers12 world units, irrespective of path sampling.
     * Splits exactly at tile seams; it neither stretches a tile to every path segment nor changes GL texture wrapping. */
    public static List<Tile> ribbonTiles(List<Vector2f> path,float width){
        if(path==null||path.size()<2||path.size()>97||!Float.isFinite(width)||width<=0||width>8)throw new IllegalArgumentException("Bounded finite cable path/width required");
        int size=path.size();float[] distance=new float[size];
        for(int i=0;i<size;i++){Vector2f p=path.get(i);if(p==null||!finite(p.x,p.y))throw new IllegalArgumentException("Finite ribbon point required");if(i>0){distance[i]=distance[i-1]+Vector2f.sub(p,path.get(i-1),null).length();if(!Float.isFinite(distance[i])||distance[i]>MAX_RENDER_LENGTH)throw new IllegalArgumentException("Cable rendering distance exceeds budget");}}
        if(distance[size-1]<.001f)return List.of();
        List<Edge> edges=new ArrayList<>(size);for(int i=0;i<size;i++){
            Vector2f p=path.get(i),a=path.get(Math.max(0,i-1)),b=path.get(Math.min(size-1,i+1));float dx=b.x-a.x,dy=b.y-a.y,len=(float)Math.hypot(dx,dy);if(len<.0001f){dx=1;dy=0;len=1;}
            float nx=-dy/len*width*.5f,ny=dx/len*width*.5f;edges.add(new Edge(p.x,p.y,p.x+nx,p.y+ny,p.x-nx,p.y-ny,distance[i]));
        }
        List<Tile> result=new ArrayList<>();for(int i=1;i<size;i++){
            float start=distance[i-1],end=distance[i],length=end-start;if(length<.0001f)continue;float at=start;
            while(end-at>.0001f){int cycle=(int)Math.floor((at+.0001f)/TILE_WORLD_LENGTH);float next=Math.min(end,(cycle+1)*TILE_WORLD_LENGTH);if(next-at<.0001f)next=end;
                float u0=clamp((at-cycle*TILE_WORLD_LENGTH)/TILE_WORLD_LENGTH),u1=clamp((next-cycle*TILE_WORLD_LENGTH)/TILE_WORLD_LENGTH);
                result.add(new Tile(interpolate(edges.get(i-1),edges.get(i),(at-start)/length),interpolate(edges.get(i-1),edges.get(i),(next-start)/length),u0,u1));
                if(result.size()>MAX_TILES)throw new IllegalArgumentException("Cable tile budget exceeded");at=next;
            }
        }return List.copyOf(result);
    }
    private static Edge interpolate(Edge a,Edge b,float t){return new Edge(mix(a.x,b.x,t),mix(a.y,b.y,t),mix(a.leftX,b.leftX,t),mix(a.leftY,b.leftY,t),mix(a.rightX,b.rightX,t),mix(a.rightY,b.rightY,t),mix(a.distance,b.distance,t));}
    private static float mix(float a,float b,float t){return a+(b-a)*t;}
    public void render(Vector2f from,Vector2f to,float time,boolean taut,boolean bio,ViewportAPI viewport){
        if(viewport==null||from==null||to==null||!finite(from.x,from.y,to.x,to.y,time)||clamp(viewport.getAlphaMult())<=0)return;
        if(Vector2f.sub(to,from,null).length()>MAX_RENDER_LENGTH*.99f){skipped++;reason="Cable exceeds finite render budget";return;}
        renderPath(points(from,to,time,taut),time,taut,bio,viewport);
    }
    /** Geometry-only renderer. Production tether supplies a curve between its two live physical
     * endpoints; recorded flight/navigation history is deliberately not a rope shape. */
    public void renderPath(List<Vector2f> path,float time,boolean taut,boolean bio,ViewportAPI viewport){
        if(viewport==null||path==null||path.size()<2||path.size()>97||!Float.isFinite(time)||clamp(viewport.getAlphaMult())<=0)return;
        float arc=0;for(int i=0;i<path.size();i++){Vector2f p=path.get(i);if(p==null||!finite(p.x,p.y)){skipped++;reason="Invalid actual cable path";return;}if(i>0)arc+=Vector2f.sub(p,path.get(i-1),null).length();}
        if(!Float.isFinite(arc)||arc>MAX_RENDER_LENGTH){skipped++;reason="Actual path exceeds finite render budget";return;}
        if(path.stream().noneMatch(p->viewport.isNearViewport(p,16)))return;List<Tile> tiles=ribbonTiles(path,WIRE_WIDTH);if(tiles.isEmpty())return;
        float alpha=clamp(viewport.getAlphaMult());
        try(State guard=new State()){
            metal.bindTexture();GL11.glEnable(GL11.GL_TEXTURE_2D);GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_MODE,GL11.GL_MODULATE);
            // Material already encodes metallic shading. White tint avoids multiplying its small bright core into grey/purple.
            GL11.glColor4f(1,1,1,alpha);float tx=metal.getTexX(),ty=metal.getTexY(),tw=metal.getTexWidth(),th=metal.getTexHeight();
            GL11.glBegin(GL11.GL_QUADS);try{for(Tile tile:tiles){Edge a=tile.from,b=tile.to;float ua=tx+tw*tile.u0,ub=tx+tw*tile.u1;
                vertex(ua,ty,a.leftX,a.leftY);vertex(ua,ty+th,a.rightX,a.rightY);vertex(ub,ty+th,b.rightX,b.rightY);vertex(ub,ty,b.leftX,b.leftY);
            }}finally{GL11.glEnd();}
            // Non-additive narrow specular strand: one screen pixel remains legible at normal combat zoom.
            // It has no halo and never replaces the metallic braid with a beam or a full-length purple arc.
            GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glLineWidth(1);GL11.glColor4f(.91f,.94f,.97f,alpha*.42f);
            GL11.glBegin(GL11.GL_LINE_STRIP);try{for(Vector2f p:path)GL11.glVertex2f(p.x,p.y);}finally{GL11.glEnd();}
        }
        draws++;tilesDrawn+=tiles.size();reason=bio?"Neutral metallic wire; native Bio EMP remains a separate effect":"Neutral metallic wire";
    }
    private static void vertex(float u,float v,float x,float y){GL11.glTexCoord2f(u,v);GL11.glVertex2f(x,y);}
    public Map<String,Object> status(){return Map.of("actualDraws",draws,"actualTiles",tilesDrawn,"budgetSkips",skipped,"widthWorld",WIRE_WIDTH,"tileWorldLength",TILE_WORLD_LENGTH,"reason",reason);}
    /** Fixed-function state is local to this draw, including texture matrix and inherited texture combiners. */
    private static final class State implements AutoCloseable {
        private final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),matrixMode=GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        State(){GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);GL20.glUseProgram(0);
            int units=Math.max(1,GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));for(int i=0;i<units;i++){
                GL13.glActiveTexture(GL13.GL_TEXTURE0+i);GL11.glDisable(GL11.GL_TEXTURE_1D);GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL12.GL_TEXTURE_3D);GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
                GL11.glDisable(GL11.GL_TEXTURE_GEN_S);GL11.glDisable(GL11.GL_TEXTURE_GEN_T);GL11.glDisable(GL11.GL_TEXTURE_GEN_R);GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glMatrixMode(GL11.GL_TEXTURE);GL11.glPushMatrix();GL11.glLoadIdentity();GL11.glMatrixMode(matrixMode);
            GL11.glDisable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_FOG);GL11.glDepthMask(false);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK,GL11.GL_FILL);GL11.glShadeModel(GL11.GL_SMOOTH);GL11.glEnable(GL11.GL_BLEND);GL14.glBlendEquation(GL14.GL_FUNC_ADD);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        @Override public void close(){try{GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glMatrixMode(GL11.GL_TEXTURE);GL11.glPopMatrix();GL11.glMatrixMode(matrixMode);GL11.glPopAttrib();}finally{GL20.glUseProgram(program);GL13.glActiveTexture(active);}}
    }
    private static float clamp(float n){return Float.isFinite(n)?Math.max(0,Math.min(1,n)):0;}
    private static boolean finite(float... values){for(float value:values)if(!Float.isFinite(value))return false;return true;}
}
