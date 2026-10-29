package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, combat-thread-only facts for this pack's native right-hand rifle callbacks. */
public final class BeamRifleVisuals {
    public static final String KEY = "xi.arsenal.rifle";
    private BeamRifleVisuals() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> state(CombatEngineAPI engine) {
        Object existing = engine.getCustomData().get(KEY);
        if (existing instanceof Map<?, ?>) return (Map<String, Object>) existing;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", 1); result.put("nativeOnFire", 0L);
        result.put("nativeOnHitVisuals", 0L); result.put("nativeShieldHits", 0L);
        result.put("muzzleGlows", 0L); result.put("shots", new ArrayList<Map<String, Object>>());
        result.put("hits", new ArrayList<Map<String, Object>>());
        engine.getCustomData().put(KEY, result);
        return result;
    }

    static void increment(CombatEngineAPI engine, String key) {
        Map<String, Object> state = state(engine);
        state.put(key, ((Number) state.getOrDefault(key, 0L)).longValue() + 1);
    }

    @SuppressWarnings("unchecked")
    static void event(CombatEngineAPI engine, String collection, Map<String, Object> event) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) state(engine).get(collection);
        if (rows.size() < 64) rows.add(event);
    }
}
