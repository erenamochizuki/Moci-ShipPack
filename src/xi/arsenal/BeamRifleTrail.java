package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.combat.entities.MovingRay;
import java.awt.Color;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

/** Read-only world-layer overlay for real rifle rays. Owns no plugin, clock, entity or damage. */
public final class BeamRifleTrail {
    public static final String WEAPON_ID = "Moci_fw_xi_beam_rifle";
    public static final String PROJECTILE_ID = "Moci_fw_xi_beam_rifle_shot";
    public static final int MAX_RAYS = 64;
    private static final int SEGMENTS = 20;
    private static final float OUTER_WIDTH = 48f;

    private BeamRifleTrail() { }

    /**
     * Call once from the owning layered renderer, in its normal world-coordinate projection.
     * Both ends come directly from the native MovingRay; no estimated tail, muzzle or head sprite.
     */
    public static void render(CombatEngineAPI engine, WeaponAPI weapon, ViewportAPI viewport) {
        if (engine == null || weapon == null || viewport == null || !BeamRiflePalette.isOwnRifle(weapon.getId())) return;
        BeamRiflePalette palette = BeamRiflePalette.forWeapon(weapon.getId());
        float viewAlpha = clamp(viewport.getAlphaMult());
        if (viewAlpha <= 0f) return;
        float time = engine.getTotalElapsedTime(false);
        int drawn = 0;
        boolean pushed = false;
        try {
            for (DamagingProjectileAPI projectile : engine.getProjectiles()) {
                if (projectile.getWeapon() != weapon || projectile.getSource() != weapon.getShip()
                        || !palette.projectileId.equals(projectile.getProjectileSpecId())
                        || !(projectile instanceof MovingRay ray) || ray.isExpired()) continue;
                Vector2f from = ray.getFrom(), to = ray.getTo();
                if (!finite(from) || !finite(to) || !visible(viewport, from, to)) continue;
                float dx = to.x - from.x, dy = to.y - from.y;
                float lengthSquared = dx * dx + dy * dy;
                if (!Float.isFinite(lengthSquared) || lengthSquared < 16f) continue;
                float alpha = clamp(ray.getBrightness()) * viewAlpha;
                if (alpha <= .001f) continue;
                if (drawn++ >= MAX_RAYS) break;
                if (!pushed) {
                    GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                    pushed = true;
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glDisable(GL11.GL_ALPHA_TEST);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    GL11.glDisable(GL11.GL_CULL_FACE);
                    GL11.glDisable(GL11.GL_LIGHTING);
                    GL11.glDepthMask(false);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                    GL11.glShadeModel(GL11.GL_SMOOTH);
                }
                float inverseLength = 1f / (float) Math.sqrt(lengthSquared);
                float nx = -dy * inverseLength, ny = dx * inverseLength;
                // Symmetric full-length bloom: neither end receives a bright moving orb.
                softRibbon(from, dx, dy, nx, ny, OUTER_WIDTH, palette.primary, .08f * alpha);
                softRibbon(from, dx, dy, nx, ny, 36f, palette.primary, .12f * alpha);
                softRibbon(from, dx, dy, nx, ny, 28f, palette.inner, .12f * alpha);
                ribbon(from, dx, dy, nx, ny, 5.5f, 1f, 1f, 1f, .82f * alpha);
                // getFrom is the travelling bolt's rear. Fill that rear third and
                // gather its strands to a single point without altering its front.
                tailCore(from, dx, dy, nx, ny, 24f, palette.inner, .24f * alpha);
                tailCore(from, dx, dy, nx, ny, 12f, Color.WHITE, .70f * alpha);
                Vector2f spawn = ray.getSpawnLocation();
                float phase = time * 18f + ray.getElapsed() * 7f;
                if (finite(spawn)) phase += spawn.x * .017f + spawn.y * .013f;
                filament(from, dx, dy, nx, ny, -6f, phase, .40f * alpha, palette.inner);
                filament(from, dx, dy, nx, ny, 6f, phase + 2.1f, .40f * alpha, palette.inner);
                // Two faint outer strands add electrical shimmer along the real segment.
                // Like the inner filaments, both ends taper equally; there is no head orb.
                filament(from, dx, dy, nx, ny, -11f, phase + 1.2f, .18f * alpha, palette.spark);
                filament(from, dx, dy, nx, ny, 11f, phase + 3.4f, .18f * alpha, palette.spark);
            }
        } finally {
            if (pushed) GL11.glPopAttrib();
        }
    }

    private static void colouredRibbon(Vector2f from, float dx, float dy, float nx, float ny,
                               float width, Color color, float alpha) {
        ribbon(from, dx, dy, nx, ny, width, color.getRed() / 255f,
                color.getGreen() / 255f, color.getBlue() / 255f, alpha);
    }

