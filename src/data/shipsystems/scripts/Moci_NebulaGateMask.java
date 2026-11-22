package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.vector.Vector2f;
import java.io.IOException;
import java.nio.FloatBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;

/** World-projected second texture: preserves each native weapon sprite, animation frame and blend mode. */
final class Moci_NebulaGateMask {
    private static final String PATH="graphics/fx/Moci_NebulaGateReveal.png";
    private static int texture;
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

    // Called inside the caller's attribute stack, before translating the visual copy.
    void begin(int id) {
        glActiveTexture(GL_TEXTURE1);
        glMatrixMode(GL_TEXTURE); glPushMatrix(); glLoadIdentity(); glMatrixMode(GL_MODELVIEW);
        glEnable(GL_TEXTURE_2D); glBindTexture(GL_TEXTURE_2D,id);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexEnvi(GL_TEXTURE_ENV,GL_TEXTURE_ENV_MODE,GL_MODULATE);
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
