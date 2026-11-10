package data.campaign.intel.ui;

import java.awt.Color;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * 悬停高亮框：盖在头像/格子上面，鼠标经过时画 2px 亮色边框 + 很淡的填充。
 *
 * <p>只看鼠标移动事件判断是否悬停，<b>绝不 consume 任何事件</b>，底下的 areaCheckbox/按钮照常收到点击。
 * 必须在被盖住的图片之后 addComponent，才能画在图片上面（{@link #attach} 已经这样做）。</p>
 */
public final class Moci_UIHoverFrame extends BaseCustomUIPanelPlugin {
    private static final float BORDER = 2f;
    private static final float FILL_ALPHA = 0.08f;

    private final Color border;
    private PositionAPI position;
    private boolean hovered;
    private boolean enabled = true;

    public Moci_UIHoverFrame() {
        this(null);
    }

    /** border 为 null 时用玩家势力亮色。 */
    public Moci_UIHoverFrame(Color border) {
        this.border = border;
    }

    /** 情报列表用：在 parent 的 (x, y) 处盖一个 w×h 的悬停框。返回悬停框面板。 */
    public static CustomPanelAPI attach(CustomPanelAPI parent, float x, float y, float w, float h) {
        if (parent == null) return null;
        CustomPanelAPI panel = parent.createCustomPanel(w, h, new Moci_UIHoverFrame());
        parent.addComponent(panel).inTL(x, y);
        return panel;
    }

    /** 弹窗用：尺寸和位置都取布局槽位（与被盖住的头像同一个槽位/序号），经 place 放置。 */
    public static CustomPanelAPI attach(CustomPanelAPI parent, Moci_UILayout layout, String slot, int index) {
        if (parent == null || layout == null) return null;
        Moci_UILayout.Rect r = layout.rect(slot, index);
        CustomPanelAPI panel = parent.createCustomPanel(r.w, r.h, new Moci_UIHoverFrame());
        layout.place(parent.addComponent(panel), slot, index);
        return panel;
    }

    /** 取面板上的悬停框插件（不是悬停框返回 null）。 */
    public static Moci_UIHoverFrame of(CustomPanelAPI panel) {
        if (panel == null) return null;
        Object plugin = panel.getPlugin();
        return plugin instanceof Moci_UIHoverFrame ? (Moci_UIHoverFrame) plugin : null;
    }

    /** 模态弹出菜单打开时把底下的悬停框关掉，避免被禁用的头像还在高亮。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) hovered = false;
    }

    public boolean isHovered() {
        return enabled && hovered;
    }

    @Override public void positionChanged(PositionAPI position) {
        this.position = position;
    }

    @Override public void processInput(List<InputEventAPI> events) {
        if (position == null || events == null) return;
        for (InputEventAPI event : events) {
            // 已被别人 consume 的事件不能再读；这里只读不 consume。
            if (event == null || event.isConsumed()) continue;
            if (event.isMouseMoveEvent()) {
                hovered = enabled && position.containsEvent(event);
            }
        }
    }

    @Override public void render(float alphaMult) {
        if (!enabled || !hovered || position == null) return;
        float x = position.getX();
        float y = position.getY();
        float w = position.getWidth();
        float h = position.getHeight();
        if (w <= 0f || h <= 0f) return;
        Color c = border != null ? border : Misc.getBrightPlayerColor();
        float r = c.getRed() / 255f;
        float g = c.getGreen() / 255f;
        float b = c.getBlue() / 255f;
        float a = Math.max(0f, Math.min(1f, alphaMult));
        float t = Math.min(BORDER, Math.min(w, h) * 0.5f);

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            GL11.glColor4f(r, g, b, FILL_ALPHA * a);
            quad(x, y, w, h);

            GL11.glColor4f(r, g, b, 0.9f * a);
            quad(x, y + h - t, w, t);   // 上
            quad(x, y, w, t);           // 下
            quad(x, y + t, t, h - 2f * t);         // 左
            quad(x + w - t, y + t, t, h - 2f * t); // 右
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static void quad(float x, float y, float w, float h) {
        if (w <= 0f || h <= 0f) return;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
    }
}
