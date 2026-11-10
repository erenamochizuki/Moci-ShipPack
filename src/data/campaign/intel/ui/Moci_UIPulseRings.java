package data.campaign.intel.ui;

import java.awt.Color;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.PositionAPI;

/**
 * 脉动波纹：3 个从 inner 扩到 outer、边扩边淡出的圆环（轮流出现），中半径上 2 段慢速反向旋转的弧，
 * outer 处一圈 36 个刻度，inner 处一个很淡的六边形。整体很淡，只做背景，不抢立绘和信息签。
 *
 * <p>只渲染：processInput 什么都不做，<b>绝不 consume 任何事件</b>。所有图形都收在面板内：
 * outer 超过「圆心到最近边的距离」时自动缩小。</p>
 *
 * <p>全部画在 renderBelow 里（本面板子组件之下），两种用法都不会盖住内容：直接当 aiStage 面板自己的插件，
 * 立绘、信息签作为子组件自然盖在波纹上；或者单独建一个同尺寸面板，先于立绘 addComponent。</p>
 *
 * <p>base 只取 RGB，alpha 忽略；传 null 用航空中队扫描线的青绿色。</p>
 */
public class Moci_UIPulseRings extends BaseCustomUIPanelPlugin {
    // ===================== 可调参数 =====================
    /** 一个圆环从 inner 扩到 outer 的时间（秒）。RING_COUNT 个环错开均分，每 RING_LIFE / RING_COUNT 秒冒出一个。 */
    private static final float RING_LIFE = 6f;
    /** 圆环个数（设计稿：3 个）。 */
    private static final int RING_COUNT = 3;
    /** 圆环最亮时的透明度（刚出现时），之后边扩边淡出到 0。 */
    private static final float RING_ALPHA = 0.20f;
    /** 圆环半宽（逻辑像素）：中线最亮，向内外各羽化到 0。 */
    private static final float RING_HALF_WIDTH = 1.5f;
    /** 圆环刚出现时的淡入时长（占寿命的比例），免得在 inner 处突然冒出来。 */
    private static final float RING_FADE_IN = 0.08f;
    /** 弧 A：跨度（度）、转速（度/秒，正 = 逆时针），约 30 秒一圈。 */
    private static final float ARC_A_SPAN = 70f;
    private static final float ARC_A_SPEED = 12f;
    /** 弧 B：跨度（度）、转速（度/秒），和 A 反向，45 秒一圈。初始与 A 相隔半圈。 */
    private static final float ARC_B_SPAN = 110f;
    private static final float ARC_B_SPEED = -8f;
    /** 弧头部（沿旋转方向的前端）透明度，尾端是它的 ARC_TAIL_RATIO 倍。弧用加色混合，略带发光。 */
    private static final float ARC_ALPHA = 0.30f;
    private static final float ARC_TAIL_RATIO = 0.15f;
    /** 弧半宽（逻辑像素）。 */
    private static final float ARC_HALF_WIDTH = 2f;
    /** 刻度个数（设计稿：36 个，每 10° 一个；要能整除 SEGMENTS），每 90° 一个长刻度。 */
    private static final int TICK_COUNT = 36;
    /** 刻度长度（逻辑像素，从 outer 往里画）：普通 / 每 90° 的长刻度。 */
    private static final float TICK_LENGTH = 4f;
    private static final float TICK_MAJOR_LENGTH = 8f;
    /** 刻度透明度：普通 / 长刻度。 */
    private static final float TICK_ALPHA = 0.14f;
    private static final float TICK_MAJOR_ALPHA = 0.26f;
    /** inner 处六边形（外接圆半径 = inner，平顶）的透明度。 */
    private static final float HEX_ALPHA = 0.08f;
    /** 没调用 setRadius 时：outer = 圆心到最近边的距离（扣掉羽化），inner = outer × 这个比例。 */
    private static final float DEFAULT_INNER_RATIO = 0.6f;
    /** base 为 null 时的颜色：航空中队扫描线色 (0.25, 0.95, 0.70)。 */
    private static final float[] DEFAULT_COLOR = {0.25f, 0.95f, 0.70f};
    /** advance 单步上限（秒）。 */
    private static final float MAX_STEP = 0.1f;
    // ====================================================

