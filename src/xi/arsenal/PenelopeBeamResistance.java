package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.loading.ProjectileSpawnType;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import org.lwjgl.util.vector.Vector2f;
import java.util.Objects;

/**
 * Native discrete ray damage supplement for the owned Penelope hullmod.
 * Sustained DPS beams use the native beam damage stats, never this listener.
 * The ship owns its listener. One closeable engine router invokes it before
 * Mod-local shield listeners that read damage.getDamage() and apply flux themselves.
 * There is no per-frame plugin and the router retains no ship table.
 */
public final class PenelopeBeamResistance implements DamageTakenModifier {
    public static final String MODIFIER = "xi_arsenal_penelope_beam_resistance";
    public static final float DAMAGE_MULTIPLIER = .65f;
    public static final String ROUTER_KEY = "xi.arsenal.penelope.beam-resistance-router";
    private final ShipAPI ship;

    private PenelopeBeamResistance(ShipAPI ship) { this.ship = ship; }

    public static void attach(ShipAPI ship) {
        if (ship != null && !ship.hasListenerOfClass(PenelopeBeamResistance.class))
            ship.addListener(new PenelopeBeamResistance(ship));
    }

    /** Called only by the public hullmod hook after the ship joins a combat engine. */
    public static void ensureGlobalRouter(CombatEngineAPI engine) {
        Objects.requireNonNull(engine, "Penelope beam resistance needs the combat engine");
        Object existing = engine.getCustomData().get(ROUTER_KEY);
        if (existing != null) {
            if (!(existing instanceof DamageRouter router) || router.engine != engine || router.closed)
                throw new IllegalStateException("Penelope beam resistance router ownership mismatch");
            return;
        }
        DamageRouter router = new DamageRouter(engine);
        // 无引擎外资源：DamageRouter.close() 只是从引擎 listenerManager 摘除自己并清 customData，
        // engine 销毁即随之消失；它由 ensureGlobalRouter 的引擎级唯一键自管，无需中央注册表。
        engine.getCustomData().put(ROUTER_KEY, router);
        engine.getListenerManager().addListener(router);
    }

    /** Exactly one per engine, so fleets do not consume one lifecycle slot per ship. */
    public static final class DamageRouter implements DamageTakenModifier, AutoCloseable {
        private final CombatEngineAPI engine;
        private boolean closed;
        private DamageRouter(CombatEngineAPI engine) { this.engine = engine; }
        @Override public String modifyDamageTaken(Object source, CombatEntityAPI target, DamageAPI damage,
                Vector2f point, boolean shieldHit) {
            if (closed || !(target instanceof ShipAPI targetShip)) return null;
            // The engine invokes global modifiers before ship-local modifiers.
            // No retained ship reference, class-name guesses, or foreign listener reorder.
            for (PenelopeBeamResistance listener : targetShip.getListeners(PenelopeBeamResistance.class))
                if (listener.ship == targetShip)
                    return listener.modifyDamageTaken(source, target, damage, point, shieldHit);
            return null;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            engine.getListenerManager().removeListener(this);
            if (engine.getCustomData().get(ROUTER_KEY) == this) engine.getCustomData().remove(ROUTER_KEY);
        }
    }

    @Override public String modifyDamageTaken(Object source, CombatEntityAPI target, DamageAPI damage,
            Vector2f point, boolean shieldHit) {
        if (target != ship || damage == null || damage.isDps()
                || !(source instanceof DamagingProjectileAPI projectile)) return null;
        ProjectileSpecAPI spec = projectile.getProjectileSpec();
        if (spec == null) return null;
        ProjectileSpawnType type = spec.getSpawnType();
        if (type != ProjectileSpawnType.BEAM && type != ProjectileSpawnType.BALLISTIC_AS_BEAM) return null;
        // Unique key makes repeated callbacks idempotent and preserves other damage modifiers.
        damage.getModifier().modifyMult(MODIFIER, DAMAGE_MULTIPLIER);
        return MODIFIER;
    }
}
