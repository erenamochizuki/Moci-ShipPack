package data.campaign.intel.ui;

import java.awt.Color;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.PositionAPI;

/**
 * 扫描线框：很淡的网格底 + 从上往下缓慢移动的扫描线（带柔和拖尾）+ 细边框 + 四角括号。
 *
 * <p>只渲染：processInput 什么都不做，<b>绝不 consume 任何事件</b>，盖在按钮/勾选框上也不影响点击。
 * 所有顶点都限制在面板矩形内；不改 glScissor，父级滚动列表的裁剪照常生效。</p>
 *
 * <p>两种放法都可以：</p>
 * <ul>
 *   <li>叠加：在图片之后 addComponent 一个同尺寸的本面板，所有效果都画在图片上面。</li>
 *   <li>底座：把图片作为本面板的子组件，网格（renderBelow）在图片下面，边框、扫描线、四角括号（render）在图片上面，
 *       层次和航空中队工作台的 Moci_AirSquadronScanPanel 一样。</li>
 * </ul>
 *
 * <p>base 只取 RGB，alpha 忽略，透明度由下面的常量决定；传 null 用航空中队扫描线的原配色。</p>
 */
public class Moci_UIScanFrame extends BaseCustomUIPanelPlugin {
    // ===================== 可调参数 =====================
    // 扫描线与拖尾：照搬 data.campaign.fighter.ui.Moci_AirSquadronScanPanel（只读参考，不要改那个文件）。
    /** 每秒走过一轮的比例：Moci_AirSquadronScanPanel.advance 里的 amount * 0.16f，约 6.25 秒一轮。 */
    private static final float SCAN_SPEED = 0.16f;
    /** 拖尾长度（逻辑像素）：Moci_AirSquadronScanPanel.render 里的 center + 48f。拖尾在扫描线上方（已经扫过的一侧）。 */
    private static final float TRAIL_LENGTH = 48f;
    /** 拖尾贴着扫描线那一端的透明度：Moci_AirSquadronScanPanel 的 alpha * 0.13f，往尾端线性淡到 0。 */
    private static final float TRAIL_ALPHA = 0.13f;
    /** 扫描线透明度：Moci_AirSquadronScanPanel 的 alpha * 0.37f。 */
    private static final float LINE_ALPHA = 0.37f;
    /** base 为 null 时的扫描线颜色：Moci_AirSquadronScanPanel 的 (0.25, 0.95, 0.70)。 */
    private static final float[] DEFAULT_LINE = {0.25f, 0.95f, 0.70f};
    /** base 为 null 时的拖尾颜色：Moci_AirSquadronScanPanel 的 (0.10, 0.86, 0.59)。 */
    private static final float[] DEFAULT_TRAIL = {0.10f, 0.86f, 0.59f};
    /** base 为 null 时的网格颜色：Moci_AirSquadronScanPanel 网格的 (0.14, 0.60, 0.51)。 */
    private static final float[] DEFAULT_GRID = {0.14f, 0.60f, 0.51f};
    /** base 为 null 时的边框颜色：Moci_AirSquadronScanPanel 普通状态边框的 (0.24, 0.66, 0.63)。 */
    private static final float[] DEFAULT_BORDER = {0.24f, 0.66f, 0.63f};
    /** 传了 base 时，扫描线和四角括号比 base 往白色提亮的比例（参考里扫描线比拖尾亮一档）；网格、边框、拖尾直接用 base。 */
    private static final float LINE_LIGHTEN = 0.2f;
    // 网格、边框、四角括号：按设计稿 combat-squad-dialog-r3.md 与 lead 的确认。
    // 网格比参考（32px / 0.17）更密更淡，因为这里叠在头像和舰船图上。
    /** 网格间距（逻辑像素）。 */
    private static final float GRID = 16f;
    /** 网格透明度。 */
    private static final float GRID_ALPHA = 0.06f;
    /** 细边框透明度。 */
    private static final float BORDER_ALPHA = 0.35f;
    /** 四角括号每条边的长度（逻辑像素）。 */
    private static final float CORNER_LENGTH = 10f;
    /** 四角括号粗细（逻辑像素）。 */
    private static final float CORNER_THICKNESS = 2f;
    /** 四角括号透明度。 */
    private static final float CORNER_ALPHA = 0.7f;
    /** advance 单步上限（秒）：卡顿或异常的 amount 不会让扫描线跳一大截。 */
    private static final float MAX_STEP = 0.1f;
    // ====================================================

