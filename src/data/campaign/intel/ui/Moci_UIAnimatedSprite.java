package data.campaign.intel.ui;

import java.awt.Color;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.ContextCapabilities;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;

/**
 * 弹窗里的贴图动画（只画不吃输入）。两种用法：
 * <ul>
 *   <li>{@link #sheet}：序列帧。贴图按 cols × rows 网格切成 frames 帧，从左到右、从上到下播放，
 *       相邻两帧先按预乘透明度插值，再一次绘制，避免半透明部位因重复叠加而闪烁。</li>
 *   <li>{@link #spin}：整张贴图绕中心匀速旋转（TacP 背景齿轮环）。</li>
 * </ul>
 * 槽位容器里先放一张同位置的静态图，再 {@link #follow} 它：插件在 renderBelow 里把静态图透明度设成 0，
 * 动画画在静态图的位置上。录制器从不调 renderBelow，所以录下来的画面、反馈页面上看到的都是那张静态图；
 * 游戏里动画贴图拿不到时，静态图恢复显示，等于退回不动的立绘。
 * 时间用进程级时钟，弹窗切页重建组件时动画不会从第一帧重来；游戏暂停时也照常播放。
 */
public class Moci_UIAnimatedSprite extends BaseCustomUIPanelPlugin {
    private static final Logger LOG = Global.getLogger(Moci_UIAnimatedSprite.class);
    private static final String P = "[Moci_UIAnimatedSprite] ";
    private static final long T0 = System.nanoTime();
    private static final int ATTRIB_BITS = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT
            | GL11.GL_TEXTURE_BIT;
    private static ContextCapabilities blendContext;
    private static boolean blendAttempted;
    private static int blendProgram;
    private static int blendOffset;
    private static int blendBounds;
    private static int blendFraction;

    private final String path;
    private final boolean sheet;
    private final int cols;
    private final int rows;
    private final int frames;
    private final float fps;
    private final float spin;

    private Color tint = Color.WHITE;
    private float alpha = 1f;
    private PositionAPI pos;
    private UIComponentAPI still;

    private SpriteAPI sprite;
    private boolean failed;
    private float baseX;
    private float baseY;
    private float baseW = 1f;
    private float baseH = 1f;
    private float halfTexelU;
    private float halfTexelV;

    private Moci_UIAnimatedSprite(String path, boolean sheet, int cols, int rows, int frames, float fps, float spin) {
        this.path = path;
        this.sheet = sheet;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.frames = Math.max(1, Math.min(frames, this.cols * this.rows));
        this.fps = fps;
        this.spin = spin;
    }

    /** 序列帧：cols × rows 网格里前 frames 格，每秒 fps 帧。 */
    public static Moci_UIAnimatedSprite sheet(String path, int cols, int rows, int frames, float fps) {
        return new Moci_UIAnimatedSprite(path, true, cols, rows, frames, Math.max(0.1f, fps), 0f);
    }

    /** 整图旋转：spinDegPerSec 度/秒，正数顺时针。 */
    public static Moci_UIAnimatedSprite spin(String path, float spinDegPerSec) {
        return new Moci_UIAnimatedSprite(path, false, 1, 1, 1, 1f, spinDegPerSec);
    }

    /** 颜色乘法着色（白色 = 原色）。 */
    public Moci_UIAnimatedSprite setTint(Color c) {
        this.tint = c == null ? Color.WHITE : c;
        return this;
    }

    public Moci_UIAnimatedSprite setAlpha(float a) {
        this.alpha = Math.max(0f, Math.min(1f, a));
        return this;
    }

    /** 跟随这张静态图：画在它的位置和尺寸上，游戏里把它隐藏；动画贴图失败时让它恢复显示。 */
    public Moci_UIAnimatedSprite follow(UIComponentAPI stillImage) {
        this.still = stillImage;
        return this;
    }

    @Override
    public void positionChanged(PositionAPI position) {
        this.pos = position;
    }

