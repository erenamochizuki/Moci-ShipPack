package data.campaign.intel.ui;

import java.awt.Color;
import java.util.List;
import java.util.Random;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.PositionAPI;

/**
 * 脉动波纹：3 个从 inner 扩到 outer、边扩边淡出的圆环（轮流出现），中半径上 2 段慢速反向旋转的弧，
 * outer 处一圈 36 个刻度，inner 处一个很淡的六边形。整体很淡，只做背景，不抢立绘和信息签。
 *
 * <p>只渲染：processInput 什么都不做，<b>绝不 consume 任何事件</b>。所有图形都收在面板内：
 * outer 超过「圆心到最近边的距离」时自动缩小。</p>
 *
 * <p>全部画在 renderBelow 里（本面板子组件之下），两种用法都不会盖住内容：直接当 aiStage 面板自己的插件，
 * 立绘、信息签作为子组件自然盖在波纹上；或者单独建一个同尺寸面板，先于立绘 addComponent。</p>
 *
 * <p>base 只取 RGB，alpha 忽略；传 null 用航空中队扫描线的青绿色。再传一个副色时，所有图形按位置在两色之间渐变，
 * 渐变方向绕圆心缓慢旋转。</p>
 *
 * <p>detail ≥ 2 起有径向光线，detail ≥ 3 起有海浪粒子。专属层二选一：奈因保留向外飞的数字，
 * 苍钢系在最终阶段显示一圈六边形流光，满级显示两圈；其他 AI 只有通用背景。</p>
 */
public class Moci_UIPulseRings extends BaseCustomUIPanelPlugin {
    // ===================== 可调参数 =====================
    /** 一个圆环从 inner 扩到 outer 的时间（秒）。RING_COUNT 个环错开均分，每 RING_LIFE / RING_COUNT 秒冒出一个。 */
    private static final float RING_LIFE = 6f;
    /** 圆环个数（设计稿：3 个）。 */
    private static final int RING_COUNT = 3;
    /** 圆环最亮时的透明度（刚出现时），之后边扩边淡出到 0。 */
    private static final float RING_ALPHA = 0.20f;
    /** 圆环半宽（逻辑像素）：中线最亮，向内外各羽化到 0。 */
    private static final float RING_HALF_WIDTH = 1.5f;
    /** 圆环刚出现时的淡入时长（占寿命的比例），免得在 inner 处突然冒出来。 */
    private static final float RING_FADE_IN = 0.08f;
    /** 弧 A：跨度（度）、转速（度/秒，正 = 逆时针），约 30 秒一圈。 */
    private static final float ARC_A_SPAN = 70f;
    private static final float ARC_A_SPEED = 12f;
    /** 弧 B：跨度（度）、转速（度/秒），和 A 反向，45 秒一圈。初始与 A 相隔半圈。 */
    private static final float ARC_B_SPAN = 110f;
    private static final float ARC_B_SPEED = -8f;
    /** 弧头部（沿旋转方向的前端）透明度，尾端是它的 ARC_TAIL_RATIO 倍。弧用加色混合，略带发光。 */
    private static final float ARC_ALPHA = 0.30f;
    private static final float ARC_TAIL_RATIO = 0.15f;
    /** 弧半宽（逻辑像素）。 */
    private static final float ARC_HALF_WIDTH = 2f;
    /** 刻度个数（设计稿：36 个，每 10° 一个；要能整除 SEGMENTS），每 90° 一个长刻度。 */
    private static final int TICK_COUNT = 36;
    /** 刻度长度（逻辑像素，从 outer 往里画）：普通 / 每 90° 的长刻度。 */
    private static final float TICK_LENGTH = 4f;
    private static final float TICK_MAJOR_LENGTH = 8f;
    /** 刻度透明度：普通 / 长刻度。 */
    private static final float TICK_ALPHA = 0.14f;
    private static final float TICK_MAJOR_ALPHA = 0.26f;
    /** inner 处六边形（外接圆半径 = inner，平顶）的透明度。 */
    private static final float HEX_ALPHA = 0.08f;
    /** 没调用 setRadius 时：outer = 圆心到最近边的距离（扣掉羽化），inner = outer × 这个比例。 */
    private static final float DEFAULT_INNER_RATIO = 0.6f;
    /** base 为 null 时的颜色：航空中队扫描线色 (0.25, 0.95, 0.70)。 */
    private static final float[] DEFAULT_COLOR = {0.25f, 0.95f, 0.70f};
    /** advance 单步上限（秒）。 */
    private static final float MAX_STEP = 0.1f;
    /** 科技线路上移动光点的半径，单位为 UI 逻辑像素；当前固定 2.4，直径 4.8，不随机。 */
    private static final float CIRCUIT_DOT_RADIUS = 2.4f;
    /** 线路光点身后尾迹的半径；当前固定 1.7，直径 3.4，不随机。 */
    private static final float CIRCUIT_TRAIL_RADIUS = 1.7f;
    /** 满级扩散粒子的最小半径（UI 逻辑像素）；修改这里和下面的最大半径即可调整随机尺寸范围。 */
    private static final float MAX_LEVEL_PARTICLE_MIN_RADIUS = 1.5f;
    /** 满级扩散粒子的最大半径（UI 逻辑像素）；羽化边缘透明，视觉上会略小。 */
    private static final float MAX_LEVEL_PARTICLE_MAX_RADIUS = 6f;
    /** 满级扩散粒子的生成间隔（秒）。 */
    private static final float MAX_LEVEL_PARTICLE_INTERVAL = 1f;
    /** 每批最少粒子数。 */
    private static final int MAX_LEVEL_PARTICLE_MIN_COUNT = 12;
    /** 每批最多粒子数。 */
    private static final int MAX_LEVEL_PARTICLE_MAX_COUNT = 24;
    /** 单个扩散粒子的运动寿命（秒），保持原来的 6 秒；与生成间隔分开调整。 */
    private static final float MAX_LEVEL_PARTICLE_LIFE = 6f;
    /** 同批粒子的最大随机出生时差（秒），避免整齐地同时出现。 */
    private static final float MAX_LEVEL_PARTICLE_BIRTH_SPREAD = 0.72f;

    // ==================== 阶段特效速查 ====================
    // detail 由调用方 setDetail 传入（Moci_CombatSquadDialog.buildAIStage：AI 等级 1–3 / 4–6 / 7–9 / 10+ → detail 1 / 2 / 3 / 4），
    // 满级由 setMaxLevel 传入（AI 等级 ≥ 该 AI 自己的等级上限）。
    //   detail 1（初始）：只有原来的圆环、弧、刻度、线路
    //   detail 2 起：加光线            ← 改 RAY_MIN_DETAIL
    //   detail 3 起：加海浪粒子        ← 改 WAVE_MIN_DETAIL
    //   奈因：detail 4 起半量数字，满级满量；生成频率见 DIGIT_INTERVAL_MIN / MAX。
    //   苍钢：最终档一圈流光；若满级前到不了第 4 档，则在满级前一级出现。满级一律两圈。
    // 想整层关掉：把对应的 *_ENABLED 改成 false。想调亮度：改各层的 *_ALPHA。
    // 两种颜色在 Moci_CombatSquadDialog 的 AI_FX（主色）和 AI_FX_2（副色）里，按阶段各一组。
    // 改完需要重新打包 JAR 才生效。
    // ======================================================

