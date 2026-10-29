package data.scripts.combat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import data.scripts.weapons.blade.Moci_BladeMotionRegistry;
import org.apache.log4j.Logger;

import java.util.List;

/**
 * 唯一战斗转发器背后的注册表。
 *
 * <p>功能自己注册，转发器只负责把每帧与原始输入送到这里。所有调用都做异常隔离：
 * 单个功能抛错只摘掉它自己并记日志，不再穿透到引擎、也不会连带其它功能。</p>
 *
 * <h2>并发与分配模型</h2>
 * <p>热路径（{@link #advanceAll} / {@link #processInputAll} / {@link #renderWorldAll} /
 * {@link #renderUiAll}）只做两件事：读一次 {@code volatile} 数组引用，然后按下标遍历。
 * 不分配、不加锁、不迭代集合。注册/注销才是写路径，走「写时复制」（生成新数组再原子替换引用），
 * 所以遍历中的数组内容永远不会被就地修改，也不存在 ConcurrentModificationException。</p>
 *
 * <p>{@code order} 只在注册时读取一次并完成插入排序，之后顺序固定；帧内不再比较。</p>
 *
 * <h2>归属与收尾</h2>
 * <p>总线只认一个引擎。{@link #begin} 换场时，如果上一场还没收尾，会先把上一场的 sink 关掉再接手，
 * 避免「插件被丢弃 / 两场重叠」时旧 sink 永远没人关。{@link #closeAll} 先校验注册表仍属于要收尾的
 * 那场战斗，不匹配就直接跳过——晚到的旧插件 close() 不会关掉新一场的 sink。</p>
 */
public final class Moci_CombatBus {

    private static final Logger LOG = Global.getLogger(Moci_CombatBus.class);

    /** 统一日志前缀：切场、收尾、摘除都带它，便于 grep 一整条战斗生命周期。 */
    private static final String PREFIX = "[MOCI-BUS] ";

    private static final Moci_CombatSink[] EMPTY = new Moci_CombatSink[0];

    /** 保护 {@link #sinks} 与 {@link #engine} 的写操作；读路径完全不碰它。 */
    private static final Object LOCK = new Object();

    /** 串行化 begin/closeAll 这类状态迁移，避免两次切场交错。可重入：内部还会再拿 {@link #LOCK}。 */
    private static final Object TRANSITION = new Object();

    /** 当前注册表快照。写路径整体替换引用，读路径只读一次。 */
    private static volatile Moci_CombatSink[] sinks = EMPTY;

    /** 当前由总线接管的战斗引擎；null 表示没有战斗在总线上。 */
    private static volatile CombatEngineAPI engine;