    private static final int ATTRIB_BITS = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT
            | GL11.GL_LINE_BIT | GL11.GL_LIGHTING_BIT;

    private final float lineR, lineG, lineB;
    private final float trailR, trailG, trailB;
    private final float gridR, gridG, gridB;
    private final float borderR, borderG, borderB;
    private PositionAPI position;
    private boolean scanning = true;
    private boolean corners = true;
    /** 扫描进度：0 = 扫描线在顶边，接近 1 = 拖尾刚走出底边。 */
    private float phase;

    public Moci_UIScanFrame(Color base) {
        float[] line = DEFAULT_LINE;
        float[] trail = DEFAULT_TRAIL;
        float[] grid = DEFAULT_GRID;
        float[] border = DEFAULT_BORDER;
        if (base != null) {
            float r = base.getRed() / 255f;
            float g = base.getGreen() / 255f;
            float b = base.getBlue() / 255f;
            float[] rgb = {r, g, b};
            trail = rgb;
            grid = rgb;
            border = rgb;
            line = new float[] {
                    r + (1f - r) * LINE_LIGHTEN,
                    g + (1f - g) * LINE_LIGHTEN,
                    b + (1f - b) * LINE_LIGHTEN};
        }
        lineR = line[0]; lineG = line[1]; lineB = line[2];
        trailR = trail[0]; trailG = trail[1]; trailB = trail[2];
        gridR = grid[0]; gridG = grid[1]; gridB = grid[2];
        borderR = border[0]; borderG = border[1]; borderB = border[2];
    }

    /** 是否画移动的扫描线，默认 true。 */
    public Moci_UIScanFrame setScanning(boolean scanning) {
        this.scanning = scanning;
        return this;
    }

    /** 是否画四角括号，默认 true。 */
    public Moci_UIScanFrame setCorners(boolean corners) {
        this.corners = corners;
        return this;
    }

    @Override public void positionChanged(PositionAPI position) {
        this.position = position;
    }

    @Override public void advance(float amount) {
        if (!(amount > 0f)) return; // 0、负数、NaN 都不走
        phase = (phase + Math.min(amount, MAX_STEP) * SCAN_SPEED) % 1f;
    }

    @Override public void processInput(List<InputEventAPI> events) {
        // 只渲染：不读、也不 consume 任何事件。
    }

    /** 网格画在子组件下面（作底座时在图片下面；作叠加时本面板没有子组件，等于画在前面的图片上面）。 */
    @Override public void renderBelow(float alphaMult) {
        if (position == null) return;
        float w = position.getWidth();
        float h = position.getHeight();
        float a = clamp01(alphaMult) * GRID_ALPHA;
        if (w < GRID || h < GRID || a <= 0f) return;
        float x = position.getX();
        float y = position.getY();
        int cols = (int) (w / GRID);
        int rows = (int) (h / GRID);
        // 网格居中：两侧留白相等（GRID/2 ~ GRID），线不会贴着边框。上下对称，所以 y 翻转不影响位置。
        float mx = (GRID + w - cols * GRID) * 0.5f;
        float my = (GRID + h - rows * GRID) * 0.5f;

        GL11.glPushAttrib(ATTRIB_BITS);
        try {
            begin2D();
            GL11.glColor4f(gridR, gridG, gridB, a);
            GL11.glBegin(GL11.GL_LINES);
            for (int i = 0; i < cols; i++) {
                float gx = x + mx + i * GRID;
                GL11.glVertex2f(gx, y + 1f);
                GL11.glVertex2f(gx, y + h - 1f);
            }
            for (int j = 0; j < rows; j++) {
                float gy = y + my + j * GRID;
                GL11.glVertex2f(x + 1f, gy);
                GL11.glVertex2f(x + w - 1f, gy);
            }
            GL11.glEnd();
        } finally {
            GL11.glPopAttrib();
        }
    }

