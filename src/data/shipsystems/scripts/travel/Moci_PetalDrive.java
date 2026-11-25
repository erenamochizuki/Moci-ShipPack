package data.shipsystems.scripts.travel;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Random;
import data.scripts.util.Moci_Util.PetalPulse;

/** 翠瓣跃迁。这里调翠瓣专用效果；两者共用的闪白、光带与入场冲击波在 Moci_DiamondDrive 开头的 Landing 组。 */
public class Moci_PetalDrive extends Moci_DiamondDrive {
    // 翠瓣专用：时间为战斗秒，R 为包含子模块的舰体半径，透明度为 0～1。
    // 只改这里的 ECHO_PEAK 不会影响菱标。例：1.5f 表示最大放大到舰体的 1.5 倍。
    static final class Petal {
        static final float ECHO_PEAK = 2f, ECHO_ALPHA = .20f; // 最大倍率、虚影初始透明度
        static final Style STYLE = new Style(true,
                4.65f, // 从短菱标开始到舰船现身的充能时间
                .15f,  // 现身后，虚影从原尺寸到峰值的时间
                .32f,  // 现身后，闪白、引擎增亮和光带消退的时间
                .40f,  // 现身后，虚影缩回并消失的时间；必须大于放大时间
                .56f,  // 现身后，地面光斑与向外冲击波消散的时间
                ECHO_PEAK, ECHO_ALPHA,
                new Color(140,255,156), // 花瓣、地面绿光、电弧外缘及飞溅粒子的颜色
                new Color(184,255,194), // 开场菱标、双环、镜头光晕和显形冲击波的颜色，不影响收缩波
                new Color(255,114,100), // 引擎光带颜色
                80);                    // 现身瞬间飞溅的粒子数

        // 开场菱标：淡入、淡出起点均占菱标自身时长的比例。
        static final float MARKER_TIME = .36f, MARKER_FADE_IN = .14f, MARKER_FADE_OUT_START = .46f;
        static final float MARKER_RADIUS = .25f, MARKER_EXPAND = .50f, MARKER_EDGE = .11f, MARKER_ALPHA = 1f; // 半径与增量（R）、相对边宽、亮度

        // 随机花瓣。随机范围采用「最小值 + 随机增量」，两瓣各自抽样，不会同时长成一样的形状。
        static final int PETALS_PER_BURST = 2; // 每批生成几瓣；0 表示关闭
        static final float FIRST_BIRTH = .025f, INTERVAL_MIN = .026f, INTERVAL_RANDOM = .029f; // 第一批出现时间、批次间隔的最小值 (>0) 与随机增量（秒）
        static final float INTERVAL_ACCEL = .48f; // 满充能时批次间隔缩短的比例；0 表示不加速，必须小于 1
        static final float GROW_MIN = .055f, GROW_RANDOM = .065f; // 单瓣放大时间（秒）
        static final float SHRINK_MIN = .085f, SHRINK_RANDOM = .09f; // 单瓣缩小直至消失的时间（秒）
        static final float REACH_MIN = .78f, REACH_RANDOM = 1.05f, SIZE = 1.04f; // 单瓣长度范围（R）、整体大小倍率
        static final float WIDTH_MIN = .21f, WIDTH_RANDOM = .19f, SKEW = .44f; // 半宽占瓣长的比例范围、两侧轮廓随机偏斜幅度
        static final float ALPHA_MIN = .55f, ALPHA_RANDOM = .43f, ALPHA_MULT = .60f; // 单瓣透明度范围、整体倍率；调低 ALPHA_MULT 会让所有花瓣更淡
        static final float GROUP_ALPHA = .47f, GROUP_BRIGHTEN = .44f; // 花瓣层初始亮度、充能到顶时增加的亮度
        static final float GROUP_START = .13f, GROUP_FADE_IN = .26f; // 花瓣层从充能第几秒开始可见、淡入时长

        // 蓄能光斑和白芯。尺寸均为 R 的倍数。
        static final float SPOT_RADIUS = .48f, SPOT_EXPAND = .82f, SPOT_ALPHA = .58f;
        static final float CORE_START = .45f, CORE_RISE = 2.20f; // 白芯开始时间、从亮起到满亮的时间
        static final float CORE_RADIUS = .14f, CORE_EXPAND = .38f, CORE_ALPHA = 1f;

        // 收缩波：频率单位为圈/秒，结束频率大于起始频率时，越充能越快。
        static final Color CONTRACTING_RING_COLOR = new Color(184,255,194); // 仅用于充能时向内聚拢的环；红、绿、蓝各为 0～255
        static final float WAVE_HZ_START = 1.55f, WAVE_HZ_END = 4.60f;
        static final float WAVE_RADIUS = 2.35f, WAVE_CONTRACT = 1.85f, WAVE_EDGE = .045f; // 外侧起始半径、向内移动的距离、环的半宽（R）
        static final float WAVE_ALPHA = .16f, WAVE_BRIGHTEN = .38f; // 初始亮度、充能到顶的增亮量

