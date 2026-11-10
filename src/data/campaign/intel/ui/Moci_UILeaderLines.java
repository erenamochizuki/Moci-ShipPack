package data.campaign.intel.ui;

import java.awt.Color;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.PositionAPI;

/**
 * 引导线：每条线在起点画一个小圆点，折线走 起点 → 拐点（与终点同高）→ 终点，终点再接一段 24px 的短横线。
 *
 * <p>拐点按 45° 放：先斜着走 min(|dx|, |dy|)，再水平走到终点；竖直距离比水平距离大时，斜线直接连到终点。
 * 短横线沿最后一段的方向继续（往左走的线往左延伸；起点终点同一 x 时朝终点所在的半边延伸），
 * 所以终点建议放在信息签靠立绘一侧的下沿角，短横线正好垫在签底。</p>
 *
 * <p>只渲染：processInput 什么都不做，<b>绝不 consume 任何事件</b>。所有点都夹在面板内。
 * 面板第一次 advance 起淡入 0.5 秒，只淡一次；clear / addLine 不会重新淡入，所以每帧重建线也不会闪。</p>
 *
 * <p>base 只取 RGB，alpha 忽略；传 null 用航空中队扫描线的青绿色。</p>
 */
public class Moci_UILeaderLines extends BaseCustomUIPanelPlugin {
    // ===================== 可调参数 =====================
    /** 起点圆点半径（逻辑像素，设计稿 2.5）：实心到 DOT_RADIUS - DOT_FEATHER/2，再羽化 DOT_FEATHER，半透明边正好落在 2.5。 */
    private static final float DOT_RADIUS = 2.5f;
    private static final float DOT_FEATHER = 1f;
    /** 圆点分段数。 */
    private static final int DOT_SEGMENTS = 16;
    /** 终点短横线长度（逻辑像素，设计稿 24）。 */
    private static final float TAIL_LENGTH = 24f;
    /** 终点短横线粗细（逻辑像素），比 1px 的折线粗一档，读起来像垫在签底的横条。 */
    private static final float TAIL_THICKNESS = 2f;
    /** 透明度（设计稿约 0.8）。 */
    private static final float LINE_ALPHA = 0.8f;
    /** 淡入时长（秒，设计稿 0.5）。 */
    private static final float FADE_IN = 0.5f;
    /** base 为 null 时的颜色：航空中队扫描线色 (0.25, 0.95, 0.70)。 */
    private static final float[] DEFAULT_COLOR = {0.25f, 0.95f, 0.70f};
    /** advance 单步上限（秒）：打开弹窗那一帧的卡顿不会把淡入直接跳过。 */
    private static final float MAX_STEP = 0.1f;
    // ====================================================

    private static final int ATTRIB_BITS = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT
            | GL11.GL_LINE_BIT | GL11.GL_LIGHTING_BIT;
    private static final float[] DOT_COS = new float[DOT_SEGMENTS + 1];
    private static final float[] DOT_SIN = new float[DOT_SEGMENTS + 1];

    static {
        for (int i = 0; i < DOT_SEGMENTS; i++) {
            double t = Math.PI * 2.0 * i / DOT_SEGMENTS;
            DOT_COS[i] = (float) Math.cos(t);
            DOT_SIN[i] = (float) Math.sin(t);
        }
        DOT_COS[DOT_SEGMENTS] = DOT_COS[0];
        DOT_SIN[DOT_SEGMENTS] = DOT_SIN[0];
    }

    private final float colorR, colorG, colorB;
    private PositionAPI position;
    /** 每条线 4 个数：x1, y1, x2, y2（面板内坐标，左上为原点，y 向下）。 */
    private float[] coords = new float[32];
    private int count;
    /** 已淡入的时间（秒，封顶 FADE_IN）。 */
    private float fade;

