package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import java.util.*;
import java.util.function.Predicate;

/** Public schema-1 host target-pool bridge; no dependency on or repackaging of host classes. */
public final class ContentTargetLocks {
    public static final String KEY = "host.combat.targetLocks";
    private static final String CURSOR_KEY = "xi.arsenal.targetLocks.cursor";
    public static final int MAX_TARGETS = 32;
    private ContentTargetLocks() { }

    /** An explicit empty source pool is a user's clear action, not a request for legacy auto-targeting. */
    public static boolean managed(CombatEngineAPI engine, ShipAPI source) {
        if (engine == null || source == null) return false;
        Object raw = engine.getCustomData().get(KEY);
        return raw instanceof Map<?, ?> bridge && bridge.get("schema") instanceof Number schema
                && schema.intValue() == 1 && bridge.get("targets") instanceof Map<?, ?> pools
                && pools.containsKey(source);
    }

    public static List<ShipAPI> snapshot(CombatEngineAPI engine, ShipAPI source) {
        if (engine == null || source == null) return List.of();
        Object raw = engine.getCustomData().get(KEY);
        if (!(raw instanceof Map<?, ?> bridge) || !(bridge.get("schema") instanceof Number schema)
                || schema.intValue() != 1 || !(bridge.get("targets") instanceof Map<?, ?> targets)
                || !(targets.get(source) instanceof List<?> pool)) return List.of();
        List<ShipAPI> result = new ArrayList<>();
        Set<ShipAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < Math.min(MAX_TARGETS, pool.size()); i++)
            if (pool.get(i) instanceof ShipAPI target && seen.add(target)) result.add(target);
        return List.copyOf(result);
    }

    public static List<ShipAPI> inRange(CombatEngineAPI engine, ShipAPI source, float range) {
        if (!Float.isFinite(range) || range < 0f) return List.of();
        return snapshot(engine, source).stream().filter(target -> legal(engine, source, target) && inRange(source, target, range)).toList();
    }

    public static boolean legal(CombatEngineAPI engine, ShipAPI source, ShipAPI target) {
        return engine != null && source != null && target != null && source != target
                && (source.getOwner() == 0 || source.getOwner() == 1)
                && (target.getOwner() == 0 || target.getOwner() == 1) && source.getOwner() != target.getOwner()
                && target.isAlive() && !target.isHulk() && !target.isPhased() && target.isTargetable()
                && target.getCollisionClass() != CollisionClass.NONE && engine.isEntityInPlay(target);
    }
    public static boolean inRange(ShipAPI source, ShipAPI target, float range) {
        float dx = target.getLocation().x - source.getLocation().x, dy = target.getLocation().y - source.getLocation().y;
        float reach = range + Math.max(0f, target.getCollisionRadius());
        return dx * dx + dy * dy <= reach * reach;
    }

    private static final class Cursor { long next; }
    /** One assignment per real emitted missile. Stable pool order gives counts differing by at most one. */
    public static ShipAPI next(CombatEngineAPI engine, ShipAPI source, float range, Predicate<ShipAPI> accepted) {
        if (source == null) return null;
        List<ShipAPI> candidates = inRange(engine, source, range).stream().filter(accepted).toList();
        if (candidates.isEmpty()) return null;
        Object previous = source.getCustomData().get(CURSOR_KEY);
        Cursor cursor;
        if (previous == null) { cursor = new Cursor(); source.setCustomData(CURSOR_KEY, cursor); }
        else if (previous instanceof Cursor own) cursor = own;
        else throw new IllegalStateException("Owned missile target-assignment cursor collision");
        ShipAPI chosen = candidates.get((int) Math.floorMod(cursor.next, (long) candidates.size()));
        cursor.next = cursor.next == Long.MAX_VALUE ? 0 : cursor.next + 1;
        return chosen;
    }
}