        // 高充能：电弧、双环和镜头光晕共用触发时刻。提前量应大于 0，不超过充能时长。
        static final float ARC_LEAD = 1.06f; // 现身前几秒开始双环、闪电和光晕；此时停发新收缩波，已有的一圈自然收完
        static final int ARCS_PER_BURST = 3; // 每批电弧数；0 表示关闭闪电，不关闭光晕和双环
        static final float ARC_INTERVAL = .11f, ARC_INTERVAL_RANDOM = .045f; // 批次间隔最小值与随机增量（秒）；越小越频繁
        static final float ARC_REACH = 1.45f, ARC_REACH_RANDOM = 1.60f; // 电弧长度的最小值与随机增量（R）
        static final float ARC_WIDTH = .042f, ARC_MIN_WIDTH = 5f; // 宽度（R）、最小宽度（世界单位）
        static final float OUTER_RISE = .42f, OUTER_RADIUS = .38f, OUTER_EXPAND = 2.70f; // 外环扩散时长、起始半径、增量（R）
        static final float OUTER_IN_EDGE = .035f, OUTER_OUT_EDGE = .045f, OUTER_ALPHA = 1f; // 外环内外侧软边宽（R）、亮度
        static final float INNER_RADIUS = .18f, INNER_EXPAND = .74f; // 内环起始半径、增量（R）
        static final float INNER_IN_EDGE = .10f, INNER_OUT_EDGE = .09f, INNER_ALPHA = 1f; // 内环内外侧软边宽（R）、亮度
        static final float LENS_SIZE = 1f, LENS_POWER = 1f; // 镜头光晕的整体尺寸、强度倍率；强度 0 表示关闭
        static final float LENS_RISE = .14f, LENS_BASE = .62f, LENS_TAIL = .12f; // 初次亮起时长、起亮占峰值比例、现身后余辉时长
    }

    static final Style PETAL=Petal.STYLE;
    public Moci_PetalDrive() { super(PETAL); }

    // 对线性变化的频率积分：加快收缩波时，正在收缩的那一圈不会突然跳回外侧。
    static float waveCycles(float elapsed,float charge) { return elapsed*(Petal.WAVE_HZ_START+.5f*(Petal.WAVE_HZ_END-Petal.WAVE_HZ_START)*elapsed/charge); }
    // 每个整数圈数都是一次新生成：到达停发后的第一个整数就结束，不再回到外缘。
    static float contractingRingPhase(float elapsed,float charge) {
        float cycles=waveCycles(elapsed,charge), cutoff=charge-Petal.ARC_LEAD;
        if(elapsed>=cutoff && cycles>=Math.ceil(waveCycles(cutoff,charge))) return -1f;
        return cycles%1f;
    }
    static float lensPower(float post) {
        if(post < -Petal.ARC_LEAD || post>=Petal.LENS_TAIL) return 0;
        if(post>=0) return 1-Style.ease(post/Petal.LENS_TAIL);
        float age=post+Petal.ARC_LEAD;
        return Style.ease(age/Petal.LENS_RISE)*(Petal.LENS_BASE+(1f-Petal.LENS_BASE)*Style.ease(age/Petal.ARC_LEAD));
    }

    static PetalPulse[] makePetals(long seed,float charge) {
        if(Petal.PETALS_PER_BURST<=0) return new PetalPulse[0];
        if(!(Petal.INTERVAL_MIN>0 && Petal.INTERVAL_RANDOM>=0 && Petal.INTERVAL_ACCEL<1f))
            throw new IllegalArgumentException("花瓣批次间隔必须为正，充能加速比例必须小于 1");
        // 同批额外花瓣用独立随机源，增减数量不会改变第一瓣或下一批的出场节奏。
        var rng=new Random(seed);
        var companion=new Random(seed^0x5DE4AC671B23L);
        var pulses=new ArrayList<PetalPulse>();
        for(float birth=Petal.FIRST_BIRTH; birth<charge; ) {
            pulses.add(samplePetal(rng,birth));
            for(int i=1;i<Petal.PETALS_PER_BURST;i++) pulses.add(samplePetal(companion,birth));
            birth+=(Petal.INTERVAL_MIN+Petal.INTERVAL_RANDOM*rng.nextFloat())*(1f-Petal.INTERVAL_ACCEL*birth/charge);
        }
        return pulses.toArray(new PetalPulse[0]);
    }

    private static PetalPulse samplePetal(Random rng,float birth) {
        float grow=Petal.GROW_MIN+Petal.GROW_RANDOM*rng.nextFloat(), shrink=Petal.SHRINK_MIN+Petal.SHRINK_RANDOM*rng.nextFloat();
        float reach=Petal.REACH_MIN+Petal.REACH_RANDOM*rng.nextFloat();
        return new PetalPulse(birth,grow,shrink,360f*rng.nextFloat(),reach,
                reach*(Petal.WIDTH_MIN+Petal.WIDTH_RANDOM*rng.nextFloat()),Petal.SKEW*(rng.nextFloat()-.5f),(Petal.ALPHA_MIN+Petal.ALPHA_RANDOM*rng.nextFloat())*Petal.ALPHA_MULT);
    }
}
