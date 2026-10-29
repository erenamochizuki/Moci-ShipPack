package xi.arsenal;

import com.fs.starfarer.api.combat.ViewportAPI;
import java.awt.Color;
import java.util.List;
import java.util.function.IntConsumer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * Pure world-space presentation for immutable samples of actual native attacks.
 * No entity, original sprite, weapon width, native clock or callback is modified.
 * The owner supplies phase and brightness from its normal combat-thread sample.
 */
public final class PsychoOpticalRenderer {
    public static final int MAX_SEGMENTS = 192, MAX_FLARES = 192;
    public static final float MAX_VISUAL_WIDTH = 120f, MAX_FLARE_RADIUS = 128f;
    private static final int STEPS = 24, RADIAL_STEPS = 32, TRANSVERSE_BANDS=12;

    public record Segment(float fromX, float fromY, float toX, float toY, float width,
            float brightness, float phase, Color primary, Color inner, boolean sustained) { }
    public record Flare(float x, float y, float angle, float radius, float brightness,
            Color primary, Color inner, boolean impact) { }

    private PsychoOpticalRenderer() { }

    /** Returns actual visible draw count, not the number of submitted samples. */
    public static int render(List<Segment> segments, ViewportAPI viewport) {
        return render(segments,viewport,null);
    }

    /** Observer receives the input index only after that exact visible segment was drawn. */
    public static int render(List<Segment> segments, ViewportAPI viewport,IntConsumer onDrawIndex) {
        if (segments == null || viewport == null || clamp(viewport.getAlphaMult()) <= 0f) return 0;
        int drawn = 0;
        State guard = null;
        try {
            for (int i = 0; i < Math.min(MAX_SEGMENTS, segments.size()); i++) {
                Segment s = segments.get(i);
                if (!validSegment(s) || !visible(s, viewport)) continue;
                float alpha = clamp(s.brightness) * clamp(viewport.getAlphaMult());
                if (alpha <= .001f) continue;
                if (guard == null) guard = new State();
                drawSegment(s, alpha);
                drawn++;
                if(onDrawIndex!=null)onDrawIndex.accept(i);
            }
        } finally { if (guard != null) guard.close(); }
        return drawn;
    }

    /** Flashes are centred at real sampled muzzle/hit coordinates, never estimated thrusters. */
    public static int renderFlares(List<Flare> flares, ViewportAPI viewport) {
        return renderFlares(flares,viewport,null);
    }

    /** Input index callbacks permit one cross-family draw batch without estimated counters. */
    public static int renderFlares(List<Flare> flares, ViewportAPI viewport,IntConsumer onDrawIndex) {
        if (flares == null || viewport == null || clamp(viewport.getAlphaMult()) <= 0f) return 0;
        int drawn = 0;
        State guard = null;
        try {
            for (int i = 0; i < Math.min(MAX_FLARES, flares.size()); i++) {
                Flare f = flares.get(i);
                if (!validFlare(f)) continue;
                float radius = Math.min(MAX_FLARE_RADIUS, f.radius);
                if (!inside(viewport, f.x - radius, f.y - radius, f.x + radius, f.y + radius)) continue;
                float alpha = clamp(f.brightness) * clamp(viewport.getAlphaMult());
                if (alpha <= .001f) continue;
                if (guard == null) guard = new State();
                drawFlare(f, radius, alpha);
                drawn++;
                if(onDrawIndex!=null)onDrawIndex.accept(i);
            }
        } finally { if (guard != null) guard.close(); }
        return drawn;
    }

    public static boolean validSegment(Segment s) {
        if (s == null || !finite(s.fromX, s.fromY, s.toX, s.toY, s.width, s.brightness, s.phase)
                || s.width <= 0f || s.brightness <= 0f) return false;
        double dx = (double) s.toX - s.fromX, dy = (double) s.toY - s.fromY;
        return dx * dx + dy * dy >= 1.0 && dx * dx + dy * dy <= Float.MAX_VALUE;
    }

    public static boolean validFlare(Flare f) {
        return f != null && finite(f.x, f.y, f.angle, f.radius, f.brightness)
                && f.radius > 0f && f.brightness > 0f;
    }

    /** This bound affects only the overlay, never BeamAPI.setWidth or projectile collision. */
    public static float visualWidth(Segment s) {
        if (!validSegment(s)) return 0f;
        return Math.min(MAX_VISUAL_WIDTH, Math.max(14f, s.width * (s.sustained ? 1.9f : 2.2f) + 10f));
    }

    /** Actual hot-spine presentation width; native damage/collision width remains untouched. */
    public static float coreWidth(Segment s){
        return validSegment(s)?Math.min(36f,Math.max(2.6f,2f+s.width*(s.sustained?.245f:.28f))):0f;
    }

    public static boolean visible(Segment s, ViewportAPI viewport) {
        if (!validSegment(s) || viewport == null) return false;
        float pad = visualWidth(s) * .5f;
        return inside(viewport, Math.min(s.fromX, s.toX) - pad, Math.min(s.fromY, s.toY) - pad,
                Math.max(s.fromX, s.toX) + pad, Math.max(s.fromY, s.toY) + pad);
    }

