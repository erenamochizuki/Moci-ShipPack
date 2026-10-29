package data.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import data.scripts.combat.Moci_CombatBus;
import data.scripts.weapons.blade.Moci_BladeMotionRegistry;
import org.apache.log4j.Logger;
import xi.arsenal.OwnBattleResources;

import java.util.List;

/**
 * 唯一保留的 xi 内容战斗插件：自身不实现任何玩法，只做两件事。
 *
 * <ol>
 *   <li>把每帧与原生输入转发给注册在 {@link Moci_CombatBus} 上的数据宿主
 *       （原始输入钩子只有战斗插件拿得到，所以这一层无法省掉）；</li>
 *   <li>战斗结束时统一收尾：关闭 xi 自有资源表、清空光剑动作表。</li>
 * </ol>
 *
 * <p>每个功能的所有权都在它自己的数据宿主上（武器每帧 / 船插 / 子系统 / 技能），
 * 关掉对应数据就不会有任何残留，也不需要在这里逐个开关。</p>
 */
public final class Moci_CombatBusPlugin extends BaseEveryFrameCombatPlugin implements AutoCloseable {

    private static final Logger LOG = Global.getLogger(Moci_CombatBusPlugin.class);

    private CombatEngineAPI engine;
    private boolean closed;

    @Override
    public void init(CombatEngineAPI engine) {
        if (closed) return;
        this.engine = engine;
        Moci_CombatBus.begin(engine);
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (closed || engine == null) return;
        if (Global.getCombatEngine() != engine || engine.isCombatOver()) {
            close();
            return;
        }
        // 暂停帧把步长压成 0：引擎在暂停时仍会调用本插件，否则步枪冷却/蓄力、破限宕机等计时会在暂停里走完。
        // 仍照常调用 sink（它们的 HUD/归属校验需要这一拍），只是不推进任何时间。
        Moci_CombatBus.advanceAll(engine.isPaused() ? 0f : amount, events);
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (closed || engine == null) return;
        Moci_CombatBus.processInputAll(amount, events);
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (closed || engine == null) return;
        Moci_CombatBus.renderWorldAll(viewport);
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (closed || engine == null) return;
        Moci_CombatBus.renderUiAll(viewport);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Moci_CombatBus.closeAll();
        CombatEngineAPI current = engine;
        engine = null;
        try {
            Moci_BladeMotionRegistry.clear();
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn("Blade motion registry clear failed", failure);
        }
        if (current != null) {
            try {
                OwnBattleResources.closeOwned(current);
            } catch (RuntimeException | LinkageError failure) {
                LOG.warn("Owned xi resources close failed", failure);
            }
        }
    }
}
