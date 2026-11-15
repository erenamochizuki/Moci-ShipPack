package data.campaign.intel.ui;

import java.awt.Color;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.ui.PositionAPI;

/**
 * 通讯头像：清晰底图上间歇叠加轻微重影、局部错位和噪点，无扫描线。
 * 作为头像容器插件使用；底图仍由原 UI 绘制，取不到贴图时也能正常显示、点击。
 * 顶点限制在头像内，不改父列表的 scissor，也不消费输入。
 */
public final class Moci_UIPortraitSignal extends BaseCustomUIPanelPlugin {
    private static final Logger LOG = Global.getLogger(Moci_UIPortraitSignal.class);
    private static final long T0 = System.nanoTime();
    private static final float BURST_SECONDS = 0.38f;
    private static final int ATTRIB_BITS = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
            | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_LIGHTING_BIT;

    private final String path;
    private final int seed;
    private final double period;
    private final double offset;
    private final Moci_UIScanFrame frame;
    private PositionAPI position;
    private SpriteAPI sprite;
    private boolean unavailable;
    private float texX, texY, texW, texH;

    public Moci_UIPortraitSignal(String path, Color base) {
        this.path = path;
        seed = path == null ? 0 : path.hashCode();
        period = 4.8 + noise(seed) * 2.4;
        offset = noise(seed + 1) * period;
        frame = new Moci_UIScanFrame(base).setScanning(false);
    }

    @Override public void positionChanged(PositionAPI position) {
        this.position = position;
        frame.positionChanged(position);
    }

    @Override public void render(float alphaMult) {
        if (position == null) return;
        float w = position.getWidth(), h = position.getHeight();
        float a = clamp01(alphaMult);
        if (!Float.isFinite(w) || !Float.isFinite(h) || w <= 2f || h <= 2f || a <= 0f) return;
        // 进程时钟使暂停时仍有通讯效果，切换页面也不会从头播放；各头像错开干扰。
        double time = (System.nanoTime() - T0) / 1.0e9 + offset;
        double phase = time % period;
        if (phase < BURST_SECONDS && sprite() != null) {
            float strength = (float) Math.sin(phase / BURST_SECONDS * Math.PI);
            drawSignal(position.getX(), position.getY(), w, h, a, strength, (int) (time * 24));
        }
        frame.render(alphaMult);
    }

    private void drawSignal(float x, float y, float w, float h, float a, float strength, int tick) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        boolean shaders = GLContext.getCapabilities().OpenGL20;
        int oldProgram = shaders ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
        int oldTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        // 在纹理单元 0 上保存状态，最后恢复调用方的纹理单元、绑定、颜色和混合方式。
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glPushAttrib(ATTRIB_BITS);
        try {
            if (shaders) GL20.glUseProgram(0);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sprite.getTextureId());
            float blur = Math.min(w / 180f, 1.5f) * strength;
            GL11.glBegin(GL11.GL_QUADS);
            image(x, y, w, h, 0f, 1f, blur, 0.3f * blur, a * strength * 0.14f, 0.75f, 1f, 1f);
            image(x, y, w, h, 0f, 1f, -blur, -0.3f * blur, a * strength * 0.12f, 1f, 0.85f, 0.9f);
            for (int i = 0; i < 3; i++) {
                int key = seed + tick * 131 + i * 977;
                float lo = 0.07f + noise(key) * 0.80f;
                float hi = Math.min(0.96f, lo + 0.025f + noise(key + 1) * 0.055f);
                float dx = (noise(key + 2) - 0.5f) * 7f * strength;
                image(x, y, w, h, lo, hi, dx, 0f, a * strength * 0.65f, 1f, 1f, 1f);
            }
            GL11.glEnd();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glBegin(GL11.GL_QUADS);
            for (int i = 0; i < 28; i++) {
                int key = seed + tick * 313 + i * 37;
                float px = x + noise(key) * (w - 3f);
                float py = y + noise(key + 1) * (h - 2f);
                float pw = 1f + noise(key + 2) * 2f;
                float shade = noise(key + 3) > 0.5f ? 1f : 0.1f;
                GL11.glColor4f(shade, shade, shade, a * strength * 0.22f);
                GL11.glVertex2f(px, py);
                GL11.glVertex2f(px + pw, py);
                GL11.glVertex2f(px + pw, py + 1f);
                GL11.glVertex2f(px, py + 1f);
            }
            GL11.glEnd();
        } finally {
            try {
                if (shaders) GL20.glUseProgram(oldProgram);
                GL11.glPopAttrib();
            } finally {
                GL13.glActiveTexture(oldTexture);
            }
        }
    }

    /** 裁剪偏移后的重影，UV 对应原图；不改变 SpriteAPI 的共享尺寸、颜色和纹理区域。 */
    private void image(float x, float y, float w, float h, float lo, float hi, float dx, float dy,
                       float a, float r, float g, float b) {
        float left = Math.max(x, x + dx), right = Math.min(x + w, x + w + dx);
        float bottom = Math.max(y, y + lo * h + dy), top = Math.min(y + h, y + hi * h + dy);
        if (right <= left || top <= bottom) return;
        float u0 = texX + (left - x - dx) / w * texW;
        float u1 = texX + (right - x - dx) / w * texW;
        float v0 = texY + (bottom - y - dy) / h * texH;
        float v1 = texY + (top - y - dy) / h * texH;
        GL11.glColor4f(r, g, b, a);
        GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(left, bottom);
        GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(right, bottom);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(right, top);
        GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(left, top);
    }

    private SpriteAPI sprite() {
        if (unavailable) return null;
        if (sprite != null) return sprite;
        if (path == null || path.isEmpty()) { unavailable = true; return null; }
        try {
            SpriteAPI s = Global.getSettings().getSprite(path);
            if (s == null || s.getTextureId() <= 0) { unavailable = true; return null; }
            texX = s.getTexX(); texY = s.getTexY();
            texW = s.getTexWidth(); texH = s.getTexHeight();
            if (!Float.isFinite(texX) || !Float.isFinite(texY) || !Float.isFinite(texW)
                    || !Float.isFinite(texH) || texW <= 0f || texH <= 0f) {
                unavailable = true;
                return null;
            }
            sprite = s;
            return s;
        } catch (RuntimeException ex) {
            unavailable = true;
            LOG.warn("[Moci_UIPortraitSignal] 头像干扰不可用，保留静态头像: " + path, ex);
            return null;
        }
    }

    private static float noise(int key) {
        int n = (key ^ (key >>> 16)) * 0x45d9f3b;
        n = (n ^ (n >>> 16)) * 0x45d9f3b;
        return ((n ^ (n >>> 16)) & 0x00ffffff) / 16777216f;
    }

    private static float clamp01(float v) {
        return v > 0f ? Math.min(v, 1f) : 0f;
    }
}
