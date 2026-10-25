package data.campaign.fighter.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.PositionAPI;

/** Direct icon button shared by tactical hullmods and weapons; one position owns drawing and input. */
final class Moci_AirSquadronWeaponTile extends BaseCustomUIPanelPlugin {
    private static final Color ACCENT = new Color(112, 228, 207);
    private static final Color BORDER = new Color(112, 228, 207, 90);
    private final Moci_AirSquadronArt.Model model;
    private final Color fill;
    private final boolean selected;
    private final float inset;
    private final BooleanSupplier enabled;
    private final Runnable click;
    private final List<Supplier<PositionAPI>> viewports = new ArrayList<>();
    private PositionAPI position;
    private boolean hovered;

    Moci_AirSquadronWeaponTile(Moci_AirSquadronArt.Model model, Color fill, boolean selected,
                             float inset, BooleanSupplier enabled, Runnable click) {
        this.model = model;
        this.fill = fill;
        this.selected = selected;
        this.inset = inset;
        this.enabled = enabled;
        this.click = click;
    }

    @Override public void positionChanged(PositionAPI position) { this.position = position; }

    void clipViewport(Supplier<PositionAPI> viewport) { viewports.add(viewport); }

    boolean hit(float x, float y) {
        if (position == null || !contains(position, x, y)) return false;
        for (Supplier<PositionAPI> viewport : viewports) {
            PositionAPI bounds = viewport.get();
            if (bounds != null && !contains(bounds, x, y)) return false;
        }
        return true;
    }

    private static boolean contains(PositionAPI bounds, float x, float y) {
        return Moci_AirSquadronLayout.rectHit(x, y, bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
    }

    @Override public void advance(float amount) {
        hovered = enabled.getAsBoolean() && Global.getSettings() != null
                && hit(Global.getSettings().getMouseX(), Global.getSettings().getMouseY());
    }

    @Override public void processInput(List<InputEventAPI> events) {
        if (!enabled.getAsBoolean()) return;
        for (InputEventAPI event : events) {
            if (event.isConsumed() || !event.isLMBDownEvent() || !hit(event.getX(), event.getY())) continue;
            click.run();
            event.consume();
            return;
        }
    }

    @Override public void renderBelow(float alpha) {
        if (position == null) return;
        float x = position.getX(), y = position.getY();
        float w = position.getWidth(), h = position.getHeight();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (fill != null && fill.getAlpha() > 0) {
                GL11.glColor4f(fill.getRed() / 255f, fill.getGreen() / 255f,
                        fill.getBlue() / 255f, alpha * fill.getAlpha() / 255f);
                rect(x, y, w, h);
            }
            Color border = selected || hovered ? ACCENT : (fill == null ? BORDER : fill);
            GL11.glLineWidth(selected || hovered ? 2f : 1f);
            GL11.glColor4f(border.getRed() / 255f, border.getGreen() / 255f,
                    border.getBlue() / 255f, alpha * (selected || hovered ? 0.95f : 0.70f));
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x, y); GL11.glVertex2f(x + w, y);
            GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x, y + h);
            GL11.glEnd();
        } finally { GL11.glPopAttrib(); }
    }

    @Override public void render(float alpha) {
        if (position == null || model == null) return;
        Moci_AirSquadronLayout.Transform transform = model.fit(
                position.getX() + inset, position.getY() + inset,
                Math.max(1f, position.getWidth() - 2f * inset), Math.max(1f, position.getHeight() - 2f * inset));
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try { model.render(transform, alpha); } finally { GL11.glPopAttrib(); }
    }

    private static void rect(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }
}