    /** 圆周分段：每段 5°。sin/cos 预先算好，渲染时只有弧的起始角要算三角函数。 */
    private static final int SEGMENTS = 72;
    private static final float STEP_DEG = 360f / SEGMENTS;
    private static final float[] COS = new float[SEGMENTS + 1];
    private static final float[] SIN = new float[SEGMENTS + 1];
    private static final int ARC_A_STEPS = arcSteps(ARC_A_SPAN);
    private static final int ARC_B_STEPS = arcSteps(ARC_B_SPAN);
    private static final int ATTRIB_BITS = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT
            | GL11.GL_LINE_BIT | GL11.GL_LIGHTING_BIT;

    static {
        for (int i = 0; i < SEGMENTS; i++) {
            double t = Math.PI * 2.0 * i / SEGMENTS;
            COS[i] = (float) Math.cos(t);
            SIN[i] = (float) Math.sin(t);
        }
        COS[SEGMENTS] = COS[0];
        SIN[SEGMENTS] = SIN[0];
    }

    private final float colorR, colorG, colorB;
    private PositionAPI position;
    private boolean hasCenter;
    private float centerX;
    private float centerY;
    private boolean hasRadius;
    private float inner;
    private float outer;
    /** 圆环进度 0~1（RING_LIFE 秒一轮）。各动画分别回卷，长时间打开也不会丢精度。 */
    private float ringPhase;
    /** 两段弧当前的起始角（度，0~360）。 */
    private float arcA;
    private float arcB = 180f;
    /** 0 保持基础波纹，1–4 逐步增加线路、节点、分段弧和流动光点。 */
    private int detail;

    public Moci_UIPulseRings(Color base) {
        if (base != null) {
            colorR = base.getRed() / 255f;
            colorG = base.getGreen() / 255f;
            colorB = base.getBlue() / 255f;
        } else {
            colorR = DEFAULT_COLOR[0];
            colorG = DEFAULT_COLOR[1];
            colorB = DEFAULT_COLOR[2];
        }
    }

    /** 面板内坐标，左上为原点（y 向下），默认面板中心。非有限值忽略。 */
    public Moci_UIPulseRings setCenter(float localX, float localY) {
        if (Float.isFinite(localX) && Float.isFinite(localY)) {
            this.centerX = localX;
            this.centerY = localY;
            this.hasCenter = true;
        }
        return this;
    }

    /** 圆环从 inner 扩到 outer（逻辑像素）。两者顺序反了会自动交换；outer 超出面板时自动缩小。非有限值忽略。 */
    public Moci_UIPulseRings setRadius(float inner, float outer) {
        if (Float.isFinite(inner) && Float.isFinite(outer)) {
            this.inner = inner;
            this.outer = outer;
            this.hasRadius = true;
        }
        return this;
    }

    @Override public void positionChanged(PositionAPI position) {
        this.position = position;
    }

    public Moci_UIPulseRings setDetail(int grade) {
        detail = Math.max(0, Math.min(4, grade));
        return this;
    }

    @Override public void advance(float amount) {
        if (!(amount > 0f)) return; // 0、负数、NaN 都不走
        float dt = Math.min(amount, MAX_STEP);
        ringPhase = (ringPhase + dt / RING_LIFE) % 1f;
        arcA = wrapDeg(arcA + dt * ARC_A_SPEED);
        arcB = wrapDeg(arcB + dt * ARC_B_SPEED);
    }

    @Override public void processInput(List<InputEventAPI> events) {
        // 只渲染：不读、也不 consume 任何事件。
    }

