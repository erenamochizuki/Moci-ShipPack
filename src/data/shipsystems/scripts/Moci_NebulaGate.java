package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;

/** 入场走星岚门；撤退由原版旅行驱动推进，越界移除由战场管理器负责。 */
public final class Moci_NebulaGate extends Moci_MassDrive {
    static final String EVENT = "Moci_NebulaGate_transit";

    public Moci_NebulaGate() { super(EVENT, "Moci_NebulaGate_sequence"); }

    private ShipSystemSpecAPI retreatSpec;
    private ShipSystemStatsScript retreatDrive;

    @Override public void apply(MutableShipStatsAPI stats,String id,State state,float level) {
        if (!(stats.getEntity() instanceof ShipAPI ship)) return;
        if (state!=State.IN && state!=State.ACTIVE && state!=State.OUT) return;
        if (retreatDrive==null && !ship.isRetreating()) {
            super.apply(stats,id,state,level);
            return;
        }
        if (retreatDrive==null) {
            if (ship.getCustomData().get(EVENT) instanceof Moci_NebulaGateTransit arrival) {
                arrival.cancel();
                ship.removeCustomData(EVENT);
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
        } else super.unapply(stats,id);
    }

    @Override Pass createPass(CombatEngineAPI engine, ShipAPI ship, int index) {
        // 引擎已分配本舰的部署位置；Transit 复制坐标，在整个出门过程中固定落点。
        return new Moci_NebulaGateTransit(engine, ship, ship.getLocation());
    }
}
