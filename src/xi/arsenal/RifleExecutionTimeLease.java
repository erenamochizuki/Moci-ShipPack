package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import data.scripts.combat.Moci_TimeFlow;
import org.apache.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 处决动作的子弹时间租约：{@link Moci_TimeFlow} 之上的一层薄适配器。
 *
 * <h2>为什么它现在很薄</h2>
 * <p>本类以前自己往 {@code engine.getTimeMult()} 和每艘模块的 {@code getTimeMult()} 上挂递增 id，
 * 再靠「自己注册成 {@code Moci_CombatSink} 当看门狗」来兜底回收。那条路线本身就是
 * 「进时缓出不来」的根因：回收完全寄生在 sink 的每帧 tick 上，sink 一旦被总线摘除或宿主
 * 先一步 close，1/f 就永久留在引擎上。</p>
 *
 * <p>现在「挂」和「摘」都在 {@link Moci_TimeFlow} 一处完成，本类只负责三件事：</p>
 * <ol>
 *   <li>把调用方采样的倍率翻译成一次 {@link Moci_TimeFlow#openBulletTime} 申请
 *       （该船 × f，世界 × 1/f，成对且原子）；</li>
 *   <li>把「控制器还在不在」翻译成 {@code valid()}；</li>
 *   <li>把 {@code close()} 翻译成句柄释放。</li>
 * </ol>
 * <p>不再有修饰符 id、不再有 {@code modifyMult/unmodify} 直调、不再有看门狗。
 * 因此本类<b>即使被彻底遗忘</b>，最多也只会在时间流权威里留下一条 ×1 的空申请，
 * 不可能留下慢速世界；慢速只可能来自「调用方已经 set 过 &gt;1 的倍率却再也不来释放」，
 * 而那条路径的自动作废属于 {@link Moci_TimeFlow} 的职责范围。</p>
 *
 * <h2>为什么申请 id 必须唯一</h2>
 * <p>子弹时间在 {@link Moci_TimeFlow} 里是两条申请：舰船那条以「舰船引用 + id」为键，
 * 世界那条以「null + id」为键。世界那条的键里没有舰船，所以两艘舰若共用同一个 id，
 * 就会共用同一条世界减速；先释放的一方会把另一方的减速一起摘掉。因此每次申请都带一个
 * 只增后缀。<b>这个后缀只是申请标签，不是修饰符 id</b>——模组写进属性表的 id 永远是
 * 唯一的 {@link Moci_TimeFlow#MODIFIER_ID}。</p>
 *
 * <h2>倍率区间</h2>
 * <p>{@code [1, 3]} 的断言留在这里而不是交给下游夹取：{@code setFactor} 的返回值在
 * {@code BeamRifleModes} 里被解释为「时间控制权还在不在」（false 会触发 cancel），
 * 所以越界数值必须以异常形式冒出来，严格区分「数值写错了」和「控制权丢了」。
 * 合法输入永远落在下游夹取区间内，因此下游的 {@code [0.02, 50]} 夹取对处决时缓是空操作。</p>
 *
 * <h2>线程</h2>
 * <p>{@link #setFactor} 必须在创建线程（战斗线程）上调用：其唯一副作用是改
 * {@link Moci_TimeFlow} 内部的申请表，那张表不是线程安全的。释放路径（{@link #valid()}、
 * {@link #close()}、{@link #status()}）刻意不抛，因为它们在拆除路径上，抛错只会把释放一起跳过。</p>
 */
public final class RifleExecutionTimeLease implements AutoCloseable {

    /** 本租约的对外身份，同时是时间流申请 id 的前缀（日志按它 grep 就能捞到某一发处决）。 */
    public static final String KEY = "xi.arsenal.rifle.execution.time";

    /** 倍率上限，与 {@code BeamRifleModes} 的时缓窗口公式 {@code 1 + 2 * smoother(strike / .1f)} 的上界一致。 */
    public static final float MAX_FACTOR = 3f;

    private static final Logger LOG = Global.getLogger(RifleExecutionTimeLease.class);

    /** 模块树跟踪上限：模块列表是外部数据，加个硬上限防止异常数据把本帧拖成超长循环。 */
    private static final int MAX_MODULES = 128;

    /** 申请后缀来源；见类注释「为什么申请 id 必须唯一」。 */
    private static final AtomicLong NEXT_REQUEST = new AtomicLong();

    /**
     * 申请存活租期（秒）。
     *
     * <p>这不是「处决最长持续多久」，而是<b>失联检测</b>：时间流权威把 {@code active()} 当作心跳，
     * 本类的 {@link #valid()} 每帧（含暂停帧）都会问一次，于是只要宿主还在推进，申请就一直在。
     * 反过来，一旦宿主被总线 detach、换场、或某条事件再也不会到来，{@code valid()} 不再被调用，
     * 租期走完由时间流权威自行作废——世界倍率不会跟着一起卡死。</p>
     *
     * <p>取 3 秒是因为抬枪对准（PRESENTING）阶段窗口因子恒为 1、{@code setFactor} 不会被调用，
     * 心跳必须能覆盖这一整段；实测该段不到 1 秒，3 秒留足冗余。</p>
     */
    private static final float LIVE_TTL_SECONDS = 3f;

    private final CombatEngineAPI engine;
    private final ShipAPI source;
    private final String requestId = KEY + "#" + Long.toUnsignedString(NEXT_REQUEST.incrementAndGet());
    private final Thread owner = Thread.currentThread();

    /** 模块各自的申请句柄：模块是独立实体，父舰的时间倍率不会传递过去，必须逐舰申请。 */
    private final Map<ShipAPI, Moci_TimeFlow.Handle> modules = new IdentityHashMap<>();

    /** 每帧复用的「本帧模块树」，避免 setFactor 在热路径上反复分配。 */
    private final Map<ShipAPI, Boolean> seen = new IdentityHashMap<>();
    private final ArrayDeque<ShipAPI> walk = new ArrayDeque<>();

    /** 子弹时间主句柄（船 × f 与世界 × 1/f 成对）；关闭后置 null。 */
    private Moci_TimeFlow.Handle handle;

    private float factor = 1f;
    private boolean closed;
    private boolean offThreadRelease;
    private int releaseFailures;
    private String lastReleaseFailure = "";
    private String reason = "owned; waiting for caller factor";

    /**
     * 取得一条处决子弹时间申请，但<b>不改变时间</b>（初始倍率 1）。
     *
     * <p>构造即失败的好处是「这一发能不能处决」在抬枪之前就有确定答案：
     * 时间流权威不可用（战斗暂停、正在收场、总线拒绝注册）时直接抛
     * {@link IllegalStateException}，{@code BeamRifleModes.beginExecution} 已经会捕获它
     * 并把动作退回 IDLE，因此不会出现「姿势开始了却永远没有时缓」的半成品状态。</p>
     *
     * <p>与上一版不同，这里不再抢战斗级「唯一所有者」键：申请之间已经由唯一 id 隔离，
     * 一条被遗忘的申请最多留一条 ×1 空申请，不该反过来把后续处决永久锁死。</p>
     */
    public RifleExecutionTimeLease(CombatEngineAPI engine, ShipAPI source) {
        this.engine = Objects.requireNonNull(engine, "Execution combat engine");
        this.source = Objects.requireNonNull(source, "Execution player ship");
        String block = invalidReason();
        if (!block.isEmpty()) throw new IllegalStateException(block);
        Moci_TimeFlow.Handle opened = Moci_TimeFlow.openBulletTime(source, requestId, 1f, LIVE_TTL_SECONDS);
        if (!opened.active()) throw new IllegalStateException("时间流权威当前不可用（战斗暂停、正在收场或总线拒绝注册）");
        handle = opened;
        if (Moci_TimeFlow.TRACE) LOG.info(Moci_TimeFlow.LOG_PREFIX + "处决子弹时间申请已建立 request=" + requestId
                + " 舰=" + hullId(source));
    }

    /** 时间控制权是否仍然在手上：申请未作废，且引擎与舰船都还在场。
     *
     * <p>刻意不抛异常也不消耗任何时钟：{@code BeamRifleModes.reconcileExecutionOwnership}
     * 会在暂停帧里调用它，而暂停不该让处决失效。</p> */
    public boolean valid() {
        if (closed) return false;
        String block = invalidReason();
        if (block.isEmpty() && handle != null && handle.active()) return true;
        reason = block.isEmpty() ? "时间流申请已作废" : block;
        close();
        return false;
    }

    /** 设置子弹时间倍率（该船 × f，世界 × 1/f）。
     *
     * @return false 只表示「控制权/存活已经丢失」，调用方据此中止自身逻辑；
     *         数值非法则抛 {@link IllegalArgumentException}，绝不与丢失控制权混淆。 */
    public boolean setFactor(float requested) {
        requireOwner();
        if (!Float.isFinite(requested) || requested < 1f || requested > MAX_FACTOR)
            throw new IllegalArgumentException("Execution factor must be finite and within 1.." + MAX_FACTOR);
        if (!valid()) return false;
        try {
            handle.set(requested);
            applyModules(requested);
            factor = requested;
            reason = "caller factor applied";
            return true;
        } catch (RuntimeException | Error failure) {
            reason = "execution time application failed";
            close(); // 幂等且不抛，所以不会吞掉原始异常
            throw failure;
        }
    }

    /**
     * 把同一倍率同步给模块树里的每个模块。
     *
     * <p>模块在引擎里是独立实体，父舰的 {@code timeMult} 不会传过去；不同步的话，
     * 世界被压到 1/f 时主舰按实时跑、模块却按 1/f 爬，模块化舰船的处决画面会散架。</p>
     *
     * <p>每帧重走模块树（模块会在战斗中被拆掉，缓存反而会漏掉新挂上的），
     * 但集合全部复用：热路径上不发集合，只发 {@code openShip} 首次申请时的那一个对象。</p>
     */
    private void applyModules(float requested) {
        seen.clear();
        walk.clear();
        walk.addLast(source);
        // 树异常深时宁可少管几层，也不能让本帧失控：上限在进入循环前就判定。
        while (!walk.isEmpty() && seen.size() < MAX_MODULES) {
            ShipAPI ship = walk.removeFirst();
            for (ShipAPI child : modulesOf(ship)) {
                if (seen.put(child, Boolean.TRUE) != null) continue;
                walk.addLast(child);
                Moci_TimeFlow.Handle module = modules.get(child);
                if (module == null || !module.active()) {
                    // 作用域舰船换了/重建过就得重新申请：旧句柄已经随它的作用域一起作废。
                    modules.put(child, Moci_TimeFlow.openShip(child, requestId, requested));
                } else {
                    module.set(requested);
                }
            }
        }
        for (var iterator = modules.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (seen.containsKey(entry.getKey())) continue;
            releaseQuietly(entry.getValue()); // 已脱离模块树（被拆掉/打爆）：本地句柄同步收掉
            iterator.remove();
        }
    }

    private static List<ShipAPI> modulesOf(ShipAPI ship) {
        List<ShipAPI> children = ship.getChildModulesCopy();
        return children == null ? List.of() : children;
    }

    public Map<String, Object> status() {
        boolean active;
        try {
            active = valid();
        } catch (RuntimeException | LinkageError failure) {
            // status() 位于拆除路径上（BeamRifleModes.closeExecution 先取快照再关时间），
            // 它自己抛错就会把后面的释放一起跳过。判断不了存活时，释放是唯一安全的答案。
            releaseFailures++;
            lastReleaseFailure = "status audit: " + failure;
            active = false;
            try {
                close();
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", active);
        result.put("closed", closed);
        result.put("factor", factor);
        result.put("globalFactor", active ? 1f / factor : 1f);
        result.put("ownedModules", active ? modules.size() + 1 : 0);
        result.put("modifierId", Moci_TimeFlow.MODIFIER_ID);
        result.put("requestId", requestId);
        result.put("reason", reason);
        result.put("releaseFailures", releaseFailures);
        result.put("lastReleaseFailure", lastReleaseFailure);
        result.put("offThreadRelease", offThreadRelease);
        result.put("clock", "NONE; 每帧由 Moci_TimeFlow 统一重算并写入唯一修饰符 id");
        return Collections.unmodifiableMap(result);
    }

    // ------------------------------------------------------------------ 释放

    /** 绝对幂等、绝不抛：先置 closed，再逐个句柄兜底释放。
     *
     * <p>顺序与所有权一致：先模块、再主句柄。任何一步失败都只记账不阻断——
     * 半途抛错留下的才是最难查到的那种残留。</p> */
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (Thread.currentThread() != owner) offThreadRelease = true;
        for (var iterator = modules.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            releaseQuietly(entry.getValue());
            iterator.remove();
        }
        Moci_TimeFlow.Handle current = handle;
        handle = null;
        releaseQuietly(current);
        factor = 1f;
        if (Moci_TimeFlow.TRACE) LOG.info(Moci_TimeFlow.LOG_PREFIX + "处决子弹时间申请已释放 request=" + requestId
                + " 原因=" + reason
                + (releaseFailures == 0 ? "" : " 释放失败=" + releaseFailures + " 最后失败=" + lastReleaseFailure)
                + (offThreadRelease ? " 注意=非战斗线程释放" : ""));
    }

    private void releaseQuietly(Moci_TimeFlow.Handle target) {
        if (target == null) return;
        try {
            target.release();
        } catch (RuntimeException | LinkageError failure) {
            releaseFailures++;
            lastReleaseFailure = "handle release: " + failure;
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 处决时缓只属于玩家舰：这是既有语义（AI 舰的处决动作不带时间控制），保持不变。 */
    private String invalidReason() {
        if (Global.getCombatEngine() != engine || engine.isCombatOver()) return "execution battle ended or replaced";
        if (engine.getPlayerShip() != source) return "execution player ship changed";
        if (!source.isAlive() || source.isHulk() || source.wasRemoved() || !engine.isEntityInPlay(source))
            return "execution player ship is no longer live";
        return "";
    }

    private static String hullId(ShipAPI ship) {
        try {
            return ship.getHullSpec() == null ? ship.getName() : ship.getHullSpec().getHullId();
        } catch (RuntimeException ignored) {
            return "?";
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Execution time belongs to its native battle thread");
    }
}
