package data.scripts.combat;

import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.List;

/**
 * 战斗内「每帧 / 原始输入」的可注册接收者。
 *
 * <p>由功能自己的数据宿主（武器每帧效果、船插、子系统、技能监听器）在需要时惰性创建，
 * 不再为每个功能单独挂一个全局战斗插件。所有权始终在宿主上：宿主销毁或数据被移除，
 * 效果随之消失，不会留下跨场次残留。</p>
 */
public interface Moci_CombatSink {

    /** 每帧推进；实现方必须自行判断战斗是否已结束、是否暂停。 */
    void advance(float amount, List<InputEventAPI> events);

    /**
     * 原生核心控制之前的原始输入钩子（只有战斗插件拿得到）。
     * 缺省不消费任何事件。
     */
    default void processInput(float amount, List<InputEventAPI> events) {
    }

    /** 世界坐标渲染钩子（对应原战斗插件的 renderInWorldCoords）。 */
    default void renderInWorldCoords(ViewportAPI viewport) {
    }

    /** 屏幕坐标渲染钩子（对应原战斗插件的 renderInUICoords）。 */
    default void renderInUICoords(ViewportAPI viewport) {
    }

    /** 战斗结束或宿主退役时调用；必须幂等。 */
    default void close() {
    }
}
