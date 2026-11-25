package data.shipsystems.scripts.travel;

import data.scripts.util.Moci_TextLoader;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import java.awt.Color;
import data.scripts.util.Moci_Util.RadialLightStyle;

/** 菱标跃迁。开头分两组：Diamond 只改菱标，Landing 同时影响菱标和翠瓣。 */
public class Moci_DiamondDrive extends BaseShipSystemScript {
    // 菱标专用：时间为战斗秒，R 为包含子模块的舰体半径，透明度为 0～1。
    // ECHO_PEAK 是虚影的最大尺寸倍率，例如 1.5f 表示舰体的 1.5 倍。
    static final class Diamond {
        static final float ECHO_PEAK = 1.5f, ECHO_ALPHA = .20f; // 最大倍率、虚影初始透明度（不影响舰体闪白）
        static final Style STYLE = new Style(false,
                .72f,  // 从提示到舰船现身；也是舰船在落点等待的时间
                .15f,  // 现身后，虚影从原尺寸放大到峰值的时间
                .58f,  // 现身后，舰体闪白、引擎增亮和光带全部消退的时间
                .58f,  // 现身后，虚影缩回原尺寸并消失的时间；必须大于放大时间
                .86f,  // 现身后，地面光斑与向外冲击波消散的时间
                ECHO_PEAK, ECHO_ALPHA,
                new Color(227,226,137), // 菱标颜色（RGB，每项 0～255）
                new Color(194,240,210), // 入场冲击波颜色
                new Color(255,79,73),   // 引擎光带颜色
                0);                     // 现身时的飞溅粒子数；0 表示关闭

        static final float MARKER_CHARGE = .81f; // 菱标用掉的充能时间比例，越大扩散越慢
        static final float MARKER_RADIUS = .12f, MARKER_EXPAND = .49f; // 起始半径、向外扩大的距离（R）
        static final float MARKER_EDGE = .08f, MARKER_ALPHA = .85f; // 边缘半宽占菱标半径的比例、峰值透明度
        static final float SPOT_RADIUS = .60f, SPOT_EXPAND = .75f, SPOT_ALPHA = .48f; // 光斑起始半径、增量（R）、峰值透明度
        static final float SPOT_START = .48f; // 在平滑充能进度的哪一点开始亮起；0～小于 1，越小越早
    }

    // 共用入场参数：改这里会同时影响菱标和翠瓣。时间为战斗秒，R 为包含子模块的舰体半径。
    static final class Landing {
        static final Color FLASH = Color.WHITE, HALO = new Color(220,231,255); // 舰体闪光/虚影/电弧白芯等高光、落点光斑颜色
        static final float HULL_GLOW = 1f; // 现身瞬间贴合舰体的闪白强度，不改变虚影透明度
        static final float ENGINE_LENGTH = 1.8f, ENGINE_WIDTH = 2.4f, ENGINE_GLOW = 3f; // 引擎火焰额外增加的长度、宽度、辉光比例
        static final float TRAIL_WIDTH = 1.35f, TRAIL_ALPHA = .82f; // 光带半宽相对喷口宽度的倍率、峰值透明度
        static final float TRAIL_LENGTH = 1f; // 光带长度倍率；1 表示从喷口延伸到地图外
        static final float WAVE_RADIUS = .65f, WAVE_EXPAND = 2.40f, WAVE_ALPHA = 1f; // 入场冲击波起始半径、扩散距离（R）、亮度
        static final float WAVE_IN_EDGE = .035f, WAVE_OUT_EDGE = .045f; // 波峰两侧的软边宽度（R）
        static final float SPOT_RADIUS = 1.80f, HOLE_EXPAND = 1.58f, HOLE_EDGE = .11f, SPOT_ALPHA = .69f; // 地面光斑外半径、中心空洞扩散距离、内缘软边宽（R）、亮度

        // 现身时的飞溅粒子。菱标默认数量为 0，所以默认只在翠瓣可见。
        static final RadialLightStyle SPARKS = new RadialLightStyle(
                .50f, .38f,  // 出生半径：最小值、随机增量（R）
                .65f, 1.60f, // 向外速度：最小值、随机增量（R/秒）
                .085f, .25f, // 粒子大小：最小值、随机增量（R）
                .65f, .35f,  // 亮度：最小值、随机增量（0～1）
                .88f, 1.65f  // 寿命：最短、最长（秒），越长飞得越远
        );
    }