    private static boolean inside(ViewportAPI viewport, float left, float bottom, float right, float top) {
        float x = viewport.getLLX(), y = viewport.getLLY(), width = viewport.getVisibleWidth(), height = viewport.getVisibleHeight();
        return finite(x, y, width, height) && width > 0 && height > 0
                && right >= x && left <= x + width && top >= y && bottom <= y + height;
    }

    private static void drawSegment(Segment s, float alpha) {
        float dx = s.toX - s.fromX, dy = s.toY - s.fromY;
        float inv = 1f / (float) Math.hypot(dx, dy), nx = -dy * inv, ny = dx * inv;
        float width = visualWidth(s);
        Color primary = color(s.primary, new Color(120, 255, 170));
        Color inner = color(s.inner, Color.WHITE);
        // The same Gaussian cross section and single longitudinal envelope drive every
        // layer. Wide native bolts no longer have a narrow, differently shaped second tail.
        softRibbon(s, dx, dy, nx, ny, width, primary, alpha * .14f);
        softRibbon(s, dx, dy, nx, ny, width * .73f, primary, alpha * .21f);
        softRibbon(s, dx, dy, nx, ny, width * .43f, inner, alpha * .30f);
        float core = coreWidth(s);
        softRibbon(s, dx, dy, nx, ny, core * 2.0f, inner, alpha * .43f);
        softRibbon(s, dx, dy, nx, ny, core, Color.WHITE, alpha * .91f);
        // Phase only animates fine detail. No scrolling sphere or forward ray extension.
        // Detail stays inside the luminous spine. Asymmetric weak threads avoid
        // the parallel cage/diamond silhouette produced by outer filaments.
        float offset = Math.min(4f, core * .34f);
        filament(s, dx, dy, nx, ny, -offset * .72f, s.phase, inner, alpha * .11f);
        filament(s, dx, dy, nx, ny, offset * .33f, s.phase + 1.7f, inner, alpha * .13f);
    }

    private static void softRibbon(Segment s, float dx, float dy, float nx, float ny,
            float width, Color color, float alpha) {
        for (int band = 0; band < TRANSVERSE_BANDS; band++) {
            float side0 = -1f+band*2f/TRANSVERSE_BANDS, side1 = -1f+(band+1)*2f/TRANSVERSE_BANDS;
            float profile0 = transverseProfile(side0), profile1 = transverseProfile(side1);
            GL11.glBegin(GL11.GL_QUAD_STRIP);
            try {
                for (int i = 0; i <= STEPS; i++) {
                    float t = i / (float) STEPS, envelope = envelope(t, s.sustained);
                    float half = width * .5f * spread(t, s.sustained);
                    float x = s.fromX + dx * t, y = s.fromY + dy * t;
                    rgba(color, alpha * envelope * profile0);
                    GL11.glVertex2f(x + nx * half * side0, y + ny * half * side0);
                    rgba(color, alpha * envelope * profile1);
                    GL11.glVertex2f(x + nx * half * side1, y + ny * half * side1);
                }
            } finally { GL11.glEnd(); }
        }
    }

    private static void filament(Segment s, float dx, float dy, float nx, float ny,
            float offset, float phase, Color color, float alpha) {
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        try {
            for (int i = 0; i <= STEPS; i++) {
                float t = i / (float) STEPS, edge = envelope(t, s.sustained);
                float gather = spread(t, s.sustained) * smooth(clamp((1f - t) / .14f));
                float wave = gather * (offset + (float) Math.sin(t * 23f - phase) * Math.min(.35f, Math.abs(offset) * .12f));
                float x = s.fromX + dx * t + nx * wave, y = s.fromY + dy * t + ny * wave;
                float pulse = .68f + .32f * (float) Math.sin(t * 37f - phase * 1.2f);
                float half = .35f * edge;
                rgba(color, alpha * edge * pulse);
                GL11.glVertex2f(x + nx * half, y + ny * half);
                GL11.glVertex2f(x - nx * half, y - ny * half);
            }
        } finally { GL11.glEnd(); }
    }

    private static float envelope(float t, boolean sustained) {return longitudinalProfile(t,sustained);}

    /** Smooth, zero-edged Gaussian; no flat four-sided plate in a large luminous halo. */
    public static float transverseProfile(float side){
        if(!Float.isFinite(side)||Math.abs(side)>=1)return 0;
        // Windowed Gaussian has both zero value and zero slope at the boundary;
        // truncating an unwindowed Gaussian would still leave a faint clipped edge.
        float window=1-side*side;
        return (float)Math.exp(-3.5*side*side)*window*window;
    }

    /** One connected pulse, with no independent second intensity peak or mid-bolt seam. */
    public static float longitudinalProfile(float t,boolean sustained){
        if(!Float.isFinite(t)||t<=0||t>=1)return 0;
        if(sustained)return smooth(clamp(t/.035f))*smooth(clamp((1-t)/.035f));
        return smooth(clamp(t/.18f))*smooth(clamp((1-t)/.18f))
                *(.91f+.09f*(float)Math.sin(Math.PI*t));
    }

