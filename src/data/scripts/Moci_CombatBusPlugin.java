package data.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import data.scripts.combat.Moci_CombatBus;
import data.scripts.weapons.blade.Moci_BladeMotionRegistry;
import org.apache.log4j.Logger;

import java.util.List;

/**
 * 唯一保留的战斗插件：自身不实现任何玩法，只做两件事。
 *
 * <ol>
 *   <li>把每帧 / 原始输入 / 世界与 UI 渲染转发给注册在 {@link Moci_CombatBus} 上的数据宿主
 *       （原始输入钩子只有战斗插件拿得到，所以这一层无法省掉）；</li>
 *   <li>战斗结束时统一收尾：按引擎归属关闭总线上的 sink，并清空刀光动作表。</li>
 * </ol>
 *
 * <p>每个功能的所有权都在它自己的数据宿主上（武器每帧 / 船插 / 子系统 / 技能），
 * 关掉对应数据就不会有任何残留，也不需要在这里逐个开关。这里不做任何 xi 侧资源清理：
 * 那类资源由各自的宿主 close() 负责，本插件只负责把 close 事件送到位。</p>
 *
 * <p>日志只出现在状态迁移（接管 / 收尾）上，热路径（advance/render）一行日志都不写；
 * 前缀统一为 {@code [MOCI-BUS]}。</p>
 */
public final class Moci_CombatBusPlugin extends BaseEveryFrameCombatPlugin implements AutoCloseable {

    private static final Logger LOG = Global.getLogger(Moci_CombatBusPlugin.class);
    private static final String PREFIX = "[MOCI-BUS] ";

    private CombatEngineAPI engine;
    private boolean closed;

    /**
     * 接管战斗。幂等：同一引擎重复调用直接返回，不会重复清总线、也不会清掉已注册的 sink。
     *
     * <p>插件实例若被引擎复用（上一场已 close），以新引擎再次 init 会重新武装自己，
     * 而不是永久停在 closed 状态空转。</p>
     */
    @Override
    public void init(CombatEngineAPI engine) {
        if (engine == null) {
            LOG.warn(PREFIX + "插件 init(null) 被忽略");
            return;
        }
        if (this.engine == engine) {
            return; // 幂等：同一场战斗
        }
        this.engine = engine;
        closed = false;
        Moci_CombatBus.begin(engine);
        LOG.info(PREFIX + "战斗插件接管 engine=" + idOf(engine));
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        CombatEngineAPI current = engine;
        if (closed || current == null) {
            return;
        }
        if (Global.getCombatEngine() != current || current.isCombatOver()) {
            close();
            return;
        }
        // 暂停帧把步长压成 0：引擎在暂停时仍会调用本插件，否则步枪冷却/蓄力、破限宕机等计时会在暂停里走完。
        // 仍照常调用 sink（它们的 HUD/归属校验需要这一拍），只是不推进任何时间。
        Moci_CombatBus.advanceAll(current.isPaused() ? 0f : amount, events);
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (closed || engine == null) {
            return;
        }
        Moci_CombatBus.processInputAll(amount, events);
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (closed || engine == null) {
            return;
        }
        Moci_CombatBus.renderWorldAll(viewport);
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (closed || engine == null) {
            return;
        }
        Moci_CombatBus.renderUiAll(viewport);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        CombatEngineAPI current = engine;
        engine = null;
        // 先问总线「这场还归它管吗」：晚到的旧插件收尾时，不许动新一场的注册表和刀光表。
        boolean owned = current != null && Moci_CombatBus.owns(current);
        try {
            // 只关「属于这场战斗」的 sink；若总线已被新一场接管，这里整段跳过。
            Moci_CombatBus.closeAll(current);
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn(PREFIX + "总线收尾异常 engine=" + idOf(current), failure);
        }
        if (owned) {
            try {
                // 收尾立刻释放刀光动作对象；begin 侧还会再清一次，兜住「上一场没走 close」的路径。
                Moci_BladeMotionRegistry.clear();
            } catch (RuntimeException | LinkageError failure) {
                LOG.warn(PREFIX + "刀光动作表清理失败 engine=" + idOf(current), failure);
            }
        }
        LOG.info(PREFIX + "战斗插件收尾完成 engine=" + idOf(current)
                + (owned ? "" : "（总线已属另一场战斗，跳过全局清理）"));
    }

    private static String idOf(CombatEngineAPI target) {
        return target == null ? "none" : "0x" + Integer.toHexString(System.identityHashCode(target));
    }
}