    static final String EVENT="Moci_signalDrive_arrival";
    static final Style DIAMOND=Diamond.STYLE;
    final Style style;
    public Moci_DiamondDrive() { this(DIAMOND); }
    Moci_DiamondDrive(Style style) { this.style=style; }

    static final class Style {
        final boolean petals;
        final float charge,grow,fade,echoEnd,ring,echoPeak,echoOpacity;
        final Color signal,wave,trail;
        final int particles;
        Style(boolean petals,float charge,float grow,float fade,float echoEnd,float ring,float echoPeak,float echoOpacity,
              Color signal,Color wave,Color trail,int particles) {
            if (!(charge>0 && grow>0 && fade>0 && echoEnd>grow && ring>0 && echoPeak>0))
                throw new IllegalArgumentException("跃迁时长和虚影倍率必须大于零，虚影消失时间必须晚于放大峰值");
            this.petals=petals; this.charge=charge; this.grow=grow; this.fade=fade; this.echoEnd=echoEnd; this.ring=ring;
            this.echoPeak=echoPeak; this.echoOpacity=echoOpacity;
            this.signal=signal; this.wave=wave; this.trail=trail; this.particles=particles;
        }
        static float ease(float t) { t=Math.max(0f,Math.min(1f,t)); return t*t*(3f-2f*t); }
        // 两种跃迁共用这条曲线：原尺寸 → 本系统的峰值倍率 → 原尺寸。t 从舰船现身起算。
        float echoScale(float t) {
            float k=t<grow ? ease(t/grow) : 1f-ease((t-grow)/(echoEnd-grow));
            return 1f+(echoPeak-1f)*k;
        }
        float echoAlpha(float t) { return t<0 ? 0 : echoOpacity*(1-ease((t-grow)/(echoEnd-grow))); }
    }

    @Override public void apply(MutableShipStatsAPI stats,String id,State state,float level) {
        if (!(stats.getEntity() instanceof ShipAPI ship)) return;
        if (state==State.OUT) stats.getMaxSpeed().unmodify(id);
        else if (state==State.IN || state==State.ACTIVE) {
            stats.getMaxSpeed().modifyFlat(id,600f*level);
            stats.getAcceleration().modifyFlat(id,600f*level);
        } else return;
        Object active=ship.getCustomData().get(EVENT);
        if (ship.isRetreating()) {
            if (active instanceof Moci_SignalArrival fx) fx.cleanup();
            // Latch retreat for this burn even if the order is cancelled before unapply.
            ship.setCustomData(EVENT,Boolean.TRUE);
            return;
        }
        CombatEngineAPI engine=Global.getCombatEngine();
        if (active instanceof Moci_SignalArrival playing) {
            if (state!=State.OUT) playing.continueBurn();
            return;
        }
        if (active!=null || state==State.OUT || engine==null || ship.isHulk() || ship.getParentStation()!=null) return;
        var fx=new Moci_SignalArrival(engine,ship,style);
        CombatEntityAPI host=engine.addLayeredRenderingPlugin(fx);
        if (host!=null) host.getLocation().set(ship.getLocation());
        ship.setCustomData(EVENT,fx);
        fx.start();
    }
    @Override public void unapply(MutableShipStatsAPI stats,String id) {
        stats.getMaxSpeed().unmodify(id); stats.getAcceleration().unmodify(id);
        if (stats.getEntity() instanceof ShipAPI ship) {
            Object active=ship.getCustomData().get(EVENT);
            if (active instanceof Moci_SignalArrival fx) fx.endBurn();
            else if (active==Boolean.TRUE) ship.removeCustomData(EVENT);
        }
    }
    @Override public StatusData getStatusData(int index,State state,float level) {
        return index==0 ? new StatusData(Moci_TextLoader.getText("code_Moci_DiamondDrive", "t001", "提高引擎出力"),false) : null;
    }
}
