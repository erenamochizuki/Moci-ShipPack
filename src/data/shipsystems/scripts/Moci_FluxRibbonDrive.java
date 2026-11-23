package data.shipsystems.scripts;

import static data.shipsystems.scripts.Moci_NebulaGateMotion.*;
import java.awt.Color;

/** 磁弧跃迁：只在穿过显形边界的舰体上显示磁流体光带，不在空中绘制星门。 */
public final class Moci_FluxRibbonDrive extends Moci_NebulaGate {
    static final String KEY = "Moci_FluxRibbonDrive_transit";

    // 仅磁弧版：间隔以动画秒计，拉长对应阶段也会拉长两批之间的实战时间。
    static final Moci_NebulaGateTransit.SmokeSettings RIBBON_SMOKE = new Moci_NebulaGateTransit.SmokeSettings(
            .45f, .74f,  // 单团初始直径：最小、最大值，单位为门半径；想填缝先增大最小值
            4,           // 每批生成几团：0 关闭烟雾，2 表示每批同时在不同方向生成两团（上限 16）
            .042f,       // 蓄势聚云：每批间隔（动画秒）；数值减半，频率翻倍（最短 .01 秒）
            .085f,       // 光带展开到舰尾完全驶出：每批间隔（动画秒）
            .16f,        // 真舰出现后的收尾：每批间隔（动画秒），收束完成后停止生成
            new Color(53,79,163,122), new Color(84,95,186,108), // 靛蓝、蓝紫；依次为红、绿、蓝、不透明度，各 0~255
            new Color(142,51,88,116), new Color(108,58,126,110)); // 暗红、紫色；可增删颜色，保留一种即为单色烟
    // 每项都是这一段实际占用的战斗秒数，不是累计时刻或倍率；默认合计 11.30 秒，保持原来的半速。暂停不计时。
    static final float CHARGE_SECONDS = 2f;     // 入场到蓄势完成（相当于开始开门）：光斑、焦边、聚云、第一批闪电。
    static final float OPENING_SECONDS = 1f;    // 蓄势完成到舰体开始驶出（相当于开门过程）：烟电继续，舰体仍隐藏。
    static final float EMERGE_SECONDS = 5f;     // 舰首开始驶出到舰尾完全显形：越大舰船开得越慢；结束时交还控制、恢复碰撞。
    static final float AFTERGLOW_SECONDS = 2f;  // 交还控制后的收尾时间：烟电逐渐停止生成；已生成的粒子按自身寿命淡出，不再锁船。
    static final float RIBBON_WIDTH_MULT = 0.5f; // 舰体显形处的发光带宽度：1 保留原宽，0.5 变细，2 加宽，0 关闭亮带；不改显形柔边、动画速度或磁流体推进特效。范围 0~3。
    static final float RIBBON_FLASH_SIZE_MULT = 2f; // 开场十字光斑的额外尺寸倍率，只影响磁弧版：1 原尺寸，1.5 放大 50%，0 隐藏光斑。
    static final Timing TIMING = new Timing(CHARGE_SECONDS,OPENING_SECONDS,EMERGE_SECONDS,AFTERGLOW_SECONDS);
    public Moci_FluxRibbonDrive() { super(KEY,true,1f); }

    /** 在战斗秒与原星岚门动画刻度之间换算；从绝对时间定位，低帧率跨段时不丢时间。 */
    static final class Timing {
        private static final float[] ANIM={0,OPEN,EXIT,HANDOFF,END};
        private final float[] battle=new float[5], rate=new float[4];
        Timing(float charge,float opening,float emerge,float afterglow) {
            float[] seconds={charge,opening,emerge,afterglow};
            for (int i=0;i<4;i++) {
                float span=ANIM[i+1]-ANIM[i];
                float duration=Float.isFinite(seconds[i]) ? Math.max(.05f,Math.min(300f,seconds[i])) : 2f*span;
                battle[i+1]=battle[i]+duration; rate[i]=span/duration;
            }
        }
        private static int phase(float time,float[] edges) {
            return time<edges[1]?0:time<edges[2]?1:time<edges[3]?2:3;
        }
        int phaseAt(float animationAge) { return phase(animationAge,ANIM); }
        float rate(int i) { return rate[i]; }
        float animationAt(float battleAge) {
            if (battleAge>=battle[4]) return END;
            int i=phase(battleAge,battle); return ANIM[i]+(battleAge-battle[i])*rate[i];
        }
        float battleAt(float animationAge) {
            int i=phaseAt(animationAge); return battle[i]+(animationAge-ANIM[i])/rate[i];
        }
    }
}
