package data.campaign.intel.ui;

import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/** 苍钢心智模型的全息光带：把共边蜂巢簇和连接线投影成椭圆，光头和余辉沿同一条闭合轨道传播。 */
final class Moci_UIArpHexFlow {
    static final float PERIOD = 12f;
    private static final int GROUPS = 12, STRIDE = 7, COLUMNS = GROUPS * STRIDE;
    private static final float[] CORNER_COL = {0f, 0.5f, 0.5f, 0f, -0.5f, -0.5f};
    private static final float[] CORNER_HEIGHT = {1f, 0.5f, -0.5f, -1f, -0.5f, 0.5f};
    private static final int LEFT_EDGE = 1 << 4, BOTTOM_EDGES = (1 << 2) | (1 << 3), TOP_EDGES = 1 | (1 << 5);
    private static final Track UPPER = new Track(1.42f, 0.46f, 0.22f, 0f);
    private static final Track LOWER = new Track(1.34f, 0.46f, -0.24f, 0.047f);

    private Moci_UIArpHexFlow() {}

    static void draw(float cx, float cy, float outer, float sideRoom, float phase, int tracks, float alpha, float pixelScale) {
        float scale = Math.min(outer - 3f, (sideRoom - 3f) / (UPPER.width + 2.5f * UPPER.cellRadius));
        if (scale < 28f || tracks == 0) return;
        if (tracks == 2) UPPER.draw(cx, cy, scale, phase, alpha, pixelScale);
        LOWER.draw(cx, cy, scale, 1.14f - phase, alpha, pixelScale);
    }

    private static final class Track {
        final float width, rise, elevation, offset, cellRadius;
        final Vertex[] wire, inlays;

        Track(float width, float rise, float elevation, float offset) {
            this.width = width;
            this.rise = rise;
            this.elevation = elevation;
            this.offset = offset;
            cellRadius = (float) (2.0 * Math.PI / (COLUMNS * Math.sqrt(3.0))) * width;
            List<Vertex> edges = new ArrayList<>(), fills = new ArrayList<>();
            for (int group = 0; group < GROUPS; group++) {
                int start = group * STRIDE, count = group % 3 == 1 ? 2 : 3;
                for (int col = 0; col < count; col++) {
                    hex(edges, fills, start + col, 0f, col == 0 ? 0 : LEFT_EDGE, false);
                }
                if (group % 3 != 2) hex(edges, fills, start + 0.5f, 1.5f, BOTTOM_EDGES, group % 2 == 0);
                if (group % 3 != 0) hex(edges, fills, start + count - 1.5f, -1.5f, TOP_EDGES, group % 2 != 0);
                Vertex last = vertex(start + count - 0.5f, 0f);
                for (int col = count; col < STRIDE; col++) {
                    Vertex next = vertex(start + col + 0.5f, 0f);
                    edges.add(last);
                    edges.add(next);
                    last = next;
                }
            }
            wire = edges.toArray(new Vertex[0]);
            inlays = fills.toArray(new Vertex[0]);
        }

        private void hex(List<Vertex> edges, List<Vertex> fills, float col, float height, int sharedEdges, boolean inlay) {
            Vertex[] corners = new Vertex[6];
            for (int side = 0; side < 6; side++) corners[side] = vertex(col + CORNER_COL[side], height + CORNER_HEIGHT[side]);
            for (int side = 0; side < 6; side++) {
                if ((sharedEdges & (1 << side)) != 0) continue;
                edges.add(corners[side]);
                edges.add(corners[(side + 1) % 6]);
            }
            if (inlay) {
                for (int side = 1; side < 5; side++) {
                    fills.add(corners[0]);
                    fills.add(corners[side]);
                    fills.add(corners[side + 1]);
                }
            }
        }

        private Vertex vertex(float col, float height) {
            float turn = col / COLUMNS + offset;
            double angle = turn * Math.PI * 2.0;
            float front = (float) Math.cos(angle), side = (float) Math.sin(angle), depth = (1f + front) * 0.5f;
            float tangentX = width * front, tangentY = rise * side + 0.057f * (float) Math.cos(angle * 3.0);
            float tangentLength = (float) Math.hypot(tangentX, tangentY);
            float across = height * cellRadius * (0.66f + 0.34f * depth) * (0.6f / tangentLength + 0.4f / width);
            float horizontal = width * side - tangentY * across;
            float vertical = elevation - rise * front + 0.019f * (float) Math.sin(angle * 3.0) + tangentX * across;
            return new Vertex(horizontal, vertical, fraction(turn * 3f), 0.56f + 0.44f * depth);
        }

        void draw(float cx, float cy, float scale, float phase, float alpha, float pixelScale) {
            float head = fraction(phase * 3f);
            GL11.glBegin(GL11.GL_TRIANGLES);
            for (Vertex vertex : inlays) {
                float light = vertex.light(head);
                GL11.glColor4f(0.08f, 0.87f, 1f, alpha * vertex.depth * light * light * 0.46f);
                GL11.glVertex2f(cx + scale * vertex.horizontal, cy + scale * vertex.vertical);
            }
            GL11.glEnd();
            strokes(cx, cy, scale, head, alpha, 6f * pixelScale, 0.07f, 0.17f);
            strokes(cx, cy, scale, head, alpha, 2.8f * pixelScale, 0.13f, 0.22f);
            strokes(cx, cy, scale, head, alpha, 1.1f * pixelScale, 0.52f, 0.44f);
        }

        private void strokes(float cx, float cy, float scale, float head, float alpha, float thickness, float base, float peak) {
            GL11.glLineWidth(thickness);
            GL11.glBegin(GL11.GL_LINES);
            for (Vertex vertex : wire) {
                float light = vertex.light(head);
                GL11.glColor4f(0.16f + 0.62f * light, 0.85f + 0.15f * light, 1f,
                        alpha * vertex.depth * (base + peak * light));
                GL11.glVertex2f(cx + scale * vertex.horizontal, cy + scale * vertex.vertical);
            }
            GL11.glEnd();
        }
    }

    private static final class Vertex {
        final float horizontal, vertical, phase, depth;

        Vertex(float horizontal, float vertical, float phase, float depth) {
            this.horizontal = horizontal;
            this.vertical = vertical;
            this.phase = phase;
            this.depth = depth;
        }

        float light(float head) {
            float gap = head - phase;
            if (gap < 0f) gap += 1f;
            float pulse = Math.max(0f, Math.max(1f - gap * 2.6f, 1f - (1f - gap) * 15f));
            return pulse * pulse;
        }
    }

    private static float fraction(float value) {
        return value - (float) Math.floor(value);
    }
}
