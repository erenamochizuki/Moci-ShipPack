package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combat-thread-only, bounded aggregate observations; no retained combat entities. */
public final class PsychoTelemetry {
    public static final String KEY = "xi.arsenal.psycho";
    private static final int MAX_PATHS = 8;
    private static final int MAX_POINTS = 64;
    private static final int MAX_SLOTS = 32;
    private static final int MAX_SHOT_TIMES = 60;
    private static final int MAX_SHIPS = 64;
    private static final int MAX_SHIP_SLOTS = 256;

    private PsychoTelemetry() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> state(CombatEngineAPI engine) {
        Object current = engine.getCustomData().get(KEY);
        if (current instanceof Map<?, ?>) return (Map<String, Object>) current;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", 4);
        result.put("mechanism", "native-charge-plus-whole-ship-owned-timed-salvo");
        result.put("emitted", 0L);
        result.put("aiCreated", 0L);
        result.put("nativeHits", 0L);
        result.put("nativeShieldHits", 0L);
        result.put("targetAcquired", 0L);
        result.put("targetReacquired", 0L);
        result.put("avoidanceDecisions", 0L);
        result.put("terminalEntries", 0L);
        result.put("orphanTimeouts", 0L);
        result.put("nativeTriggered", 0L);
        result.put("scheduledEmitted", 0L);
        result.put("salvosStarted", 0L);
        result.put("salvosCompleted", 0L);
        result.put("salvosCancelled", 0L);
        result.put("salvoFrameBudgetHits", 0L);
        result.put("salvoLateRounds", 0L);
        result.put("scheduledFluxSpent", 0d);
        result.put("salvoStatusBySlot", new LinkedHashMap<String, Map<String, Object>>());
        result.put("maxObservedSpeed", 0d);
        result.put("travelDistance", 0d);
        result.put("absoluteCourseChangeDegrees", 0d);
        result.put("emittedBySlot", new LinkedHashMap<String, Long>());
        result.put("firstFireTimeBySlot", new LinkedHashMap<String, Double>());
        result.put("lastFireTimeBySlot", new LinkedHashMap<String, Double>());
        result.put("firstSalvoLastFireTimeBySlot", new LinkedHashMap<String, Double>());
        result.put("shotTimesBySlot", new LinkedHashMap<String, List<Map<String, Object>>>());
        result.put("weaponClocksBySlot", new LinkedHashMap<String, Map<String, Object>>());
        // Legacy BySlot aggregates stay available; these maps distinguish identical slot names
        // on different real combat ships and never assume a thirty-round magazine.
        result.put("emittedByShipSlot", new LinkedHashMap<String, Long>());
        result.put("firstFireTimeByShipSlot", new LinkedHashMap<String, Double>());
        result.put("lastFireTimeByShipSlot", new LinkedHashMap<String, Double>());
        result.put("firstSalvoLastFireTimeByShipSlot", new LinkedHashMap<String, Double>());
        result.put("shotTimesByShipSlot", new LinkedHashMap<String, List<Map<String, Object>>>());
        result.put("weaponClocksByShipSlot", new LinkedHashMap<String, Map<String, Object>>());
        result.put("salvoStatusByShipSlot", new LinkedHashMap<String, Map<String, Object>>());
        result.put("shipSalvoStatusByShip", new LinkedHashMap<String, Map<String, Object>>());
        result.put("shipSalvoHistoryByShip", new LinkedHashMap<String, List<Map<String, Object>>>());
        result.put("paths", new LinkedHashMap<String, List<Map<String, Object>>>());
        engine.getCustomData().put(KEY, result);
        return result;
    }

    static long increment(CombatEngineAPI engine, String name) {
        Map<String, Object> data = state(engine);
        long next = ((Number) data.getOrDefault(name, 0L)).longValue() + 1L;
        data.put(name, next);
        return next;
    }

    static void add(CombatEngineAPI engine, String name, double amount) {
        Map<String, Object> data = state(engine);
        data.put(name, ((Number) data.getOrDefault(name, 0d)).doubleValue() + amount);
    }

    static void maximum(CombatEngineAPI engine, String name, double amount) {
        Map<String, Object> data = state(engine);
        data.put(name, Math.max(((Number) data.getOrDefault(name, 0d)).doubleValue(), amount));
    }

