package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Client-local cockpit listener contract. Call only from this client's combat thread after its
 * local controller possesses/releases a ship. Do not mirror these calls for remote players,
 * AI ships or server snapshots, and do not serialize this registry into multiplayer state.
 *
 * A client has exactly one cockpit warning recipient and one self-flight source fixed at the
 * native listener. Other units retain their own spatial flight sources, including while this
 * registry selects spectator silence. A remote client has its own binding; this is not networking.
 */
public final class LocalCockpitAudio {
    private static final Map<CombatEngineAPI, Binding> BINDINGS = new WeakHashMap<>();

    private record Binding(WeakReference<ShipAPI> ship) { }
    private LocalCockpitAudio() { }

    /** Explicitly possess one local cockpit. A null ship explicitly selects spectator silence. */
    public static synchronized void bind(CombatEngineAPI engine, ShipAPI localShip) {
        BINDINGS.put(Objects.requireNonNull(engine, "Local combat engine"),
                new Binding(new WeakReference<>(localShip)));
    }

    /** Explicit spectator/disconnected state, even if getPlayerShip still reports an old ship. */
    public static void silence(CombatEngineAPI engine) { bind(engine, null); }

    /** Release an extension override and use the engine's current local player ship again. */
    public static synchronized void clear(CombatEngineAPI engine) {
        if (engine != null) BINDINGS.remove(engine);
    }

    /** Default is the native local player. Explicit spectators and expired bindings return null. */
    public static synchronized ShipAPI resolve(CombatEngineAPI engine) {
        if (engine == null) return null;
        Binding binding = BINDINGS.get(engine);
        return binding == null ? engine.getPlayerShip() : binding.ship().get();
    }

    public static synchronized boolean hasExplicitBinding(CombatEngineAPI engine) {
        return engine != null && BINDINGS.containsKey(engine);
    }
}
