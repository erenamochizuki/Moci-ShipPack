package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** A player-only, reversible execution-time effect; the rifle remains its sole clock.
 *
 * The caller supplies its desired factor, including any entry/recovery curve.
 * This helper never advances a timer, spends flux, or installs a combat plugin.
 * Other temporal effects multiply normally and are never cleared or reset.
 */
public final class RifleExecutionTimeLease implements AutoCloseable {
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

    public Map<String, Object> status() {
        requireOwner();
        boolean active = valid();
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

    @Override public void close() {
        requireOwner();
        if (closed) return;
        for (ShipAPI ship : touched) ship.getMutableStats().getTimeMult().unmodify(modifierId);
        touched.clear();
        engine.getTimeMult().unmodify(modifierId);
        if (engine.getCustomData().get(KEY) == this) engine.getCustomData().remove(KEY);
        factor = 1f;
        closed = true;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Execution time belongs to its native battle thread");
    }
}
