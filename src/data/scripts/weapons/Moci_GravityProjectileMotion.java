package data.scripts.weapons;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 战斗内共享的可撤销减速。实际速度 = 自身飞行速度 * 距离倍率 + 引力等物理速度。
 * 自身飞行和引力分别保存；脱离引力场时恢复自身飞行分量，保留已经获得的引力动量。
 */
final class Moci_GravityProjectileMotion extends BaseEveryFrameCombatPlugin {
    interface Field {
        float getProjectileSpeedMultiplier(DamagingProjectileAPI projectile);
    }

    private static final String KEY = "moci_gravity_projectile_motion";
    private final CombatEngineAPI engine;
    private final Map<DamagingProjectileAPI, Motion> motions = new IdentityHashMap<>();

    private Moci_GravityProjectileMotion(CombatEngineAPI engine) {
        this.engine = engine;
    }

    static Moci_GravityProjectileMotion get(CombatEngineAPI engine) {
        Object existing = engine.getCustomData().get(KEY);
        if (existing instanceof Moci_GravityProjectileMotion) return (Moci_GravityProjectileMotion) existing;
        Moci_GravityProjectileMotion plugin = new Moci_GravityProjectileMotion(engine);
        engine.getCustomData().put(KEY, plugin);
        engine.addPlugin(plugin);
        return plugin;
    }

    Vector2f beforeGravity(DamagingProjectileAPI projectile, Field field) {
        if (!finite(projectile.getVelocity())) return null;
        Motion motion = motions.get(projectile);
        if (motion == null) {
            motion = new Motion(projectile.getVelocity());
            motions.put(projectile, motion);
        }
        motion.fields.put(field, Boolean.TRUE);
        if (!motion.captureExternalChanges(projectile)) return null;
        return new Vector2f(motion.pull);
    }

    void afterGravity(DamagingProjectileAPI projectile, Vector2f pullVelocity) {
        Motion motion = motions.get(projectile);
        if (motion != null && finite(pullVelocity)) {
            motion.pull.set(pullVelocity);
            motion.apply(projectile, multiplier(projectile, motion));
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine.isPaused() || amount <= 0f) return;
        Iterator<Map.Entry<DamagingProjectileAPI, Motion>> iterator = motions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<DamagingProjectileAPI, Motion> entry = iterator.next();
            DamagingProjectileAPI projectile = entry.getKey();
            Motion motion = entry.getValue();
            if (projectile.isExpired() || !engine.isEntityInPlay(projectile)) {
                iterator.remove();
                continue;
            }
            if (!motion.captureExternalChanges(projectile)) {
                iterator.remove();
                continue;
            }
            float multiplier = projectile.didDamage() ? 1f : multiplier(projectile, motion);
            motion.apply(projectile, multiplier);
            if (multiplier == 1f) iterator.remove();
        }
    }

    private float multiplier(DamagingProjectileAPI projectile, Motion motion) {
        float result = 1f;
        Iterator<Field> iterator = motion.fields.keySet().iterator();
        while (iterator.hasNext()) {
            Field field = iterator.next();
            float multiplier = field.getProjectileSpeedMultiplier(projectile);
            if (multiplier >= 1f) iterator.remove();
            else result = Math.min(result, multiplier);
        }
        return result;
    }

    private static boolean finite(Vector2f vector) {
        return Float.isFinite(vector.x) && Float.isFinite(vector.y);
    }

    static final class Motion {
        private final Map<Field, Boolean> fields = new IdentityHashMap<>();
        private final Vector2f flight;
        private final Vector2f pull = new Vector2f();
        private final Vector2f lastWritten;
        private final float entrySpeed;

        Motion(Vector2f velocity) {
            flight = new Vector2f(velocity);
            lastWritten = new Vector2f(velocity);
            entrySpeed = (float) Math.min(Float.MAX_VALUE, Math.hypot(velocity.x, velocity.y));
        }

        boolean captureExternalChanges(DamagingProjectileAPI projectile) {
            Vector2f velocity = projectile.getVelocity();
            if (!finite(velocity)) return false;
            // 导弹引擎/制导及其他效果对速度的改动继续计入自身飞行分量。
            flight.x += velocity.x - lastWritten.x;
            flight.y += velocity.y - lastWritten.y;
            if (!finite(flight)) return false;
            // 导弹会试图补偿减速，不把这部分补偿积累成无上限的潜在弹速。
            float maxFlightSpeed = entrySpeed;
            if (projectile instanceof MissileAPI) {
                float missileSpeed = ((MissileAPI) projectile).getMaxSpeed();
                if (Float.isFinite(missileSpeed)) maxFlightSpeed = Math.max(maxFlightSpeed, missileSpeed);
            }
            double flightSpeed = Math.hypot(flight.x, flight.y);
            if (flightSpeed > maxFlightSpeed) flight.scale((float) (maxFlightSpeed / flightSpeed));
            lastWritten.set(velocity);
            return true;
        }

        void apply(DamagingProjectileAPI projectile, float multiplier) {
            Vector2f result = new Vector2f(flight.x * multiplier + pull.x, flight.y * multiplier + pull.y);
            if (!finite(result)) return;
            projectile.getVelocity().set(result);
            lastWritten.set(result);
        }
    }
}