    private static float spread(float t, boolean sustained) {return longitudinalSpread(t,sustained);}

    /** Shared geometric/texture envelope; only the actual native segment is covered. */
    public static float longitudinalSpread(float t,boolean sustained){
        if(!Float.isFinite(t)||t<=0||t>=1)return 0;
        if(sustained)return .65f+.35f*smooth(clamp(Math.min(t,1-t)/.08f));
        return smooth(clamp(t/.22f))*smooth(clamp((1-t)/.19f))
                *(.86f+.14f*(float)Math.sin(Math.PI*t));
    }

    private static void drawFlare(Flare f, float radius, float alpha) {
        Color primary = color(f.primary, new Color(120, 255, 170)), inner = color(f.inner, Color.WHITE);
        glow(f.x, f.y, radius, primary, alpha * .24f);
        glow(f.x, f.y, radius * .63f, primary, alpha * .43f);
        glow(f.x, f.y, radius * .33f, inner, alpha * .68f);
        glow(f.x, f.y, radius * .14f, Color.WHITE, alpha * .95f);
        cross(f, radius * .82f, Math.max(.8f, radius * .023f), inner, alpha * .65f, f.angle);
        cross(f, radius * .57f, Math.max(.7f, radius * .017f), primary, alpha * .45f, f.angle + 90f);
        if (f.impact) {
            cross(f, radius * .38f, Math.max(.6f, radius * .014f), inner, alpha * .27f, f.angle + 37f);
            cross(f, radius * .31f, Math.max(.6f, radius * .012f), primary, alpha * .22f, f.angle + 132f);
        }
    }

    private static void glow(float x, float y, float radius, Color color, float alpha) {
        // Several radially fading annuli approximate a compact soft core without a hard ring.
        for (int band = 0; band < 4; band++) {
            float r0 = radius * band / 4f, r1 = radius * (band + 1) / 4f;
            float a0 = square(1f - square(band / 4f)), a1 = square(1f - square((band + 1) / 4f));
            GL11.glBegin(GL11.GL_QUAD_STRIP);
            try {
                for (int i = 0; i <= RADIAL_STEPS; i++) {
                    double angle = Math.PI * 2 * i / RADIAL_STEPS;
                    float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
                    rgba(color, alpha * a0); GL11.glVertex2f(x + c * r0, y + s * r0);
                    rgba(color, alpha * a1); GL11.glVertex2f(x + c * r1, y + s * r1);
                }
            } finally { GL11.glEnd(); }
        }
    }

    private static void cross(Flare f, float length, float width, Color color, float alpha, float angle) {
        double radians = Math.toRadians(angle);
        float dx = (float) Math.cos(radians), dy = (float) Math.sin(radians), nx = -dy, ny = dx;
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        try {
            rgba(color, alpha); GL11.glVertex2f(f.x, f.y);
            rgba(color, 0); GL11.glVertex2f(f.x + dx * length, f.y + dy * length);
            rgba(color, alpha * .65f); GL11.glVertex2f(f.x + nx * width, f.y + ny * width);
            rgba(color, 0); GL11.glVertex2f(f.x - dx * length, f.y - dy * length);
            rgba(color, alpha * .65f); GL11.glVertex2f(f.x - nx * width, f.y - ny * width);
            rgba(color, 0); GL11.glVertex2f(f.x + dx * length, f.y + dy * length);
        } finally { GL11.glEnd(); }
    }

    private static void rgba(Color color, float alpha) {
        GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f,
                clamp(alpha) * color.getAlpha() / 255f);
    }
    private static Color color(Color value, Color fallback) { return value == null ? fallback : value; }
    private static float square(float value) { return value * value; }
    private static float smooth(float value) { return value * value * (3f - 2f * value); }
    private static float clamp(float value) { return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0; }
    private static boolean finite(float... values) { for (float value : values) if (!Float.isFinite(value)) return false; return true; }

    /** Fixed-function draw guard; all texture units and externally installed shaders are preserved. */
    private static final class State implements AutoCloseable {
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        State() {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL20.glUseProgram(0);
            int units = Math.max(1, GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));
            for (int unit = 0; unit < units; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                GL11.glDisable(GL11.GL_TEXTURE_1D); GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL12.GL_TEXTURE_3D);
                GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
                GL11.glDisable(GL11.GL_TEXTURE_GEN_S); GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
                GL11.glDisable(GL11.GL_TEXTURE_GEN_R); GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glDisable(GL11.GL_ALPHA_TEST); GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG); GL11.glDepthMask(false);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            GL11.glShadeModel(GL11.GL_SMOOTH);
        }
        @Override public void close() {
            try { GL11.glPopAttrib(); }
            finally { GL20.glUseProgram(program); GL13.glActiveTexture(activeTexture); }
        }
    }
}
