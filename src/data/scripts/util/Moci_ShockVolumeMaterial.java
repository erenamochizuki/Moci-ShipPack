package data.scripts.util;

import com.fs.starfarer.api.Global;
import data.scripts.weapons.render.Moci_ShaderManager;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.util.vector.Vector2f;

/** GPU material for the composite blast only. The caller owns the disc, matrices and blend-state scope. */
final class Moci_ShockVolumeMaterial {
    private static final int N = 96;
    private static final String CLOUD = "graphics/fx/blastwave/moci_cloud_volume.png";
    private static int program, texture, center, radii, partId, seedId, timeId, rimId, coreId, gainId, clouds, fronts;
    private static boolean disabled;
    private static int lowId, steepId, crossId, crestId;
    private static int lowShape, steepShape, crossShape, lowTime, steepTime, crossTime;

    static boolean draw(int part, float seed, float time, Vector2f pos, float radius,
                        Color rim, Color core, float gain, int vertices, int mask, Moci_Util.TiltedFronts group) {
        if (disabled) return false;
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previous = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        try {
            if (texture == 0) init();
            GL20.glUseProgram(program);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
            GL20.glUniform1i(clouds, 0);
            GL20.glUniform2f(center, pos.x, pos.y);
            GL20.glUniform2f(radii, 0f, radius);
            GL20.glUniform2f(seedId, seed, seed * 1.618034f);
            GL20.glUniform1i(partId, part);
            if (part==4) {
                GL20.glUniform3f(fronts,mask&1,(mask>>1)&1,(mask>>2)&1);
                front(lowId,lowShape,lowTime,group.low);
                front(steepId,steepShape,steepTime,group.steep);
                front(crossId,crossShape,crossTime,group.cross);
                rgba(crestId,group.crest);
            } else {
                color(rimId, rim); color(coreId, core);
            }
            GL20.glUniform1f(timeId, time);
            GL20.glUniform1f(gainId, gain);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, vertices);
            return true;
        } catch (IOException | RuntimeException e) {
            disabled = true;
            if (texture != 0) GL11.glDeleteTextures(texture);
            texture = 0;
            Global.getLogger(Moci_ShockVolumeMaterial.class).warn("[Moci_BlastWave] Volume unavailable; using legacy sphere",e);
            return false;
        } finally {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous);
        }
    }

    private static void init() throws IOException {
        if (GL11.glGetInteger(GL12.GL_MAX_3D_TEXTURE_SIZE) < N) throw new IOException("3D texture limit");
        program = Moci_ShaderManager.getInstance().createShader(Moci_Util.BLASTWAVE_VERT,
                "data/shaders/Moci_shockvolume.frag", "Moci_shockvolume");
        if (program == 0) throw new IOException("Volume shader did not link");
        center=loc("center"); radii=loc("radii"); seedId=loc("seed"); partId=loc("part"); timeId=loc("time");
        rimId=loc("rimColor"); coreId=loc("coreColor"); gainId=loc("gain"); clouds=loc("clouds"); fronts=loc("fronts");
        lowId=loc("lowTint"); steepId=loc("steepTint"); crossId=loc("crossTint"); crestId=loc("crestTint");
        lowShape=loc("lowShape"); steepShape=loc("steepShape"); crossShape=loc("crossShape");
        lowTime=loc("lowTime"); steepTime=loc("steepTime"); crossTime=loc("crossTime");
        BufferedImage image;
        try (InputStream in = Global.getSettings().openStream(CLOUD)) { image = ImageIO.read(in); }
        if (image == null || image.getWidth()!=N*12 || image.getHeight()!=N*8) throw new IOException("Invalid cloud volume");
        int[] rgb = image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
        ByteBuffer bytes = BufferUtils.createByteBuffer(rgb.length * 3);
        for (int x : rgb) bytes.put((byte)(x>>16)).put((byte)(x>>8)).put((byte)x);
        bytes.flip();
        texture=GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D,texture);
        int target=GL12.GL_TEXTURE_3D;
        GL11.glTexParameteri(target,GL11.GL_TEXTURE_WRAP_S,GL11.GL_REPEAT);
        GL11.glTexParameteri(target,GL11.GL_TEXTURE_WRAP_T,GL11.GL_REPEAT);
        GL11.glTexParameteri(target,GL12.GL_TEXTURE_WRAP_R,GL11.GL_REPEAT);
        GL11.glTexParameteri(target,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
        boolean mip=GLContext.getCapabilities().OpenGL30;
        GL11.glTexParameteri(target,GL11.GL_TEXTURE_MIN_FILTER,mip ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
        boolean pbo=GLContext.getCapabilities().OpenGL21;
        int unpack=pbo ? GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) : 0;
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try {
            if (unpack!=0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,0);
            GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT,0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS,0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS,0);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES,0);
            GL12.glTexImage3D(target,0,GL11.GL_RGB8,N,N,N,0,GL11.GL_RGB,GL11.GL_UNSIGNED_BYTE,bytes);
            if (GL11.glGetTexLevelParameteri(target,0,GL11.GL_TEXTURE_WIDTH)!=N) throw new IOException("Volume upload failed");
            if (mip) GL30.glGenerateMipmap(target);
        } finally {
            GL11.glPopClientAttrib();
            if (unpack!=0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,unpack);
        }
    }

    private static int loc(String name) { return GL20.glGetUniformLocation(program,name); }
    private static void front(int tint, int shape, int timing, Moci_Util.TiltedFront f) {
        rgba(tint,f.tint);
        GL20.glUniform3f(shape,f.angle,f.opening,f.scale);
        GL20.glUniform4f(timing,f.start,f.end,f.fadeIn,f.fadeOut);
    }
    private static void rgba(int id, Color c) {
        if (c == null) { GL20.glUniform4f(id,0,0,0,0); return; }
        GL20.glUniform4f(id,c.getRed()/255f,c.getGreen()/255f,c.getBlue()/255f,c.getAlpha()/255f);
    }
    private static void color(int id, Color c) {
        if (c == null) { GL20.glUniform3f(id,0,0,0); return; }
        float a=c.getAlpha()/(255f*255f);
        GL20.glUniform3f(id,c.getRed()*a,c.getGreen()*a,c.getBlue()*a);
    }
}
