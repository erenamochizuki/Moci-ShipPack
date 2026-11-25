package data.shipsystems.scripts.travel;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import java.awt.Color;
import java.io.IOException;
import java.util.Map;
import org.dark.shaders.light.LightShader;
import org.dark.shaders.light.StandardLight;
import org.dark.shaders.util.ShaderAPI;
import org.dark.shaders.util.ShaderHook;
import org.dark.shaders.util.ShaderLib;
import org.json.JSONException;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

/** 一次入场独占的电弧灯；不生成、不持有也不改变原版 EMP 弧。 */
final class Moci_TransitArcLights {
    private static final String LIGHT_DATA = "shaderlib_LightShader";
    private final CombatEngineAPI engine;
    private Object shaderData;
    private ShaderAPI shader;
    private float sizeScale = Float.NaN;
    private Arc first;
    private boolean mayAdd;

    Moci_TransitArcLights(CombatEngineAPI engine) { this.engine = engine; }

    /** 在本帧实际生成运动弧后传入它返回的战斗秒数；0 表示未生成。两种颜色均可为 null。 */
    void add(Vector2f from, Vector2f to, float width, Color fringe, Color core, float travel) {
        if (!mayAdd || from == null || to == null || !(travel > 0f) || !Float.isFinite(travel)
                || !(width > 0f) || !Float.isFinite(width) || !currentBattle()) return;
        float scale = activeScale();
        if (!(scale > 0f)) return;
        double dx = (double) to.x - from.x, dy = (double) to.y - from.y;
        double length = Math.hypot(dx, dy);
        if (!(length > 0) || !Double.isFinite(length)) return;
        if (fringe == null) fringe = core != null ? core : Color.WHITE;
        if (core == null) core = fringe;
        if (fringe.getAlpha() == 0 && core.getAlpha() == 0) return;

        Arc arc = new Arc(from, to, dx, dy, length, width, fringe, core, travel, scale);
        if (!arc.position()) return;
        LightShader.addLight(arc);
        arc.next = first;
        first = arc;
        mayAdd = false;
    }

    /** 每帧在发弧循环之前调用一次；传原始战斗秒（不乘阶段倍率），visible 包含视口和战争迷雾。 */
    void advance(float battleSeconds, boolean visible) {
        if (!visible || !currentBattle() || (first != null && !(activeScale() > 0f))) {
            clear();
            return;
        }
        mayAdd = battleSeconds > 0f && Float.isFinite(battleSeconds) && !engine.isPaused();
        if (!mayAdd) return;
        Arc previous = null;
        for (Arc arc = first, next; arc != null; arc = next) {
            next = arc.next;
            arc.age += battleSeconds;
            if (arc.getIntensity() > 0f && arc.age < arc.lifetimeLimit && arc.position()) {
                previous = arc;
            } else {
                if (previous == null) first = next;
                else previous.next = next;
                retire(arc);
            }
        }
    }

    /** 取消、撤退、退出或失去可见性时立即熄灯；允许重复调用。 */
    void clear() {
        mayAdd = false;
        while (first != null) {
            Arc arc = first;
            first = arc.next;
            retire(arc);
        }
    }

    private boolean currentBattle() {
        return engine != null && Global.getCombatEngine() == engine && !engine.isCombatOver();
    }

    private void retire(Arc arc) {
        arc.next = null;
        arc.fadeOut(0f);
        if (Global.getCombatEngine() == engine && engine.getCustomData() != null) LightShader.removeLight(arc);
    }

    private float activeScale() {
        if (!ShaderHook.enableShaders || !ShaderLib.areShadersAllowed() || !ShaderLib.areBuffersAllowed()) return 0f;
        Map<String, Object> data = engine.getCustomData();
        Object local = data == null ? null : data.get(LIGHT_DATA);
        if (local == null) return 0f;
        if (local != shaderData) {
            shaderData = local;
            shader = ShaderLib.getShaderAPI(LightShader.class);
            sizeScale = Float.NaN;
        }
        if (shader == null || !shader.isEnabled()) return 0f;
        if (Float.isNaN(sizeScale)) {
            SettingsAPI settings = Global.getSettings();
            if (settings == null) return 0f;
            try {
                JSONObject options = settings.loadJSON("GRAPHICS_OPTIONS.ini");
                float scale = options == null ? 0f : (float) options.optDouble("sizeScale", 0d);
                sizeScale = Float.isFinite(scale) && scale > 0f ? scale : 0f;
            } catch (IOException | JSONException unavailable) {
                sizeScale = 0f;
            }
        }
        return sizeScale;
    }

    private static final class Arc extends StandardLight {
        private static final double FULL_HALF = .18 / 2, FADE_HALF = .22 / 2;
        private final float startX, startY, endX, endY, travel, lifetimeLimit, nominalSize, scale, peak;
        private final double dx, dy, length;
        private double age;
        private Arc next;

        Arc(Vector2f from, Vector2f to, double dx, double dy, double length, float width,
                Color fringe, Color core, float travel, float scale) {
            startX = from.x; startY = from.y; endX = to.x; endY = to.y;
            this.dx = dx; this.dy = dy; this.length = length; this.travel = travel; this.scale = scale;
            nominalSize = Math.min(64f, width * 2f);
            float fringeWeight = 2f * fringe.getAlpha(), coreWeight = core.getAlpha();
            float normalizer = 1f / (255f * (fringeWeight + coreWeight));
            setColor((fringeWeight * fringe.getRed() + coreWeight * core.getRed()) * normalizer,
                    (fringeWeight * fringe.getGreen() + coreWeight * core.getGreen()) * normalizer,
                    (fringeWeight * fringe.getBlue() + coreWeight * core.getBlue()) * normalizer);
            peak = 1.25f * (fringeWeight + coreWeight) / (3f * 255f);
            lifetimeLimit = Math.min(travel, 60f);
            setLifetime(lifetimeLimit);
            setAutoFadeOutTime(0f);
            setHeight(nominalSize);
        }

        /** 0.98 的运动亮段中心为 sqrt(1.5 * age/travel)；尾部越过终点即消失，不补终点闪光。 */
        boolean position() {
            double center = Math.sqrt(1.5 * age / travel);
            double tail = Math.max(0d, center - FULL_HALF - FADE_HALF);
            if (tail >= 1d) return false;
            double along = Math.min(center, (tail + 1d) * .5);
            float worldX = (float) (startX + dx * along), worldY = (float) (startY + dy * along);
            double gap = (((double) endX - worldX) * dx + ((double) endY - worldY) * dy) / length;
            float radius = (float) Math.min(nominalSize, Math.min(length * (FULL_HALF + FADE_HALF), gap * .875) / scale);
            if (!(radius > 0f)) return false;
            getLocation().set(worldX, worldY);
            setSize(radius);
            setIntensity(peak * (float) Math.min(1d, (FULL_HALF + FADE_HALF - (center - along)) / FADE_HALF));
            return true;
        }
    }
}
