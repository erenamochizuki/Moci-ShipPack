package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.*;


/**
 * 自定义战斗动作调度插件：
 * 用于在战斗引擎中管理延迟动作的执行（如弹丸消失后触发的爆炸效果）
 */
public class Moci_CombatActionPlugin extends BaseEveryFrameCombatPlugin {

    private final PriorityQueue<DelayedAction> actionQueue = new PriorityQueue<>();
    private float currentTime;  // 累计时间（单位：秒）

    @Override
    public void init(CombatEngineAPI engine) {
        // 将插件实例注册到战斗引擎的自定义数据中
        engine.getCustomData().put("Moci_ActionPlugin", this);
        currentTime = 0f;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (Global.getCombatEngine().isPaused()) return;

        currentTime += amount;

        // 执行到期动作（时间单位：秒 → 毫秒）
        while (!actionQueue.isEmpty() && actionQueue.peek().triggerTime <= currentTime * 1000) {
            actionQueue.poll().action.run();
        }
    }

    /**
     * 外部接口：添加延迟动作
     * @param action 要执行的动作
     * @param delay 延迟时间（秒）
     */
    public static void schedule(Runnable action, float delay) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        // 从引擎中获取插件实例
        Moci_CombatActionPlugin instance = (Moci_CombatActionPlugin)
                engine.getCustomData().get("Moci_ActionPlugin");

        if (instance != null) {
            // 计算触发时间（转换为毫秒）
            long triggerTime = (long) ((instance.currentTime + delay) * 1000);
            instance.actionQueue.add(new DelayedAction(action, triggerTime));
        }
    }

    /** 弹丸状态检测 */
    public static boolean isProjectileRemoved(DamagingProjectileAPI proj) {
            return proj.didDamage() || !Global.getCombatEngine().isEntityInPlay(proj);
    }

    /** 延迟动作包装类 */
    private static class DelayedAction implements Comparable<DelayedAction> {
        final Runnable action; // 实际要执行的动作
        final long triggerTime; // 触发时间（毫秒）

        DelayedAction(Runnable action, long triggerTime) {
            this.action = action;
            this.triggerTime = triggerTime;
        }

        @Override
        public int compareTo(DelayedAction other) {
            return Long.compare(this.triggerTime, other.triggerTime);
        }
    }
}