    @SuppressWarnings("unchecked")
    static void emitted(CombatEngineAPI engine, WeaponAPI weapon, boolean scheduled) {
        increment(engine, "emitted");
        increment(engine, scheduled ? "scheduledEmitted" : "nativeTriggered");
        String slot = boundedSlot(engine, weapon.getSlot().getId());
        Map<String, Long> slots = (Map<String, Long>) state(engine).get("emittedBySlot");
        long count = slots.getOrDefault(slot, 0L) + 1L;
        slots.put(slot, count);
        String shipSlot = boundedShipSlot(engine, weapon);
        Map<String, Long> physical = (Map<String, Long>) state(engine).get("emittedByShipSlot");
        long physicalCount = physical.getOrDefault(shipSlot, 0L) + 1L;
        physical.put(shipSlot, physicalCount);
        double engineTime = engine.getTotalElapsedTime(false);
        Map<String, Double> first = (Map<String, Double>) state(engine).get("firstFireTimeBySlot");
        Map<String, Double> last = (Map<String, Double>) state(engine).get("lastFireTimeBySlot");
        Map<String, Double> firstSalvoLast = (Map<String, Double>) state(engine).get("firstSalvoLastFireTimeBySlot");
        first.putIfAbsent(slot, engineTime);
        last.put(slot, engineTime);
        Map<String, Double> physicalFirst = (Map<String, Double>) state(engine).get("firstFireTimeByShipSlot");
        Map<String, Double> physicalLast = (Map<String, Double>) state(engine).get("lastFireTimeByShipSlot");
        Map<String, Double> physicalFirstSalvoLast = (Map<String, Double>) state(engine).get("firstSalvoLastFireTimeByShipSlot");
        physicalFirst.putIfAbsent(shipSlot, engineTime); physicalLast.put(shipSlot, engineTime);
        if (physicalCount == weapon.getMaxAmmo()) {
            firstSalvoLast.putIfAbsent(slot, engineTime);
            physicalFirstSalvoLast.put(shipSlot, engineTime);
        }

        Map<String, List<Map<String, Object>>> times =
                (Map<String, List<Map<String, Object>>>) state(engine).get("shotTimesBySlot");
        List<Map<String, Object>> shots = times.computeIfAbsent(slot, k -> new ArrayList<>());
        Map<String, Map<String, Object>> clocks =
                (Map<String, Map<String, Object>>) state(engine).get("weaponClocksByShipSlot");
        Map<String, Object> clock = clocks.get(shipSlot);
        Map<String, Object> shot = new LinkedHashMap<>();
        shot.put("shot", count);
        shot.put("shipShot", physicalCount);
        shot.put("shipId", weapon.getShip().getId());
        shot.put("slotId", weapon.getSlot().getId());
        shot.put("capacity", weapon.getMaxAmmo());
        shot.put("origin", scheduled ? "scheduled" : "native-trigger");
        shot.put("engineTime", engineTime);
        // Native weapon advance amount already includes ship-local time; the native RoF tracker
        // can transform it further. Report RoF separately rather than invent tracker timestamps.
        shot.put("weaponLocalTime", clock == null ? null : clock.get("weaponLocalTime"));
        shot.put("shipTimeMult", weapon.getShip().getMutableStats().getTimeMult().getModifiedValue());
        shot.put("missileRoFMult", weapon.getShip().getMutableStats().getMissileRoFMult().getModifiedValue());
        shot.put("ammoAtNativeCallback", weapon.getAmmo());
        if (shots.size() < MAX_SHOT_TIMES) shots.add(shot);
        Map<String, List<Map<String, Object>>> groupedTimes =
                (Map<String, List<Map<String, Object>>>) state(engine).get("shotTimesByShipSlot");
        List<Map<String, Object>> physicalShots = groupedTimes.computeIfAbsent(shipSlot, k -> new ArrayList<>());
        if (physicalShots.size() < MAX_SHOT_TIMES) physicalShots.add(shot);
    }

