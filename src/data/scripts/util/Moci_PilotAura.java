package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import org.apache.log4j.Logger;

import java.awt.Color;
import java.util.Random;

/**
 * 驾驶员感应类能力（新人类 / 强化人）生效时的周身极光，基于 {@link Moci_Util} 的 Aurora 封装。
 *
 * <p>用法：能力生效的每一帧调 {@link #update(float)}（没有就生成，有就按强度更新尺寸），
 * 结束时调 {@link #fade()}（幂等）。一个实例对应一艘船上的一个能力来源。</p>
 *
 * <p>由三层极光叠加（{@link #LAYERS}）：底层宽而暗、中层、外层细而长。每层有自己的转速和方向、
 * 外缘波形、呼吸曲线和一组随机闪焰。运动方式参考原版磁场极光（MagneticFieldTerrainPlugin +
 * FlareManager）：原版光环本身几乎不转，「活」的感觉来自外缘缓慢流动的脉动，以及随机出现的
 * 闪焰——一段弧度先亮起、向外伸长，再慢慢退回，淡入淡出用开根号的曲线，所以既不规律又不突兀。</p>
 *
 * <p>尺寸（r 为舰船碰撞半径）：内半径 = r × {@link #INNER_MULT}，始终贴着舰体；
 * 满强度基础厚度 = (r × {@link #THICKNESS_MULT} + {@link #THICKNESS_FLAT}) × 样式的尺寸倍率，
 * 随 effectLevel 线性增长；各层再按自己的厚度倍率缩放。</p>
 */
public final class Moci_PilotAura {
    private static final Logger LOG = Global.getLogger(Moci_PilotAura.class);
    private static final String LOG_PREFIX = "[MOCI-AURA] ";

    // ========== 可配置参数 ==========
    public static final float INNER_MULT = 0.75f;       // 内半径 / 碰撞半径
    public static final float THICKNESS_MULT = 0.8f;    // 厚度随碰撞半径增长的部分
    public static final float THICKNESS_FLAT = 120f;    // 厚度的固定部分（像素），保证小船也看得见
    public static final float MIN_SCALE = 0.35f;        // effectLevel 为 0 时的基础厚度比例下限
    public static final float ALPHA = 0.7f;             // 整体亮度倍率（三层加法叠加，比单层时低）
    public static final float FADE_IN = 0.2f;           // 淡入（秒），与 GROW_IN 同步
    public static final float FADE_OUT = 0.25f;         // 能力结束后的淡出（秒），与 GROW_OUT 同步
    /** 启动时光环从小长到满尺寸的时长（秒） */
    private static final float GROW_IN = 0.2f;
    /** 启动瞬间的厚度比例 */
    private static final float GROW_START_SCALE = 0.15f;
    /** 消失时光环一边淡出一边向外扩开的时长（秒） */
    private static final float GROW_OUT = 0.25f;
    /** 消失过程中厚度额外扩大的比例 */
    private static final float GROW_OUT_EXTRA = 0.45f;
    /** 波谷处的最小厚度倍率（估计值），用于保证低强度时外缘不缩进舰体 */
    private static final float MIN_WIDTH_MULT = 0.55f;
    /** 单个角度上厚度倍率的下限，防止多层波谷叠加后光带断开 */
    private static final float MIN_THICKNESS_MULT = 0.25f;
    /** 闪焰让该段同时变亮的比例：亮度增益 = 闪焰伸长量 × 此值 */
    private static final float FLARE_ALPHA_GAIN = 0.9f;
    /** 外缘波形带动的明暗起伏幅度（相对亮度） */
    private static final float WAVE_LIGHT_AMP = 0.25f;
    /** 呼吸带动的整体明暗起伏幅度（相对亮度） */
    private static final float BREATH_LIGHT_AMP = 0.15f;

    /** 新人类：绿色与蓝绿色沿圆周渐变 */
    public static final Color NEWTYPE_GREEN = new Color(110, 255, 140, 255);
    public static final Color NEWTYPE_TEAL = new Color(40, 220, 215, 255);
    /** 一圈内双色渐变的完整周期数 */
    public static final int NEWTYPE_GRADIENT_CYCLES = 2;

