package data.scripts.util;

import org.lwjgl.opengl.*;

/** Compatibility renderer: isolate fixed-function texture state and the caller's actual draw/read targets. */
public final class Moci_OffscreenPass implements AutoCloseable {
    private final ContextCapabilities caps = GLContext.getCapabilities();
    private final boolean hasFbo = caps.OpenGL30 || caps.GL_ARB_framebuffer_object || caps.GL_EXT_framebuffer_object;
    private final int draw = hasFbo ? GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) : 0;
    private final boolean split = caps.OpenGL30 || caps.GL_ARB_framebuffer_object || caps.GL_EXT_framebuffer_blit;
    private final int read = split ? GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) : draw;
    private final int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int program = caps.OpenGL20 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;

    public Moci_OffscreenPass() {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        if (caps.OpenGL20) GL20.glUseProgram(0);
        for (int i = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS) - 1; i >= 0; i--) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glDisable(GL11.GL_TEXTURE_1D);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL12.GL_TEXTURE_3D);
            GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
            if (caps.OpenGL31 || caps.GL_ARB_texture_rectangle) GL11.glDisable(GL31.GL_TEXTURE_RECTANGLE);
        }
        GL11.glDisable(GL11.GL_TEXTURE_GEN_S);
        GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
        GL11.glDisable(GL11.GL_TEXTURE_GEN_R);
        GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
    }

    private void bind(int target, int value) {
        if (caps.OpenGL30) GL30.glBindFramebuffer(target, value);
        else if (caps.GL_ARB_framebuffer_object) ARBFramebufferObject.glBindFramebuffer(target, value);
        else EXTFramebufferObject.glBindFramebufferEXT(target, value);
    }

    private void restoreTarget() {
        if (!hasFbo) return;
        if (draw == read) bind(GL30.GL_FRAMEBUFFER, draw);
        else {
            bind(GL30.GL_DRAW_FRAMEBUFFER, draw);
            bind(GL30.GL_READ_FRAMEBUFFER, read);
        }
    }

    public void drawTrail(int fbo, int width, int height, Runnable drawTrail) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        bind(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, width, height);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, width, 0, height, -2000, 2000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            drawTrail.run();
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            restoreTarget();
            GL11.glPopAttrib();
        }
    }

    @Override public void close() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPopMatrix();
        restoreTarget();
        GL11.glPopAttrib();
        GL13.glActiveTexture(unit);
        if (caps.OpenGL20) GL20.glUseProgram(program);
    }
}