    private Moci_CombatBus() {
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 新一场战斗开始：接管引擎并清空上一场的注册。
     *
     * <p>幂等：同一个引擎重复调用直接返回（不会清掉已经注册的 sink，也不会重复清刀光表）。
     * 与上一场不同引擎时，先对上一场执行一次收尾，保证旧 sink 不会因为插件被丢弃而泄漏。</p>
     */
    public static void begin(CombatEngineAPI newEngine) {
        if (newEngine == null) {
            LOG.warn(PREFIX + "begin(null) 被忽略：没有引擎可以接管");
            return;
        }
        synchronized (TRANSITION) {
            CombatEngineAPI previous;
            synchronized (LOCK) {
                if (engine == newEngine) {
                    return; // 幂等：同一场战斗重复 init
                }
                previous = engine;
            }
            if (previous != null) {
                teardown(previous); // 上一场没走完 close 的兜底收尾
            }
            synchronized (LOCK) {
                engine = newEngine;
                sinks = EMPTY;
            }
            clearBladeMotions("begin");
            LOG.info(PREFIX + "接管战斗 engine=" + idOf(newEngine) + "（前一场 engine=" + idOf(previous) + "）");
        }
    }

    /** 总线当前是否正接管这个引擎。只读 volatile，可在任意相位调用。 */
    public static boolean owns(CombatEngineAPI current) {
        return current != null && engine == current;
    }

    /** 当前注册的 sink 数量（只读诊断用，热路径不要调用）。 */
    public static int registeredCount() {
        return sinks.length;
    }

    // ------------------------------------------------------------------ 注册

    /**
     * 注册 sink：幂等（同一个实例重复注册只保留一份），并按 {@link Moci_CombatSink#order()} 升序插入。
     *
     * <p>写时复制：先复制出新数组再整体替换 {@link #sinks}，正在遍历的帧不受影响。</p>
     */
    public static void register(Moci_CombatSink sink) {
        if (sink == null) {
            return;
        }
        synchronized (LOCK) {
            Moci_CombatSink[] current = sinks;
            for (int i = 0; i < current.length; i++) {
                if (current[i] == sink) {
                    return; // 幂等
                }
            }
            int order = orderOf(sink);
            int at = current.length;
            for (int i = 0; i < current.length; i++) {
                if (orderOf(current[i]) > order) { // 严格大于：同值保持插入先后（稳定）
                    at = i;
                    break;
                }
            }
            Moci_CombatSink[] next = new Moci_CombatSink[current.length + 1];
            if (at > 0) {
                System.arraycopy(current, 0, next, 0, at);
            }
            next[at] = sink;
            if (at < current.length) {
                System.arraycopy(current, at, next, at + 1, current.length - at);
            }
            sinks = next;
        }
    }

    /** 注销 sink：按引用相等（不是 equals）移除，未注册时静默返回。 */
    public static void unregister(Moci_CombatSink sink) {
        if (sink == null) {
            return;
        }
        synchronized (LOCK) {
            Moci_CombatSink[] current = sinks;
            int at = -1;
            for (int i = 0; i < current.length; i++) {
                if (current[i] == sink) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                return;
            }
            Moci_CombatSink[] next = new Moci_CombatSink[current.length - 1];
            if (at > 0) {
                System.arraycopy(current, 0, next, 0, at);
            }
            if (at < current.length - 1) {
                System.arraycopy(current, at + 1, next, at, current.length - 1 - at);
            }
            sinks = next;
        }
    }

    // ------------------------------------------------------------------ 相位分派（热路径：零分配、零锁）

    public static void advanceAll(float amount, List<InputEventAPI> events) {
        Moci_CombatSink[] snapshot = sinks;
        for (int i = 0; i < snapshot.length; i++) {
            Moci_CombatSink sink = snapshot[i];
            try {
                sink.advance(amount, events);
            } catch (RuntimeException | LinkageError failure) {
                detach(sink, "advance", failure);
            }
        }
    }

    public static void processInputAll(float amount, List<InputEventAPI> events) {
        Moci_CombatSink[] snapshot = sinks;
        for (int i = 0; i < snapshot.length; i++) {
            Moci_CombatSink sink = snapshot[i];
            try {
                sink.processInput(amount, events);
            } catch (RuntimeException | LinkageError failure) {
                detach(sink, "input", failure);
            }
        }
    }

    public static void renderWorldAll(ViewportAPI viewport) {
        Moci_CombatSink[] snapshot = sinks;
        for (int i = 0; i < snapshot.length; i++) {
            Moci_CombatSink sink = snapshot[i];
            try {
                sink.renderInWorldCoords(viewport);
            } catch (RuntimeException | LinkageError failure) {
                detach(sink, "render", failure);
            }
        }
    }

    public static void renderUiAll(ViewportAPI viewport) {
        Moci_CombatSink[] snapshot = sinks;
        for (int i = 0; i < snapshot.length; i++) {
            Moci_CombatSink sink = snapshot[i];
            try {
                sink.renderInUICoords(viewport);
            } catch (RuntimeException | LinkageError failure) {
                detach(sink, "render", failure);
            }
        }
    }

    // ------------------------------------------------------------------ 收尾

    /**
     * 收尾一场战斗：关闭属于该引擎、且自认归属它的 sink，然后清空注册表。
     *
     * <p>归属判定分两层：</p>
     * <ol>
     *   <li>注册表级：总线当前接管的引擎必须就是 {@code closingEngine}，否则整场收尾直接跳过
     *       （日志记录，不改任何状态）。这是「旧插件晚到的 close() 不许关新一场」的主闸门。</li>
     *   <li>sink 级：{@link Moci_CombatSink#owns(CombatEngineAPI)} 为 false 的 sink 跳过 {@code close()}
     *       （由它自己的宿主负责关闭），同时从本场注册表移除、不保留引用。</li>
     * </ol>
     *
     * <p>{@code closingEngine} 为 null 时只对「注册表也没有引擎」的空台账生效，等价于清理残表。</p>
     */
    public static void closeAll(CombatEngineAPI closingEngine) {
        synchronized (TRANSITION) {
            teardown(closingEngine);
        }
    }

    /** 兼容旧调用：按总线当前登记的引擎收尾。 */
    public static void closeAll() {
        closeAll(engine);
    }

    // ------------------------------------------------------------------ 内部

    private static void teardown(CombatEngineAPI closingEngine) {
        CombatEngineAPI owner;
        synchronized (LOCK) {
            owner = engine;
        }
        if (owner != closingEngine) {
            LOG.info(PREFIX + "跳过收尾：注册表属于 engine=" + idOf(owner)
                    + "，本次收尾针对 engine=" + idOf(closingEngine));
            return;
        }
        Moci_CombatSink[] snapshot = sinks;
        int closed = 0;
        int skipped = 0;
        for (int i = 0; i < snapshot.length; i++) {
            Moci_CombatSink sink = snapshot[i];
            if (!ownsSafely(sink, closingEngine)) {
                skipped++;
                continue;
            }
            try {
                sink.close();
                closed++;
            } catch (RuntimeException | LinkageError failure) {
                LOG.warn(PREFIX + "sink 收尾失败: " + sink.getClass().getName(), failure);
            }
        }
        synchronized (LOCK) {
            if (engine == closingEngine) {
                sinks = EMPTY;
                engine = null;
            }
        }
        LOG.info(PREFIX + "战斗收尾完成 engine=" + idOf(closingEngine)
                + "（关闭 " + closed + "，跳过非本场 " + skipped + "）");
    }

    private static void detach(Moci_CombatSink sink, String phase, Throwable failure) {
        unregister(sink);
        LOG.warn(PREFIX + "sink 已摘除（" + phase + " 抛错）: " + sink.getClass().getName(), failure);
    }

    private static boolean ownsSafely(Moci_CombatSink sink, CombatEngineAPI closingEngine) {
        try {
            return sink.owns(closingEngine);
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn(PREFIX + "sink owns() 抛错，按「不归属」跳过: " + sink.getClass().getName(), failure);
            return false;
        }
    }

    private static int orderOf(Moci_CombatSink sink) {
        try {
            return sink.order();
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn(PREFIX + "sink order() 抛错，按 0 处理: " + sink.getClass().getName(), failure);
            return Moci_CombatSink.ORDER_LOGIC;
        }
    }

    /** 刀光动作层是静态表，跨场不清就会攥着已失效的武器实例；这里只在状态迁移时清一次。 */
    private static void clearBladeMotions(String reason) {
        try {
            Moci_BladeMotionRegistry.clear();
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn(PREFIX + "刀光动作表清理失败（" + reason + "）", failure);
        }
    }

    private static String idOf(CombatEngineAPI target) {
        return target == null ? "none" : "0x" + Integer.toHexString(System.identityHashCode(target));
    }
}