    /** Smooth transverse falloff; no opaque parallel shell around the pulse. */
    private static void softRibbon(Vector2f from, float dx, float dy, float nx, float ny,
                                   float width, Color color, float alpha) {
        for (int band = 0; band < 4; band++) {
            float side0 = band * .5f - 1f, side1 = side0 + .5f;
            float profile0 = (1f - side0 * side0) * (1f - side0 * side0);
            float profile1 = (1f - side1 * side1) * (1f - side1 * side1);
            GL11.glBegin(GL11.GL_QUAD_STRIP);
            try {
                for (int i = 0; i <= SEGMENTS; i++) {
                    float t = i / (float) SEGMENTS;
                    float edge = envelope(t), half = width * .5f * tailSpread(t);
                    float x = from.x + dx * t, y = from.y + dy * t;
                    GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f,
                            color.getBlue() / 255f, alpha * edge * profile0);
                    GL11.glVertex2f(x + nx * half * side0, y + ny * half * side0);
                    GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f,
                            color.getBlue() / 255f, alpha * edge * profile1);
                    GL11.glVertex2f(x + nx * half * side1, y + ny * half * side1);
                }
            } finally { GL11.glEnd(); }
        }
    }

    private static void ribbon(Vector2f from, float dx, float dy, float nx, float ny,
                               float width, float red, float green, float blue, float alpha) {
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        try {
            for (int i = 0; i <= SEGMENTS; i++) {
                float t = i / (float) SEGMENTS;
                float edge = envelope(t), half = width * .5f * (.45f + .55f * edge) * tailSpread(t);
                float x = from.x + dx * t, y = from.y + dy * t;
                GL11.glColor4f(red, green, blue, alpha * edge);
                GL11.glVertex2f(x + nx * half, y + ny * half);
                GL11.glVertex2f(x - nx * half, y - ny * half);
            }
        } finally { GL11.glEnd(); }
    }

    private static void filament(Vector2f from, float dx, float dy, float nx, float ny,
                                 float offset, float phase, float alpha, Color color) {
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        try {
            for (int i = 0; i <= SEGMENTS; i++) {
                float t = i / (float) SEGMENTS;
                float edge = envelope(t);
                float wave = smooth(clamp(t / .32f))
                        * (offset + 1.1f * (float) Math.sin(t * 21f - phase));
                float x = from.x + dx * t + nx * wave, y = from.y + dy * t + ny * wave;
                float pulse = .55f + .45f * (float) Math.sin(t * 35f - phase * 1.25f);
                GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f,
                        color.getBlue() / 255f, alpha * edge * pulse);
                GL11.glVertex2f(x + nx * .7f, y + ny * .7f);
                GL11.glVertex2f(x - nx * .7f, y - ny * .7f);
            }
        } finally { GL11.glEnd(); }
    }

    private static float envelope(float t) {
        // Identical treatment of both ends; the long center remains evenly luminous.
        return clamp(Math.min(t, 1f - t) / .12f);
    }

    private static float tailSpread(float t) { return .10f + .90f * smooth(clamp(t / .24f)); }
    private static float smooth(float t) { return t * t * (3f - 2f * t); }

    /** Rear-only soft spine; its front end dissolves into the unchanged body. */
    private static void tailCore(Vector2f from, float dx, float dy, float nx, float ny,
                                 float width, Color color, float alpha) {
        for (int band = 0; band < 2; band++) {
            float side0 = band - 1f, side1 = side0 + 1f;
            GL11.glBegin(GL11.GL_QUAD_STRIP);
            try {
                for (int i = 0; i <= 12; i++) {
                    float t = .36f * i / 12f;
                    float weight = clamp(t / .065f) * (1f - smooth(clamp((t - .22f) / .14f)));
                    float half = width * .5f * smooth(clamp(t / .28f));
                    float x = from.x + dx * t, y = from.y + dy * t;
                    GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f,
                            color.getBlue() / 255f, alpha * weight * (1f - Math.abs(side0)));
                    GL11.glVertex2f(x + nx * half * side0, y + ny * half * side0);
                    GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f,
                            color.getBlue() / 255f, alpha * weight * (1f - Math.abs(side1)));
                    GL11.glVertex2f(x + nx * half * side1, y + ny * half * side1);
                }
            } finally { GL11.glEnd(); }
        }
    }

    private static boolean visible(ViewportAPI viewport, Vector2f from, Vector2f to) {
        float padding = OUTER_WIDTH;
        return Math.max(from.x, to.x) + padding >= viewport.getLLX()
                && Math.min(from.x, to.x) - padding <= viewport.getLLX() + viewport.getVisibleWidth()
                && Math.max(from.y, to.y) + padding >= viewport.getLLY()
                && Math.min(from.y, to.y) - padding <= viewport.getLLY() + viewport.getVisibleHeight();
    }

    private static boolean finite(Vector2f point) {
        return point != null && Float.isFinite(point.x) && Float.isFinite(point.y);
    }

    private static float clamp(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }
}