    /** 扫描线、边框、四角括号画在子组件上面。 */
    @Override public void render(float alphaMult) {
        if (position == null) return;
        float w = position.getWidth();
        float h = position.getHeight();
        float a = clamp01(alphaMult);
        if (w <= 2f || h <= 2f || a <= 0f) return;
        float x = position.getX();
        float y = position.getY();

        GL11.glPushAttrib(ATTRIB_BITS);
        try {
            begin2D();
            if (scanning) drawScan(x, y, w, h, a);

            // 细边框：内缩半个像素，整条线落在面板里面。
            GL11.glColor4f(borderR, borderG, borderB, a * BORDER_ALPHA);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x + 0.5f, y + 0.5f);
            GL11.glVertex2f(x + w - 0.5f, y + 0.5f);
            GL11.glVertex2f(x + w - 0.5f, y + h - 0.5f);
            GL11.glVertex2f(x + 0.5f, y + h - 0.5f);
            GL11.glEnd();

            if (corners) drawCorners(x, y, w, h, a);
        } finally {
            GL11.glPopAttrib();
        }
    }

    private void drawScan(float x, float y, float w, float h, float a) {
        // 屏幕坐标 y 向上，扫描线从顶边 (y + h) 往下走。
        // 比参考多走一个拖尾长度：拖尾完全走出底边才回到顶部，回卷时拖尾不会突然消失；一轮仍是 1 / SCAN_SPEED 秒。
        float center = y + h - phase * (h + TRAIL_LENGTH);
        float top = y + h - 1f;
        float bottom = y + 1f;
        float near = Math.max(bottom, center);
        float far = Math.min(top, center + TRAIL_LENGTH);
        if (far > near) {
            // 被面板边缘截掉的部分按距离算出端点透明度，渐变不会因为截断而变硬。
            float aNear = TRAIL_ALPHA * (1f - (near - center) / TRAIL_LENGTH);
            float aFar = TRAIL_ALPHA * (1f - (far - center) / TRAIL_LENGTH);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glColor4f(trailR, trailG, trailB, a * aNear);
            GL11.glVertex2f(x + 1f, near);
            GL11.glVertex2f(x + w - 1f, near);
            GL11.glColor4f(trailR, trailG, trailB, a * aFar);
            GL11.glVertex2f(x + w - 1f, far);
            GL11.glVertex2f(x + 1f, far);
            GL11.glEnd();
        }
        if (center >= bottom && center <= top) {
            GL11.glColor4f(lineR, lineG, lineB, a * LINE_ALPHA);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(x + 1f, center);
            GL11.glVertex2f(x + w - 1f, center);
            GL11.glEnd();
        }
    }

    private void drawCorners(float x, float y, float w, float h, float a) {
        float len = Math.min(CORNER_LENGTH, Math.min(w, h) * 0.5f);
        float t = Math.min(CORNER_THICKNESS, len);
        float x2 = x + w;
        float y2 = y + h;
        GL11.glColor4f(lineR, lineG, lineB, a * CORNER_ALPHA);
        GL11.glBegin(GL11.GL_QUADS);
        rect(x, y2 - t, len, t);             // 左上：横
        rect(x, y2 - len, t, len - t);       // 左上：竖
        rect(x2 - len, y2 - t, len, t);      // 右上：横
        rect(x2 - t, y2 - len, t, len - t);  // 右上：竖
        rect(x, y, len, t);                  // 左下：横
        rect(x, y + t, t, len - t);          // 左下：竖
        rect(x2 - len, y, len, t);           // 右下：横
        rect(x2 - t, y + t, t, len - t);     // 右下：竖
        GL11.glEnd();
    }

    private static void begin2D() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glShadeModel(GL11.GL_SMOOTH); // 拖尾渐变依赖逐顶点颜色插值
        GL11.glLineWidth(1f);
    }

    /** 往当前 GL_QUADS 批次里加一个矩形（屏幕坐标：左下角 + 宽高）。 */
    private static void rect(float x, float y, float w, float h) {
        if (w <= 0f || h <= 0f) return;
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
    }

    private static float clamp01(float v) {
        return v > 0f ? (v < 1f ? v : 1f) : 0f; // NaN → 0
    }
}
