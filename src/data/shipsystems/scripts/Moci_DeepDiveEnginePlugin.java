package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.List;

/**
 * 深潜系统配套战斗插件
 *
 * 职责：
 * 1. 给战场中的舰船挂载“深潜反锁定监听器”。
 * 2. 当目标处于深潜状态时，清空手动目标并强制关闭自动开火锁定。
 *
 * 说明：
 * - 该插件与 Moci_DeepDive 通过 customData 键值联动。
 * - 逻辑按 HSIDeepDiveEnginePlugin 进行等价实现。
 */
public class Moci_DeepDiveEnginePlugin extends BaseEveryFrameCombatPlugin {

    /** 引擎 customData 注册键，保证插件单实例。 */
    private static final String KEY = "Moci_DeepDive_Plugin";

    /**
     * 获取或创建插件实例。
     *
     * @param engine 当前战斗引擎
     * @return 插件实例；若引擎为空则返回 null
     */
    public static Moci_DeepDiveEnginePlugin getInstance(CombatEngineAPI engine) {
        if (engine == null) {
            return null;
        }
        if (engine.getCustomData().containsKey(KEY)) {
            return (Moci_DeepDiveEnginePlugin) engine.getCustomData().get(KEY);
        }

        Moci_DeepDiveEnginePlugin plugin = new Moci_DeepDiveEnginePlugin();
        engine.addPlugin(plugin);
        engine.getCustomData().put(KEY, plugin);
        return plugin;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        super.advance(amount, events);

        // 原逻辑为低频抽样更新（约10%帧触发一次），用于降低循环成本。
        if (Math.random() >= 0.1f) {
            return;
        }

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }

        // 为战场舰船注入监听器（owner=100 常用于特殊实体，这里沿用原逻辑跳过）。
        for (ShipAPI ship : engine.getShips()) {
            if (ship == null || ship.getOwner() == 100) {
                continue;
            }
            Moci_DeepDiveNoLockListener.getInstance(ship);
        }
    }

    /**
     * 深潜目标反锁定监听器
     *
     * 作用：
     * - 若当前目标处于深潜状态，则清空选中目标。
     * - 若自动开火 AI 锁定的是深潜目标，则强制关火，避免无效射击。
     */
    public static class Moci_DeepDiveNoLockListener implements AdvanceableListener {
        private final ShipAPI ship;

        public Moci_DeepDiveNoLockListener(ShipAPI ship) {
            this.ship = ship;
        }

        /**
         * 获取或创建舰船监听器实例。
         */
        public static Moci_DeepDiveNoLockListener getInstance(ShipAPI ship) {
            if (ship.hasListenerOfClass(Moci_DeepDiveNoLockListener.class)) {
                return ship.getListeners(Moci_DeepDiveNoLockListener.class).get(0);
            }

            Moci_DeepDiveNoLockListener listener = new Moci_DeepDiveNoLockListener(ship);
            ship.addListener(listener);
            return listener;
        }

        @Override
        public void advance(float amount) {
            // 手动目标锁定：若目标深潜，则取消锁定。
            ShipAPI target = ship.getShipTarget();
            if (isTargetDeepDiving(target)) {
                ship.setShipTarget(null);
            }

            // 自动开火锁定：若目标深潜，则强制关闭该自动开火逻辑。
            for (WeaponGroupAPI group : ship.getWeaponGroupsCopy()) {
                if (!group.isAutofiring()) {
                    continue;
                }
                for (AutofireAIPlugin ai : group.getAIPlugins()) {
                    if (ai == null) {
                        continue;
                    }
                    if (isTargetDeepDiving(ai.getTargetShip())) {
                        ai.forceOff();
                    }
                }
            }
        }

        /**
         * 判断目标舰船是否处于深潜状态。
         */
        private boolean isTargetDeepDiving(ShipAPI target) {
            if (target == null) {
                return false;
            }
            Object flag = target.getCustomData().get(Moci_DeepDive.DEEP_DIVE_FLAG_KEY);
            return flag instanceof Boolean && (Boolean) flag;
        }
    }
}

