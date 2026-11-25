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
 * 基础厚度随 effectLevel 线性增长，低强度时也为外缘波谷留出碰撞半径所需的空间。</p>
 */
public final class Moci_PilotAura {
    private static final Logger LOG = Global.getLogger(Moci_PilotAura.class);
    private static final String LOG_PREFIX = "[MOCI-AURA] ";

    // ========== 可配置参数 ==========
    public static final float INNER_MULT = 0.75f;       // 内半径 / 碰撞半径
    public static final float THICKNESS_MULT = 0.8f;    // 厚度随碰撞半径增长的部分
    public static final float THICKNESS_FLAT = 120f;    // 厚度的固定部分（像素），保证小船也看得见
    public static final float MIN_SCALE = 0.35f;        // effectLevel 为 0 时的基础厚度比例下限
    public static final float ALPHA = 0.85f;            // 整体亮度倍率
    public static final float FADE_IN = 0.4f;           // 淡入（秒）
    public static final float FADE_OUT = 0.8f;          // 能力结束后的淡出（秒）

    /** 新人类：绿色与蓝绿色沿圆周渐变 */
    public static final Color NEWTYPE_GREEN = new Color(110, 255, 140, 255);
    public static final Color NEWTYPE_TEAL = new Color(40, 220, 215, 255);
    /** 一圈内双色渐变的完整周期数，两条光带都按世界角度取色 */
    public static final int NEWTYPE_GRADIENT_CYCLES = 2;
    /** 新人类轮廓和颜色的共同转速（度/秒） */
    public static final float NEWTYPE_GRADIENT_ROTATE_SPEED = 9f;

    /** 强化人：紫色，尺寸为新人类的 75% */
    public static final Color AUGMENTED_PURPLE = new Color(175, 80, 255, 255);
    private static final Color AUGMENTED_MAGENTA = new Color(232, 85, 215, 255); // 紫色渐变的洋红端
    public static final float AUGMENTED_SIZE_MULT = 0.75f;
    private static final float AUGMENTED_ROTATE_SPEED = -13f; // 强化人反向旋转（度/秒）
    private static final float AUGMENTED_TEMPO = 1.42f;       // 强化人的波形、呼吸和明暗流速倍率
    private static final int GRADIENT_STEPS = 128;            // 一个双色渐变周期的采样数

    // 外缘三层波：一圈中的波峰数、振幅（占基础厚度）、相对旋转轮廓的相位速度（弧度/秒）。
    private static final int BROAD_CYCLES = 2, MID_CYCLES = 3, FINE_CYCLES = 5;
    private static final float BROAD_AMP = 0.12f, MID_AMP = 0.075f, FINE_AMP = 0.045f;
    private static final double BROAD_RATE = -0.13, MID_RATE = 0.37, FINE_RATE = -0.53;
    private static final double MID_PHASE = 1.4, FINE_PHASE = 2.7; // 错开中波和细波的初相（弧度）
    private static final double BREATH_RATE = 0.71;          // 整圈呼吸的相位速度（弧度/秒）
    private static final float BREATH_WIDTH = 0.045f;        // 呼吸的厚度振幅
    private static final float BREATH_ALPHA = 0.05f;         // 呼吸的亮度振幅
    private static final int LIGHT_CYCLES = 3;               // 明暗带每圈波峰数
    private static final double LIGHT_RATE = -0.46;          // 明暗带的相对相位速度（弧度/秒）
    private static final float LIGHT_AMP = 0.13f;            // 明暗带的亮度振幅
    private static final float BAND_PULSE = 0.025f;          // 两条纹理光带各自保留的细小脉动
    private static final float MIN_WIDTH_MULT =              // 波谷处最小厚度倍率，含渲染器自身脉动
            1f - BROAD_AMP - MID_AMP - FINE_AMP - BREATH_WIDTH - BAND_PULSE;
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
            float minThickness = Math.max(fullThickness * MIN_SCALE, (radius - inner) / MIN_WIDTH_MULT);
            float outer = inner + minThickness + (fullThickness - minThickness) * level;