    /** 强化人：紫色到洋红，尺寸为新人类的 75% */
    public static final Color AUGMENTED_PURPLE = new Color(175, 80, 255, 255);
    private static final Color AUGMENTED_MAGENTA = new Color(232, 85, 215, 255);
    public static final float AUGMENTED_SIZE_MULT = 0.75f;
    /** 强化人的整体节奏倍率：转速、波形、呼吸、闪焰都按它加快，显得更急促 */
    private static final float AUGMENTED_TEMPO = 1.45f;
    /** 强化人的闪焰更高 */
    private static final float AUGMENTED_FLARE_MULT = 1.3f;
    /** 强化人闪焰之间的间隔更短 */
    private static final float AUGMENTED_GAP_MULT = 0.6f;
    private static final int GRADIENT_STEPS = 128;      // 一个双色渐变周期的采样数

    /**
     * 三层极光。转速为新人类的取值（度/秒，正为逆时针）；强化人整层反向并乘以 {@link #AUGMENTED_TEMPO}。
     * 厚度、振幅、闪焰伸长量都相对于基础厚度。
     */
    private static final LayerSpec[] LAYERS = {
            // 底层：宽而暗，几乎不动，缓慢正弦呼吸，偶尔有大片柔和的闪焰
            new LayerSpec(
                    0.85f, 0.55f,       // 厚度倍率、亮度
                    4f, 10f, 0.03f,     // 转速（度/秒）、光带脉动相位速度（度/秒）、光带脉动幅度
                    2, 0.10f, 0.12f,    // 外缘波峰数、波幅、波形相对本层的漂移（弧度/秒）
                    BreathCurve.SINE, 0.07f, 5.5f, // 呼吸曲线、幅度、周期（秒）
                    2, 70f, 130f,       // 闪焰槽数、弧宽范围（度）
                    0.18f, 0.30f,       // 闪焰伸长量范围
                    1.4f, 2.2f, 1.8f, 2.8f, 0.8f, 2.5f, // 淡入、淡出、间隔的范围（秒）
                    0f),                // 颜色渐变的相位偏移（周期）
            // 中层：反向转，三瓣波形，呼吸在两端停留（缓入缓出的三角波），闪焰中等
            new LayerSpec(
                    1.0f, 0.5f,
                    -7f, -14f, 0.04f,
                    3, 0.12f, -0.2f,
                    BreathCurve.EASED_TRIANGLE, 0.06f, 3.8f,
                    3, 35f, 75f,
                    0.25f, 0.45f,
                    0.8f, 1.4f, 1.0f, 1.8f, 0.4f, 1.6f,
                    0.33f),
            // 外层：细丝，伸得最远，两个不成整数比的正弦叠加，几乎不重复；闪焰小而快
            new LayerSpec(
                    1.15f, 0.35f,
                    11f, 20f, 0.05f,
                    5, 0.07f, 0.35f,
                    BreathCurve.DRIFT, 0.05f, 2.9f,
                    4, 15f, 40f,
                    0.30f, 0.60f,
                    0.35f, 0.7f, 0.5f, 1.0f, 0.2f, 1.2f,
                    0.66f),
    };
    // ================================

    public enum Style {
        NEWTYPE(1f),
        AUGMENTED(AUGMENTED_SIZE_MULT);

        final float sizeMult;

        Style(float sizeMult) {
            this.sizeMult = sizeMult;
        }
    }

    /** 呼吸曲线：三层各用一种，避免同步起伏。 */
    private enum BreathCurve {
        /** 普通正弦 */
        SINE,
        /** 三角波再做缓入缓出：在最大、最小处停留，中间较快地过渡 */
        EASED_TRIANGLE,
        /** 两个周期比为黄金比例的正弦叠加，几乎不重复 */
        DRIFT
    }

    private static final class LayerSpec {
        final float thicknessMult, alpha;
        final float rotateSpeed, bandPhaseSpeed, bandPulse;
        final int waveCycles;
        final float waveAmp, waveDrift;
        final BreathCurve breathCurve;
        final float breathAmp, breathPeriod;
        final int flareSlots;
        final float flareArcMin, flareArcMax, flareExtraMin, flareExtraMax;
        final float flareInMin, flareInMax, flareOutMin, flareOutMax, flareGapMin, flareGapMax;
        final float colorOffset;

