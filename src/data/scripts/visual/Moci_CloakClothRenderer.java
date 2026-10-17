package data.scripts.visual;

import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

/**
 * 全新斗篷渲染器（布料节点 + OpenGL三角带贴图）
 */
public class Moci_CloakClothRenderer extends BaseCombatLayeredRenderingPlugin {

    private static final float VIEWPORT_MARGIN = 500f;
    private static final float MAX_SUBSTEP = 1f / 60f;

    private final ConcurrentHashMap<String, CloakState> states;

    public static final class ClothPoint {
        public final Vector2f pos = new Vector2f();
        public final Vector2f prev = new Vector2f();
    }

    public static final class CloakState {
        public ShipAPI ship;
        public SpriteAPI texture;

        public final List<ClothPoint> points = new ArrayList<ClothPoint>();

        // 几何参数
        public float length = 240f;
        public float frontWidth = 68f;
        public float endWidth = 156f;
        public float alphaMult = 1f;

        // 物理参数
        public int constraintIterations = 5;
        public float damping = 0.055f;            // 速度衰减
        public float gravity = 35f;               // 向下重力
        public float baselineBackPull = 95f;      // 向后拉力
        public float velocityInfluence = 0.32f;   // 速度影响
        public float flutterAmplitude = 28f;      // 抖动强度
        public float flutterFrequency = 5.4f;     // 抖动频率
        public float bendRelax = 0.28f;           // 弯曲松弛
        public float hullCollisionPadding = 10f;  // 船体碰撞留边

        public float seed = (float) (Math.random() * Math.PI * 2f);
        public float simTime = 0f;

        public boolean initialized = false;
        public boolean valid = true;

        private final Vector2f tmpA = new Vector2f();
        private final Vector2f tmpB = new Vector2f();
        private final Vector2f tmpC = new Vector2f();

        public void resetChain(Vector2f anchor, float backFacing) {
            if (points.size() < 2) {
                return;
            }
            float segLen = Math.max(1f, length / (points.size() - 1f));
            float rad = (float) Math.toRadians(backFacing);
            float dirX = (float) Math.cos(rad);
            float dirY = (float) Math.sin(rad);
            for (int i = 0; i < points.size(); i++) {
                ClothPoint p = points.get(i);
                float d = i * segLen;
                p.pos.set(anchor.x + dirX * d, anchor.y + dirY * d);
                p.prev.set(p.pos);
            }
            initialized = true;
        }

        public void simulate(float amount, Vector2f anchor, float backFacing) {
            if (points.size() < 2) {
                return;
            }
            if (!initialized) {
                resetChain(anchor, backFacing);
            }

            int steps = Math.max(1, (int) Math.ceil(amount / MAX_SUBSTEP));
            float dt = amount / steps;
            float segLen = Math.max(1f, length / (points.size() - 1f));

            for (int step = 0; step < steps; step++) {
                simTime += dt;
                integrate(dt, anchor, backFacing);
                solveConstraints(segLen, anchor, dt);
                solveHullCollision();
            }
        }

        private void integrate(float dt, Vector2f anchor, float backFacing) {
            ClothPoint root = points.get(0);
            root.pos.set(anchor);
            root.prev.set(anchor);

            float keep = Math.max(0f, 1f - damping);

            float backRad = (float) Math.toRadians(backFacing);
            float backX = (float) Math.cos(backRad);
            float backY = (float) Math.sin(backRad);

            Vector2f shipVel;
            if (ship != null) {
                shipVel = ship.getVelocity();
            } else {
                tmpA.set(0f, 0f);
                shipVel = tmpA;
            }
            float flowX = backX * baselineBackPull - shipVel.x * velocityInfluence;
            float flowY = backY * baselineBackPull - shipVel.y * velocityInfluence;

            for (int i = 1; i < points.size(); i++) {
                ClothPoint p = points.get(i);

                float vx = (p.pos.x - p.prev.x) * keep;
                float vy = (p.pos.y - p.prev.y) * keep;
                p.prev.set(p.pos);

                float t = i / (float) (points.size() - 1);
                float flutter = (float) Math.sin(simTime * flutterFrequency + seed + i * 0.72f) * flutterAmplitude * t;
                float sideX = -backY;
                float sideY = backX;

                float ax = flowX * (0.5f + t * 1.1f) + sideX * flutter;
                float ay = flowY * (0.5f + t * 1.1f) + sideY * flutter - gravity;

                p.pos.x += vx + ax * dt * dt;
                p.pos.y += vy + ay * dt * dt;
            }
        }

        private void solveConstraints(float segLen, Vector2f anchor, float dt) {
            int iter = Math.max(1, constraintIterations);

            for (int k = 0; k < iter; k++) {
                points.get(0).pos.set(anchor);

                // 长度约束（相邻点距离）
                for (int i = 1; i < points.size(); i++) {
                    ClothPoint a = points.get(i - 1);
                    ClothPoint b = points.get(i);
                    tmpA.set(b.pos.x - a.pos.x, b.pos.y - a.pos.y);
                    float dist = tmpA.length();
                    if (dist < 0.0001f) {
                        continue;
                    }
                    float diff = (dist - segLen) / dist;

                    if (i == 1) {
                        b.pos.x -= tmpA.x * diff;
                        b.pos.y -= tmpA.y * diff;
                    } else {
                        float half = diff * 0.5f;
                        a.pos.x += tmpA.x * half;
                        a.pos.y += tmpA.y * half;
                        b.pos.x -= tmpA.x * half;
                        b.pos.y -= tmpA.y * half;
                    }
                }

                // 弯曲松弛（抑制抖折）
                for (int i = 1; i < points.size() - 1; i++) {
                    ClothPoint a = points.get(i - 1);
                    ClothPoint b = points.get(i);
                    ClothPoint c = points.get(i + 1);
                    float targetX = (a.pos.x + c.pos.x) * 0.5f;
                    float targetY = (a.pos.y + c.pos.y) * 0.5f;
                    b.pos.x += (targetX - b.pos.x) * bendRelax * dt * 60f;
                    b.pos.y += (targetY - b.pos.y) * bendRelax * dt * 60f;
                }

                points.get(0).pos.set(anchor);
            }
        }