    public Moci_UILeaderLines(Color base) {
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

    /** 清空所有线（不会重新淡入）。 */
    public Moci_UILeaderLines clear() {
        count = 0;
        return this;
    }

    /** 加一条线。面板内坐标，左上为原点（y 向下）：(x1, y1) 起点（画圆点），(x2, y2) 终点（接短横线）。非有限值忽略。 */
    public Moci_UILeaderLines addLine(float x1, float y1, float x2, float y2) {
        if (!Float.isFinite(x1) || !Float.isFinite(y1) || !Float.isFinite(x2) || !Float.isFinite(y2)) return this;
        int need = (count + 1) * 4;
        if (need > coords.length) {
            float[] grown = new float[Math.max(need, coords.length * 2)];
            System.arraycopy(coords, 0, grown, 0, count * 4);
            coords = grown;
        }
        int o = count * 4;
        coords[o] = x1;
        coords[o + 1] = y1;
        coords[o + 2] = x2;
        coords[o + 3] = y2;
        count++;
        return this;
    }

    @Override public void positionChanged(PositionAPI position) {
        this.position = position;
    }

    @Override public void advance(float amount) {
        if (!(amount > 0f) || fade >= FADE_IN) return; // 0、负数、NaN 都不走
        fade = Math.min(FADE_IN, fade + Math.min(amount, MAX_STEP));
    }

    @Override public void processInput(List<InputEventAPI> events) {
        // 只渲染：不读、也不 consume 任何事件。
    }

    @Override public void render(float alphaMult) {
        if (position == null || count <= 0) return;
        float w = position.getWidth();
        float h = position.getHeight();
        float a = clamp01(alphaMult) * LINE_ALPHA * clamp01(fade / FADE_IN);
        if (w <= 8f || h <= 8f || a <= 0.002f) return;
        float x = position.getX();
        float y = position.getY();
        float dotSolid = DOT_RADIUS - DOT_FEATHER * 0.5f;
        float dotOuter = DOT_RADIUS + DOT_FEATHER * 0.5f;
        float halfT = TAIL_THICKNESS * 0.5f;

        GL11.glPushAttrib(ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glShadeModel(GL11.GL_SMOOTH); // 圆点羽化依赖逐顶点颜色插值
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1f);

            for (int n = 0; n < count; n++) {
                int o = n * 4;
                // 面板内坐标，先夹进面板：起点留出圆点半径，终点留出短横线粗细。
                float sx = clamp(coords[o], dotOuter, w - dotOuter);
                float sy = clamp(coords[o + 1], dotOuter, h - dotOuter);
                float ex = clamp(coords[o + 2], 1f, w - 1f);
                float ey = clamp(coords[o + 3], halfT, h - halfT);
                float dx = ex - sx;
                float dy = ey - sy;
                float dir = dx > 0f ? 1f : dx < 0f ? -1f : (ex < w * 0.5f ? -1f : 1f);
                float kx = sx + dir * Math.min(Math.abs(dx), Math.abs(dy)); // 拐点：45° 斜线的落点，与终点同高
                float tx = clamp(ex + dir * TAIL_LENGTH, 0f, w);           // 短横线末端

                // 换算成屏幕坐标（y 向上）：(x + lx, y + h - ly)
                float sX = x + sx;
                float sY = y + h - sy;
                float kX = x + kx;
                float eX = x + ex;
                float eY = y + h - ey;
                float tX = x + tx;

                GL11.glColor4f(colorR, colorG, colorB, a);
                // 折线从圆点实心边缘开始，免得和圆点叠成一块更亮的斑。
                float fx = kX - sX;
                float fy = eY - sY;
                float flen = (float) Math.sqrt(fx * fx + fy * fy);
                float startX = sX;
                float startY = sY;
                if (flen > dotSolid) {
                    startX += fx / flen * dotSolid;
                    startY += fy / flen * dotSolid;
                }
                GL11.glBegin(GL11.GL_LINE_STRIP);
                GL11.glVertex2f(startX, startY);
                GL11.glVertex2f(kX, eY);
                GL11.glVertex2f(eX, eY);
                GL11.glEnd();

                // 终点短横线
                float tx0 = Math.min(eX, tX);
                float tx1 = Math.max(eX, tX);
                if (tx1 > tx0) {
                    GL11.glBegin(GL11.GL_QUADS);
                    GL11.glVertex2f(tx0, eY - halfT);
                    GL11.glVertex2f(tx0, eY + halfT);
                    GL11.glVertex2f(tx1, eY + halfT);
                    GL11.glVertex2f(tx1, eY - halfT);
                    GL11.glEnd();
                }

                // 起点小圆点：实心扇形 + 1px 羽化外圈
                GL11.glBegin(GL11.GL_TRIANGLE_FAN);
                GL11.glVertex2f(sX, sY);
                for (int i = 0; i <= DOT_SEGMENTS; i++) {
                    GL11.glVertex2f(sX + DOT_COS[i] * dotSolid, sY + DOT_SIN[i] * dotSolid);
                }
                GL11.glEnd();
                GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
                for (int i = 0; i <= DOT_SEGMENTS; i++) {
                    GL11.glColor4f(colorR, colorG, colorB, a);
                    GL11.glVertex2f(sX + DOT_COS[i] * dotSolid, sY + DOT_SIN[i] * dotSolid);
                    GL11.glColor4f(colorR, colorG, colorB, 0f);
                    GL11.glVertex2f(sX + DOT_COS[i] * dotOuter, sY + DOT_SIN[i] * dotOuter);
                }
                GL11.glEnd();
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) return (lo + hi) * 0.5f;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp01(float v) {
        return v > 0f ? (v < 1f ? v : 1f) : 0f; // NaN → 0
    }
}