        LayerSpec(float thicknessMult, float alpha,
                  float rotateSpeed, float bandPhaseSpeed, float bandPulse,
                  int waveCycles, float waveAmp, float waveDrift,
                  BreathCurve breathCurve, float breathAmp, float breathPeriod,
                  int flareSlots, float flareArcMin, float flareArcMax,
                  float flareExtraMin, float flareExtraMax,
                  float flareInMin, float flareInMax, float flareOutMin, float flareOutMax,
                  float flareGapMin, float flareGapMax,
                  float colorOffset) {
            this.thicknessMult = thicknessMult;
            this.alpha = alpha;
            this.rotateSpeed = rotateSpeed;
            this.bandPhaseSpeed = bandPhaseSpeed;
            this.bandPulse = bandPulse;
            this.waveCycles = waveCycles;
            this.waveAmp = waveAmp;
            this.waveDrift = waveDrift;
            this.breathCurve = breathCurve;
            this.breathAmp = breathAmp;
            this.breathPeriod = breathPeriod;
            this.flareSlots = flareSlots;
            this.flareArcMin = flareArcMin;
            this.flareArcMax = flareArcMax;
            this.flareExtraMin = flareExtraMin;
            this.flareExtraMax = flareExtraMax;
            this.flareInMin = flareInMin;
            this.flareInMax = flareInMax;
            this.flareOutMin = flareOutMin;
            this.flareOutMax = flareOutMax;
            this.flareGapMin = flareGapMin;
            this.flareGapMax = flareGapMax;
            this.colorOffset = colorOffset;
        }
    }

    private final ShipAPI ship;
    private final Style style;
    private final Moci_Util.AuroraCombatPlugin[] layers = new Moci_Util.AuroraCombatPlugin[LAYERS.length];
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
            float baseThickness = minThickness + (fullThickness - minThickness) * level;

