package data.shipsystems.scripts.ai;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;

public class Moci_BallisticCooldownResetAI implements ShipSystemAIScript {

    private static final float MIN_COOLDOWN_SAVED = 2.5f;
    private static final float MIN_COOLDOWN_FRACTION = 0.45f;
    private static final float OPPORTUNITY_COOLDOWN_FRACTION = 0.30f;
    private static final float MIN_TIME_BETWEEN_USES = 3f;
    private static final float ENGAGEMENT_RANGE_BUFFER = 250f;
    private static final float FALLBACK_ENGAGEMENT_RANGE = 1200f;

    private ShipAPI ship;
    private ShipSystemAPI system;
    private CombatEngineAPI engine;

    private final IntervalUtil tracker = new IntervalUtil(0.4f, 0.7f);
    private float sinceLastUse = MIN_TIME_BETWEEN_USES;

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.engine = engine;
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (engine == null || engine.isPaused() || ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        sinceLastUse += amount;
        tracker.advance(amount);
        if (!tracker.intervalElapsed()) {
            return;
        }

        if (system == null || system.isActive() || system.isCoolingDown() || system.isOutOfAmmo()) {
            return;
        }
        if (!system.canBeActivated() || system.getCooldownRemaining() > 0f || sinceLastUse < MIN_TIME_BETWEEN_USES) {
            return;
        }
        if (!hasFluxForUse()) {
            return;
        }

        ShipAPI bestTarget = getBestTarget(target);
        if (bestTarget == null || !hasEnemyInBallisticRange(bestTarget)) {
            return;
        }

        CooldownValue value = getCooldownValue();
        if (value.maxCooldown <= 0f || value.cooldownSaved < MIN_COOLDOWN_SAVED) {
            return;
        }

        float fraction = value.cooldownSaved / value.maxCooldown;
        float threshold = isTargetVulnerable(bestTarget) ? OPPORTUNITY_COOLDOWN_FRACTION : MIN_COOLDOWN_FRACTION;
        if (fraction >= threshold) {
            ship.useSystem();
            sinceLastUse = 0f;
        }
    }

    private boolean hasFluxForUse() {
        float fluxPerUse = system.getFluxPerUse();
        if (fluxPerUse <= 0f) {
            return true;
        }

        float maxFlux = ship.getFluxTracker().getMaxFlux();
        if (maxFlux <= 0f) {
            return false;
        }

        return ship.getFluxTracker().getCurrFlux() + fluxPerUse < maxFlux * 0.95f;
    }

    private ShipAPI getBestTarget(ShipAPI target) {
        if (isValidEnemy(target)) {
            return target;
        }

        ShipAPI shipTarget = ship.getShipTarget();
        if (isValidEnemy(shipTarget)) {
            return shipTarget;
        }

        ShipAPI best = null;
        float bestDistanceSq = Float.MAX_VALUE;
        for (ShipAPI other : engine.getShips()) {
            if (!isValidEnemy(other)) {
                continue;
            }

            float distanceSq = getDistanceSq(ship.getLocation(), other.getLocation());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = other;
            }
        }
        return best;
    }

    private boolean hasEnemyInBallisticRange(ShipAPI target) {
        float maxRange = 0f;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (isRelevantBallisticWeapon(weapon)) {
                maxRange = Math.max(maxRange, weapon.getRange());
            }
        }

        if (maxRange <= 0f) {
            return false;
        }

        float allowedRange = Math.max(maxRange + target.getCollisionRadius() + ENGAGEMENT_RANGE_BUFFER,
                FALLBACK_ENGAGEMENT_RANGE);
        return getDistanceSq(ship.getLocation(), target.getLocation()) <= allowedRange * allowedRange;
    }

    private CooldownValue getCooldownValue() {
        CooldownValue value = new CooldownValue();
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!isRelevantBallisticWeapon(weapon) || weapon.isInBurst()) {
                continue;
            }

            float cooldown = weapon.getCooldown();
            float remaining = weapon.getCooldownRemaining();
            if (cooldown <= 0.5f || remaining <= 0.1f) {
                continue;
            }

            value.maxCooldown += cooldown;
            value.cooldownSaved += remaining;
        }
        return value;
    }

    private boolean isRelevantBallisticWeapon(WeaponAPI weapon) {
        return weapon != null && weapon.getType() == WeaponAPI.WeaponType.BALLISTIC && !weapon.isDecorative();
    }

    private boolean isTargetVulnerable(ShipAPI target) {
        if (target == null || target.getFluxTracker() == null) {
            return false;
        }
        return target.getFluxTracker().isOverloadedOrVenting()
                && (target.getFluxTracker().getOverloadTimeRemaining() > 2f
                || target.getFluxTracker().getTimeToVent() > 2f);
    }

    private boolean isValidEnemy(ShipAPI other) {
        return other != null && other != ship && other.isAlive() && !other.isHulk()
                && other.getOwner() != ship.getOwner();
    }

    private float getDistanceSq(Vector2f a, Vector2f b) {
        return Vector2f.sub(a, b, null).lengthSquared();
    }

    private static class CooldownValue {
        float maxCooldown = 0f;
        float cooldownSaved = 0f;
    }
}