    @SuppressWarnings("unchecked")
    static void salvo(CombatEngineAPI engine, WeaponAPI weapon, Map<String, Object> status) {
        Map<String, Map<String, Object>> states =
                (Map<String, Map<String, Object>>) state(engine).get("salvoStatusBySlot");
        states.put(boundedSlot(engine, weapon.getSlot().getId()), status);
        Map<String, Map<String, Object>> physicalStates =
                (Map<String, Map<String, Object>>) state(engine).get("salvoStatusByShipSlot");
        physicalStates.put(boundedShipSlot(engine, weapon), status);
    }

    @SuppressWarnings("unchecked")
    static void shipSalvo(CombatEngineAPI engine, ShipAPI ship, Map<String, Object> status) {
        Map<String, Map<String, Object>> states =
                (Map<String, Map<String, Object>>) state(engine).get("shipSalvoStatusByShip");
        String key = ship.getId();
        if (!states.containsKey(key) && states.size() >= MAX_SHIPS) return;
        states.put(key, status);
        Map<String, List<Map<String, Object>>> histories =
                (Map<String, List<Map<String, Object>>>) state(engine).get("shipSalvoHistoryByShip");
        List<Map<String, Object>> history = histories.computeIfAbsent(key, k -> new ArrayList<>());
        if (history.size() >= 4) history.remove(0);
        history.add(status);
    }

    @SuppressWarnings("unchecked")
    static void clock(CombatEngineAPI engine, WeaponAPI weapon, float amount) {
        String slot = boundedSlot(engine, weapon.getSlot().getId());
        Map<String, Map<String, Object>> clocks =
                (Map<String, Map<String, Object>>) state(engine).get("weaponClocksBySlot");
        Map<String, Object> clock = clocks.computeIfAbsent(slot, k -> new LinkedHashMap<>());
        double local = ((Number) clock.getOrDefault("weaponLocalTime", 0d)).doubleValue() + amount;
        clock.put("weaponLocalTime", local);
        clock.put("engineTime", (double) engine.getTotalElapsedTime(false));
        clock.put("shipTimeMult", weapon.getShip().getMutableStats().getTimeMult().getModifiedValue());
        clock.put("missileRoFMult", weapon.getShip().getMutableStats().getMissileRoFMult().getModifiedValue());
        Map<String, Map<String, Object>> physicalClocks =
                (Map<String, Map<String, Object>>) state(engine).get("weaponClocksByShipSlot");
        String shipSlot = boundedShipSlot(engine, weapon);
        Map<String, Object> physicalClock = physicalClocks.computeIfAbsent(shipSlot, k -> new LinkedHashMap<>());
        double physicalLocal = ((Number) physicalClock.getOrDefault("weaponLocalTime", 0d)).doubleValue() + amount;
        physicalClock.putAll(clock);
        physicalClock.put("weaponLocalTime", physicalLocal);
    }

    @SuppressWarnings("unchecked")
    private static String boundedShipSlot(CombatEngineAPI engine, WeaponAPI weapon) {
        Map<String, Map<String, Object>> clocks =
                (Map<String, Map<String, Object>>) state(engine).get("weaponClocksByShipSlot");
        String key = weapon.getShip().getId() + "/" + weapon.getSlot().getId();
        if (!clocks.containsKey(key) && clocks.size() >= MAX_SHIP_SLOTS - 1) return "other";
        return key;
    }

    @SuppressWarnings("unchecked")
    private static String boundedSlot(CombatEngineAPI engine, String slot) {
        Map<String, Map<String, Object>> clocks =
                (Map<String, Map<String, Object>>) state(engine).get("weaponClocksBySlot");
        if (!clocks.containsKey(slot) && clocks.size() >= MAX_SLOTS - 1) return "other";
        return slot;
    }

    @SuppressWarnings("unchecked")
    static void point(CombatEngineAPI engine, long sequence, float age, float x, float y,
                      float speed, String stage, float targetDistance) {
        if (sequence > MAX_PATHS) return;
        Map<String, List<Map<String, Object>>> paths =
                (Map<String, List<Map<String, Object>>>) state(engine).get("paths");
        List<Map<String, Object>> path = paths.computeIfAbsent(Long.toString(sequence), k -> new ArrayList<>());
        if (path.size() >= MAX_POINTS) return;
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("t", age); point.put("x", x); point.put("y", y);
        point.put("speed", speed); point.put("stage", stage);
        point.put("targetDistance", targetDistance);
        path.add(point);
    }
}
