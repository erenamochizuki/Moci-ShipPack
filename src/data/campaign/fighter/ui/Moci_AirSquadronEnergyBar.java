package data.campaign.fighter.ui;

import java.awt.Color;
import org.lwjgl.opengl.GL11;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.ui.PositionAPI;
import data.campaign.fighter.Moci_FighterRefitBudget.Budget;

/** 分格显示改造值占原始 OP 容量的比例；格数仅影响显示，不改变改装成本。 */
final class Moci_AirSquadronEnergyBar extends BaseCustomUIPanelPlugin {
    // 格子数量：20 = 每格 5%，10 = 每格 10%；必须大于 0。
    static final int SEGMENTS = 20;
    // 格间距（UI 像素）；总宽不变时，增加格数/间距会使单格变窄。
    // 单格宽 = (总宽 - 间距 × (格数 - 1)) / 格数；宽度须大于 2 才能绘制。
    static final float SEGMENT_GAP = 2f;
    // 总宽、高度和位置在 Moci_AirSquadronLayout 的 ENERGY_BAR_* 参数中调整。
    static final Color BLUE = new Color(74, 156, 242);
    static final Color RED = new Color(238, 83, 95);
    private final float ratio;
    private final Color color;
    private PositionAPI position;

    Moci_AirSquadronEnergyBar(Budget budget) {
        ratio = budget == null ? 0f : budget.usageRatio();
        color = budget != null && budget.overLimit() ? RED : BLUE;
    }

    Color color() { return color; }
    float segmentFill(int index) {
        if (index < 0 || index >= SEGMENTS) throw new IllegalArgumentException("Invalid segment");
        return Math.max(0f, Math.min(1f, ratio * SEGMENTS - index));
    }
    @Override public void positionChanged(PositionAPI position) { this.position = position; }

    @Override public void renderBelow(float alpha) {
        if (position == null) return;
        float x = position.getX(), y = position.getY(), h = position.getHeight();
        float gap = SEGMENT_GAP, w = (position.getWidth() - gap * (SEGMENTS - 1)) / SEGMENTS;
        if (w <= 2f || h <= 2f) return;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glLineWidth(1f);
            for (int i = 0; i < SEGMENTS; i++) {
                float left = x + i * (w + gap);
                tint(alpha * 0.14f); rect(left, y, w, h);
                float fill = segmentFill(i);
                if (fill > 0f) { tint(alpha * 0.90f); rect(left + 1f, y + 1f, (w - 2f) * fill, h - 2f); }
                tint(alpha * 0.70f);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(left, y); GL11.glVertex2f(left + w, y);
                GL11.glVertex2f(left + w, y + h); GL11.glVertex2f(left, y + h);
                GL11.glEnd();
            }
        } finally { GL11.glPopAttrib(); }
    }
    private void tint(float alpha) {
        GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, alpha);
    }
    private static void rect(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }
}