            PilotAuraParams params;
            if (aurora == null || aurora.isExpired() || aurora.isFadingOut()) {
                params = new PilotAuraParams(style);
                params.setAnchor(ship)
                        .setRadius(inner, outer)
                        .setAlphaMult(ALPHA)
                        // 厚度逐帧变化，必须锁定平铺次数，否则纹理会一格一格突跳
                        .setLockTexTiling(true)
                        // duration < 0：不自己到时，由 fade() 负责淡出
                        .setLifetime(FADE_IN, -1f, FADE_OUT);
                aurora = Moci_Util.spawnAurora(params);
            } else {
                params = (PilotAuraParams) aurora.getParams();
                params.setRadius(inner, outer);
            }

            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine != null) params.setTime(engine.getTotalElapsedTime(false));
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

    // ---------- 沿圆周的双色渐变 ----------

    /** 一个渐变周期的颜色表：极光每帧每个顶点都要取色，查表避免每帧 new 几百个 Color */
    private static final Color[] NEWTYPE_GRADIENT_LUT = buildGradient(NEWTYPE_GREEN, NEWTYPE_TEAL, GRADIENT_STEPS);
    private static final Color[] AUGMENTED_GRADIENT_LUT = buildGradient(AUGMENTED_PURPLE, AUGMENTED_MAGENTA, GRADIENT_STEPS);

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

    private static final class PilotAuraParams extends Moci_Util.AuroraParams {
        private final Color[] lut;
        private final double rotateSpeed, tempo;
        private double rotation, broadPhase, midPhase, finePhase, lightPhase;
        private float breath;

        PilotAuraParams(Style style) {
            boolean augmented = style == Style.AUGMENTED;
            lut = augmented ? AUGMENTED_GRADIENT_LUT : NEWTYPE_GRADIENT_LUT;
            rotateSpeed = augmented ? AUGMENTED_ROTATE_SPEED : NEWTYPE_GRADIENT_ROTATE_SPEED;
            tempo = augmented ? AUGMENTED_TEMPO : 1.0;
            color = lut[0];
            shortenMult = BAND_PULSE;
            innerOffsetMult = 0f; // 内缘始终固定在贴舰半径
            wobble = 0f;          // 外缘只用相对厚度起伏，避免固定像素偏移淹没小舰光环
        }

        void setTime(double elapsed) {
            rotation = (elapsed * rotateSpeed) % 360.0;
            double turn = Math.toRadians(rotation);
            double t = elapsed * tempo;
            broadPhase = t * BROAD_RATE - turn * BROAD_CYCLES;
            midPhase = t * MID_RATE - turn * MID_CYCLES + MID_PHASE;
            finePhase = t * FINE_RATE - turn * FINE_CYCLES + FINE_PHASE;
            lightPhase = t * LIGHT_RATE - turn * LIGHT_CYCLES;
            breath = (float) Math.sin(t * BREATH_RATE);
        }

        @Override
        public float getAuroraThicknessMult(float angle) {
            double a = Math.toRadians(angle);
            return (float) (1f + BREATH_WIDTH * breath
                    + BROAD_AMP * Math.sin(a * BROAD_CYCLES + broadPhase)
                    + MID_AMP * Math.sin(a * MID_CYCLES + midPhase)
                    + FINE_AMP * Math.sin(a * FINE_CYCLES + finePhase));
        }

        @Override
        public float getAuroraAlphaMultForAngle(float angle) {
            return 1f + BREATH_ALPHA * breath
                    + LIGHT_AMP * (float) Math.sin(Math.toRadians(angle) * LIGHT_CYCLES + lightPhase);
        }

        @Override
        public Color getAuroraColorForAngle(float angle) {
            double phase = (angle - rotation) * NEWTYPE_GRADIENT_CYCLES / 360.0;
            int index = (int) ((phase - Math.floor(phase)) * lut.length);
            return lut[index % lut.length];
        }
    }
}
