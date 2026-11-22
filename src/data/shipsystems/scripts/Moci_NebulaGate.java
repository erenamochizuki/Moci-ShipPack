package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import java.awt.Color;

/** 入场走星岚门；撤退由原版旅行驱动推进，越界移除由战场管理器负责。 */
public class Moci_NebulaGate extends BaseShipSystemScript {
    static final String EVENT = "Moci_NebulaGate_transit";

    // 星岚门入场烟雾。间隔以动画秒计，播放速度变化时随动画一起伸缩。
    static final Moci_NebulaGateTransit.SmokeSettings GATE_SMOKE = new Moci_NebulaGateTransit.SmokeSettings(
            .90f, 1.48f, // 单团初始直径：最小、最大值，单位为门半径；想填缝先增大最小值
            1,           // 每批生成几团：0 关闭烟雾，2 表示每批同时在不同方向生成两团（上限 16）
            .042f,       // 蓄势聚云：每隔多少动画秒生成一批；数值减半，频率翻倍（最短 .01 秒）
            .085f,       // 开门到舰尾完全驶出：每批间隔（动画秒）
            .16f,        // 真舰出现后的收尾：每批间隔（动画秒），门关闭后停止生成
            new Color(53,79,163,122), new Color(84,95,186,108), // 靛蓝、蓝紫；依次为红、绿、蓝、不透明度，各 0~255
            new Color(142,51,88,116), new Color(108,58,126,110)); // 暗红、紫色；可增删颜色，保留一种即为单色烟

    static final float ANIMATION_SPEED = 1f; // 原版入场播放速度：1 为原速，0.5 为半速；不影响原版撤退驱动
    private final String transitKey;
    private final boolean ribbon;
    private final float speed;
    public Moci_NebulaGate() { this(EVENT,false,ANIMATION_SPEED); }
    Moci_NebulaGate(String key,boolean ribbon,float speed) {
        this.transitKey=key; this.ribbon=ribbon;
        this.speed=Float.isFinite(speed) && speed>0f ? speed : 1f;
    }

    private ShipSystemSpecAPI retreatSpec;
    private ShipSystemStatsScript retreatDrive;

    @Override public void apply(MutableShipStatsAPI stats,String id,State state,float level) {
        if (!(stats.getEntity() instanceof ShipAPI ship)) return;
        if (state!=State.IN && state!=State.ACTIVE && state!=State.OUT) return;
        if (retreatDrive==null && !ship.isRetreating()) {
            if (state==State.OUT) {
                stats.getMaxSpeed().unmodify(id);
                return;
            }
            stats.getMaxSpeed().modifyFlat(id,600f*level);
            stats.getAcceleration().modifyFlat(id,600f*level);
            CombatEngineAPI engine=Global.getCombatEngine();
            if (engine==null || ship.isHulk() || ship.getParentStation()!=null
                    || ship.getCustomData().get(transitKey) instanceof Moci_NebulaGateTransit) return;
            // 部署位置由引擎给定。同一轮燃烧保留标记，即使演出先结束也不再次开门。
            var arrival=new Moci_NebulaGateTransit(engine,ship,ship.getLocation(),transitKey,ribbon,speed);
            CombatEntityAPI host=engine.addLayeredRenderingPlugin(arrival);
            ship.setCustomData(transitKey,arrival);
            arrival.registered(host);
            return;
        }
        if (retreatDrive==null) {
            if (ship.getCustomData().get(transitKey) instanceof Moci_NebulaGateTransit arrival) {
                arrival.cleanup();
                ship.removeCustomData(transitKey);
            }
            retreatSpec=Global.getSettings().getShipSystemSpec("traveldrive");
            retreatDrive=retreatSpec.getStatsScript();
        }
        // 同一轮撤退燃烧即使取消命令也不重开星门，等 unapply 后才接受下一次入场。
        retreatDrive.apply(stats,id,state,level);
        // ENGINE_MOD 读的是本系统的配置；仅在撤退期间套用原版尾焰，不改变入场演出。
        ShipEngineControllerAPI engines=ship.getEngineController();
        if (retreatSpec.getEngineGlowColor()!=null)
            engines.fadeToOtherColor(this,retreatSpec.getEngineGlowColor(),retreatSpec.getEngineGlowContrailColor(),level,retreatSpec.getEngineGlowMaxBlend());
        engines.extendFlame(this,(retreatSpec.getEngineGlowLengthMult()-1f)*level,
                (retreatSpec.getEngineGlowWidthMult()-1f)*level,(retreatSpec.getEngineGlowGlowMult()-1f)*level);
    }

    @Override public void unapply(MutableShipStatsAPI stats,String id) {
        if (retreatDrive!=null) {
            retreatDrive.unapply(stats,id);
            retreatDrive=null;
            retreatSpec=null;
            return;
        }
        stats.getMaxSpeed().unmodify(id);
        stats.getAcceleration().unmodify(id);
        if (stats.getEntity() instanceof ShipAPI ship && ship.getCustomData().get(transitKey) instanceof Moci_NebulaGateTransit arrival) {
            arrival.stop();
            if (arrival.isExpired()) ship.removeCustomData(transitKey);
        }
    }

    @Override public StatusData getStatusData(int index,State state,float effectLevel) {
        return index==0 ? new StatusData("提高引擎出力",false) : null;
    }
}