        private void solveHullCollision() {
            if (ship == null) {
                return;
            }
            Vector2f center = ship.getLocation();
            float minR = Math.max(20f, ship.getCollisionRadius() * 0.55f) + hullCollisionPadding;

            for (int i = 1; i < points.size(); i++) {
                ClothPoint p = points.get(i);
                tmpB.set(p.pos.x - center.x, p.pos.y - center.y);
                float d = tmpB.length();
                if (d > 0.0001f && d < minR) {
                    float scale = minR / d;
                    p.pos.set(center.x + tmpB.x * scale, center.y + tmpB.y * scale);
                }
            }
        }
    }

    public Moci_CloakClothRenderer(ConcurrentHashMap<String, CloakState> states) {
        this.states = states;
    }

    @Override
    public void advance(float amount) {
        if (states == null || states.isEmpty()) {
            return;
        }
        List<String> remove = null;
        for (Map.Entry<String, CloakState> e : states.entrySet()) {
            CloakState s = e.getValue();
            if (s == null || !s.valid || s.ship == null || !s.ship.isAlive() || s.ship.isHulk() || s.texture == null) {
                if (remove == null) {
                    remove = new ArrayList<String>();
                }
                remove.add(e.getKey());
            }
        }
        if (remove != null) {
            for (String key : remove) {
                states.remove(key);
            }
        }
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (layer != CombatEngineLayers.BELOW_SHIPS_LAYER || states == null || states.isEmpty()) {
            return;
        }

        float vpX = viewport.getLLX();
        float vpY = viewport.getLLY();
        float vpW = viewport.getVisibleWidth();
        float vpH = viewport.getVisibleHeight();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_LIGHTING);

        for (CloakState s : states.values()) {
            if (s == null || !s.valid || s.points.size() < 2 || s.texture == null || s.ship == null) {
                continue;
            }

            Vector2f anchor = s.points.get(0).pos;
            float radius = s.length + s.endWidth + VIEWPORT_MARGIN;
            if (!inViewport(anchor, radius, vpX, vpY, vpW, vpH)) {
                continue;
            }

            drawCloakStrip(s, viewport);
        }

        GL11.glPopAttrib();
    }

    private void drawCloakStrip(CloakState s, ViewportAPI viewport) {
        Color shipColor = s.ship.getSpriteAPI() != null ? s.ship.getSpriteAPI().getColor() : Color.white;
        float baseAlpha =
                viewport.getAlphaMult()
                        * s.ship.getAlphaMult()
                        * s.alphaMult
                        * (shipColor.getAlpha() / 255f);
        if (baseAlpha <= 0.001f) {
            return;
        }

        s.texture.bindTexture();

        int n = s.points.size();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i < n; i++) {
            ClothPoint p = s.points.get(i);
            Vector2f tangent = computeTangent(s.points, i);
            Vector2f normal = new Vector2f(-tangent.y, tangent.x);
            float normLen = normal.length();
            if (normLen > 0.0001f) {
                normal.scale(1f / normLen);
            } else {
                normal.set(0f, 1f);
            }

            float t = i / (float) (n - 1);
            float width = lerp(s.frontWidth, s.endWidth, t);
            float halfW = Math.max(1f, width * 0.5f);

            float tipFade = 1f - t * t * 0.35f;
            float alpha = baseAlpha * tipFade;

            float r = shipColor.getRed() / 255f;
            float g = shipColor.getGreen() / 255f;
            float b = shipColor.getBlue() / 255f;
            GL11.glColor4f(r, g, b, alpha);

            float lx = p.pos.x + normal.x * halfW;
            float ly = p.pos.y + normal.y * halfW;
            float rx = p.pos.x - normal.x * halfW;
            float ry = p.pos.y - normal.y * halfW;

            GL11.glTexCoord2f(0f, t);
            GL11.glVertex2f(lx, ly);
            GL11.glTexCoord2f(1f, t);
            GL11.glVertex2f(rx, ry);
        }
        GL11.glEnd();
    }

    private Vector2f computeTangent(List<ClothPoint> points, int i) {
        int n = points.size();
        if (n < 2) {
            return new Vector2f(1f, 0f);
        }
        if (i <= 0) {
            return new Vector2f(points.get(1).pos.x - points.get(0).pos.x, points.get(1).pos.y - points.get(0).pos.y);
        }
        if (i >= n - 1) {
            return new Vector2f(points.get(n - 1).pos.x - points.get(n - 2).pos.x, points.get(n - 1).pos.y - points.get(n - 2).pos.y);
        }
        return new Vector2f(points.get(i + 1).pos.x - points.get(i - 1).pos.x, points.get(i + 1).pos.y - points.get(i - 1).pos.y);
    }

    private boolean inViewport(Vector2f p, float r, float x, float y, float w, float h) {
        return p.x >= x - r && p.x <= x + w + r && p.y >= y - r && p.y <= y + h + r;
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    public float getRenderRadius() {
        return Float.MAX_VALUE;
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.BELOW_SHIPS_LAYER);
    }

    @Override
    public boolean isExpired() {
        return false;
    }
}