    // ---------- 双色渐变（所有阶段都有） ----------
    /** 主色、副色各占圆的一侧，沿一条穿过圆心的方向过渡；这个方向转一圈的时间（秒）。≤ 0 时方向固定不转。 */
    private static final float GRADIENT_PERIOD = 18f;

    // ---------- 海浪粒子（detail ≥ WAVE_MIN_DETAIL 时出现，数量随 detail 增加） ----------
    /** 海浪总开关：false 则任何阶段都不出现海浪。 */
    private static final boolean WAVE_ENABLED = true;
    /** 从第几阶段开始有海浪（detail 1 = 初始阶段）。 */
    private static final int WAVE_MIN_DETAIL = 3;
    /** 同时存在的浪数：各浪错开均分，一道接一道向外涌。 */
    private static final int WAVE_FRONTS = 4;
    /** 一道浪从内圈涌到外圈的时间（秒）。 */
    private static final float WAVE_LIFE = 7f;
    /** 每道浪的粒子数 = BASE + PER_DETAIL × detail（detail 3、4 → 132、160 个，总数 528、640 个点）。 */
    private static final int WAVE_POINTS_BASE = 48;
    private static final int WAVE_POINTS_PER_DETAIL = 28;
    /** 浪线起伏幅度（占全程的比例）：越大，浪线越弯。 */
    private static final float WAVE_RIPPLE = 0.065f;
    /** 浪尖身后拖着的飞沫厚度（占全程的比例）。 */
    private static final float WAVE_THICKNESS = 0.10f;
    /** 起伏形状绕圆心漂移的速度（圈/秒）。 */
    private static final float WAVE_DRIFT_SPEED = 0.07f;
    /** 浪从 inner × 这个比例处冒出。 */
    private static final float WAVE_START_RATIO = 0.75f;
    /** 粒子最亮时的透明度（加色混合）。 */
    private static final float WAVE_ALPHA = 0.55f;
    /** 粒子直径（逻辑像素）：约三分之一是大粒子，其余是小粒子。 */
    private static final float WAVE_POINT_SMALL = 2f;
    private static final float WAVE_POINT_LARGE = 3f;

    // ---------- 向外射出的光线（detail ≥ RAY_MIN_DETAIL 时出现，频率随 detail 增加） ----------
    /** 光线总开关：false 则任何阶段都不出现光线。 */
    private static final boolean RAY_ENABLED = true;
    /** 从第几阶段开始有光线（detail 1 = 初始阶段）。 */
    private static final int RAY_MIN_DETAIL = 2;
    /** 同时存在的光线上限。 */
    private static final int RAY_CAPACITY = 24;
    /** 两条光线的生成间隔（秒，随机取值）；实际间隔再除以 0.4 + 0.2 × detail，即 detail 2–4 → 除以 0.8–1.2。 */
    private static final float RAY_INTERVAL_MIN = 0.08f;
    private static final float RAY_INTERVAL_MAX = 0.28f;
    /** 单条光线从射出到消失的时间（秒，随机取值）。 */
    private static final float RAY_LIFE_MIN = 0.8f;
    private static final float RAY_LIFE_MAX = 1.7f;
    /** 光线长度（逻辑像素，随机取值，短的居多）。 */
    private static final float RAY_LENGTH_MIN = 36f;
    private static final float RAY_LENGTH_MAX = 150f;
    /** 光线最宽处的半宽（逻辑像素，随机取值）。 */
    private static final float RAY_HALF_WIDTH_MIN = 1.2f;
    private static final float RAY_HALF_WIDTH_MAX = 2.8f;
    /** 光线从 inner × 这个比例处射出。 */
    private static final float RAY_START_RATIO = 0.70f;
    /** 光线最远射到 outer × 这个比例（碰到面板边缘就提前停）；调成 1 则不超出外圈。 */
    private static final float RAY_REACH = 1.35f;
    /** 光线最亮处的透明度（加色混合）。 */
    private static final float RAY_ALPHA = 0.6f;

    // ---------- 奈因专属：向外射出的数字（detail ≥ DIGIT_MIN_DETAIL 时少量出现，同时满级才满量） ----------
    /** 数字总开关：false 则任何阶段都不出现数字。 */
    private static final boolean DIGIT_ENABLED = true;
    /** 从第几阶段开始有数字（detail 1 = 初始阶段）。没到这个阶段时，即使满级也没有数字。 */
    private static final int DIGIT_MIN_DETAIL = 4;
    /** 到了上面的阶段但未满级时的数字量，相对满量的比例（0.5 = 生成频率减半，1 = 和满级一样多）。 */
    private static final float DIGIT_PARTIAL_RATIO = 0.5f;
    /** 同时存在的数字上限。 */
    private static final int DIGIT_CAPACITY = 32;
    /** 满量时两个数字的生成间隔（秒，随机取值）；未满级时间隔再除以 DIGIT_PARTIAL_RATIO。 */
    private static final float DIGIT_INTERVAL_MIN = 0.13f;
    private static final float DIGIT_INTERVAL_MAX = 0.33f;
    /** 单个数字从出现到消失的时间（秒，随机取值）。 */
    private static final float DIGIT_LIFE_MIN = 2.6f;
    private static final float DIGIT_LIFE_MAX = 4.4f;
    /** 数字高度（逻辑像素，随机取值）；飞出过程中从 0.75 倍放大到 1.2 倍。 */
    private static final float DIGIT_SIZE_MIN = 6f;
    private static final float DIGIT_SIZE_MAX = 11f;
    /** 数字向右倾斜的程度（0 = 不倾斜）。 */
    private static final float DIGIT_SLANT = 0.16f;
    /** 会不断跳变数值的数字所占比例。 */
    private static final float DIGIT_FLIP_CHANCE = 0.4f;
    /** 数字最远飞到 outer × 这个比例（碰到面板边缘就提前停）；调成 1 则不超出外圈。 */
    private static final float DIGIT_REACH = 1.3f;
    /** 数字最亮时的透明度（加色混合）。 */
    private static final float DIGIT_ALPHA = 0.7f;
    // ====================================================

