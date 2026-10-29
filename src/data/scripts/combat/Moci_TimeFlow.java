package data.scripts.combat;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一场战斗里唯一的「时间流权威」。
 *
 * <h2>为什么必须集中</h2>
 * 原来每个功能各自往 {@code engine.getTimeMult()} / {@code ship.getMutableStats().getTimeMult()}
 * 上挂自己的 id，再各自写一套清除逻辑。这套做法的失效模式是结构性的：
 * 只要「挂上去」和「摘下来」不在同一处，中间任何一条路径（换乘、目标消失、异常、
 * 宿主被总线摘除、战斗结束）走岔了，修饰符就永久留在引擎上 —— 表现就是「进了时缓出不来」。
 *
 * <p>本类把这件事变成一条不变量：</p>
 * <ul>
 *   <li>整个模组只向引擎写 <b>一个</b> id（{@link #MODIFIER_ID}），船侧同理每船一个 id；</li>
 *   <li>任何功能都不直接改 time mult，只能 {@link #openWorld}/{@link #openShip}/{@link #openBulletTime}
 *       申请一个 {@link Handle}；</li>
 *   <li>每帧由本 sink 统一重算「所有存活申请」的乘积，再一次性写入；</li>
 *   <li>申请的作用域死了（舰船死亡/离场）就自动作废，不依赖申请方记得释放；</li>
 *   <li>战斗结束、引擎换场、本实例退役时，一次性摘干净。</li>
 * </ul>
 *
 * <h2>三种申请</h2>
 * <ul>
 *   <li>{@link #openWorld} —— 全世界时间倍率（例如「新人类」领域展开把世界压到 1/1.5）。</li>
 *   <li>{@link #openShip} —— 单船时间倍率（例如船插自身的加速）。</li>
 *   <li>{@link #openBulletTime} —— <b>子弹时间</b>：该船 × f，同时世界 × 1/f。
 *       两者互为倒数，所以这艘船相对世界的时间流速变成 f²，而它自己的动作、武器冷却、
 *       动画在现实时间里保持原速 —— 也就是「只有我在动，世界在减速」，
 *       这正是处决动作时缓要求的效果，不需要调用方自己算补偿。</li>
 * </ul>
 *
 * <h2>因子取值</h2>
 * 倍率一律是「时间流速倍数」：2.0 = 快一倍，0.5 = 慢一半。越界值会被夹到
 * [{@link #MIN_FACTOR}, {@link #MAX_FACTOR}]，NaN / 无穷直接忽略（保留上一帧值）。
 *
 * <h2>线程</h2>
 * 只在战斗主线程使用；内部不做同步，也不暴露给战斗外的调用方。
 */
public final class Moci_TimeFlow implements Moci_CombatSink {

    /** 本实例挂在引擎 customData 上的键。 */
    public static final String KEY = "moci.timeflow";

    /** 全模组唯一的引擎侧时间修饰 id。 */
    public static final String MODIFIER_ID = "[moci] timeflow";

    /** 常驻排查开关：置 false 可关掉本类的全部日志（不影响逻辑）。 */
    public static final boolean TRACE = true;

    /** 日志前缀，便于 {@code grep "[MOCI-TIME]" starsector.log} 一把捞干净。 */
    public static final String LOG_PREFIX = "[MOCI-TIME] ";

    public static final float MIN_FACTOR = 0.02f;
    public static final float MAX_FACTOR = 50f;

    /** 浮动比较阈值：小于它就不重写 stat，避免每帧无意义地碰属性表。 */
    private static final float EPSILON = 1e-4f;

    private final CombatEngineAPI engine;

    /** 全部存活申请，按插入序（日志可读）。 */
    private final Map<Key, Entry> entries = new LinkedHashMap<>();

    /** 每帧复用的快照，避免热路径分配。 */
    private final ArrayList<Entry> frame = new ArrayList<>();

    /** 每帧复用的「本帧出现过申请的船 → 乘积」。 */
    private final IdentityHashMap<ShipAPI, Float> shipFactor = new IdentityHashMap<>();

    /** 每帧复用的「上一帧写过、本帧不该再写」的船。 */
    private final ArrayList<ShipAPI> shipStale = new ArrayList<>();

    /** 已写入船侧的倍率，跨帧比较用。 */
    private final IdentityHashMap<ShipAPI, Float> shipApplied = new IdentityHashMap<>();

    /** 已写入引擎侧的倍率；NaN = 尚未写过。 */
    private float worldApplied = Float.NaN;

    private int openCount;
    private int dropCount;
    private String lastDrop = "";
    private String lastDropReason = "";

    private boolean closed;

    private Moci_TimeFlow(CombatEngineAPI engine) {
        this.engine = engine;
        openCount = 0;
    }

    /* ==================================================================
       静态入口
       ================================================================== */

    /**
     * 取本场战斗的时间流权威；不在战斗里、引擎暂停或战斗已结束时返回 {@code null}。
     *
     * <p>暂停时返回 null 是刻意的：暂停期间任何申请都不应该生效，而调用方
     * 一律在推进帧里申请，落在这里返回 null 属于正常分流，不是错误。</p>
     */
    public static Moci_TimeFlow ensure() {
        if (Global.getCurrentState() != GameState.COMBAT) return null;
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused() || engine.isCombatOver()) return null;
        Object existing = engine.getCustomData().get(KEY);
        if (existing instanceof Moci_TimeFlow flow) return flow.closed ? null : flow;
        Moci_TimeFlow created = new Moci_TimeFlow(engine);
        try {
            Moci_CombatBus.register(created);
        } catch (RuntimeException | LinkageError failure) {
            log("时间流权威注册失败，本帧不接管时间：" + failure);
            return null;
        }
        engine.getCustomData().put(KEY, created);
        return created;
    }

    /** 全世界时间倍率。{@code ttlSeconds > 0} 时该申请在无人续期的情况下自动作废。 */
    public static Handle openWorld(String id, float factor) {
        return openWorld(id, factor, 0f);
    }

    /** @see #openWorld(String, float) */
    public static Handle openWorld(String id, float factor, float ttlSeconds) {
        Moci_TimeFlow flow = ensure();
        if (flow == null) return InertHandle.INSTANCE;
        return flow.lease(null, id, factor, ttlSeconds, false);
    }

    /** 单船时间倍率。 */
    public static Handle openShip(ShipAPI ship, String id, float factor) {
        return openShip(ship, id, factor, 0f);
    }

    /** @see #openShip(ShipAPI, String, float) */
    public static Handle openShip(ShipAPI ship, String id, float factor, float ttlSeconds) {
        if (ship == null) return InertHandle.INSTANCE;
        Moci_TimeFlow flow = ensure();
        if (flow == null) return InertHandle.INSTANCE;
        return flow.lease(ship, id, factor, ttlSeconds, false);
    }

    /**
     * 子弹时间：{@code ship} × factor，世界 × 1/factor。
     *
     * <p>两个修饰必须成对出现、成对消失，所以只暴露这一个入口，不提供分开申请的写法。</p>
     */
    public static Handle openBulletTime(ShipAPI ship, String id, float factor) {
        return openBulletTime(ship, id, factor, 0f);
    }

    /**
     * 带存活租期的子弹时间。
     *
     * <p>{@code ttlSeconds > 0} 时，这份申请必须在租期内被 {@link Handle#active()} 或
     * {@link Handle#set(float)} 触碰一次，否则自动作废。这是给「持有者自己也是一个可能被
     * 摘掉的宿主」准备的保险：只要调用方还在每帧问「我还活着吗」，申请就活着；
     * 调用方一旦消失，申请跟着消失，世界的时间不会陪着一起卡死。</p>
     */
    public static Handle openBulletTime(ShipAPI ship, String id, float factor, float ttlSeconds) {
        if (ship == null) return InertHandle.INSTANCE;
        Moci_TimeFlow flow = ensure();
        if (flow == null) return InertHandle.INSTANCE;
        return flow.lease(ship, id, factor, ttlSeconds, true);
    }

    /* ==================================================================
       句柄
       ================================================================== */

    /** 一份时间申请。{@code close()} 与 {@code release()} 等价且幂等。 */
    public interface Handle extends AutoCloseable {

        /** 改倍率；已释放或已作废的句柄调用无副作用。 */
        void set(float factor);

        /** 当前倍率（子弹时间返回船侧那一半）。 */
        float factor();

        /** 是否仍在生效。作废后返回 false，调用方可据此中止自身逻辑。 */
        boolean active();

        /** 释放（幂等）。 */
        void release();

        @Override
        default void close() {
            release();
        }
    }

    /** 引擎不可用时的空实现，让调用方不必到处判空。 */
    private enum InertHandle implements Handle {
        INSTANCE;

        @Override public void set(float factor) { }
        @Override public float factor() { return 1f; }
        @Override public boolean active() { return false; }
        @Override public void release() { }
    }

    private Handle lease(Object scope, String id, float factor, float ttlSeconds, boolean bulletTime) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("time request id must not be empty");
        float initial = clamp(factor);
        Key shipKey = new Key(scope, id);
        Entry shipEntry = entries.get(shipKey);
        if (shipEntry == null) {
            shipEntry = new Entry(scope, id, initial, ttlSeconds, false);
            entries.put(shipKey, shipEntry);
            openCount++;
        } else {
            shipEntry.retarget(initial, ttlSeconds);
        }
        Entry worldEntry = null;
        Key worldKey = null;
        if (bulletTime) {
            worldKey = new Key(null, bulletWorldId(id));
            worldEntry = entries.get(worldKey);
            float reciprocal = 1f / initial;
            if (worldEntry == null) {
                worldEntry = new Entry(null, bulletWorldId(id), reciprocal, ttlSeconds, true);
                worldEntry.retarget(reciprocal, ttlSeconds);
                entries.put(worldKey, worldEntry);
            } else {
                worldEntry.retarget(reciprocal, ttlSeconds);
            }
        }
        return new Lease(shipEntry, worldEntry);
    }

    private static String bulletWorldId(String id) {
        return id + "\u0000world";
    }

    private final class Lease implements Handle {

        private Entry shipPart;
        private Entry worldPart;
        private boolean released;

        private Lease(Entry shipPart, Entry worldPart) {
            this.shipPart = shipPart;
            this.worldPart = worldPart;
        }

        @Override
        public void set(float factor) {
            if (released) return;
            float f = clamp(factor);
            if (shipPart != null && !shipPart.dropped) shipPart.retarget(f, shipPart.ttl);
            if (worldPart != null && !worldPart.dropped) worldPart.retarget(1f / f, worldPart.ttl);
        }

        @Override
        public float factor() {
            return shipPart == null ? 1f : clamp(shipPart.factor);
        }

        @Override
        public boolean active() {
            if (released || closed) return false;
            if (shipPart == null || shipPart.dropped) return false;
            renewLease();
            return true;
        }

        /**
         * 租期续期：还愿意每帧问一句「我活着吗」的调用方，就是还在驱动这份申请的调用方。
         *
         * <p>这样一来，ttl 就变成了「调用方失联检测」而不是「功能时长限制」——
         * 只要宿主还在推进，时间流就一直归它；宿主一旦被摘掉（异常、被总线 detach、
         * 事件再也不会触发），租期走完自动作废，世界不会陪着一起卡死。</p>
         */
        private void renewLease() {
            if (shipPart.ttl > 0f) shipPart.remaining = shipPart.ttl;
            if (worldPart != null && !worldPart.dropped && worldPart.ttl > 0f) {
                worldPart.remaining = worldPart.ttl;
            }
        }

        @Override
        public void release() {
            if (released) return;
            released = true;
            dropEntry(shipPart, "句柄释放");
            dropEntry(worldPart, "句柄释放");
            shipPart = null;
            worldPart = null;
        }
    }

    private void dropEntry(Entry entry, String reason) {
        if (entry == null || entry.dropped) return;
        entry.dropped = true;
        entries.remove(new Key(entry.scope, entry.id));
        dropCount++;
        lastDrop = entry.id;
        lastDropReason = reason;
        log(String.format(Locale.ROOT,
                "释放时间申请 id=%s scope=%s factor=%.3f 原因=%s 存活=%d",
                entry.id, scopeName(entry.scope), entry.factor, reason, entries.size()));
    }

    /* ==================================================================
       每帧推进
       ================================================================== */

    @Override
    public int order() {
        // 必须跑在其它 sink 之前：别人读 time mult 时拿到的应当是本帧最新值。
        return ORDER_LOGIC - 100;
    }

    @Override
    public boolean owns(CombatEngineAPI other) {
        return other == engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (closed) return;
        if (Global.getCombatEngine() != engine || engine.isCombatOver()) {
            close();
            return;
        }
        if (engine.isPaused()) return;
        float step = amount > 0f && Float.isFinite(amount) ? amount : 0f;
        expire(step);
        recompute();
    }

    /** TTL 与作用域存活判定；两者都是「申请方不需要记得」的自动失效条件。 */
    private void expire(float step) {
        if (entries.isEmpty()) return;
        frame.clear();
        frame.addAll(entries.values());
        for (Entry entry : frame) {
            if (entry.dropped) continue;
            if (entry.ttl > 0f && step > 0f) {
                entry.remaining -= step;
                if (entry.remaining <= 0f) {
                    dropEntry(entry, "申请未续期，超时");
                    dropPairedWorld(entry);
                    continue;
                }
            }
            if (entry.scope instanceof ShipAPI ship && !inPlay(ship)) {
                dropEntry(entry, "作用域舰船已离场");
                dropPairedWorld(entry);
            }
        }
    }

    /**
     * 子弹时间是一笔申请的两个投影：船侧 ×f 与世界 ×1/f。
     *
     * <p>船侧一旦失效（舰船被打爆、离场、租期超时），世界那一半必须一起走。世界半的
     * {@code scope} 是 null —— 它在语义上不属于某一艘船，所以存活判定天然看不见船，
     * 只能由船侧的失效事件显式带走。漏掉这一刀的后果非常具体：玩家在自己压慢的世界里
     * 被打死，世界就永远停在那个倍率上，直到本场战斗结束。</p>
     */
    private void dropPairedWorld(Entry shipEntry) {
        if (shipEntry.scope == null) return;
        Entry paired = entries.get(new Key(null, bulletWorldId(shipEntry.id)));
        if (paired != null && !paired.dropped && paired.reciprocal) {
            dropEntry(paired, "成对申请的船侧已失效");
        }
    }

    private boolean inPlay(ShipAPI ship) {
        return ship.isAlive() && !ship.isHulk() && !ship.wasRemoved() && engine.isEntityInPlay(ship);
    }

    /** 重算并写入；数值没变就不碰属性表。 */
    private void recompute() {
        float world = 1f;
        shipFactor.clear();
        if (!entries.isEmpty()) {
            frame.clear();
            frame.addAll(entries.values());
            for (Entry entry : frame) {
                if (entry.dropped) continue;
                if (entry.scope == null) {
                    world *= entry.factor;
                } else {
                    ShipAPI ship = (ShipAPI) entry.scope;
                    Float current = shipFactor.get(ship);
                    shipFactor.put(ship, (current == null ? 1f : current) * entry.factor);
                }
            }
        }
        writeWorld(world);
        writeShips();
    }

    private void writeWorld(float factor) {
        float target = clamp(worldFactor(factor));
        if (!Float.isNaN(worldApplied) && Math.abs(target - worldApplied) < EPSILON) return;
        MutableStat stat = engine.getTimeMult();
        if (stat == null) return;
        try {
            if (Math.abs(target - 1f) < EPSILON) stat.unmodify(MODIFIER_ID);
            else stat.modifyMult(MODIFIER_ID, target);
            worldApplied = target;
        } catch (RuntimeException | LinkageError failure) {
            log("写引擎时间倍率失败：" + failure);
        }
    }

    /** 世界侧乘出来的东西仍然要夹一次：多个申请叠起来可能远离安全区。 */
    private static float worldFactor(float multiplied) {
        if (!Float.isFinite(multiplied) || multiplied <= 0f) return 1f;
        return multiplied;
    }

    private void writeShips() {
        for (Map.Entry<ShipAPI, Float> pair : shipFactor.entrySet()) {
            ShipAPI ship = pair.getKey();
            float target = clamp(pair.getValue());
            Float applied = shipApplied.get(ship);
            if (applied != null && Math.abs(target - applied) < EPSILON) continue;
            if (applyShip(ship, target)) shipApplied.put(ship, target);
        }
        if (shipApplied.isEmpty()) return;
        shipStale.clear();
        for (ShipAPI ship : shipApplied.keySet()) {
            if (!shipFactor.containsKey(ship)) shipStale.add(ship);
        }
        for (ShipAPI ship : shipStale) {
            if (applyShip(ship, 1f)) shipApplied.remove(ship);
        }
    }

    private boolean applyShip(ShipAPI ship, float factor) {
        if (ship.getMutableStats() == null) return true;
        MutableStat stat = ship.getMutableStats().getTimeMult();
        if (stat == null) return true;
        try {
            if (Math.abs(factor - 1f) < EPSILON) stat.unmodify(MODIFIER_ID);
            else stat.modifyMult(MODIFIER_ID, factor);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            log("写舰船时间倍率失败 ship=" + hullId(ship) + "：" + failure);
            return false;
        }
    }

    /* ==================================================================
       收尾
       ================================================================== */

    /**
     * 摘干净并退役。
     *
     * <p>每一步独立兜底：任何一个 stat 因为舰船被回收而失效，都不能阻止后面的清理 ——
     * 这是唯一一处允许「忽略异常」的地方，因为它的目的就是把已经不可能成功的 unmodify
     * 跳过，继续做还能做的部分。</p>
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Entry entry : entries.values()) entry.dropped = true;
        entries.clear();
        try {
            MutableStat stat = engine.getTimeMult();
            if (stat != null) stat.unmodify(MODIFIER_ID);
            worldApplied = 1f;
        } catch (RuntimeException | LinkageError ignored) {
        }
        for (ShipAPI ship : new ArrayList<>(shipApplied.keySet())) {
            try {
                if (ship.getMutableStats() != null && ship.getMutableStats().getTimeMult() != null) {
                    ship.getMutableStats().getTimeMult().unmodify(MODIFIER_ID);
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        shipApplied.clear();
        shipFactor.clear();
        shipStale.clear();
        frame.clear();
        try {
            if (engine.getCustomData().get(KEY) == this) engine.getCustomData().remove(KEY);
        } catch (RuntimeException ignored) {
        }
        Moci_CombatBus.unregister(this);
        log(String.format(Locale.ROOT,
                "时间流权威退役：累计申请=%d 释放=%d 最后释放=%s（%s）",
                openCount, dropCount, lastDrop.isEmpty() ? "-" : lastDrop,
                lastDropReason.isEmpty() ? "-" : lastDropReason));
    }

    /* ==================================================================
       内部
       ================================================================== */

    /**
     * 倍率夹取。非有限值一律返回 {@code 1f}，调用方据此得到「不改变时间」而不是
     * 把 NaN 写进属性表（写进去就是全场时间永久静止）。
     */
    private static float clamp(float factor) {
        if (!Float.isFinite(factor)) return 1f;
        if (factor < MIN_FACTOR) return MIN_FACTOR;
        if (factor > MAX_FACTOR) return MAX_FACTOR;
        return factor;
    }

    private static String scopeName(Object scope) {
        if (scope == null) return "world";
        if (scope instanceof ShipAPI ship) return hullId(ship);
        return scope.getClass().getSimpleName();
    }

    private static String hullId(ShipAPI ship) {
        try {
            return ship.getHullSpec() == null ? ship.getName() : ship.getHullSpec().getHullId();
        } catch (RuntimeException ignored) {
            return "?";
        }
    }

    private static void log(String message) {
        if (!TRACE) return;
        try {
            Global.getLogger(Moci_TimeFlow.class).info(LOG_PREFIX + message);
        } catch (RuntimeException ignored) {
        }
    }

    /** 申请的唯一标识：作用域按引用相等，id 按字符串相等。 */
    private static final class Key {

        private final Object scope;
        private final String id;
        private final int hash;

        private Key(Object scope, String id) {
            this.scope = scope;
            this.id = id;
            this.hash = 31 * System.identityHashCode(scope) + id.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return key.scope == scope && key.id.equals(id);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** 一份申请的实际数据。 */
    private static final class Entry {

        private final Object scope;
        private final String id;
        private final boolean reciprocal;
        private final float ttl;
        private float factor;
        private float remaining;
        private boolean dropped;

        private Entry(Object scope, String id, float factor, float ttl, boolean reciprocal) {
            this.scope = scope;
            this.id = id;
            this.factor = clamp(factor);
            this.ttl = ttl > 0f ? ttl : 0f;
            this.remaining = this.ttl;
            this.reciprocal = reciprocal;
        }

        private void retarget(float factor, float ttlSeconds) {
            this.factor = clamp(factor);
            if (ttlSeconds > 0f) this.remaining = ttlSeconds;
        }
    }
}