    @Override
    public void renderBelow(float alphaMult) {
        SpriteAPI s = sprite();
        if (s == null) {
            if (still != null) still.setOpacity(1f);
            return;
        }
        PositionAPI p = still != null ? still.getPosition() : pos;
        if (p == null || p.getWidth() <= 0f || p.getHeight() <= 0f) return;
        if (still != null) still.setOpacity(0f);
        float a = Math.max(0f, Math.min(1f, alphaMult)) * alpha;
        if (a <= 0f) return;
        float x = p.getX();
        float y = p.getY();
        float w = p.getWidth();
        float h = p.getHeight();
        double t = (System.nanoTime() - T0) / 1.0e9;
        boolean shaders = GLContext.getCapabilities().OpenGL20;
        int oldProgram = shaders ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
        int oldTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(ATTRIB_BITS);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if (shaders) GL20.glUseProgram(0);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            s.setNormalBlend();
            s.setColor(tint);
            s.setSize(w, h);
            if (sheet) {
                double cursor = t * fps;
                double whole = Math.floor(cursor);
                int i = (int) (((long) whole % frames + frames) % frames);
                float frac = (float) (cursor - whole);
                drawBlendedFrame(s, i, (i + 1) % frames, frac, x, y, w, h, a);
            } else {
                s.setTexX(baseX);
                s.setTexY(baseY);
                s.setTexWidth(baseW);
                s.setTexHeight(baseH);
                s.setAngle((float) (-(spin * t) % 360.0));
                s.setAlphaMult(a);
                s.renderAtCenter(x + w / 2f, y + h / 2f);
            }
        } catch (RuntimeException ex) {
            failed = true;
            sprite = null;
            if (still != null) still.setOpacity(1f);
            LOG.warn(P + "绘制失败，改回静态图: " + path, ex);
        } finally {
            try {
                if (shaders) GL20.glUseProgram(oldProgram);
                GL11.glPopAttrib();
            } finally {
                GL13.glActiveTexture(oldTexture);
            }
        }
    }

    private void drawBlendedFrame(SpriteAPI s, int current, int next, float fraction,
                                  float x, float y, float w, float h, float a) {
        if (!ensureBlendProgram()) {
            // Older hardware still draws one frame at normal opacity, without double compositing.
            drawFrame(s, fraction < 0.5f ? current : next, x, y, w, h, a);
            return;
        }
        float cw = baseW / cols;
        float ch = baseH / rows;
        float u = baseX + (current % cols) * cw;
        float v = baseY + (current / cols) * ch;
        // Clamp each sample inside its atlas cell so bilinear filtering cannot bleed adjacent frames.
        GL20.glUseProgram(blendProgram);
        try {
            GL20.glUniform2f(blendOffset, ((next % cols) - (current % cols)) * cw,
                    ((next / cols) - (current / cols)) * ch);
            GL20.glUniform4f(blendBounds, u + halfTexelU, v + halfTexelV,
                    u + cw - halfTexelU, v + ch - halfTexelV);
            GL20.glUniform1f(blendFraction, fraction);
            drawFrame(s, current, x, y, w, h, a);
        } finally {
            GL20.glUseProgram(0);
        }
    }

    /** One shared program per GL context; context destruction releases its GPU objects. */
    private static boolean ensureBlendProgram() {
        ContextCapabilities context = GLContext.getCapabilities();
        if (blendContext != context) {
            blendContext = context;
            blendAttempted = false;
            blendProgram = 0;
        }
        if (blendAttempted) return blendProgram != 0;
        blendAttempted = true;
        if (!context.OpenGL20) return false;
        int vertex = 0;
        int fragment = 0;
        int program = 0;
        try {
            vertex = compileShader(GL20.GL_VERTEX_SHADER, "#version 120\n"
                    + "void main() { gl_Position = ftransform(); gl_TexCoord[0] = gl_MultiTexCoord0; gl_FrontColor = gl_Color; }\n");
            fragment = compileShader(GL20.GL_FRAGMENT_SHADER, "#version 120\n"
                    + "uniform sampler2D atlas; uniform vec2 nextOffset; uniform vec4 bounds; uniform float fraction;\n"
                    + "void main() {\n"
                    + "  vec2 uv = clamp(gl_TexCoord[0].xy, bounds.xy, bounds.zw);\n"
                    + "  vec4 a = texture2D(atlas, uv); vec4 b = texture2D(atlas, uv + nextOffset);\n"
                    + "  vec4 p = mix(vec4(a.rgb * a.a, a.a), vec4(b.rgb * b.a, b.a), fraction);\n"
                    + "  vec3 rgb = p.a > 0.00001 ? p.rgb / p.a : vec3(0.0);\n"
                    + "  gl_FragColor = vec4(rgb * gl_Color.rgb, p.a * gl_Color.a);\n"
                    + "}\n");
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program, 4096));
            }
            // The atlas sampler defaults to texture unit 0; other uniforms are set on each draw.
            blendOffset = GL20.glGetUniformLocation(program, "nextOffset");
            blendBounds = GL20.glGetUniformLocation(program, "bounds");
            blendFraction = GL20.glGetUniformLocation(program, "fraction");
            blendProgram = program;
            program = 0;
            return true;
        } catch (RuntimeException ex) {
            LOG.warn(P + "帧插值不可用，改为单帧播放: ", ex);
            return false;
        } finally {
            if (program != 0) GL20.glDeleteProgram(program);
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String message = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(message);
        }
        return shader;
    }

    /**
     * 第 i 帧在网格里的位置：列 = i % cols，行 = i / cols（从图片顶部数）。
     * 贴图坐标从加载后的基准（getTexX/Y/Width/Height，覆盖整张图）按格子等分。
     * 行号按「贴图 v 从图片顶部开始」换算；如果引擎约定相反，只是两行对调，循环动画看不出来。
     */
    private void drawFrame(SpriteAPI s, int i, float x, float y, float w, float h, float a) {
        float cw = baseW / cols;
        float ch = baseH / rows;
        int col = i % cols;
        int row = i / cols;
        s.setTexX(baseX + col * cw);
        s.setTexY(baseY + row * ch);
        s.setTexWidth(cw);
        s.setTexHeight(ch);
        s.setAlphaMult(a);
        s.render(x, y);
    }

    private SpriteAPI sprite() {
        if (failed) return null;
        if (sprite != null) return sprite;
        try {
            SpriteAPI s = Global.getSettings().getSprite(path);
            if (s == null || s.getTextureId() <= 0 || s.getWidth() <= 0f || s.getHeight() <= 0f) {
                failed = true;
                LOG.warn(P + "贴图不可用，改回静态图: " + path);
                return null;
            }
            baseX = s.getTexX();
            baseY = s.getTexY();
            baseW = s.getTexWidth() > 0f ? s.getTexWidth() : 1f;
            baseH = s.getTexHeight() > 0f ? s.getTexHeight() : 1f;
            halfTexelU = baseW / s.getWidth() * 0.5f;
            halfTexelV = baseH / s.getHeight() * 0.5f;
            sprite = s;
            return s;
        } catch (RuntimeException ex) {
            failed = true;
            LOG.warn(P + "读取贴图失败，改回静态图: " + path, ex);
            return null;
        }
    }
}