    /** 画在子组件之下：波纹只做背景，立绘和信息签永远在它上面。 */
    @Override public void renderBelow(float alphaMult) {
        if (position == null) return;
        float w = position.getWidth();
        float h = position.getHeight();
        float a = clamp01(alphaMult);
        if (w <= 0f || h <= 0f || a <= 0f) return;
        float lx = hasCenter ? centerX : w * 0.5f;
        float ly = hasCenter ? centerY : h * 0.5f;
        if (lx < 0f || lx > w || ly < 0f || ly > h) return; // 圆心在面板外就不画
        // 最大半径：圆心到最近边的距离，扣掉羽化宽度，保证不画出面板。
        float maxR = Math.min(Math.min(lx, w - lx), Math.min(ly, h - ly))
                - Math.max(RING_HALF_WIDTH, ARC_HALF_WIDTH) - 1f;
        float ro;
        float ri;
        if (hasRadius) {
            ro = Math.min(Math.max(inner, outer), maxR);
            ri = Math.max(0f, Math.min(Math.min(inner, outer), ro));
        } else {
            ro = maxR;
            ri = ro * DEFAULT_INNER_RATIO;
        }
        if (ro < 4f) return;
        // 面板内坐标（y 向下）→ 屏幕坐标（y 向上）：(x + lx, y + h - ly)
        float cx = position.getX() + lx;
        float cy = position.getY() + h - ly;

        GL11.glPushAttrib(ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glShadeModel(GL11.GL_SMOOTH); // 羽化依赖逐顶点颜色插值
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1f);

            // 1) inner 处的淡六边形（平顶）
            if (ri > 2f) {
                GL11.glColor4f(colorR, colorG, colorB, a * HEX_ALPHA);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                for (int k = 0; k < 6; k++) {
                    int idx = k * SEGMENTS / 6;
                    GL11.glVertex2f(cx + COS[idx] * ri, cy + SIN[idx] * ri);
                }
                GL11.glEnd();
            }

            // 2) outer 处一圈刻度，从 outer 往里画，每 90° 一个长刻度
            int majorEvery = Math.max(1, TICK_COUNT / 4);
            GL11.glBegin(GL11.GL_LINES);
            for (int k = 0; k < TICK_COUNT; k++) {
                int idx = k * SEGMENTS / TICK_COUNT;
                boolean major = k % majorEvery == 0;
                float r0 = Math.max(0f, ro - (major ? TICK_MAJOR_LENGTH : TICK_LENGTH));
                GL11.glColor4f(colorR, colorG, colorB, a * (major ? TICK_MAJOR_ALPHA : TICK_ALPHA));
                GL11.glVertex2f(cx + COS[idx] * r0, cy + SIN[idx] * r0);
                GL11.glVertex2f(cx + COS[idx] * ro, cy + SIN[idx] * ro);
            }
            GL11.glEnd();

            // 3) 外扩淡出的圆环，错开均分轮流出现；半径先快后慢，透明度随扩散线性淡出
            for (int k = 0; k < RING_COUNT; k++) {
                float p = ringPhase + (float) k / RING_COUNT;
                if (p >= 1f) p -= 1f;
                float ease = 1f - (1f - p) * (1f - p);
                float r = ri + (ro - ri) * ease;
                float fade = (p < RING_FADE_IN ? p / RING_FADE_IN : 1f) * (1f - p);
                float ra = a * RING_ALPHA * fade;
                band(cx, cy, r, RING_HALF_WIDTH, 1f, 0f, SEGMENTS, ra, ra);
            }

            // 4) 中半径上两段反向慢转的弧：加色混合，头部（旋转方向的前端）最亮，尾端淡出
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            float rm = (ri + ro) * 0.5f;
            drawArc(cx, cy, rm, arcA, ARC_A_STEPS, ARC_A_SPEED > 0f, a);
            drawArc(cx, cy, rm, arcB, ARC_B_STEPS, ARC_B_SPEED > 0f, a);
            if (detail > 0) drawCircuits(cx, cy, ri, ro, a);
        } finally {
            GL11.glPopAttrib();
        }
    }

    /** 固定线路批量绘制；只让细小光点缓慢行进，避免整层明暗脉冲。 */
    private void drawCircuits(float cx, float cy, float ri, float ro, float a) {
        float space = ro - ri;
        if (space < 24f) return;
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(colorR, colorG, colorB, a * 0.26f);
        GL11.glBegin(GL11.GL_LINES);
        for (int sector = 0; sector < 8; sector++) {
            int idx = sector * SEGMENTS / 8;
            float c = COS[idx], s = SIN[idx];
            for (int lane = 0; lane < detail; lane++) {
                float offset = (lane - (detail - 1) * 0.5f) * 5f;
                float r0 = ri * 0.94f;
                float r1 = ri + space * (0.28f + lane * 0.14f);
                circuitVertex(cx, cy, c, s, r0, offset);
                circuitVertex(cx, cy, c, s, r1, offset);
                circuitVertex(cx, cy, c, s, r1, offset);
                circuitVertex(cx, cy, c, s, r1 + 6f, offset + 6f);
                circuitVertex(cx, cy, c, s, r1 + 6f, offset + 6f);
                circuitVertex(cx, cy, c, s, r1 + 6f, offset + 18f);
                if (detail >= 2) {
                    // 线路末端的小菱形节点。
                    float r = r1 + 6f, t = offset + 18f;
                    for (int k = 0; k < 4; k++) {
                        int p = k * SEGMENTS / 4, q = (k + 1) * SEGMENTS / 4;
                        circuitVertex(cx, cy, c, s, r + COS[p] * 2.5f, t + SIN[p] * 2.5f);
                        circuitVertex(cx, cy, c, s, r + COS[q] * 2.5f, t + SIN[q] * 2.5f);
                    }
                }
            }
        }
        GL11.glEnd();
        if (detail >= 3) {
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            for (int k = 0; k < 4; k++) {
                double angle = Math.toRadians(arcB + k * 90f);
                band(cx, cy, ro - 14f, 0.8f, (float) Math.cos(angle), (float) Math.sin(angle),
                        7, a * 0.06f, a * 0.32f);
            }
        }
        if (detail >= 4) {
            GL11.glColor4f(colorR, colorG, colorB, a * 0.65f);
            GL11.glBegin(GL11.GL_QUADS);
            for (int k = 0; k < 8; k++) {
                int idx = k * SEGMENTS / 8;
                float p = (ringPhase + k / 8f) % 1f;
                float r = ri + space * (0.08f + 0.64f * p);
                float x = cx + COS[idx] * r, y = cy + SIN[idx] * r;
                // 两端平滑淡出，循环回卷时不会跳亮。
                GL11.glColor4f(colorR, colorG, colorB, a * 0.65f * Math.min(1f, Math.min(p, 1f - p) * 8f));
                GL11.glVertex2f(x - 1.4f, y); GL11.glVertex2f(x, y + 1.4f);
                GL11.glVertex2f(x + 1.4f, y); GL11.glVertex2f(x, y - 1.4f);
            }
            GL11.glEnd();
        }
    }

    private static void circuitVertex(float cx, float cy, float c, float s, float radius, float tangent) {
        GL11.glVertex2f(cx + c * radius - s * tangent, cy + s * radius + c * tangent);
    }

    private void drawArc(float cx, float cy, float r, float startDeg, int steps, boolean headAtEnd, float a) {
        double rad = Math.toRadians(startDeg);
        float c0 = (float) Math.cos(rad);
        float s0 = (float) Math.sin(rad);
        float head = a * ARC_ALPHA;
        float tail = head * ARC_TAIL_RATIO;
        band(cx, cy, r, ARC_HALF_WIDTH, c0, s0, steps, headAtEnd ? tail : head, headAtEnd ? head : tail);
    }

    /**
     * 羽化环带：从起始角（c0、s0 是它的 cos、sin）开始逆时针走 steps 个 5° 分段。
     * 中线（半径 r）的透明度从 aStart 线性变到 aEnd，向内外各 hw 淡到 0。steps = SEGMENTS 时是整圈。
     */
    private void band(float cx, float cy, float r, float hw, float c0, float s0, int steps, float aStart, float aEnd) {
        if (steps <= 0 || r <= 0f || (aStart <= 0.002f && aEnd <= 0.002f)) return;
        if (steps > SEGMENTS) steps = SEGMENTS;
        float rIn = Math.max(0f, r - hw);
        float rOut = r + hw;
        for (int pass = 0; pass < 2; pass++) {
            // pass 0：内侧羽化（rIn 透明 → r 最亮）；pass 1：外侧羽化（r 最亮 → rOut 透明）
            float r0 = pass == 0 ? rIn : r;
            float r1 = pass == 0 ? r : rOut;
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for (int i = 0; i <= steps; i++) {
                float c = c0 * COS[i] - s0 * SIN[i];
                float s = s0 * COS[i] + c0 * SIN[i];
                float am = aStart + (aEnd - aStart) * i / steps;
                GL11.glColor4f(colorR, colorG, colorB, pass == 0 ? 0f : am);
                GL11.glVertex2f(cx + c * r0, cy + s * r0);
                GL11.glColor4f(colorR, colorG, colorB, pass == 0 ? am : 0f);
                GL11.glVertex2f(cx + c * r1, cy + s * r1);
            }
            GL11.glEnd();
        }
    }

    private static int arcSteps(float spanDeg) {
        return Math.max(1, Math.min(SEGMENTS, Math.round(spanDeg / STEP_DEG)));
    }

    private static float wrapDeg(float d) {
        d %= 360f;
        return d < 0f ? d + 360f : d;
    }

    private static float clamp01(float v) {
        return v > 0f ? (v < 1f ? v : 1f) : 0f; // NaN → 0
    }
}
