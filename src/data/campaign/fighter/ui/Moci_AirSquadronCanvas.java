package data.campaign.fighter.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.PositionAPI;

/** Single-ship visual with optional whole-card and slot hit targets, all in virtual UI pixels. */
final class Moci_AirSquadronCanvas extends BaseCustomUIPanelPlugin {
    private final Moci_AirSquadronScanPanel scan;
    private final Moci_AirSquadronArt.Model model;
    private final List<Marker> markers = new ArrayList<>();
    private final float inset, offsetX;
    private final boolean selected;
    private final Runnable click;
    private PositionAPI position;
    private final List<Supplier<PositionAPI>> viewports = new ArrayList<>();
    private String selectedSlot;
    private String hoveredSlot;

    private record Marker(String id, float x, float y, PositionAPI target, Runnable click) {}

    Moci_AirSquadronCanvas(Moci_AirSquadronArt.Model model, boolean scanning, boolean selected,
                           float inset, float offsetX, Runnable click) {
        this(model, scanning, selected, inset, offsetX, click, true);
    }

    Moci_AirSquadronCanvas(Moci_AirSquadronArt.Model model, boolean scanning, boolean selected,
                           float inset, float offsetX, Runnable click, boolean frame) {
        this.model = model; this.selected = selected; this.inset = inset; this.offsetX = offsetX; this.click = click;
        this.scan = new Moci_AirSquadronScanPanel(scanning, frame, selected);
    }

    void clipViewport(Supplier<PositionAPI> viewport) { viewports.add(viewport); }
    void marker(String slotId, float localX, float localY, PositionAPI target, Runnable action) {
        // A hull points right in API coordinates; the displayed sprite points up.
        markers.add(new Marker(slotId, -localY, localX, target, action));
    }

    void focus(String selectedSlot, String hoveredSlot) {
        this.selectedSlot = selectedSlot;
        this.hoveredSlot = hoveredSlot;
    }

    boolean isMarkerActive(String slotId) {
        return slotId != null && (slotId.equals(selectedSlot) || slotId.equals(hoveredSlot));
    }

    /** Text options and image hotspots share the same viewport-clipped hover target. */
    String hitMarker(float mouseX, float mouseY) {
        if (position == null || !insideViewports(mouseX, mouseY)) return null;
        for (Marker marker : markers) {
            if (marker.target != null && contains(marker.target, mouseX, mouseY)) return marker.id;
        }
        Marker nearest = imageMarker(mouseX, mouseY);
        return nearest == null ? null : nearest.id;
    }

    private Marker imageMarker(float mouseX, float mouseY) {
        if (position == null || !contains(position, mouseX, mouseY)) return null;
        Moci_AirSquadronLayout.Transform transform = transform();
        Marker nearest = null;
        float nearestDistance = 14f * 14f;
        for (Marker marker : markers) {
            float dx = mouseX - transform.mapX(marker.x), dy = mouseY - transform.mapY(marker.y);
            float distance = dx * dx + dy * dy;
            if (distance <= nearestDistance) {
                nearest = marker;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private boolean insideViewports(float mouseX, float mouseY) {
        for (Supplier<PositionAPI> viewport : viewports) {
            PositionAPI bounds = viewport.get();
            if (bounds != null && !contains(bounds, mouseX, mouseY)) return false;
        }
        return true;
    }

    @Override public void positionChanged(PositionAPI position) { this.position = position; scan.positionChanged(position); }
    @Override public void advance(float amount) { scan.advance(amount); }
    @Override public void renderBelow(float alpha) {
        scan.renderBelow(alpha);
    }

    private Moci_AirSquadronLayout.Transform transform() {
        return model.fit(position.getX() + inset + offsetX, position.getY() + inset,
                Math.max(1, position.getWidth() - 2 * inset - Math.abs(offsetX)), Math.max(1, position.getHeight() - 2 * inset));
    }

    @Override public void render(float alpha) {
        if (position == null) return;
        Moci_AirSquadronLayout.Transform transform = transform();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try { model.render(transform, alpha); } finally { GL11.glPopAttrib(); }
        scan.render(alpha);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            for (Marker marker : markers) {
                float x = transform.mapX(marker.x), y = transform.mapY(marker.y);
                boolean chosen = Objects.equals(marker.id, selectedSlot);
                boolean active = isMarkerActive(marker.id);
                prepare(alpha * (active ? 1f : 0.60f));
                GL11.glLineWidth(active ? 2f : 1f);
                if (active && marker.target != null) {
                    // Parent's native scissor remains active: long callouts cannot escape the scrolling canvas.
                    float tx = marker.target.getX(), ty = marker.target.getCenterY();
                    GL11.glBegin(GL11.GL_LINE_STRIP);
                    GL11.glVertex2f(x, y); GL11.glVertex2f(x + 12f, y); GL11.glVertex2f(tx, ty);
                    GL11.glEnd();
                }
                ring(x, y, active ? 8f : 6f);
                if (chosen) ring(x, y, 11f);
            }
        } finally { GL11.glPopAttrib(); }
    }

    @Override public void processInput(List<InputEventAPI> events) {
        if (position == null) return;
        for (InputEventAPI event : events) {
            if (event.isConsumed() || !event.isLMBDownEvent() || !contains(position, event.getX(), event.getY())
                    || !insideViewports(event.getX(), event.getY())) continue;
            Marker nearest = imageMarker(event.getX(), event.getY());
            if (nearest != null) { nearest.click.run(); event.consume(); }
            else if (click != null) { click.run(); event.consume(); }
        }
    }

    private static boolean contains(PositionAPI bounds, float mouseX, float mouseY) {
        return Moci_AirSquadronLayout.rectHit(mouseX, mouseY, bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
    }

    private static void prepare(float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0.40f, 0.96f, 0.81f, alpha);
    }
    private static void ring(float x, float y, float radius) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2d * i / 24d;
            GL11.glVertex2f(x + (float) Math.cos(a) * radius, y + (float) Math.sin(a) * radius);
        }
        GL11.glEnd();
    }
}
