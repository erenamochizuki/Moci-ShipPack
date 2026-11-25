package data.shipsystems.scripts.travel;

import com.fs.starfarer.api.Global;
import data.scripts.weapons.engine.Moci_MagnetohydrodynamicEngine;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.vector.Vector2f;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;

/** World-projected second texture: preserves each native weapon sprite, animation frame and blend mode. */
final class Moci_NebulaGateMask {
    private static final String PATH="graphics/fx/Moci_NebulaGateReveal.png";
    private static int texture;
    private static int ribbonId;
    private static boolean missing;
    private final FloatBuffer s=BufferUtils.createFloatBuffer(4), t=BufferUtils.createFloatBuffer(4);

    Moci_NebulaGateMask(Vector2f gate,float dx,float dy,float radius) {
        float inv=1f/radius;
        plane(s,dx*inv,dy*inv,gate);
        plane(t,-dy*inv*.5f,dx*inv*.5f,gate);
    }
    private static void plane(FloatBuffer b,float x,float y,Vector2f g) {
        b.put(x).put(y).put(0f).put((float)(.5-x*(double)g.x-y*(double)g.y)).flip();
    }

    static int texture() {
        if (texture==0 && !missing) {
            try {
                Global.getSettings().loadTexture(PATH);
                texture=Global.getSettings().getSprite(PATH).getTextureId();
            } catch (IOException | RuntimeException ex) {
                missing=true;
                Global.getLogger(Moci_NebulaGateMask.class).warn("No gate reveal texture: using plane clipping",ex);
            }
        }
        return texture;
    }

    // 一张投影图同时编码亮带 RGB 和显形透明度。只在 GL 绘制阶段首次使用时生成，无需单独贴图或着色器。
    static int ribbonTexture() {
        if (ribbonId!=0) return ribbonId;
        final int n=256;
        ByteBuffer pixels=ribbonPixels(n,Moci_FluxRibbonDrive.RIBBON_WIDTH_MULT);
        glPushAttrib(GL_TEXTURE_BIT);
        try {
            ribbonId=glGenTextures(); glBindTexture(GL_TEXTURE_2D,ribbonId);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,n,n,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        } finally { glPopAttrib(); }
        return ribbonId;
    }

    static ByteBuffer ribbonPixels(int n,float widthMult) {
        ByteBuffer pixels=BufferUtils.createByteBuffer(n*n*4);
        final float half=.064f; // 磁弧版原有的显形柔边，单位为门半径；不随推进引擎参数或亮带宽度改变。
        float lightHalf=half*(Float.isFinite(widthMult) ? Math.max(0f,Math.min(3f,widthMult)) : 1f);
        for (int y=0;y<n;y++) {
            float lateral=2f*(y+.5f)/n-1f;
            float crest=2f*(Moci_MagnetohydrodynamicEngine.ribbonOffset(lateral)-Moci_MagnetohydrodynamicEngine.ribbonOffset(1f))-half;
            float frontHalf=Math.min(lightHalf,-crest); // 前侧最远到交接面；加宽时真船接替前已恢复原色。
            for (int x=0;x<n;x++) {
                float delta=(x+.5f)/n-.5f-crest;
                float d=delta/half;
                float light=lightHalf>0f ? Math.max(0f,1f-Math.abs(delta)/(delta>0f ? frontHalf : lightHalf)) : 0f;
                pixels.put((byte)Math.round(145*light)).put((byte)Math.round(215*light)).put((byte)Math.round(255*light));
                pixels.put((byte)Math.round(255*Moci_NebulaGateMotion.ease(1f+d)));
            }
        }
        pixels.flip();
        return pixels;
    }

    // Called inside the caller's attribute stack, before translating the visual copy.
    void begin(int id) {
        begin(id,false);
    }
    void begin(int id,boolean ribbon) {
        glActiveTexture(GL_TEXTURE1);
        glMatrixMode(GL_TEXTURE); glPushMatrix(); glLoadIdentity(); glMatrixMode(GL_MODELVIEW);
        glEnable(GL_TEXTURE_2D); glBindTexture(GL_TEXTURE_2D,id);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexEnvi(GL_TEXTURE_ENV,GL_TEXTURE_ENV_MODE,GL_MODULATE);
        if (ribbon) {
            // 加亮原舰体颜色，透明度仍乘原精灵的 alpha：光带不在舰体外框或贴图透明区发光。
            glTexEnvi(GL_TEXTURE_ENV,GL_TEXTURE_ENV_MODE,GL_COMBINE);
            glTexEnvi(GL_TEXTURE_ENV,GL_COMBINE_RGB,GL_ADD);
            glTexEnvi(GL_TEXTURE_ENV,GL_SOURCE0_RGB,GL_PREVIOUS); glTexEnvi(GL_TEXTURE_ENV,GL_SOURCE1_RGB,GL_TEXTURE);
            glTexEnvi(GL_TEXTURE_ENV,GL_OPERAND0_RGB,GL_SRC_COLOR); glTexEnvi(GL_TEXTURE_ENV,GL_OPERAND1_RGB,GL_SRC_COLOR);
            glTexEnvi(GL_TEXTURE_ENV,GL_COMBINE_ALPHA,GL_MODULATE);
            glTexEnvi(GL_TEXTURE_ENV,GL_SOURCE0_ALPHA,GL_PREVIOUS); glTexEnvi(GL_TEXTURE_ENV,GL_SOURCE1_ALPHA,GL_TEXTURE);
            glTexEnvi(GL_TEXTURE_ENV,GL_OPERAND0_ALPHA,GL_SRC_ALPHA); glTexEnvi(GL_TEXTURE_ENV,GL_OPERAND1_ALPHA,GL_SRC_ALPHA);
            glTexEnvi(GL_TEXTURE_ENV,GL_RGB_SCALE,1); glTexEnvi(GL_TEXTURE_ENV,GL_ALPHA_SCALE,1);
        }
        glTexGeni(GL_S,GL_TEXTURE_GEN_MODE,GL_EYE_LINEAR); glTexGen(GL_S,GL_EYE_PLANE,s);
        glTexGeni(GL_T,GL_TEXTURE_GEN_MODE,GL_EYE_LINEAR); glTexGen(GL_T,GL_EYE_PLANE,t);
        glEnable(GL_TEXTURE_GEN_S); glEnable(GL_TEXTURE_GEN_T);
        glActiveTexture(GL_TEXTURE0);
    }
    void end() {
        glActiveTexture(GL_TEXTURE1); glMatrixMode(GL_TEXTURE); glPopMatrix();
        glMatrixMode(GL_MODELVIEW); glActiveTexture(GL_TEXTURE0);
    }
}
