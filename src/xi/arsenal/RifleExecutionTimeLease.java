package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import data.scripts.combat.Moci_CombatBus;
import data.scripts.combat.Moci_CombatSink;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** A player-only, reversible execution-time effect; the rifle remains its sole clock.
 *
 * The caller supplies its desired factor, including any entry/recovery curve.
 * This helper never advances a timer or spends flux.
 * Other temporal effects multiply normally and are never cleared or reset.
 *
 * <p>这个类自己写属性表、自己摘：构造函数往 {@link Moci_CombatBus} 注册自己当 sink，
 * 每帧只做一件事——发现「战斗没了 / 玩家舰换了 / 舰船死了」就 {@link #close()}。
 * 为什么不指望调用方兜底：调用方（{@code BeamRifleHost}）本身也是总线上的一个 sink，
 * 它被摘除、宿主关闭或异常中断时 {@code close()} 永远不会到，引擎上那条 1/f 就会永久留场。</p>
 */
public final class RifleExecutionTimeLease implements AutoCloseable, Moci_CombatSink {
    public static final String KEY = "xi.arsenal.rifle.execution.time";
    public static final float MAX_FACTOR = 3f;
    public static final int MAX_MODULES = 128;
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private final Thread owner = Thread.currentThread();
    private final CombatEngineAPI engine;
    private final ShipAPI source;
    private final String modifierId = KEY + "." + Long.toUnsignedString(NEXT_ID.incrementAndGet());
    private final Set<ShipAPI> touched = Collections.newSetFromMap(new IdentityHashMap<>());
    private float factor = 1f;
    private boolean closed;
    private String reason = "owned; waiting for caller factor";

    /** Acquires this battle's single execution-time owner without changing time yet. */
    public RifleExecutionTimeLease(CombatEngineAPI engine, ShipAPI source) {
        this.engine = Objects.requireNonNull(engine, "Execution combat engine");
        this.source = Objects.requireNonNull(source, "Execution player ship");
        String block = invalidReason();
        if (!block.isEmpty()) throw new IllegalStateException(block);
        if (engine.getCustomData().containsKey(KEY))
            throw new IllegalStateException("Execution time already has a battle owner");
        engine.getCustomData().put(KEY, this);
        try {
            Moci_CombatBus.register(this);
        } catch (RuntimeException | LinkageError failure) {
            // 注册不进总线就没有自发兜底，宁可当场退役也不能留一条无人收尾的修饰符。
            releaseAll();
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Execution time sink registration failed", failure);
        }
    }

    /** Returns false and releases only this lease if the player/battle ownership is lost.
     * Pausing does not invalidate the lease and no wall-clock time is consumed. */
    public boolean valid() {
        requireOwner();
        if (closed) return false;
        String block = invalidReason();
        if (block.isEmpty() && engine.getCustomData().get(KEY) == this) return true;
        reason = block.isEmpty() ? "execution time ownership replaced" : block;
        close();
        return false;
    }

    /** Applies a caller-sampled 1..3 factor. The existing rifle owner supplies the clock.
     * Returns false after an ordinary ownership/liveness loss; invalid factors throw. */
    public boolean setFactor(float requested) {
        requireOwner();
        if (!Float.isFinite(requested) || requested < 1f || requested > MAX_FACTOR)
            throw new IllegalArgumentException("Execution factor must be finite and within 1..3");
        if (!valid()) return false;
        try {
            Set<ShipAPI> current = moduleTree();
            for (ShipAPI ship : current) {
                // Claim cleanup ownership before mutating; a failing public API write
                // must not leave a successfully-applied modifier outside cleanup.
                touched.add(ship);
                ship.getMutableStats().getTimeMult().modifyMult(modifierId, requested);
            }
            for (var iterator = touched.iterator(); iterator.hasNext();) {
                ShipAPI ship = iterator.next();
                if (!current.contains(ship)) {
                    ship.getMutableStats().getTimeMult().unmodify(modifierId);
                    iterator.remove();
                }
            }
            engine.getTimeMult().modifyMult(modifierId, 1f / requested);
            factor = requested;
            reason = "caller factor applied";
            return true;
        } catch (RuntimeException | Error failure) {
            reason = "execution time application failed";
            try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * 每帧自检：本租约唯一依赖的外部条件一失效就摘干净。
     *
     * <p>这是「谁申请、谁释放」的最后一道兜底。刻意只做这一件事——它不驱动倍率、不计时、
     * 不碰别的修饰符，所以即使和别的时缓实现并存也不会互相干扰。</p>
     */
    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (closed) return;
        try {
            valid(); // 失效时 valid() 内部已经 close()；这里只是每帧看一眼
        } catch (RuntimeException | LinkageError failure) {
            reason = "execution self-check failed: " + failure;
            close();
        }
    }

    /** 只属于本场战斗：别场战斗 {@code closeAll} 时不该顺手关掉我们。 */
    @Override
    public boolean owns(CombatEngineAPI other) {
        return other == engine;
    }

    private Set<ShipAPI> moduleTree() {
        Set<ShipAPI> result = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ShipAPI> queue = new ArrayDeque<>();
        result.add(source);
        queue.add(source);
        while (!queue.isEmpty()) {
            ShipAPI ship = queue.removeFirst();
            for (ShipAPI child : ship.getChildModulesCopy()) if (child != null && result.add(child)) {
                if (result.size() > MAX_MODULES)
                    throw new IllegalStateException("Execution module tree exceeds its 128-entity bound");
                queue.addLast(child);
            }
        }
        return result;
    }

    private String invalidReason() {
        if (Global.getCombatEngine() != engine || engine.isCombatOver()) return "execution battle ended or replaced";
        if (engine.getPlayerShip() != source) return "execution player ship changed";
        if (!source.isAlive() || source.isHulk() || source.wasRemoved() || !engine.isEntityInPlay(source))
            return "execution player ship is no longer live";
        float world = engine.getTimeMult().getModifiedValue();
        float local = source.getMutableStats().getTimeMult().getModifiedValue();
        if (!Float.isFinite(world) || world <= 0 || !Float.isFinite(local) || local <= 0)
            return "execution native time flow is invalid";
        return "";
    }

    /** 供调试/状态栏读的摘要；**绝不抛**——它常被嵌进别人每帧的 HUD 里。 */
    public Map<String, Object> status() {
        boolean active = false;
        try {
            active = valid();
        } catch (RuntimeException | LinkageError failure) {
            reason = "execution status self-check failed: " + failure;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", active);
        result.put("closed", closed);
        result.put("factor", factor);
        result.put("globalFactor", active ? 1f / factor : 1f);
        result.put("ownedModules", touched.size());
        result.put("modifierId", modifierId);
        result.put("reason", reason);
        result.put("clock", "NONE; caller samples its sole native battle clock");
        return Collections.unmodifiableMap(result);
    }

    /**
     * 逐项摘除；**幂等且绝不抛**。
     *
     * <p>先置 {@code closed} 再动属性表：舰船被打爆后属性表可能已失效，任何一步抛异常都不能
     * 让剩下的修饰符留在场上，也不能让调用方重试出一条「部分清理」的假成功。</p>
     */
    @Override public void close() {
        releaseAll();
    }

    private void releaseAll() {
        if (closed) return;
        closed = true;
        factor = 1f;
        for (ShipAPI ship : touched) {
            try {
                ship.getMutableStats().getTimeMult().unmodify(modifierId);
            } catch (RuntimeException | LinkageError ignored) {
                // 舰船已被回收：这条属性表已经不存在了，没有可摘的东西。
            }
        }
        touched.clear();
        try {
            engine.getTimeMult().unmodify(modifierId);
        } catch (RuntimeException | LinkageError ignored) {
        }
        try {
            if (engine.getCustomData().get(KEY) == this) engine.getCustomData().remove(KEY);
        } catch (RuntimeException | LinkageError ignored) {
        }
        try {
            Moci_CombatBus.unregister(this);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Execution time belongs to its native battle thread");
    }
}
