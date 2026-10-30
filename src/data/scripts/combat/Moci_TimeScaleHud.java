package data.scripts.combat;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.apache.log4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * 玩家状态栏的「时间倍率」读数。
 *
 * <h2>它是什么，不是什么</h2>
 * 它<b>只读</b>，不写任何时间属性，也不管理任何人的生命周期。时间和缓速仍由各功能在自己的
 * 代码里 {@code getTimeMult().modifyMult(id, factor)} / {@code unmodify(id)} 成对完成——
 * 这是原版 {@code TemporalShellStats} 的写法，也是本项目既有的约定。这里存在的唯一目的，
 * 是让「最终生效值」在战斗里可见：时缓类问题没法靠读代码验收，只能看屏幕上的数字。
 *
 * <h2>为什么读最终值而不是申请值</h2>
 * 读的是 {@code engine.getTimeMult().getMult()} 与玩家舰
 * {@code getMutableStats().getTimeMult().getMult()}——属性表合成后的结果。任何一处漏摘的
 * 修饰符都会立刻显形（世界倍率回不到 ×1.00），这正是排查时最需要的那条线索。括号里的数字
 * 是当前挂着的乘算修饰符条数，用来区分「倍率是 1 但有 2 个 ×1.0 的条目」这种隐性堆积。
 *
 * <h2>生命周期</h2>
 * 战斗开始时由 {@code Moci_CombatBusPlugin.init} 注册进总线；战斗收尾时总线调用
 * {@link #close()}。它不维护自己的 engine 引用以外的任何状态，{@link #owns} 用严格同一性判断，
 * 所以两场战斗重叠/插件重建时不会被别人顺手关掉。
 */
public final class Moci_TimeScaleHud implements Moci_CombatSink {

    /** 引擎 customData 上的单实例键。 */
    public static final String KEY = "moci.timescale.hud";

    /** 状态栏条目 id：与旧实现保持一致，便于按同一字符串排查。 */
    public static final String STATUS_ID = "moci_timeflow";

    private static final String ICON = "graphics/icons/hullsys/temporal_shell.png";
    private static final String LOG_PREFIX = "[MOCI-TIME] ";
    private static final Logger LOG = Logger.getLogger(Moci_TimeScaleHud.class);

    /** 读数变化小于这个量就不重拼字符串（避免每帧分配）。 */
    private static final float EPSILON = 0.01f;

    private final CombatEngineAPI engine;

    private boolean closed;
    private boolean built;
    private boolean loggedOnce;

    private boolean shownShip;
    private float shownWorld = Float.NaN;
    private float shownShipValue = Float.NaN;
    private int shownWorldMods = -1;
    private int shownShipMods = -1;

    private String title = "时间流";
    private String text = "世界 ×? · 本舰 ×?";

    private Moci_TimeScaleHud(CombatEngineAPI engine) {
        this.engine = engine;
    }

    /**
     * 惰性确保本场战斗有一条读数行。
     *
     * <p>只做「没有就建」：已经存在就什么都不做，所以可以在每场战斗的 init 里无脑调用。</p>
     */
    public static void ensure(CombatEngineAPI engine) {
        if (engine == null || engine.getCustomData() == null) {
            return;
        }
        Object existing = engine.getCustomData().get(KEY);
        if (existing instanceof Moci_TimeScaleHud) {
            return;
        }
        Moci_TimeScaleHud hud = new Moci_TimeScaleHud(engine);
        try {
            engine.getCustomData().put(KEY, hud);
            Moci_CombatBus.register(hud);
        } catch (RuntimeException | LinkageError failure) {
            // 读数行不是战斗的必需品：注册失败就放弃，绝不把战斗拖下水。
            engine.getCustomData().remove(KEY);
            LOG.warn(LOG_PREFIX + "时间倍率读数注册失败，本场不再显示", failure);
        }
    }

    @Override
    public int order() {
        // 视觉层：读本帧最终写完之后的值。
        return ORDER_VISUAL;
    }

    @Override
    public boolean owns(CombatEngineAPI other) {
        return other == engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (closed || engine == null) {
            return;
        }
        if (engine.isPaused() || engine.isCombatOver()) {
            // 暂停帧不重登记：引擎会把没被重登记的条目淡出并移除，读数行会自己消失再出现。
            // 这比每帧刷新一个冻结数字更真实——暂停时时间本来就没在流。
            return;
        }
        if (engine.getPlayerShip() == null) {
            return;
        }

        float world = Float.NaN;
        float ship = Float.NaN;
        int worldMods = 0;
        int shipMods = 0;
        boolean showShip = false;
        try {
            MutableStat scale = engine.getTimeMult();
            if (scale != null) {
                world = scale.getMult();
                worldMods = countMods(scale);
            }
            ShipAPI player = engine.getPlayerShip();
            if (player != null && player.getMutableStats() != null) {
                MutableStat own = player.getMutableStats().getTimeMult();
                if (own != null) {
                    ship = own.getMult();
                    shipMods = countMods(own);
                }
                showShip = true;
            }
        } catch (RuntimeException | LinkageError failure) {
            // 读数失败就降级成 ×?，绝不因为一行 UI 把战斗逻辑带崩。
            if (!loggedOnce) {
                loggedOnce = true;
                LOG.warn(LOG_PREFIX + "读时间倍率失败，状态栏降级为 ×?", failure);
            }
        }

        if (!built
                || showShip != shownShip
                || worldMods != shownWorldMods
                || shipMods != shownShipMods
                || changed(world, shownWorld)
                || changed(ship, shownShipValue)) {
            rebuild(world, ship, worldMods, shipMods, showShip);
        }

        try {
            engine.maintainStatusForPlayerShip(STATUS_ID, ICON, title, text, false);
        } catch (RuntimeException | LinkageError failure) {
            if (!loggedOnce) {
                loggedOnce = true;
                LOG.warn(LOG_PREFIX + "维护状态栏失败，本场不再重试显示", failure);
            }
        }
    }

    /** 只在读数真的变了才重建（重建路径允许分配，稳态一帧零分配）。 */
    private void rebuild(float world, float ship, int worldMods, int shipMods, boolean showShip) {
        StringBuilder builder = new StringBuilder(72);
        builder.append("世界 ").append(multText(world)).append('(').append(worldMods).append(')');
        if (showShip) {
            builder.append(" · 本舰 ").append(multText(ship)).append('(').append(shipMods).append(')');
        }
        title = "时间流 · 生效 " + (worldMods + (showShip ? shipMods : 0)) + " 条";
        text = builder.toString();
        shownWorld = world;
        shownShipValue = ship;
        shownWorldMods = worldMods;
        shownShipMods = shipMods;
        shownShip = showShip;
        built = true;
    }

    /** 乘算修饰符条数；读不到就当 0（读数行仍能显示倍率）。 */
    private static int countMods(MutableStat stat) {
        try {
            return stat.getMultMods() == null ? 0 : stat.getMultMods().size();
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    private static String multText(float value) {
        if (!Float.isFinite(value)) {
            return "×?";
        }
        return String.format(Locale.ROOT, "×%.2f", value);
    }

    /** NaN 感知比较（NaN 与 NaN 视为相同，都是「读不到」）。 */
    private static boolean changed(float shown, float actual) {
        boolean shownNaN = Float.isNaN(shown);
        boolean actualNaN = Float.isNaN(actual);
        if (shownNaN || actualNaN) {
            return !(shownNaN && actualNaN);
        }
        return Math.abs(shown - actual) >= EPSILON;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (engine != null && engine.getCustomData() != null
                    && engine.getCustomData().get(KEY) == this) {
                engine.getCustomData().remove(KEY);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 收尾不允许抛：总线在 closeAll 里逐个兜底，这里再守一层。
        }
        try {
            Moci_CombatBus.unregister(this);
        } catch (RuntimeException | LinkageError ignored) {
            // 已经被摘除，忽略。
        }
    }
}