            CombatEngineAPI engine = Global.getCombatEngine();
            double elapsed = engine != null ? engine.getTotalElapsedTime(false) : 0.0;
            for (int i = 0; i < LAYERS.length; i++) {
                LayerSpec spec = LAYERS[i];
                float outer = inner + baseThickness * spec.thicknessMult;
                Moci_Util.AuroraCombatPlugin layer = layers[i];
                PilotAuraParams params;
                if (layer == null || layer.isExpired() || layer.isFadingOut()) {
                    params = new PilotAuraParams(style, spec);
                    params.setAnchor(ship)
                            .setRadius(inner, outer)
                            .setAlphaMult(ALPHA * spec.alpha)
                            // 厚度逐帧变化，必须锁定平铺次数，否则纹理会一格一格突跳
                            .setLockTexTiling(true)
                            // duration < 0：不自己到时，由 fade() 负责淡出
                            .setLifetime(FADE_IN, -1f, FADE_OUT);
                    layers[i] = Moci_Util.spawnAurora(params);
                } else {
                    params = (PilotAuraParams) layer.getParams();
                    params.setRadius(inner, outer);
                }
                params.setTime(elapsed);
            }
        } catch (RuntimeException | LinkageError e) {
            fade();
            if (!errorLogged) {
                errorLogged = true;
                LOG.warn(LOG_PREFIX + "周身极光更新失败 style=" + style + " ship=" + ship.getId(), e);
            }
        }
    }

    /** 收掉极光（一边向外扩开一边淡出，之后自行移除）。幂等，可以每帧调。 */
    public void fade() {
        for (int i = 0; i < layers.length; i++) {
            Moci_Util.AuroraCombatPlugin current = layers[i];
            if (current == null) continue;
            layers[i] = null;
            try {
                if (current.getParams() instanceof PilotAuraParams) {
                    ((PilotAuraParams) current.getParams()).beginFadeOut();
                }
                current.fadeOutAndExpire();
            } catch (RuntimeException | LinkageError ignored) {
                // 战场已经换了：旧特效随旧引擎一起没了
            }
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

    // ---------- 单层极光的逐角度参数 ----------

    private static final class PilotAuraParams extends Moci_Util.AuroraParams {
        private final LayerSpec spec;
        private final Color[] lut;
        /** 节奏倍率：新人类 1，强化人 {@link #AUGMENTED_TEMPO} */
        private final float tempo;
        /** 转向：新人类 1，强化人 -1 */
        private final float direction;
        private final float flareMult, gapMult;
        private final Random random = new Random();

        // 闪焰槽：0 = 间隔中，1 = 淡入，2 = 淡出。固定数组，逐帧不分配对象。
        private final int[] flareState;
        private final float[] flareTimer, flareIn, flareOut, flareCenter, flareHalfArc, flareExtra;
        /** 本帧每个闪焰的亮度（已按开根号曲线处理），逐角度回调里直接用 */
        private final float[] flareLevel;

        private double lastElapsed = Double.NaN;
        /** 生成时刻与开始消失的时刻（战斗总时间），用于启动 / 消失时的缩放 */
        private double spawnTime = Double.NaN;
        private double fadeStartTime = Double.NaN;
        private float rotation;      // 本层转过的角度（度）
        private float wavePhase;     // 外缘波形相位（弧度）
        private float breath;        // 呼吸值 -1 ~ 1

        PilotAuraParams(Style style, LayerSpec spec) {
            this.spec = spec;
            boolean augmented = style == Style.AUGMENTED;
            lut = augmented ? AUGMENTED_GRADIENT_LUT : NEWTYPE_GRADIENT_LUT;
            tempo = augmented ? AUGMENTED_TEMPO : 1f;
            direction = augmented ? -1f : 1f;
            flareMult = augmented ? AUGMENTED_FLARE_MULT : 1f;
            gapMult = augmented ? AUGMENTED_GAP_MULT : 1f;
            color = lut[0];
            shortenMult = spec.bandPulse;
            phaseSpeed = spec.bandPhaseSpeed * tempo * direction;
            innerOffsetMult = 0f; // 内缘始终固定在贴舰半径
            wobble = 0f;          // 外缘只用相对厚度起伏，避免固定像素偏移淹没小舰光环

            int n = spec.flareSlots;
            flareState = new int[n];
            flareTimer = new float[n];
            flareIn = new float[n];
            flareOut = new float[n];
            flareCenter = new float[n];
            flareHalfArc = new float[n];
            flareExtra = new float[n];
            flareLevel = new float[n];
            for (int i = 0; i < n; i++) {
                // 开场错开：各槽先等一段随机间隔，避免同时亮起
                flareState[i] = 0;
                flareTimer[i] = range(0f, spec.flareGapMax + spec.flareInMax) * gapMult;
            }
        }

        void setTime(double elapsed) {
            float dt = Double.isNaN(lastElapsed) ? 0f : (float) Math.max(0.0, Math.min(0.25, elapsed - lastElapsed));
            lastElapsed = elapsed;
            if (Double.isNaN(spawnTime)) spawnTime = elapsed;
            double t = elapsed * tempo;

            rotation = (float) ((t * spec.rotateSpeed * direction) % 360.0);
            // 波形跟着本层转，同时相对本层缓慢漂移
            wavePhase = (float) (t * spec.waveDrift * direction
                    - Math.toRadians(rotation) * spec.waveCycles);
            breath = breathValue(spec.breathCurve, t / spec.breathPeriod);
            advanceFlares(dt * tempo);
        }

        private static float breathValue(BreathCurve curve, double cycles) {
            switch (curve) {
                case EASED_TRIANGLE: {
                    double u = cycles - Math.floor(cycles);
                    double x = u < 0.5 ? 2.0 * u : 2.0 - 2.0 * u;   // 0→1→0 的三角波
                    double eased = x * x * (3.0 - 2.0 * x);       // 缓入缓出
                    return (float) (eased * 2.0 - 1.0);
                }
                case DRIFT:
                    return (float) (0.6 * Math.sin(Math.PI * 2.0 * cycles)
                            + 0.4 * Math.sin(Math.PI * 2.0 * cycles / 0.618 + 1.3));
                default:
                    return (float) Math.sin(Math.PI * 2.0 * cycles);
            }
        }

        private void advanceFlares(float dt) {
            for (int i = 0; i < flareState.length; i++) {
                flareTimer[i] -= dt;
                if (flareTimer[i] <= 0f) {
                    if (flareState[i] == 0) {
                        // 新闪焰：随机位置、弧宽、伸长量、淡入淡出时长
                        flareState[i] = 1;
                        flareCenter[i] = range(0f, 360f);
                        flareHalfArc[i] = range(spec.flareArcMin, spec.flareArcMax) * 0.5f;
                        flareExtra[i] = range(spec.flareExtraMin, spec.flareExtraMax) * flareMult;
                        flareIn[i] = range(spec.flareInMin, spec.flareInMax);
                        flareOut[i] = range(spec.flareOutMin, spec.flareOutMax);
                        flareTimer[i] = flareIn[i];
                    } else if (flareState[i] == 1) {
                        flareState[i] = 2;
                        flareTimer[i] = flareOut[i];
                    } else {
                        flareState[i] = 0;
                        flareTimer[i] = range(spec.flareGapMin, spec.flareGapMax) * gapMult;
                    }
                }
                float level;
                if (flareState[i] == 1) {
                    level = 1f - Math.max(0f, flareTimer[i]) / Math.max(0.001f, flareIn[i]);
                } else if (flareState[i] == 2) {
                    level = Math.max(0f, flareTimer[i]) / Math.max(0.001f, flareOut[i]);
                } else {
                    level = 0f;
                }
                // 与原版 FlareManager 一样用开根号：亮起得快、退去得慢
                level = level * level * (3f - 2f * level);
                flareLevel[i] = (float) Math.sqrt(Math.max(0f, level));
            }
        }

        void beginFadeOut() {
            if (Double.isNaN(fadeStartTime)) fadeStartTime = currentTime();
        }

        private double currentTime() {
            CombatEngineAPI engine = Global.getCombatEngine();
            return engine != null ? engine.getTotalElapsedTime(false) : lastElapsed;
        }

        /**
         * 启动与消失时的厚度缩放：启动时 0.2 秒内从 {@link #GROW_START_SCALE} 长到 1（先快后慢），
         * 消失时在淡出的同时向外扩大到 1 + {@link #GROW_OUT_EXTRA}。消失后不再有 update，所以这里直接读战斗时间。
         */
        private float growScale() {
            double now = currentTime();
            float scale = 1f;
            if (!Double.isNaN(spawnTime)) {
                float u = (float) Math.max(0.0, Math.min(1.0, (now - spawnTime) / GROW_IN));
                float eased = 1f - (1f - u) * (1f - u) * (1f - u);
                scale = GROW_START_SCALE + (1f - GROW_START_SCALE) * eased;
            }
            if (!Double.isNaN(fadeStartTime)) {
                float u = (float) Math.max(0.0, Math.min(1.0, (now - fadeStartTime) / GROW_OUT));
                float eased = 1f - (1f - u) * (1f - u);
                scale *= 1f + GROW_OUT_EXTRA * eased;
            }
            return scale;
        }

        /** 该角度上所有闪焰叠加后的伸长量（相对基础厚度） */
        private float flareAt(float angle) {
            float sum = 0f;
            for (int i = 0; i < flareLevel.length; i++) {
                float level = flareLevel[i];
                if (level <= 0f) continue;
                // 闪焰随本层一起转
                float d = angle - (flareCenter[i] + rotation);
                d -= 360f * (float) Math.floor((d + 180f) / 360f);
                float x = Math.abs(d) / flareHalfArc[i];
                if (x >= 1f) continue;
                sum += flareExtra[i] * level * (0.5f + 0.5f * (float) Math.cos(Math.PI * x));
            }
            return sum;
        }

        @Override
        public float getAuroraTextureRotation() {
            return rotation;
        }

        @Override
        public float getAuroraThicknessMult(float angle) {
            double a = Math.toRadians(angle);
            float mult = 1f + spec.breathAmp * breath
                    + spec.waveAmp * (float) Math.sin(a * spec.waveCycles + wavePhase)
                    + flareAt(angle);
            return Math.max(MIN_THICKNESS_MULT, mult) * growScale();
        }

        @Override
        public float getAuroraAlphaMultForAngle(float angle) {
            double a = Math.toRadians(angle);
            float wave = (float) Math.sin(a * spec.waveCycles + wavePhase);
            return (1f + BREATH_LIGHT_AMP * breath) * (1f - WAVE_LIGHT_AMP + WAVE_LIGHT_AMP * wave)
                    + FLARE_ALPHA_GAIN * flareAt(angle);
        }

        @Override
        public Color getAuroraColorForAngle(float angle) {
            double phase = (angle - rotation) * NEWTYPE_GRADIENT_CYCLES / 360.0 + spec.colorOffset;
            int index = (int) ((phase - Math.floor(phase)) * lut.length);
            return lut[Math.min(lut.length - 1, Math.max(0, index))];
        }

        private float range(float min, float max) {
            return min + (max - min) * random.nextFloat();
        }
    }
}