    /** 圆周分段：每段 5°。sin/cos 预先算好，渲染时只有弧的起始角要算三角函数。 */
    private static final int SEGMENTS = 72;
    private static final float STEP_DEG = 360f / SEGMENTS;
    private static final float[] COS = new float[SEGMENTS + 1];
    private static final float[] SIN = new float[SEGMENTS + 1];
    private static final int ARC_A_STEPS = arcSteps(ARC_A_SPAN);
    private static final int ARC_B_STEPS = arcSteps(ARC_B_SPAN);
    private static final int ATTRIB_BITS = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT
            | GL11.GL_LINE_BIT | GL11.GL_POINT_BIT | GL11.GL_LIGHTING_BIT;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    /** 海浪起伏用的正弦表（一圈 256 格）：每帧几百个粒子只查表，不算三角函数。 */
    private static final int WAVE_LUT = 256;
    private static final int WAVE_MASK = WAVE_LUT - 1;
    private static final float[] WAVE_SIN = new float[WAVE_LUT];
    /** 各道浪一圈里的起伏个数，互不相同，免得几道浪形状一样。 */
    private static final int[] WAVE_LOBES = {3, 5, 4, 6};
    private static final int WAVE_POINTS_MAX = WAVE_POINTS_BASE + WAVE_POINTS_PER_DETAIL * 4;
    private static final int WAVE_CAPACITY = WAVE_POINTS_MAX * WAVE_FRONTS;
    /** 七段数码管：bit0–bit6 依次是 上、右上、右下、下、左下、左上、中 七段。 */
    private static final int[] DIGIT_SEGMENTS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};
    /** 七段的两个端点（x1, y1, x2, y2）：x 以半宽为单位，y 以半高为单位，y 向上。 */
    private static final float[] SEGMENT_POINTS = {
            -1f, 1f, 1f, 1f,
            1f, 1f, 1f, 0f,
            1f, 0f, 1f, -1f,
            -1f, -1f, 1f, -1f,
            -1f, 0f, -1f, -1f,
            -1f, 1f, -1f, 0f,
            -1f, 0f, 1f, 0f};

    static {
        for (int i = 0; i < SEGMENTS; i++) {
            double t = Math.PI * 2.0 * i / SEGMENTS;
            COS[i] = (float) Math.cos(t);
            SIN[i] = (float) Math.sin(t);
        }
        COS[SEGMENTS] = COS[0];
        SIN[SEGMENTS] = SIN[0];
        for (int i = 0; i < WAVE_LUT; i++) {
            WAVE_SIN[i] = (float) Math.sin(Math.PI * 2.0 * i / WAVE_LUT);
        }
    }

    enum Signature {
        NONE(0), NINE_DIGITS(0), ARP_HALF(1), ARP_FULL(2);

        final int hexTracks;

        Signature(int hexTracks) {
            this.hexTracks = hexTracks;
        }

        static Signature forAI(String aiTypeId, int level, int levelCap, boolean finalStage) {
            if ("Moci_Nine".equals(aiTypeId)) return NINE_DIGITS;
            if (aiTypeId == null || !aiTypeId.startsWith("Moci_ARP_")) return NONE;
            if (level >= levelCap) return ARP_FULL;
            return finalStage || level == levelCap - 1 ? ARP_HALF : NONE;
        }
    }

    private final Signature signature;
    private float hexPhase;
    private final float colorR, colorG, colorB;
    /** 渐变副色；没传副色时等于主色，即不渐变。 */
    private final float color2R, color2G, color2B;
    /** 渐变方向当前的角度（弧度）及其 cos、sin；gradInv = 0.5 / outer，把圆内位置换算成 0–1 的混色比例。 */
    private float gradAngle;
    private float gradCos = 1f, gradSin, gradInv;
    /** shade() 算出的当前颜色。 */
    private float tintR, tintG, tintB;
    /** 本帧的圆心（屏幕坐标），以及圆心到面板 左、右、上、下 四条边的距离。 */
    private float ox, oy, boxL, boxR, boxU, boxD;
    /** 线路绘制当前的透明度。 */
    private float lineAlpha;
    /** 界面缩放倍数：glPointSize 的单位是物理像素，乘上它才是逻辑像素。 */
    private final float pointScale;
    private PositionAPI position;
    private boolean hasCenter;
    private float centerX;
    private float centerY;
    private boolean hasRadius;
    private float inner;
    private float outer;
    /** 圆环进度 0~1（RING_LIFE 秒一轮）。各动画分别回卷，长时间打开也不会丢精度。 */
    private float ringPhase;
    /** 两段弧当前的起始角（度，0~360）。 */
    private float arcA;
    private float arcB = 180f;
    /** 0 保持基础波纹，1–4 逐步增加线路、节点、分段弧和流动光点。 */
    private int detail;
    private boolean maxLevel;
    private final Random random = new Random();
    // 最短 2 秒一批，最长 6 秒路程，16 个槽位足够容纳同时存活的光点。
    private final float[] dotAge = new float[16];
    private final float[] dotLife = new float[16];
    private final int[] dotRoute = new int[16];
    private float nextDots = 2f + random.nextFloat() * 2f;
    // 按寿命和间隔预留重叠批次的容量；生成、更新和绘制时不创建临时粒子对象。
    private static final int MAX_LEVEL_PARTICLE_CAPACITY = ((int) Math.ceil(
            (MAX_LEVEL_PARTICLE_LIFE + MAX_LEVEL_PARTICLE_BIRTH_SPREAD) / MAX_LEVEL_PARTICLE_INTERVAL) + 1)
            * MAX_LEVEL_PARTICLE_MAX_COUNT;
    private final float[] particleCos = new float[MAX_LEVEL_PARTICLE_CAPACITY];
    private final float[] particleSin = new float[MAX_LEVEL_PARTICLE_CAPACITY];
    private final float[] particleAge = new float[MAX_LEVEL_PARTICLE_CAPACITY];
    private final float[] particleLife = new float[MAX_LEVEL_PARTICLE_CAPACITY];
    /** 每次生成时随机尺寸，运动中保持不变，避免闪烁。 */
    private final float[] particleRadius = new float[MAX_LEVEL_PARTICLE_CAPACITY];
    private float nextMaxLevelParticles = MAX_LEVEL_PARTICLE_INTERVAL;
    private float sampleX, sampleY;
    // 海浪粒子：方向、起伏查表位置、落后浪尖的距离、亮度、偏色都在构造时定好，运行中只推进两个相位。
    private final float[] waveCos = new float[WAVE_CAPACITY];
    private final float[] waveSin = new float[WAVE_CAPACITY];
    private final int[] waveAngle = new int[WAVE_CAPACITY];
    private final float[] waveJitter = new float[WAVE_CAPACITY];
    private final float[] waveBright = new float[WAVE_CAPACITY];
    private final float[] waveTone = new float[WAVE_CAPACITY];
    private float wavePhase;
    private float waveDrift;
    // 光线：固定槽位，消失后复用。
    private final float[] rayCos = new float[RAY_CAPACITY];
    private final float[] raySin = new float[RAY_CAPACITY];
    private final float[] rayAge = new float[RAY_CAPACITY];
    private final float[] rayLife = new float[RAY_CAPACITY];
    private final float[] rayLength = new float[RAY_CAPACITY];
    private final float[] rayWidth = new float[RAY_CAPACITY];
    private final float[] rayReach = new float[RAY_CAPACITY];
    private final float[] rayTone = new float[RAY_CAPACITY];
    private float nextRay;
    /** prepareRay() 算出的当前光线：尾、最亮处、头的半径，以及透明度。 */
    private float rayTail, rayPeak, rayTip, rayAlpha;
    // 数字：固定槽位，消失后复用。
    private final float[] digitCos = new float[DIGIT_CAPACITY];
    private final float[] digitSin = new float[DIGIT_CAPACITY];
    private final float[] digitAge = new float[DIGIT_CAPACITY];
    private final float[] digitLife = new float[DIGIT_CAPACITY];
    private final float[] digitSize = new float[DIGIT_CAPACITY];
    private final float[] digitStart = new float[DIGIT_CAPACITY];
    private final float[] digitReach = new float[DIGIT_CAPACITY];
    private final float[] digitTone = new float[DIGIT_CAPACITY];
    /** 数值跳变间隔（秒）；0 = 不跳变。 */
    private final float[] digitFlip = new float[DIGIT_CAPACITY];
    private final int[] digitValue = new int[DIGIT_CAPACITY];
    private float nextDigit;
    /** 第一帧 advance 时预放光线和数字，只做一次。 */
    private boolean primed;

    public Moci_UIPulseRings(Color base) {
        this(base, null);
    }

    /** secondary 是渐变的副色，只取 RGB；传 null 则整体保持 base 单色。 */
    public Moci_UIPulseRings(Color base, Color secondary) {
        this(base, secondary, Signature.NONE);
    }

    Moci_UIPulseRings(Color base, Color secondary, Signature signature) {
        this.signature = signature;
        if (base != null) {
            colorR = base.getRed() / 255f;
            colorG = base.getGreen() / 255f;
            colorB = base.getBlue() / 255f;
        } else {
            colorR = DEFAULT_COLOR[0];
            colorG = DEFAULT_COLOR[1];
            colorB = DEFAULT_COLOR[2];
        }
        if (secondary != null) {
            color2R = secondary.getRed() / 255f;
            color2G = secondary.getGreen() / 255f;
            color2B = secondary.getBlue() / 255f;
        } else {
            color2R = colorR;
            color2G = colorG;
            color2B = colorB;
        }
        float scale = Global.getSettings().getScreenScaleMult();
        pointScale = Float.isFinite(scale) && scale > 0f ? scale : 1f;
        gradAngle = random.nextFloat() * TWO_PI;
        initWaves();
    }

    /** 每道浪的粒子按黄金分割角排布：只用前若干个（detail 较低）时也能均匀铺满一圈。 */
    private void initWaves() {
        for (int i = 0; i < WAVE_CAPACITY; i++) {
            int front = i % WAVE_FRONTS;
            int slot = i / WAVE_FRONTS;
            double turn = slot * 0.6180339887 + front * 0.37 + random.nextFloat() * 0.004;
            turn -= Math.floor(turn);
            waveCos[i] = (float) Math.cos(turn * Math.PI * 2.0);
            waveSin[i] = (float) Math.sin(turn * Math.PI * 2.0);
            waveAngle[i] = (int) (turn * WAVE_LUT) & WAVE_MASK;
            // 多数粒子贴着浪尖，少数落在后面成为飞沫；越靠后越暗。
            float behind = random.nextFloat();
            waveJitter[i] = -behind * behind * WAVE_THICKNESS;
            waveBright[i] = 1f - 0.6f * behind;
            waveTone[i] = (random.nextFloat() - 0.5f) * 0.5f;
        }
    }

    /** 面板内坐标，左上为原点（y 向下），默认面板中心。非有限值忽略。 */
    public Moci_UIPulseRings setCenter(float localX, float localY) {
        if (Float.isFinite(localX) && Float.isFinite(localY)) {
            this.centerX = localX;
            this.centerY = localY;
            this.hasCenter = true;
        }
        return this;
    }

    /** 圆环从 inner 扩到 outer（逻辑像素）。两者顺序反了会自动交换；outer 超出面板时自动缩小。非有限值忽略。 */
    public Moci_UIPulseRings setRadius(float inner, float outer) {
        if (Float.isFinite(inner) && Float.isFinite(outer)) {
            this.inner = inner;
            this.outer = outer;
            this.hasRadius = true;
        }
        return this;
    }

    @Override public void positionChanged(PositionAPI position) {
        this.position = position;
    }

    public Moci_UIPulseRings setDetail(int grade) {
        detail = Math.max(0, Math.min(4, grade));
        return this;
    }

    private boolean raysActive() {
        return RAY_ENABLED && detail >= RAY_MIN_DETAIL;
    }

    private boolean wavesActive() {
        return WAVE_ENABLED && detail >= WAVE_MIN_DETAIL;
    }

    /** 奈因沿用原数字节奏：最终阶段半量，最终阶段且满级时满量；其他 AI 不生成也不绘制数字。 */
    private float digitAmount() {
        if (!DIGIT_ENABLED || signature != Signature.NINE_DIGITS || detail < DIGIT_MIN_DETAIL) return 0f;
        return maxLevel ? 1f : DIGIT_PARTIAL_RATIO;
    }

    /** 满级按该核心自己的等级上限判断，与特效档位独立。 */
    public Moci_UIPulseRings setMaxLevel(boolean value) {
        if (value && !maxLevel) {
            java.util.Arrays.fill(particleLife, 0f);
            spawnMaxLevelParticles();
            nextMaxLevelParticles = MAX_LEVEL_PARTICLE_INTERVAL;
        }
        maxLevel = value;
        return this;
    }

    @Override public void advance(float amount) {
        if (!(amount > 0f)) return; // 0、负数、NaN 都不走
        float dt = Math.min(amount, MAX_STEP);
        if (signature.hexTracks > 0) hexPhase = (hexPhase + dt / Moci_UIArpHexFlow.PERIOD) % 1f;
        ringPhase = (ringPhase + dt / RING_LIFE) % 1f;
        arcA = wrapDeg(arcA + dt * ARC_A_SPEED);
        arcB = wrapDeg(arcB + dt * ARC_B_SPEED);
        for (int i = 0; i < dotLife.length; i++) {
            if (dotLife[i] > 0f) {
                dotAge[i] += dt;
                if (dotAge[i] >= dotLife[i]) dotLife[i] = 0f;
            }
        }
        if (detail >= 4) {
            nextDots -= dt;
            if (nextDots <= 0f) {
                spawnCircuitDots();
                nextDots += 2f + random.nextFloat() * 2f;
            }
        }
        if (maxLevel) {
            for (int i = 0; i < particleLife.length; i++) {
                if (particleLife[i] <= 0f) continue;
                particleAge[i] += dt;
                if (particleAge[i] >= particleLife[i]) particleLife[i] = 0f;
            }
            nextMaxLevelParticles -= dt;
            if (nextMaxLevelParticles <= 0f) {
                spawnMaxLevelParticles();
                nextMaxLevelParticles += MAX_LEVEL_PARTICLE_INTERVAL;
            }
        }
        if (GRADIENT_PERIOD > 0f) gradAngle = (gradAngle + dt * TWO_PI / GRADIENT_PERIOD) % TWO_PI;
        float digits = digitAmount();
        if (!primed) {
            // 第一帧（阶段和满级都已设好）先放出几条已经在半路上的光线和数字，免得开头几秒空着。
            primed = true;
            if (raysActive()) for (int k = 0; k < 2 + detail; k++) spawnRay(random.nextFloat() * 0.7f);
            for (int k = 0; k < Math.round(10f * digits); k++) spawnDigit(random.nextFloat() * 0.7f);
        }
        if (wavesActive()) {
            wavePhase = (wavePhase + dt / WAVE_LIFE) % 1f;
            waveDrift = (waveDrift + dt * WAVE_DRIFT_SPEED) % 1f;
        }
        if (raysActive()) {
            float rate = 0.4f + 0.2f * detail;
            for (int i = 0; i < RAY_CAPACITY; i++) {
                if (rayLife[i] <= 0f) continue;
                rayAge[i] += dt;
                if (rayAge[i] >= rayLife[i]) rayLife[i] = 0f;
            }
            nextRay -= dt;
            if (nextRay <= 0f) {
                spawnRay(0f);
                nextRay = (RAY_INTERVAL_MIN + random.nextFloat() * (RAY_INTERVAL_MAX - RAY_INTERVAL_MIN)) / rate;
            }
        }
        if (digits > 0f) {
            for (int i = 0; i < DIGIT_CAPACITY; i++) {
                if (digitLife[i] <= 0f) continue;
                digitAge[i] += dt;
                if (digitAge[i] >= digitLife[i]) digitLife[i] = 0f;
            }
            nextDigit -= dt;
            if (nextDigit <= 0f) {
                spawnDigit(0f);
                nextDigit = (DIGIT_INTERVAL_MIN + random.nextFloat() * (DIGIT_INTERVAL_MAX - DIGIT_INTERVAL_MIN)) / digits;
            }
        }
    }

    /** 在空槽位里放一条随机方向、长度、粗细的光线；progress 是出生时已走完的寿命比例（0 = 刚射出）。槽位满了就不生成。 */
    private void spawnRay(float progress) {
        for (int i = 0; i < RAY_CAPACITY; i++) {
            if (rayLife[i] > 0f) continue;
            double angle = random.nextDouble() * Math.PI * 2.0;
            rayCos[i] = (float) Math.cos(angle);
            raySin[i] = (float) Math.sin(angle);
            float longer = random.nextFloat();
            rayLength[i] = RAY_LENGTH_MIN + longer * longer * (RAY_LENGTH_MAX - RAY_LENGTH_MIN);
            rayWidth[i] = RAY_HALF_WIDTH_MIN + random.nextFloat() * (RAY_HALF_WIDTH_MAX - RAY_HALF_WIDTH_MIN);
            rayReach[i] = 0.85f + random.nextFloat() * Math.max(0f, RAY_REACH - 0.85f);
            rayTone[i] = (random.nextFloat() - 0.5f) * 0.8f;
            rayLife[i] = RAY_LIFE_MIN + random.nextFloat() * (RAY_LIFE_MAX - RAY_LIFE_MIN);
            rayAge[i] = rayLife[i] * progress;
            return;
        }
    }

    /** 在空槽位里放一个随机方向、大小、数值的数字；progress 含义同 spawnRay。槽位满了就不生成。 */
    private void spawnDigit(float progress) {
        for (int i = 0; i < DIGIT_CAPACITY; i++) {
            if (digitLife[i] > 0f) continue;
            double angle = random.nextDouble() * Math.PI * 2.0;
            digitCos[i] = (float) Math.cos(angle);
            digitSin[i] = (float) Math.sin(angle);
            digitSize[i] = DIGIT_SIZE_MIN + random.nextFloat() * (DIGIT_SIZE_MAX - DIGIT_SIZE_MIN);
            digitStart[i] = random.nextFloat() * 0.3f;
            digitReach[i] = 0.9f + random.nextFloat() * Math.max(0f, DIGIT_REACH - 0.9f);
            digitTone[i] = (random.nextFloat() - 0.5f) * 0.8f;
            digitValue[i] = random.nextInt(10);
            digitFlip[i] = random.nextFloat() < DIGIT_FLIP_CHANCE ? 0.3f + random.nextFloat() * 0.6f : 0f;
            digitLife[i] = DIGIT_LIFE_MIN + random.nextFloat() * (DIGIT_LIFE_MAX - DIGIT_LIFE_MIN);
            digitAge[i] = digitLife[i] * progress;
            return;
        }
    }

    /** 每批重新随机数量、方向、尺寸和出生时差；只复用已经消失的粒子槽位。 */
    private void spawnMaxLevelParticles() {
        int remaining = MAX_LEVEL_PARTICLE_MIN_COUNT
                + random.nextInt(MAX_LEVEL_PARTICLE_MAX_COUNT - MAX_LEVEL_PARTICLE_MIN_COUNT + 1);
        for (int i = 0; i < particleLife.length && remaining > 0; i++) {
            if (particleLife[i] > 0f) continue;
            double angle = random.nextDouble() * Math.PI * 2.0;
            particleCos[i] = (float) Math.cos(angle);
            particleSin[i] = (float) Math.sin(angle);
            particleRadius[i] = MAX_LEVEL_PARTICLE_MIN_RADIUS
                    + random.nextFloat() * (MAX_LEVEL_PARTICLE_MAX_RADIUS - MAX_LEVEL_PARTICLE_MIN_RADIUS);
            particleAge[i] = -random.nextFloat() * MAX_LEVEL_PARTICLE_BIRTH_SPREAD;
            particleLife[i] = MAX_LEVEL_PARTICLE_LIFE;
            remaining--;
        }
    }

    private void spawnCircuitDots() {
        int remaining = 1 + random.nextInt(4);
        // 每批线路不重复，上一批未走完的光点继续保留。
        int usedRoutes = 0;
        for (int i = 0; i < dotLife.length && remaining > 0; i++) {
            if (dotLife[i] > 0f) continue;
            int route = random.nextInt(8 * detail);
            while ((usedRoutes & (1 << route)) != 0) route = (route + 1) % (8 * detail);
            usedRoutes |= 1 << route;
            dotRoute[i] = route;
            dotAge[i] = 0f;
            dotLife[i] = 4f + random.nextFloat() * 2f;
            remaining--;
        }
    }

    @Override public void processInput(List<InputEventAPI> events) {
        // 只渲染：不读、也不 consume 任何事件。
    }

    /** 画在子组件之下：波纹只做背景，立绘和信息签永远在它上面。 */
    @Override public void renderBelow(float alphaMult) {
        if (position == null) return;
        float w = position.getWidth();
        float h = position.getHeight();
        float a = clamp01(alphaMult);
        if (w <= 0f || h <= 0f || a <= 0f) return;
        float lx = hasCenter ? centerX : w * 0.5f;
        float ly = hasCenter ? centerY : h * 0.5f;
        if (lx < 0f || lx > w || ly < 0f || ly > h) return; // 圆心在面板外就不画
        // 最大半径：圆心到最近边的距离，扣掉羽化宽度，保证不画出面板。
        float maxR = Math.min(Math.min(lx, w - lx), Math.min(ly, h - ly))
                - Math.max(RING_HALF_WIDTH, ARC_HALF_WIDTH) - 1f;
        float ro;
        float ri;
        if (hasRadius) {
            ro = Math.min(Math.max(inner, outer), maxR);
            ri = Math.max(0f, Math.min(Math.min(inner, outer), ro));
        } else {
            ro = maxR;
            ri = ro * DEFAULT_INNER_RATIO;
        }
        if (ro < 4f) return;
        // 面板内坐标（y 向下）→ 屏幕坐标（y 向上）：(x + lx, y + h - ly)
        float cx = position.getX() + lx;
        float cy = position.getY() + h - ly;
        ox = cx;
        oy = cy;
        boxL = lx;
        boxR = w - lx;
        boxU = ly;
        boxD = h - ly;
        gradCos = (float) Math.cos(gradAngle);
        gradSin = (float) Math.sin(gradAngle);
        gradInv = 0.5f / ro;

        GL11.glPushAttrib(ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glShadeModel(GL11.GL_SMOOTH); // 羽化依赖逐顶点颜色插值
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1f);

            // 1) inner 处的淡六边形（平顶）
            if (ri > 2f) {
                GL11.glBegin(GL11.GL_LINE_LOOP);
                for (int k = 0; k < 6; k++) {
                    int idx = k * SEGMENTS / 6;
                    tint(COS[idx] * ri, SIN[idx] * ri, 0f, a * HEX_ALPHA);
                    GL11.glVertex2f(cx + COS[idx] * ri, cy + SIN[idx] * ri);
                }
                GL11.glEnd();
            }

            // 2) outer 处一圈刻度，从 outer 往里画，每 90° 一个长刻度
            int majorEvery = Math.max(1, TICK_COUNT / 4);
            GL11.glBegin(GL11.GL_LINES);
            for (int k = 0; k < TICK_COUNT; k++) {
                int idx = k * SEGMENTS / TICK_COUNT;
                boolean major = k % majorEvery == 0;
                float r0 = Math.max(0f, ro - (major ? TICK_MAJOR_LENGTH : TICK_LENGTH));
                tint(COS[idx] * ro, SIN[idx] * ro, 0f, a * (major ? TICK_MAJOR_ALPHA : TICK_ALPHA));
                GL11.glVertex2f(cx + COS[idx] * r0, cy + SIN[idx] * r0);
                GL11.glVertex2f(cx + COS[idx] * ro, cy + SIN[idx] * ro);
            }
            GL11.glEnd();

            // 3) 外扩淡出的圆环，错开均分轮流出现；半径先快后慢，透明度随扩散线性淡出
            for (int k = 0; k < RING_COUNT; k++) {
                float p = ringPhase + (float) k / RING_COUNT;
                if (p >= 1f) p -= 1f;
                float ease = 1f - (1f - p) * (1f - p);
                float r = ri + (ro - ri) * ease;
                float fade = (p < RING_FADE_IN ? p / RING_FADE_IN : 1f) * (1f - p);
                float ra = a * RING_ALPHA * fade;
                band(cx, cy, r, RING_HALF_WIDTH, 1f, 0f, SEGMENTS, ra, ra);
            }

            // 4) 中半径上两段反向慢转的弧：加色混合，头部（旋转方向的前端）最亮，尾端淡出
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            float rm = (ri + ro) * 0.5f;
            drawArc(cx, cy, rm, arcA, ARC_A_STEPS, ARC_A_SPEED > 0f, a);
            drawArc(cx, cy, rm, arcB, ARC_B_STEPS, ARC_B_SPEED > 0f, a);
            if (detail > 0) drawCircuits(cx, cy, ri, ro, a);
            if (maxLevel) drawMaxLevelParticles(cx, cy, ri, ro, a);
            // 5) 海浪、光线和专属特效都画在立绘和信息签下面。
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            if (wavesActive()) drawWaves(ri, ro, a);
            if (raysActive()) drawRays(ri, ro, a);
            if (digitAmount() > 0f) drawDigits(ri, ro, a);
            if (signature.hexTracks > 0) Moci_UIArpHexFlow.draw(cx, cy, ro, Math.min(boxL, boxR),
                    hexPhase, signature.hexTracks, a, pointScale);
        } finally {
            GL11.glPopAttrib();
        }
    }

    /** 固定线路批量绘制；只让细小光点缓慢行进，避免整层明暗脉冲。 */
    private void drawCircuits(float cx, float cy, float ri, float ro, float a) {
        float space = ro - ri;
        if (space < 24f) return;
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        lineAlpha = a * 0.26f;
        GL11.glBegin(GL11.GL_LINES);
        for (int sector = 0; sector < 8; sector++) {
            int idx = sector * SEGMENTS / 8;
            float c = COS[idx], s = SIN[idx];
            for (int lane = 0; lane < detail; lane++) {
                float offset = (lane - (detail - 1) * 0.5f) * 5f;
                float r0 = ri * 0.94f;
                float r1 = ri + space * (0.28f + lane * 0.14f);
                circuitVertex(cx, cy, c, s, r0, offset);
                circuitVertex(cx, cy, c, s, r1, offset);
                circuitVertex(cx, cy, c, s, r1, offset);
                circuitVertex(cx, cy, c, s, r1 + 6f, offset + 6f);
                circuitVertex(cx, cy, c, s, r1 + 6f, offset + 6f);
                circuitVertex(cx, cy, c, s, r1 + 6f, offset + 18f);
                if (detail >= 2) {
                    // 线路末端的小菱形节点。
                    float r = r1 + 6f, t = offset + 18f;
                    for (int k = 0; k < 4; k++) {
                        int p = k * SEGMENTS / 4, q = (k + 1) * SEGMENTS / 4;
                        circuitVertex(cx, cy, c, s, r + COS[p] * 2.5f, t + SIN[p] * 2.5f);
                        circuitVertex(cx, cy, c, s, r + COS[q] * 2.5f, t + SIN[q] * 2.5f);
                    }
                }
            }
        }
        GL11.glEnd();
        if (detail >= 3) {
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            for (int k = 0; k < 4; k++) {
                double angle = Math.toRadians(arcB + k * 90f);
                band(cx, cy, ro - 14f, 0.8f, (float) Math.cos(angle), (float) Math.sin(angle),
                        7, a * 0.06f, a * 0.32f);
            }
        }
        if (detail >= 4) {
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            GL11.glBegin(GL11.GL_TRIANGLES);
            for (int k = 0; k < dotLife.length; k++) {
                if (dotLife[k] <= 0f) continue;
                float p = dotAge[k] / dotLife[k];
                float fade = Math.min(1f, Math.min(p, 1f - p) * 10f);
                for (int trail = 3; trail >= 0; trail--) {
                    float t = p - trail * 0.022f;
                    if (t < 0f) continue;
                    sampleCircuit(dotRoute[k], t, ri, ro);
                    glowDiamond(cx + sampleX, cy + sampleY, trail == 0 ? CIRCUIT_DOT_RADIUS : CIRCUIT_TRAIL_RADIUS,
                            a * fade * (trail == 0 ? 0.9f : 0.24f / trail));
                }
            }
            GL11.glEnd();
        }
    }

    /** 与 circuitVertex 使用相同的三段折线，按路程匀速走过径向段、斜角和末端横线。 */
    private void sampleCircuit(int route, float p, float ri, float ro) {
        int lane = route % detail;
        int idx = route / detail * SEGMENTS / 8;
        float offset = (lane - (detail - 1) * 0.5f) * 5f;
        float r0 = ri * 0.94f;
        float r1 = ri + (ro - ri) * (0.28f + lane * 0.14f);
        float first = r1 - r0;
        float diagonal = 8.485281f; // sqrt(6² + 6²)
        float distance = clamp01(p) * (first + diagonal + 12f);
        float radius, tangent;
        if (distance <= first) {
            radius = r0 + distance;
            tangent = offset;
        } else if (distance <= first + diagonal) {
            float step = (distance - first) / diagonal * 6f;
            radius = r1 + step;
            tangent = offset + step;
        } else {
            radius = r1 + 6f;
            tangent = offset + 6f + distance - first - diagonal;
        }
        sampleX = COS[idx] * radius - SIN[idx] * tangent;
        sampleY = SIN[idx] * radius + COS[idx] * tangent;
    }

    /** 随机批次的粒子从内圈范围缓慢散开，出生和消失时均透明。 */
    private void drawMaxLevelParticles(float cx, float cy, float ri, float ro, float a) {
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int i = 0; i < particleCos.length; i++) {
            if (particleLife[i] <= 0f || particleAge[i] < 0f) continue;
            float p = particleAge[i] / particleLife[i];
            float ease = 1f - (1f - p) * (1f - p);
            float start = ri * 0.5f;
            float radius = start + (ro - 5f - start) * ease;
            float fade = Math.min(1f, p / 0.16f) * (1f - p) * (1f - p);
            glowDiamond(cx + particleCos[i] * radius, cy + particleSin[i] * radius,
                    particleRadius[i],
                    a * 0.56f * fade);
        }
        GL11.glEnd();
    }

    /** 在同一批 GL_TRIANGLES 内画羽化菱形，中心亮、边缘透明。 */
    private void glowDiamond(float x, float y, float radius, float a) {
        shade(x - ox, y - oy, 0f);
        for (int i = 0; i < 4; i++) {
            int p = i * SEGMENTS / 4, q = (i + 1) * SEGMENTS / 4;
            GL11.glColor4f(tintR, tintG, tintB, a);
            GL11.glVertex2f(x, y);
            GL11.glColor4f(tintR, tintG, tintB, 0f);
            GL11.glVertex2f(x + COS[p] * radius, y + SIN[p] * radius);
            GL11.glVertex2f(x + COS[q] * radius, y + SIN[q] * radius);
        }
    }

    private void circuitVertex(float cx, float cy, float c, float s, float radius, float tangent) {
        float dx = c * radius - s * tangent;
        float dy = s * radius + c * tangent;
        tint(dx, dy, 0f, lineAlpha);
        GL11.glVertex2f(cx + dx, cy + dy);
    }

    /**
     * 按相对圆心的位置 (dx, dy) 算出渐变色，存进 tintR/G/B：渐变方向一侧是主色，另一侧是副色，中间平滑过渡。
     * tone 是额外的偏色量（负偏主色、正偏副色），让粒子、光线、数字在同一位置也有两种颜色混杂。
     */
    private void shade(float dx, float dy, float tone) {
        float t = 0.5f + (dx * gradCos + dy * gradSin) * gradInv + tone;
        t = t > 0f ? (t < 1f ? t : 1f) : 0f;
        t = t * t * (3f - 2f * t);
        tintR = colorR + (color2R - colorR) * t;
        tintG = colorG + (color2G - colorG) * t;
        tintB = colorB + (color2B - colorB) * t;
    }

    /** 算出渐变色并设为当前颜色。 */
    private void tint(float dx, float dy, float tone, float alpha) {
        shade(dx, dy, tone);
        GL11.glColor4f(tintR, tintG, tintB, alpha);
    }

    /** 从圆心沿方向 (c, s) 走到面板边缘的距离（屏幕坐标，s 正 = 向上）。 */
    private float edgeDistance(float c, float s) {
        float d = Float.MAX_VALUE;
        if (c > 1.0e-4f) d = boxR / c;
        else if (c < -1.0e-4f) d = boxL / -c;
        if (s > 1.0e-4f) d = Math.min(d, boxU / s);
        else if (s < -1.0e-4f) d = Math.min(d, boxD / -s);
        return d;
    }

    /**
     * 海浪粒子：每道浪是一圈粒子，整体由内向外涌（先快后慢、边涌边淡出），浪线按两组正弦起伏并缓慢绕圈漂移，
     * 起伏靠前的部分更亮。粒子用 GL_POINTS 画，大小两种各一个批次，每个粒子只有一个顶点。
     */
    private void drawWaves(float ri, float ro, float a) {
        float start = ri * WAVE_START_RATIO;
        float span = ro - 3f - start;
        if (span < 12f) return;
        int count = Math.min(WAVE_POINTS_MAX, WAVE_POINTS_BASE + WAVE_POINTS_PER_DETAIL * detail) * WAVE_FRONTS;
        int drift = (int) (waveDrift * WAVE_LUT);
        float peak = a * WAVE_ALPHA;
        float fadeIn = 0.1f;
        GL11.glEnable(GL11.GL_POINT_SMOOTH);
        for (int pass = 0; pass < 2; pass++) {
            GL11.glPointSize((pass == 0 ? WAVE_POINT_SMALL : WAVE_POINT_LARGE) * pointScale);
            GL11.glBegin(GL11.GL_POINTS);
            for (int i = 0; i < count; i++) {
                if (((i / WAVE_FRONTS) % 3 == 0) != (pass == 1)) continue;
                int front = i % WAVE_FRONTS;
                int lobes = WAVE_LOBES[front % WAVE_LOBES.length];
                int angle = waveAngle[i];
                float ripple = WAVE_SIN[(angle * lobes + drift) & WAVE_MASK] * 0.65f
                        + WAVE_SIN[(angle * (lobes * 2 + 1) - drift * 2 + front * 61) & WAVE_MASK] * 0.35f;
                float p = wavePhase + (float) front / WAVE_FRONTS + waveJitter[i] + ripple * WAVE_RIPPLE;
                if (p < 0f) p += 1f;
                else if (p >= 1f) p -= 1f;
                float alpha = peak * (p < fadeIn ? p / fadeIn : 1f) * (1f - p)
                        * waveBright[i] * (0.6f + 0.4f * ripple);
                if (alpha < 0.012f) continue;
                float radius = start + span * p * (1.4f - 0.4f * p);
                float dx = waveCos[i] * radius;
                float dy = waveSin[i] * radius;
                tint(dx, dy, waveTone[i], alpha);
                GL11.glVertex2f(ox + dx, oy + dy);
            }
            GL11.glEnd();
        }
    }

    /**
     * 光线：从内圈沿径向射出的细长光针，头部附近最亮，头尾两端和两侧都淡到透明。
     * 先用一批三角形画带宽度的光晕，再用一批线段画中间的亮芯。
     */
    private void drawRays(float ri, float ro, float a) {
        float r0 = ri * RAY_START_RATIO;
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int i = 0; i < RAY_CAPACITY; i++) {
            if (!prepareRay(i, r0, ro, a)) continue;
            float c = rayCos[i], s = raySin[i];
            float px = -s * rayWidth[i], py = c * rayWidth[i];
            float tx = ox + c * rayTail, ty = oy + s * rayTail;
            float kx = ox + c * rayPeak, ky = oy + s * rayPeak;
            float hx = ox + c * rayTip, hy = oy + s * rayTip;
            rayTriangle(tx, ty, kx + px, ky + py, kx, ky);
            rayTriangle(tx, ty, kx - px, ky - py, kx, ky);
            rayTriangle(hx, hy, kx + px, ky + py, kx, ky);
            rayTriangle(hx, hy, kx - px, ky - py, kx, ky);
        }
        GL11.glEnd();
        GL11.glBegin(GL11.GL_LINES);
        for (int i = 0; i < RAY_CAPACITY; i++) {
            if (!prepareRay(i, r0, ro, a)) continue;
            float c = rayCos[i], s = raySin[i];
            float kx = ox + c * rayPeak, ky = oy + s * rayPeak;
            GL11.glColor4f(tintR, tintG, tintB, 0f);
            GL11.glVertex2f(ox + c * rayTail, oy + s * rayTail);
            GL11.glColor4f(tintR, tintG, tintB, rayAlpha);
            GL11.glVertex2f(kx, ky);
            GL11.glVertex2f(kx, ky);
            GL11.glColor4f(tintR, tintG, tintB, 0f);
            GL11.glVertex2f(ox + c * rayTip, oy + s * rayTip);
        }
        GL11.glEnd();
    }

    /** 算出第 i 条光线本帧的尾、最亮处、头的半径以及透明度和颜色；不需要画时返回 false。 */
    private boolean prepareRay(int i, float r0, float ro, float a) {
        if (rayLife[i] <= 0f) return false;
        float c = rayCos[i], s = raySin[i];
        // 最远到 outer × rayReach，碰到面板边缘就提前停，留 3px 给光晕半宽。
        float travel = Math.min(ro * rayReach[i], edgeDistance(c, s) - 3f) - r0;
        if (travel < 16f) return false;
        float p = rayAge[i] / rayLife[i];
        rayAlpha = a * RAY_ALPHA * Math.min(1f, p / 0.1f) * Math.min(1f, (1f - p) / 0.35f);
        if (rayAlpha < 0.01f) return false;
        rayTip = r0 + travel * p * (1.5f - 0.5f * p);
        rayTail = Math.max(r0, rayTip - Math.min(rayLength[i], travel * 0.8f));
        rayPeak = rayTail + (rayTip - rayTail) * 0.78f;
        shade(c * rayPeak, s * rayPeak, rayTone[i]);
        return true;
    }

    /** 光针的四分之一：端点和侧边透明，中线上最亮处不透明。 */
    private void rayTriangle(float endX, float endY, float sideX, float sideY, float peakX, float peakY) {
        GL11.glColor4f(tintR, tintG, tintB, 0f);
        GL11.glVertex2f(endX, endY);
        GL11.glVertex2f(sideX, sideY);
        GL11.glColor4f(tintR, tintG, tintB, rayAlpha * 0.55f);
        GL11.glVertex2f(peakX, peakY);
    }

    /**
     * 数字：七段数码管样式的 0–9，正着摆放、略向右倾斜，沿径向由内向外飞（先快后慢），边飞边放大，出现和消失时淡入淡出。
     * 全部数字在一个 GL_LINES 批次里画完，不用贴图和字体。
     */
    private void drawDigits(float ri, float ro, float a) {
        float space = ro - ri;
        GL11.glBegin(GL11.GL_LINES);
        for (int i = 0; i < DIGIT_CAPACITY; i++) {
            if (digitLife[i] <= 0f) continue;
            float c = digitCos[i], s = digitSin[i];
            float p = digitAge[i] / digitLife[i];
            float alpha = a * DIGIT_ALPHA * Math.min(1f, p / 0.12f) * Math.min(1f, (1f - p) / 0.4f);
            if (alpha < 0.01f) continue;
            float size = digitSize[i] * (0.75f + 0.45f * p);
            float r0 = ri * 0.70f + space * digitStart[i];
            // 最远到 outer × digitReach，碰到面板边缘就提前停，留出半个字的位置。
            float travel = Math.min(ro * digitReach[i], edgeDistance(c, s) - size * 0.75f - 2f) - r0;
            if (travel < 8f) continue;
            float radius = r0 + travel * (1f - (1f - p) * (1f - p));
            float dx = c * radius, dy = s * radius;
            tint(dx, dy, digitTone[i], alpha);
            int value = digitValue[i];
            if (digitFlip[i] > 0f) value = (value + (int) (digitAge[i] / digitFlip[i])) % 10;
            int mask = DIGIT_SEGMENTS[value];
            float hw = size * 0.28f, hh = size * 0.5f;
            float slant = hh * DIGIT_SLANT;
            float x = ox + dx, y = oy + dy;
            for (int seg = 0; seg < 7; seg++) {
                if ((mask & (1 << seg)) == 0) continue;
                int o = seg * 4;
                GL11.glVertex2f(x + SEGMENT_POINTS[o] * hw + SEGMENT_POINTS[o + 1] * slant,
                        y + SEGMENT_POINTS[o + 1] * hh);
                GL11.glVertex2f(x + SEGMENT_POINTS[o + 2] * hw + SEGMENT_POINTS[o + 3] * slant,
                        y + SEGMENT_POINTS[o + 3] * hh);
            }
        }
        GL11.glEnd();
    }

    private void drawArc(float cx, float cy, float r, float startDeg, int steps, boolean headAtEnd, float a) {
        double rad = Math.toRadians(startDeg);
        float c0 = (float) Math.cos(rad);
        float s0 = (float) Math.sin(rad);
        float head = a * ARC_ALPHA;
        float tail = head * ARC_TAIL_RATIO;
        band(cx, cy, r, ARC_HALF_WIDTH, c0, s0, steps, headAtEnd ? tail : head, headAtEnd ? head : tail);
    }

    /**
     * 羽化环带：从起始角（c0、s0 是它的 cos、sin）开始逆时针走 steps 个 5° 分段。
     * 中线（半径 r）的透明度从 aStart 线性变到 aEnd，向内外各 hw 淡到 0。steps = SEGMENTS 时是整圈。
     */
    private void band(float cx, float cy, float r, float hw, float c0, float s0, int steps, float aStart, float aEnd) {
        if (steps <= 0 || r <= 0f || (aStart <= 0.002f && aEnd <= 0.002f)) return;
        if (steps > SEGMENTS) steps = SEGMENTS;
        float rIn = Math.max(0f, r - hw);
        float rOut = r + hw;
        for (int pass = 0; pass < 2; pass++) {
            // pass 0：内侧羽化（rIn 透明 → r 最亮）；pass 1：外侧羽化（r 最亮 → rOut 透明）
            float r0 = pass == 0 ? rIn : r;
            float r1 = pass == 0 ? r : rOut;
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for (int i = 0; i <= steps; i++) {
                float c = c0 * COS[i] - s0 * SIN[i];
                float s = s0 * COS[i] + c0 * SIN[i];
                float am = aStart + (aEnd - aStart) * i / steps;
                shade(c * r, s * r, 0f);
                GL11.glColor4f(tintR, tintG, tintB, pass == 0 ? 0f : am);
                GL11.glVertex2f(cx + c * r0, cy + s * r0);
                GL11.glColor4f(tintR, tintG, tintB, pass == 0 ? am : 0f);
                GL11.glVertex2f(cx + c * r1, cy + s * r1);
            }
            GL11.glEnd();
        }
    }

    private static int arcSteps(float spanDeg) {
        return Math.max(1, Math.min(SEGMENTS, Math.round(spanDeg / STEP_DEG)));
    }

    private static float wrapDeg(float d) {
        d %= 360f;
        return d < 0f ? d + 360f : d;
    }

    private static float clamp01(float v) {
        return v > 0f ? (v < 1f ? v : 1f) : 0f; // NaN → 0
    }
}
