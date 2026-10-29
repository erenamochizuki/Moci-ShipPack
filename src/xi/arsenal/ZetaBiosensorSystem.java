package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

/** Native timed F lifecycle; the one owned combat controller applies all instance effects. */
public final class ZetaBiosensorSystem extends BaseShipSystemScript {
    @Override public StatusData getStatusData(int index,State state,float effectLevel){return switch(index){
        case 0->new StatusData("超载步枪连发 / 巨型军刀 / 榴弹高速装填 / 钢索超载牵引",false);
        case 1->new StatusData("机动与最高速度额外+25% / 尾焰×1.5 / 光束吸收 / 控制免疫",false);
        case 2->new StatusData("持续40秒；每击沉一舰+10秒，结束瘫痪10秒并承受EMP；每击沉多瘫痪1秒",false);
        default->null;};}
    @Override public String getInfoText(ShipSystemAPI system,ShipAPI ship){return system!=null&&system.isOn()?"生物传感器超载":"40秒爆发 / 击沉延时 / 结束反噬";}
}
