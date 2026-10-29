package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 全周天驾驶舱：座舱告警音（敌机接近、来袭导弹）的宿主。
 *
 * <p>谁装这个船插谁有座舱告警，不再按舰船名单写死。没装的舰船不会有任何额外音频；
 * 移除船插后下一场战斗即失效，因为宿主是按数据存在与否惰性安装的。</p>
 */
public final class Moci_PanoramicCockpit extends BaseHullMod {

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) return;
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused() || engine.isCombatOver()) return;
        try {
            xi.arsenal.XiSoundscape.ensure(engine);
        } catch (RuntimeException | LinkageError ignored) {
            // 座舱音是纯附加功能：失败不应影响战斗主循环。
        }
    }
}
