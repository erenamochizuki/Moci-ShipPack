package data.campaign.fighter.ui;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.ui.PositionAPI;

/** Stateless with respect to campaign assets: only cached bounds and animation time are rendered. */
final class Moci_AirSquadronScanPanel extends BaseCustomUIPanelPlugin {
    private final boolean scanning;
    private final boolean frame;
    /** 是否为「已选中」状态：用更亮的底色和更粗的描边做明显高亮。 */
    private final boolean highlight;
    private PositionAPI position;
    private float phase;

    Moci_AirSquadronScanPanel(boolean scanning) { this(scanning, true, false); }
    Moci_AirSquadronScanPanel(boolean scanning, boolean frame, boolean highlight) {
        this.scanning = scanning;
        this.frame = frame;
        this.highlight = highlight;
    }

    @Override public void positionChanged(PositionAPI position) { this.position = position; }
    @Override public void advance(float amount) { phase = (phase + amount * 0.16f) % 1f; }

    @Override public void renderBelow(float alpha) {
        if (position == null) return;
        float x = position.getX(), y = position.getY(), w = position.getWidth(), h = position.getHeight();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (frame || scanning) {
                if (highlight) GL11.glColor4f(0.10f, 0.42f, 0.36f, alpha * 0.92f);
                else GL11.glColor4f(0.005f, 0.015f, 0.021f, alpha * 0.98f);
                rect(x, y, w, h);
            }
            GL11.glColor4f(0.14f, 0.60f, 0.51f, alpha * 0.17f);
            if (scanning) {
                GL11.glBegin(GL11.GL_LINES);
                for (float gx = x + 24f; gx < x + w; gx += 32f) { GL11.glVertex2f(gx, y); GL11.glVertex2f(gx, y + h); }
                for (float gy = y + 24f; gy < y + h; gy += 32f) { GL11.glVertex2f(x, gy); GL11.glVertex2f(x + w, gy); }
                GL11.glEnd();
            }
            if (frame) {
                GL11.glLineWidth(highlight ? 3f : 1f);
                if (highlight) GL11.glColor4f(0.44f, 0.99f, 0.87f, alpha);
                else GL11.glColor4f(0.24f, 0.66f, 0.63f, alpha * 0.65f);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(x, y); GL11.glVertex2f(x + w, y);
                GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x, y + h);
                GL11.glEnd();
            }
        } finally { GL11.glPopAttrib(); }
    }

    @Override public void render(float alpha) {
        if (!scanning || position == null) return;
        float x = position.getX(), y = position.getY(), w = position.getWidth(), h = position.getHeight();
        float center = y + h * (1f - phase);
        // Vertices are clipped to this canvas in logical coordinates. Keep any parent scissor intact.
        float bottom = Math.max(y + 1f, center - 1f);
        float top = Math.min(y + h - 1f, center + 48f);
        if (top <= bottom) return;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            // The trail stays above the downward-moving leading edge and fades towards its tail.
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glColor4f(0.10f, 0.86f, 0.59f, alpha * 0.13f);
            GL11.glVertex2f(x + 1f, bottom); GL11.glVertex2f(x + w - 1f, bottom);
            GL11.glColor4f(0.10f, 0.86f, 0.59f, 0f);
            GL11.glVertex2f(x + w - 1f, top); GL11.glVertex2f(x + 1f, top);
            GL11.glEnd();
            GL11.glColor4f(0.25f, 0.95f, 0.70f, alpha * 0.37f);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(x + 1f, center); GL11.glVertex2f(x + w - 1f, center);
            GL11.glEnd();
        } finally { GL11.glPopAttrib(); }
    }

    private static void rect(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x + w, y); GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }
}
