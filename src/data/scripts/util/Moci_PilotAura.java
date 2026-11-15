package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import org.apache.log4j.Logger;

import java.awt.Color;

/**
 * 驾驶员感应类能力（新人类 / 强化人）生效时的周身极光，基于 {@link Moci_Util} 的 Aurora 封装。
 *
 * <p>用法：能力生效的每一帧调 {@link #update(float)}（没有就生成，有就按强度更新尺寸），
 * 结束时调 {@link #fade()}（幂等）。一个实例对应一艘船上的一个能力来源。</p>
 *
 * <p>尺寸（r 为舰船碰撞半径）：内半径 = r × {@link #INNER_MULT}，始终贴着舰体；
 * 满强度厚度 = (r × {@link #THICKNESS_MULT} + {@link #THICKNESS_FLAT}) × 样式的尺寸倍率；
 * 实际厚度 = 满强度厚度 × ({@link #MIN_SCALE} + (1 - MIN_SCALE) × effectLevel)。</p>
 */
public final class Moci_PilotAura {
    private static final Logger LOG = Global.getLogger(Moci_PilotAura.class);
    private static final String LOG_PREFIX = "[MOCI-AURA] ";

    // ========== 可配置参数 ==========
    public static final float INNER_MULT = 0.75f;       // 内半径 / 碰撞半径
    public static final float THICKNESS_MULT = 0.8f;    // 厚度随碰撞半径增长的部分
    public static final float THICKNESS_FLAT = 120f;    // 厚度的固定部分（像素），保证小船也看得见
    public static final float MIN_SCALE = 0.35f;        // effectLevel 为 0 时的厚度比例
    public static final float ALPHA = 0.85f;
    public static final float FADE_IN = 0.4f;           // 淡入（秒）
    public static final float FADE_OUT = 0.8f;          // 能力结束后的淡出（秒）

    /** 新人类：绿色与蓝绿色沿圆周渐变 */
    public static final Color NEWTYPE_GREEN = new Color(110, 255, 140, 255);
    public static final Color NEWTYPE_TEAL = new Color(40, 220, 215, 255);
    /** 一圈内「绿 → 蓝绿 → 绿」重复的次数；必须是偶数，极光的两条光带相差 180° 才能对上色 */
    public static final int NEWTYPE_GRADIENT_CYCLES = 2;
    /** 渐变沿圆周缓慢转动的速度（度/秒） */
    public static final float NEWTYPE_GRADIENT_ROTATE_SPEED = 24f;

    /** 强化人：紫色，尺寸为新人类的 75% */
    public static final Color AUGMENTED_PURPLE = new Color(175, 80, 255, 255);
    public static final float AUGMENTED_SIZE_MULT = 0.75f;
    // ================================

    public enum Style {
        NEWTYPE(1f),
        AUGMENTED(AUGMENTED_SIZE_MULT);

        final float sizeMult;

        Style(float sizeMult) {
            this.sizeMult = sizeMult;
        }
    }

    private final ShipAPI ship;
    private final Style style;
    private Moci_Util.AuroraCombatPlugin aurora;
    /** 出错只报一次，避免每帧刷屏。 */
    private boolean errorLogged;

    public Moci_PilotAura(ShipAPI ship, Style style) {
        this.ship = ship;
        this.style = style != null ? style : Style.NEWTYPE;
    }

    /**
     * 没有极光就生成，有就按当前强度和碰撞半径更新尺寸。
     * 特效只是观感，任何异常都吞在这里，不影响调用方的主逻辑。
     *
     * @param effectLevel 能力强度 0~1
     */
    public void update(float effectLevel) {
        try {
            float level = Float.isFinite(effectLevel) ? Math.max(0f, Math.min(1f, effectLevel)) : 0f;
            float radius = Math.max(1f, ship.getCollisionRadius());
            float inner = radius * INNER_MULT;
            float fullThickness = (radius * THICKNESS_MULT + THICKNESS_FLAT) * style.sizeMult;
            float outer = inner + fullThickness * (MIN_SCALE + (1f - MIN_SCALE) * level);

            if (aurora == null || aurora.isExpired() || aurora.isFadingOut()) {
                Moci_Util.AuroraParams params = createParams();
                params.setAnchor(ship)
                        .setRadius(inner, outer)
                        .setAlphaMult(ALPHA)
                        // 厚度逐帧变化，必须锁定平铺次数，否则纹理会一格一格突跳
                        .setLockTexTiling(true)
                        // duration < 0：不自己到时，由 fade() 负责淡出
                        .setLifetime(FADE_IN, -1f, FADE_OUT);
                aurora = Moci_Util.spawnAurora(params);
            } else {
                aurora.getParams().setRadius(inner, outer);
            }

            if (aurora != null && aurora.getParams() instanceof GradientParams) {
                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine != null) {
                    ((GradientParams) aurora.getParams()).rotation =
                            engine.getTotalElapsedTime(false) * NEWTYPE_GRADIENT_ROTATE_SPEED;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            aurora = null;
            if (!errorLogged) {
                errorLogged = true;
                LOG.warn(LOG_PREFIX + "周身极光更新失败 style=" + style + " ship=" + ship.getId(), e);
            }
        }
    }

    /** 收掉极光（淡出后自行移除）。幂等，可以每帧调。 */
    public void fade() {
        Moci_Util.AuroraCombatPlugin current = aurora;
        if (current == null) return;
        aurora = null;
        try {
            current.fadeOutAndExpire();
        } catch (RuntimeException | LinkageError ignored) {
            // 战场已经换了：旧特效随旧引擎一起没了
        }
    }

    private Moci_Util.AuroraParams createParams() {
        if (style == Style.AUGMENTED) {
            return new Moci_Util.AuroraParams().setColor(AUGMENTED_PURPLE);
        }
        return new GradientParams(NEWTYPE_GRADIENT_LUT, NEWTYPE_GRADIENT_CYCLES);
    }

    // ---------- 沿圆周的双色渐变 ----------

    /** 一个渐变周期的颜色表：极光每帧每个顶点都要取色，查表避免每帧 new 几百个 Color */
    private static final Color[] NEWTYPE_GRADIENT_LUT = buildGradient(NEWTYPE_GREEN, NEWTYPE_TEAL, 64);

    /** from → to → from 的余弦渐变，首尾相接无接缝 */
    private static Color[] buildGradient(Color from, Color to, int steps) {
        Color[] lut = new Color[steps];
        for (int i = 0; i < steps; i++) {
            float t = 0.5f - 0.5f * (float) Math.cos(Math.PI * 2.0 * i / steps);
            lut[i] = new Color(
                    Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
                    Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
                    Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t),
                    Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * t));
        }
        return lut;
    }

    private static final class GradientParams extends Moci_Util.AuroraParams {
        private final Color[] lut;
        private final int cycles;
        /** 渐变整体转过的角度（度），由 update() 每帧写入 */
        float rotation;

        GradientParams(Color[] lut, int cycles) {
            this.lut = lut;
            this.cycles = Math.max(1, cycles);
            this.color = lut[0];
        }

        @Override
        public Color getAuroraColorForAngle(float angle) {
            float phase = (angle + rotation) * cycles / 360f;
            phase -= (float) Math.floor(phase);
            if (!(phase >= 0f)) return lut[0]; // NaN 兜底
            int index = (int) (phase * lut.length);
            if (index >= lut.length) index = lut.length - 1;
            return lut[index];
        }
    }
}
