package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import data.scripts.weapons.render.Moci_ShaderManager;
import org.dark.shaders.util.ShaderLib;
import org.dark.shaders.util.TextureData.TextureDataType;
import org.dark.shaders.util.TextureEntry;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import java.awt.Color;
import java.io.IOException;
import java.util.EnumSet;

/** 磁流体引擎：保留模拟引擎尾灯，推进光弧优先使用 _glow1，否则使用舰体轮廓。 */
public final class Moci_MagnetohydrodynamicEngine extends Moci_AxisEngine {
    // ===== 光弧外观调参（以整张舰体贴图的高度为 1，前进、倒车和制动共用） =====
    // 宽度：沿扫动方向，从一侧透明边缘到另一侧的总厚度，含两侧柔边。取正值；0.064 = 舰高的 6.4%，改为 0.032 会细一半。
    private static final float ARC_WIDTH = 0.064f;
    // 弧度：两翼相对中央沿扫动方向落后的距离，并非角度。0 为直线，0.075 = 舰高的 7.5%，改为 0.12 更弯；取非负值，倒车自动翻转。
    private static final float ARC_CURVATURE = 0.075f;

    private boolean attempted;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        super.advance(amount, engine, weapon); // 即使没有蒙版，原有引擎亮度和光源也照常工作。
        if (attempted || engine == null || engine.isPaused() || weapon == null) return;
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || !engine.isEntityInPlay(ship)) return;
        attempted = true;
        String sprite = ship.getHullSpec() == null ? null : ship.getHullSpec().getSpriteName();
        int ext = sprite == null ? -1 : sprite.lastIndexOf('.');
        SpriteAPI mask = ext < 0 ? null : optionalTexture(sprite.substring(0, ext) + "_glow1" + sprite.substring(ext));
        // 没有专用蒙版时复用当前舰体精灵的透明度，不重载舰体，也不把涂装颜色混入光弧。
        if (mask == null) mask = ship.getSpriteAPI();
        if (!hasTexture(mask)) return;
        Color color = weapon.getSpec().getGlowColor();
        Sweep visual = new Sweep(engine, ship, mask, color == null ? Color.WHITE : color, findHullNormal(ship, sprite));
        CombatEntityAPI host = engine.addLayeredRenderingPlugin(visual);
        if (host != null) host.getLocation().set(ship.getLocation());
    }

    private static boolean hasTexture(SpriteAPI sprite) {
        return sprite != null && sprite.getTextureId() > 0;
    }

    private static SpriteAPI optionalTexture(String path) {
        try {
            Global.getSettings().loadTexture(path);
            SpriteAPI sprite = Global.getSettings().getSprite(path);
            return hasTexture(sprite) ? sprite : null;
        } catch (IOException | RuntimeException missingOptionalMap) {
            return null;
        }
    }

    /** 优先用 GraphicsLib 登记的舰体法线（含贴图覆盖）；未提供时尝试 Moci 的同名法线路径。 */
    private static SpriteAPI findHullNormal(ShipAPI ship, String hullPath) {
        try {
            TextureEntry entry = ShaderLib.getShipTexture(ship, TextureDataType.NORMAL_MAP);
            if (entry != null && hasTexture(entry.sprite)) return entry.sprite;
        } catch (RuntimeException ex) {
            Global.getLogger(Moci_MagnetohydrodynamicEngine.class).debug("Unable to query registered hull normal: " + hullPath, ex);
        }
        if (hullPath == null) return null;
        int ext = hullPath.lastIndexOf('.');
        if (!hullPath.startsWith("graphics/") || ext <= "graphics/".length()) return null;
        return optionalTexture("graphics/normal/" + hullPath.substring("graphics/".length(), ext) + "_normal" + hullPath.substring(ext));
    }

    enum Thrust { IDLE, FORWARD, REVERSE, BRAKE }

    /** 每艘舰各有一个计时器。发射间隔只决定何时新增亮带，不重置已发亮带的位置。 */
    static final class Clock {
        // 调参区（单位：秒，均须大于 0）。持续推进时，在途道数约等于 TRAVEL / 发射间隔。
        static final double FORWARD = 0.28;       // 满速前进的发射间隔。越小越密。
        static final double REVERSE = FORWARD * 2; // 满速倒车/制动的间隔。相同航速下，频率是前进的一半。
        static final double TRAVEL = 0.85;        // 单道扫过整艘舰的时间，两种方向相同。越大越慢。
        static final double REST_RATE = 0.50;     // 零速时的发射频率倍率；随实际航速线性升至 1，超速不再增加。

        // 每道在发出时记下时间和方向。极端调参时最多保留最近 32 道。
        private static final int MAX_BANDS = 32;
        private static final double EPS = 1e-9;
        private final double[] births = new double[MAX_BANDS];
        private final boolean[] sternToBow = new boolean[MAX_BANDS];
        private int first, count;
        private double now, emissionCredit = Math.max(FORWARD, REVERSE);

        static double emissionRate(double speed, double maxSpeed) {
            if (!(maxSpeed > 0) || !Double.isFinite(maxSpeed) || !Double.isFinite(speed)) return REST_RATE;
            double fraction = Math.min(1, Math.max(0, speed) / maxSpeed);
            return REST_RATE + (1 - REST_RATE) * fraction;
        }

        void advance(double dt, Thrust thrust, double rate) {
            if (!(dt > 0)) return;
            now += dt;
            // 松开推进只停发，不清空队列：每道光弧都按原方向走完。
            while (count > 0 && now - births[first] >= TRAVEL - EPS) {
                first = (first + 1) % MAX_BANDS;
                count--;
            }
            // 只给发射计时乘速度倍率；出生时间和 TRAVEL 始终使用真实战斗秒数。
            if (thrust == Thrust.IDLE) {
                emissionCredit = Math.min(Math.max(FORWARD, REVERSE), emissionCredit + dt * rate);
                return;
            }
            double baseInterval = thrust == Thrust.FORWARD ? FORWARD : REVERSE;
            double wait = Math.max(0, baseInterval - emissionCredit) / rate;
            if (wait > dt + EPS) {
                emissionCredit += dt * rate;
                return;
            }

            // 卡帧跨过多个发射点时，按原定时间补上仍在途的亮带；切换前进/后退不挪动旧带。
            double interval = baseInterval / rate;
            double beats = Math.floor((dt - wait + EPS) / interval);
            double tail = Math.max(0, dt - wait - beats * interval);
            double lastEmission = now - tail;
            emissionCredit = tail * rate;
            for (int i = (int) Math.min(beats, MAX_BANDS - 1); i >= 0; i--) {
                double birth = lastEmission - i * interval;
                if (now - birth >= TRAVEL - EPS) continue;
                if (count == MAX_BANDS) {
                    first = (first + 1) % MAX_BANDS;
                    count--;
                }
                int slot = (first + count++) % MAX_BANDS;
                births[slot] = birth;
                sternToBow[slot] = thrust == Thrust.REVERSE;
            }
        }

        int activeCount() { return count; }

        boolean isSternToBow(int index) { return sternToBow[(first + index) % MAX_BANDS]; }

        /** 从最早发出的一道开始编号；进度从起点的 0 增至终点的 1，与方向无关。 */
        float progress(int index) {
            return (float) ((now - births[(first + index) % MAX_BANDS]) / TRAVEL);
        }
    }

    private static final class Sweep extends BaseCombatLayeredRenderingPlugin {
        private static final float NORMAL_RELIEF_STRENGTH = 3f; // 同贴膜盾的常态浮雕强度；0 关闭法线明暗。
        private static final float HALF_WIDTH = ARC_WIDTH * 0.5f;
        private static final int ARC_SEGMENTS = 20;
        private static final EnumSet<CombatEngineLayers> LAYERS = EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_LAYER);
        private final CombatEngineAPI engine;
        private final ShipAPI ship;
        private final SpriteAPI mask;
        private final SpriteAPI normal;
        private int reliefProgram = -1, maskUnit, normalUnit, normalRect, strength;
        private final Color color;
        private final Clock clock = new Clock();

        Sweep(CombatEngineAPI engine, ShipAPI ship, SpriteAPI mask, Color color, SpriteAPI normal) {
            this.engine = engine;
            this.ship = ship;
            this.mask = mask;
            this.normal = normal;
            this.color = color;
        }

        /** 只在有法线的首个可见帧编译/查询；程序由 Moci_ShaderManager 跨舰共用。 */
        private boolean bindRelief() {
            if (normal == null || NORMAL_RELIEF_STRENGTH <= 0f) return false;
            if (reliefProgram == -1) {
                String path = "data/shaders/Moci_axis_sweep";
                reliefProgram = Moci_ShaderManager.getInstance().createShader(path + ".vert", path + ".frag", "Moci_axis_sweep_relief");
                if (reliefProgram > 0) {
                    maskUnit = GL20.glGetUniformLocation(reliefProgram, "maskTex");
                    normalUnit = GL20.glGetUniformLocation(reliefProgram, "normalTex");
                    normalRect = GL20.glGetUniformLocation(reliefProgram, "normalRect");
                    strength = GL20.glGetUniformLocation(reliefProgram, "strength");
                }
            }
            if (reliefProgram <= 0) return false;
            GL20.glUseProgram(reliefProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, normal.getTextureId());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUniform1i(maskUnit, 0);
            GL20.glUniform1i(normalUnit, 1);
            GL20.glUniform4f(normalRect, normal.getTexX(), normal.getTexY(), normal.getTexWidth(), normal.getTexHeight());
            GL20.glUniform1f(strength, NORMAL_RELIEF_STRENGTH);
            return true;
        }

        @Override public EnumSet<CombatEngineLayers> getActiveLayers() { return LAYERS; }
        @Override public float getRenderRadius() {
            return Math.max(ship.getSpriteAPI().getWidth(), ship.getSpriteAPI().getHeight());
        }
        @Override public boolean isExpired() {
            return !ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship);
        }
        @Override public void advance(float amount) {
            if (engine.isPaused() || isExpired()) return;
            entity.getLocation().set(ship.getLocation());
            ShipEngineControllerAPI drive = ship.getEngineController();
            double rate = Clock.emissionRate(ship.getVelocity().length(), ship.getMaxSpeed());
            Thrust thrust = Thrust.IDLE;
            if (drive != null) {
                if (drive.isAccelerating()) thrust = Thrust.FORWARD;
                else if (drive.isAcceleratingBackwards()) thrust = Thrust.REVERSE;
                else if (drive.isDecelerating()) thrust = Thrust.BRAKE;
            }
            clock.advance(amount, thrust, rate);
        }

        @Override public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            int bandCount = clock.activeCount();
            if (bandCount == 0 || isExpired()) return;
            float alpha = viewport.getAlphaMult() * ship.getAlphaMult() * ship.getExtraAlphaMult()
                    * ship.getExtraAlphaMult2() * color.getAlpha() / 255f;
            if (alpha <= 0f) return;
            FogOfWarAPI fog = engine.getFogOfWar(0);
            if (fog != null && !fog.isVisible(ship)) return;
            SpriteAPI hull = ship.getSpriteAPI();
            float w = hull.getWidth(), h = hull.getHeight();
            float cx = hull.getCenterX(), cy = hull.getCenterY();
            if (cx == -1 || cy == -1) { cx = w * 0.5f; cy = h * 0.5f; }

            boolean shaders = GLContext.getCapabilities().OpenGL20;
            int previousProgram = shaders ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_COLOR_BUFFER_BIT
                    | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_TRANSFORM_BIT);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                GL11.glTranslatef(ship.getLocation().x, ship.getLocation().y, 0f);
                GL11.glRotatef(ship.getFacing() - 90f, 0f, 0f, 1f);
                GL11.glTranslatef(-cx, -cy, 0f);
                GL11.glScalef(w, h, 1f);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, mask.getTextureId());
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glShadeModel(GL11.GL_SMOOTH);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                if (!shaders || !bindRelief()) {
                    if (previousProgram != 0) GL20.glUseProgram(0);
                    bindFlatMask();
                }
                // 蒙版和混合状态每帧只绑定一次，再逐道按各自的进度绘制。
                for (int i = 0; i < bandCount; i++) drawRibbon(clock.progress(i), clock.isSternToBow(i), alpha);
            } finally {
                if (shaders) GL20.glUseProgram(previousProgram);
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        }

        private void bindFlatMask() {
            // 两种蒙版都只取透明度，光弧颜色取自引擎。
            int env = GL11.GL_TEXTURE_ENV;
            GL11.glTexEnvi(env, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
            GL11.glTexEnvi(env, GL13.GL_COMBINE_RGB, GL11.GL_REPLACE);
            GL11.glTexEnvi(env, GL13.GL_SOURCE0_RGB, GL13.GL_PRIMARY_COLOR);
            GL11.glTexEnvi(env, GL13.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
            GL11.glTexEnvi(env, GL13.GL_RGB_SCALE, 1);
            GL11.glTexEnvi(env, GL13.GL_COMBINE_ALPHA, GL11.GL_MODULATE);
            GL11.glTexEnvi(env, GL13.GL_SOURCE0_ALPHA, GL11.GL_TEXTURE);
            GL11.glTexEnvi(env, GL13.GL_SOURCE1_ALPHA, GL13.GL_PRIMARY_COLOR);
            GL11.glTexEnvi(env, GL13.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
            GL11.glTexEnvi(env, GL13.GL_OPERAND1_ALPHA, GL11.GL_SRC_ALPHA);
            GL11.glTexEnvi(env, GL11.GL_ALPHA_SCALE, 2);
        }

        private void drawRibbon(float progress, boolean sternToBow, float alpha) {
            float crest = 1f + HALF_WIDTH - progress * (1f + ARC_CURVATURE + ARC_WIDTH);
            float r = color.getRed() / 255f, g = color.getGreen() / 255f, b = color.getBlue() / 255f;
            float u0 = mask.getTexX(), v0 = mask.getTexY(), du = mask.getTexWidth(), dv = mask.getTexHeight();
            // 两片共用明亮的弧顶，向两侧线性淡出，形成短柔边。
            for (int side = -1; side <= 1; side += 2) {
                GL11.glBegin(GL11.GL_QUAD_STRIP);
                for (int i = 0; i <= ARC_SEGMENTS; i++) {
                    float x = (float) i / ARC_SEGMENTS, lateral = 2f * x - 1f;
                    float y = crest + ARC_CURVATURE * lateral * lateral;
                    // 倒车时只翻转光弧的位置和弯向，蒙版和法线仍取舰体原位。
                    if (sternToBow) y = 1f - y;
                    float u = u0 + x * du;
                    GL11.glColor4f(r, g, b, alpha);
                    float center = Math.max(0f, Math.min(1f, y));
                    GL11.glTexCoord2f(u, v0 + center * dv);
                    GL11.glVertex2f(x, center);
                    float edge = Math.max(0f, Math.min(1f, y + side * HALF_WIDTH));
                    GL11.glColor4f(r, g, b, 0f);
                    GL11.glTexCoord2f(u, v0 + edge * dv);
                    GL11.glVertex2f(x, edge);
                }
                GL11.glEnd();
            }
        }
    }
}
