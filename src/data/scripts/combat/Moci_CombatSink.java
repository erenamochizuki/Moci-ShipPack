package data.scripts.combat;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.List;

/**
 * 战斗内「每帧 / 原始输入」的可注册接收者。
 *
 * <p>由功能自己的数据宿主（武器每帧效果、船插、子系统、技能监听器）在需要时惰性创建，
 * 不再为每个功能单独挂一个全局战斗插件。所有权始终在宿主上：宿主销毁或数据被移除，
 * 效果随之消失，不会留下跨场次残留。</p>
 *
 * <p>调度约定：{@link Moci_CombatBus} 在注册时读取一次 {@link #order()}，把 sink 按升序排好，
 * 之后每帧只按数组顺序遍历（热路径不做排序、不分配、不加锁）。因此 order 必须是构造期常量，
 * 不要在运行期改变它——改了也不会重排，需要改顺序就注销后重新注册。</p>
 */
public interface Moci_CombatSink {

    /** 默认顺序：普通玩法逻辑（伤害、冷却、状态机）。 */
    int ORDER_LOGIC = 0;

    /** 音频、环境声一类「跟着逻辑走但无所谓先后」的 sink 建议值。 */
    int ORDER_AUDIO = 50;

    /** 渲染、视觉、动作层建议值：放在所有逻辑之后，读到的才是本帧最新状态。 */
    int ORDER_VISUAL = 100;

    /**
     * 同一相位内的执行顺序，取值小者先跑。
     *
     * <p>缺省 {@link #ORDER_LOGIC}。渲染类 sink 应返回 {@link #ORDER_VISUAL} 或更大的值，
     * 确保「先算完逻辑再画」。</p>
     */
    default int order() {
        return ORDER_LOGIC;
    }

    /**
     * 本 sink 是否属于这场战斗（用于收尾时的归属判定）。
     *
     * <p>{@link Moci_CombatBus#closeAll(CombatEngineAPI)} 只对返回 true 的 sink 调用 {@code close()}；
     * 返回 false 的 sink 会被跳过（不调用 close），由它自己的宿主负责关闭——这正是
     * 「两场战斗重叠 / 插件重建」时不会被别人顺手关掉的开关。</p>
     *
     * <p>缺省 true：sink 默认归属当前战斗。宿主若持有自己的 engine 引用，应覆写为
     * {@code return engine == this.engine;} 之类的一致性判断。</p>
     */
    default boolean owns(CombatEngineAPI engine) {
        return true;
    }

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
