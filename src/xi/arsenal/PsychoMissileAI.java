package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import java.util.Iterator;
import java.util.Map;
import org.lwjgl.util.vector.Vector2f;

/**
 * Three-stage guidance: disperse, curved interception with local bullet avoidance, terminal intercept.
 * The engine still moves and collides the missile and resolves shield/armour/hull damage.
 * Guidance never writes position or target health. Its velocity correction is acceleration-limited
 * to 6000 units/s^2 for Xi and 2100 for the own Penelope hull. Penelope also bounds
 * velocity course changes and samples target motion less often; neither path teleports to aim.
 */
public final class PsychoMissileAI implements MissileAIPlugin, GuidedMissileAI {
    public static final String PROJECTILE_ID = "Moci_fw_xi_psycho_missile_shot";
    private static final float SEARCH_RADIUS = 6200f;
    private static final float AVOID_RADIUS = 240f;
    private static final int MAX_AVOID_OBJECTS = 64;
    private static final int MAX_TARGET_OBJECTS = 128;
    private static final float ORPHAN_LIFETIME = 3f;
    private static final String GUIDANCE_MODIFIER = "xi.arsenal.penelopeGuidance";

    private final CombatEngineAPI engine;
    private final MissileAPI missile;
    private final ShipAPI launchingShip;
    private final PsychoSalvoProfile profile;
    private final long sequence;
    private final float lane;
    private final float launchFacing;
    private final float outwardAngle;
    private final float dispersionTime;
    private final float launchSpeed;
    private final float launchAcceleration;
    private final float curvePhase;
    private final float curvePhase2;
    private final float curveFrequency;
    private final float curveFrequency2;
    private final float curveAmplitude;
    private final float curveAmplitude2;
    private final float targetCheckInterval;
    private final Vector2f lastLocation;
    private ShipAPI target;
    private ShipAPI missedTarget;
    private float closestApproach = Float.POSITIVE_INFINITY;
    private boolean terminalPassArmed;
    private float age;
    private float noTargetTime;
    private float targetCheck;
    private float avoidanceCheck;
    private float avoidanceRadians;
    private float sampleCheck;
    private float lastVelocityAngle;
    private float targetSampleAge;
    private final Vector2f targetPosition = new Vector2f(), targetVelocity = new Vector2f();
    private boolean everTargeted;
    private boolean terminalEntered;
    private boolean orphanEnded;

    public PsychoMissileAI(MissileAPI missile, ShipAPI launchingShip) {
        this.engine = Global.getCombatEngine();
        this.missile = missile;
        ShipAPI nativeSource = missile.getSource();
        this.launchingShip = nativeSource == null ? launchingShip : nativeSource;
        this.profile = PsychoSalvoProfile.forShip(this.launchingShip);
        this.sequence = PsychoTelemetry.increment(engine, "aiCreated");
        String slot = missile.getWeapon() == null || missile.getWeapon().getSlot() == null
                ? "script" : missile.getWeapon().getSlot().getId();
        long styleSeed = mix(((long) slot.hashCode() << 32) ^ sequence * 0x9E3779B97F4A7C15L);
        this.lane = unit(styleSeed) * 2f - 1f;
        this.launchFacing = missile.getFacing();
        this.outwardAngle = -94f + unit(styleSeed + 1L) * 188f;
        this.dispersionTime = 0.23f + unit(styleSeed + 2L) * 0.25f;
        this.launchSpeed = 300f + unit(styleSeed + 3L) * 180f;
        this.launchAcceleration = 800f + unit(styleSeed + 4L) * 600f;
        this.curvePhase = unit(styleSeed + 5L) * (float) Math.PI * 2f;
        this.curvePhase2 = unit(styleSeed + 6L) * (float) Math.PI * 2f;
        this.curveFrequency = 3.4f + unit(styleSeed + 7L) * 4.4f;
        this.curveFrequency2 = 1.5f + unit(styleSeed + 8L) * 3f;
        this.curveAmplitude = 0.12f + unit(styleSeed + 9L) * 0.16f;
        this.curveAmplitude2 = 0.04f + unit(styleSeed + 10L) * 0.08f;
        this.targetCheckInterval = profile.targetCheckMinimum + unit(styleSeed + 11L) * profile.targetCheckSpread;
        this.lastLocation = new Vector2f(missile.getLocation());
        this.lastVelocityAngle = angle(missile.getVelocity().x, missile.getVelocity().y);
        this.avoidanceCheck = unit(styleSeed + 12L) * 0.08f;
        missile.setEccmChanceOverride(1f);
        missile.setArmedWhileFizzling(false);
        if (profile == PsychoSalvoProfile.PENELOPE) {
            // Instance-only native engine modifiers; the common projectile spec and Xi stay intact.
            MutableShipStatsAPI engineStats = missile.getEngineStats();
            if (engineStats != null) {
                // At most a quarter of the original turn rate, with the same
                // source-profile ceiling (currently140deg/s) as our physical
                // velocity-course correction. Xi never receives these modifiers.
                float originalTurn = engineStats.getMaxTurnRate().getModifiedValue();
                engineStats.getMaxTurnRate().modifyMult(GUIDANCE_MODIFIER,
                        originalTurn > 0f ? Math.min(0.25f, profile.turnRate / originalTurn) : 0.25f);
                engineStats.getTurnAcceleration().modifyMult(GUIDANCE_MODIFIER, 0.40f);
            }
        }
        missile.setCustomData("xi.arsenal.guidance", Map.of("profile", profile.id,
                "steerAcceleration", profile.steerAcceleration, "turnRateLimit", profile.turnRate,
                "targetMotionSampleSeconds", targetCheckInterval));
    }

    @Override
    public void advance(float amount) {
        if (engine.isPaused() || amount <= 0f || !Float.isFinite(amount)) return;
        if (missile.isExpired() || missile.wasRemoved() || missile.isFading()
                || missile.isFizzling() || missile.didDamage()) return;
        age += amount;
        targetCheck -= amount;
        avoidanceCheck -= amount;
        sampleCheck -= amount;
        targetSampleAge += amount;
        if (target != null && !valid(target)) {
            target = null;
            resetApproach();
            if (profile != PsychoSalvoProfile.PENELOPE) targetCheck = 0f;
        }
        if (profile == PsychoSalvoProfile.PENELOPE && target != null) observeTerminalPass(amount);
        if (targetCheck <= 0f) {
            targetCheck = targetCheckInterval;
            if (target == null) acquireTarget();
            if (target != null) sampleTargetMotion();
        }

        // Explicit targeted jamming is distinct from probabilistic flares/ECCM. Keep
        // native flight lifetime and ordinary momentum, but never steer/re-lock to a
        // protected target or expire our3s orphan clock solely because NTD hides it.
        if (target == null && missedTarget == null && UnicornNtdInterference.isMissileSuppressed(missile)) {
            missile.giveCommand(ShipCommand.ACCELERATE);
            observe("target-jammed-coast", -1f, amount);
            return;
        }

        float desiredAngle;
        float desiredSpeed;
        float distance = -1f;
        String stage;
        if (target == null) {
            noTargetTime += amount;
            boolean missed = profile == PsychoSalvoProfile.PENELOPE && missedTarget != null;
            desiredAngle = missed ? angle(missile.getVelocity().x, missile.getVelocity().y)
                    : (float) Math.toRadians(launchFacing + outwardAngle * 0.7f);
            desiredSpeed = missed ? missile.getMaxSpeed() : Math.min(700f, missile.getMaxSpeed());
            stage = missed ? "missed-coast" : "search";
            // An overshot Penelope missile coasts for its original native finite
            // flight time. It never turns around or acquires the same ship again.
            if (!missed && noTargetTime >= ORPHAN_LIFETIME && !orphanEnded) {
                orphanEnded = true;
                PsychoTelemetry.increment(engine, "orphanTimeouts");
                missile.flameOut();
                return;
            }
        } else {
            noTargetTime = 0f;
            boolean sampled = profile == PsychoSalvoProfile.PENELOPE;
            float targetX = sampled ? targetPosition.x + targetVelocity.x * targetSampleAge : target.getLocation().x;
            float targetY = sampled ? targetPosition.y + targetVelocity.y * targetSampleAge : target.getLocation().y;
            Vector2f motion = sampled ? targetVelocity : target.getVelocity();
            float dx = targetX - missile.getLocation().x;
            float dy = targetY - missile.getLocation().y;
            distance = (float) Math.hypot(dx, dy);
            float edgeDistance = Math.max(0f, distance - target.getCollisionRadius());
            float maxSpeed = Math.max(1f, missile.getMaxSpeed());
            float interceptTime = interceptTime(dx, dy, motion.x, motion.y, maxSpeed);
            float aimX = dx + motion.x * interceptTime;
            float aimY = dy + motion.y * interceptTime;
            desiredAngle = angle(aimX, aimY);
            if (age < dispersionTime && edgeDistance > 200f) {
                stage = "disperse";
                float blend = age / dispersionTime;
                float outward = (float) Math.toRadians(launchFacing + outwardAngle);
                desiredAngle = outward + shortest(outward, desiredAngle) * blend * 0.45f;
                desiredSpeed = Math.min(maxSpeed, launchSpeed + age * launchAcceleration);
            } else {
                float terminalBlend = clamp((edgeDistance - 180f) / 350f, 0f, 1f);
                if (terminalBlend < 1f) {
                    stage = "terminal";
                    if (!terminalEntered) {
                        terminalEntered = true;
                        PsychoTelemetry.increment(engine, "terminalEntries");
                    }
                } else stage = "cruise";
                // Independent hashed frequencies, phases and amplitudes avoid repeated fan/ring bands.
                // Both smooth components disappear before contact, preserving terminal interception.
                float curve = ((float) Math.sin(age * curveFrequency + curvePhase) * curveAmplitude
                        + (float) Math.sin(age * curveFrequency2 + curvePhase2) * curveAmplitude2
                        + lane * 0.12f) * terminalBlend;
                if (avoidanceCheck <= 0f) {
                    avoidanceCheck = 0.08f;
                    avoidanceRadians = avoidance(desiredAngle);
                }
                float avoidanceBlend = clamp((edgeDistance - 250f) / 250f, 0f, 1f);
                desiredAngle += curve + avoidanceRadians * avoidanceBlend;
                // Braking near the target prevents fast missiles orbiting a small enemy forever.
                float closingSpeedFloor = Math.max(480f, motion.length() + 350f);
                desiredSpeed = Math.min(maxSpeed,
                        Math.max(closingSpeedFloor,
                                (float) Math.sqrt(2f * 6500f * Math.max(edgeDistance, 15f))));
            }
        }

        steer(desiredAngle, desiredSpeed, amount);
        observe(stage, distance, amount);
    }

    private boolean valid(ShipAPI ship) {
        return ship != null && ship != launchingShip && ship.getOwner() >= 0
                && ship.getOwner() != 100 && ship.getOwner() != missile.getOwner()
                && ship.isAlive() && !ship.isHulk() && !ship.isPhased() && ship.isTargetable()
                && !UnicornNtdInterference.isTargetSuppressed(missile, ship)
                && ship.getCollisionClass() != CollisionClass.NONE && engine.isEntityInPlay(ship);
    }

    private void acquireTarget() {
        if (profile == PsychoSalvoProfile.PENELOPE && missedTarget != null) return;
        ShipAPI chosen = null;
        boolean lockedPool = ContentTargetLocks.managed(engine, launchingShip);
        if (lockedPool) {
            float range = missile.getWeapon() == null ? SEARCH_RADIUS : missile.getWeapon().getRange();
            chosen = ContentTargetLocks.next(engine, launchingShip, range, this::valid);
            if (chosen != null) missile.setCustomData("xi.arsenal.assignedLockTarget", chosen.getId());
        }
        if (!lockedPool && launchingShip != null && valid(launchingShip.getShipTarget())) {
            ShipAPI selected = launchingShip.getShipTarget();
            if (distanceSquared(selected.getLocation(), missile.getLocation()) <= SEARCH_RADIUS * SEARCH_RADIUS)
                chosen = selected;
        }
        if (!lockedPool && chosen == null) {
            float bestScore = SEARCH_RADIUS * SEARCH_RADIUS;
            Iterator<Object> nearby = engine.getShipGrid().getCheckIterator(missile.getLocation(),
                    SEARCH_RADIUS * 2f, SEARCH_RADIUS * 2f);
            for (int checked = 0; checked < MAX_TARGET_OBJECTS && nearby.hasNext(); checked++) {
                Object object = nearby.next();
                if (!(object instanceof ShipAPI ship) || !valid(ship)) continue;
                float score = distanceSquared(ship.getLocation(), missile.getLocation());
                // Prefer substantial hulls over nearby incidental fighters without ignoring fighters.
                if (ship.isFighter() || ship.isDrone()) score *= 1.6f;
                if (score < bestScore) { bestScore = score; chosen = ship; }
            }
        }
        if (target != chosen) resetApproach();
        target = chosen;
        if (chosen != null) {
            PsychoTelemetry.increment(engine, everTargeted ? "targetReacquired" : "targetAcquired");
            everTargeted = true;
        }
    }

    private void sampleTargetMotion() {
        targetPosition.set(target.getLocation()); targetVelocity.set(target.getVelocity());
        targetSampleAge = 0f;
    }

    private void resetApproach() {
        closestApproach = Float.POSITIVE_INFINITY; terminalPassArmed = false;
    }

    /**
     * Lose an actually passed terminal target, never a merely side-facing launch.
     * Real relative motion, collision envelopes and a prior closest approach
     * must all agree that separation is increasing with the target behind us.
     */
    private void observeTerminalPass(float amount) {
        if (age < dispersionTime) return;
        float dx = target.getLocation().x - missile.getLocation().x;
        float dy = target.getLocation().y - missile.getLocation().y;
        float distance = (float) Math.hypot(dx, dy);
        float vx = missile.getVelocity().x, vy = missile.getVelocity().y;
        float rvx = target.getVelocity().x - vx, rvy = target.getVelocity().y - vy;
        float relativeSpeed = (float) Math.hypot(rvx, rvy);
        float separationRate = (dx * rvx + dy * rvy) / Math.max(1f, distance);
        float envelope = Math.max(1f, target.getCollisionRadius()) + Math.max(1f, missile.getCollisionRadius());
        float terminalRange = envelope + Math.max(100f, relativeSpeed * 0.18f);
        if (distance <= terminalRange && separationRate < -40f) terminalPassArmed = true;
        closestApproach = Math.min(closestApproach, distance);
        float speed = (float) Math.hypot(vx, vy);
        float behindCosine = (dx * vx + dy * vy) / Math.max(1f, distance * speed);
        float passedMargin = Math.max(18f, relativeSpeed * Math.min(0.1f, amount) * 0.6f);
        if (!terminalPassArmed || distance <= envelope || distance <= closestApproach + passedMargin
                || separationRate <= Math.max(80f, relativeSpeed * 0.08f) || behindCosine >= -0.25f) return;
        missedTarget = target;
        missile.setCustomData("xi.arsenal.guidanceMiss", Map.of("reason", "terminal-target-passed",
                "age", age, "closestApproach", closestApproach, "distance", distance,
                "collisionEnvelope", envelope, "separationRate", separationRate, "behindCosine", behindCosine,
                "reacquisitionAllowed", false));
        PsychoTelemetry.increment(engine, "penelopeMissedTargets");
        target = null;
    }

    /** At most 64 nearby grid objects every 0.08 simulated seconds; no whole-battle projectile scan. */
    private float avoidance(float desiredAngle) {
        Iterator<Object> nearby = engine.getAllObjectGrid().getCheckIterator(missile.getLocation(),
                AVOID_RADIUS * 2f, AVOID_RADIUS * 2f);
        float sum = 0f;
        float fx = (float) Math.cos(desiredAngle), fy = (float) Math.sin(desiredAngle);
        for (int checked = 0; checked < MAX_AVOID_OBJECTS && nearby.hasNext(); checked++) {
            Object object = nearby.next();
            if (!(object instanceof DamagingProjectileAPI projectile) || projectile == missile
                    || projectile.getOwner() == missile.getOwner() || projectile.getOwner() < 0
                    || projectile.getOwner() == 100 || projectile.isFading() || projectile.didDamage()
                    || projectile.getCollisionClass() == CollisionClass.NONE) continue;
            float rx = projectile.getLocation().x - missile.getLocation().x;
            float ry = projectile.getLocation().y - missile.getLocation().y;
            if (rx * rx + ry * ry > AVOID_RADIUS * AVOID_RADIUS) continue;
            float vx = projectile.getVelocity().x - missile.getVelocity().x;
            float vy = projectile.getVelocity().y - missile.getVelocity().y;
            float vv = vx * vx + vy * vy;
            if (vv < 1f) continue;
            float closestTime = -(rx * vx + ry * vy) / vv;
            if (closestTime < 0f || closestTime > 0.28f) continue;
            float cx = rx + vx * closestTime, cy = ry + vy * closestTime;
            float safeDistance = 30f + Math.min(25f, projectile.getCollisionRadius());
            float nearest = (float) Math.hypot(cx, cy);
            if (nearest >= safeDistance) continue;
            float side = fx * ry - fy * rx;
            if (Math.abs(side) < 0.01f) side = lane >= 0f ? 1f : -1f;
            sum -= Math.copySign(0.7f * (1f - nearest / safeDistance)
                    * (1f - closestTime / 0.35f), side);
        }
        sum = clamp(sum, -0.65f, 0.65f);
        if (Math.abs(sum) > 0.01f) PsychoTelemetry.increment(engine, "avoidanceDecisions");
        return sum;
    }

    private void steer(float desiredAngle, float speed, float amount) {
        float facing = (float) Math.toRadians(missile.getFacing());
        float error = shortest(facing, desiredAngle);
        if (error > 0.01f) missile.giveCommand(ShipCommand.TURN_LEFT);
        else if (error < -0.01f) missile.giveCommand(ShipCommand.TURN_RIGHT);
        float angular = missile.getAngularVelocity();
        if (Math.abs(Math.toDegrees(error)) < Math.abs(angular) * 0.07f)
            missile.setAngularVelocity((float) Math.toDegrees(error) / 0.07f);
        if (profile == PsychoSalvoProfile.PENELOPE)
            missile.setAngularVelocity(clamp(missile.getAngularVelocity(), -profile.turnRate, profile.turnRate));
        missile.giveCommand(ShipCommand.ACCELERATE);

        // Continuous physical correction: |delta velocity| <= acceleration * simulated frame amount.
        // There is deliberately no getLocation().set, setFacing(target), or teleport-to-contact.
        Vector2f velocity = missile.getVelocity();
        if (profile == PsychoSalvoProfile.PENELOPE && velocity.lengthSquared() > 1f) {
            float course = angle(velocity.x, velocity.y);
            float limit = Math.min((float) Math.PI, (float) Math.toRadians(profile.turnRate) * amount);
            desiredAngle = course + clamp(shortest(course, desiredAngle), -limit, limit);
        }
        float dx = (float) Math.cos(desiredAngle) * speed - velocity.x;
        float dy = (float) Math.sin(desiredAngle) * speed - velocity.y;
        float delta = (float) Math.hypot(dx, dy);
        float factor = delta > 0f ? Math.min(1f, profile.steerAcceleration * amount / delta) : 0f;
        velocity.x += dx * factor;
        velocity.y += dy * factor;
        float actualSpeed = velocity.length();
        // Account for inherited launching-ship velocity with bounded braking, never a hard speed snap.
        float cap = Math.max(1f, missile.getMaxSpeed());
        if (profile != PsychoSalvoProfile.PENELOPE && actualSpeed > cap) {
            float reduced = Math.max(cap, actualSpeed - 6500f * amount);
            velocity.scale(reduced / actualSpeed);
        }
    }

    private void observe(String stage, float distance, float amount) {
        float dx = missile.getLocation().x - lastLocation.x;
        float dy = missile.getLocation().y - lastLocation.y;
        PsychoTelemetry.add(engine, "travelDistance", Math.hypot(dx, dy));
        lastLocation.set(missile.getLocation());
        float course = angle(missile.getVelocity().x, missile.getVelocity().y);
        PsychoTelemetry.add(engine, "absoluteCourseChangeDegrees", Math.abs(Math.toDegrees(shortest(lastVelocityAngle, course))));
        lastVelocityAngle = course;
        PsychoTelemetry.maximum(engine, "maxObservedSpeed", missile.getVelocity().length());
        if (sampleCheck <= 0f) {
            sampleCheck = 0.1f;
            PsychoTelemetry.point(engine, sequence, age, missile.getLocation().x, missile.getLocation().y,
                    missile.getVelocity().length(), stage, distance);
        }
    }

    /** Constant-velocity intercept; capped prediction avoids extreme leads on evasive targets. */
    static float interceptTime(float x, float y, float vx, float vy, float speed) {
        double a = vx * (double) vx + vy * (double) vy - speed * (double) speed;
        double b = 2d * (x * (double) vx + y * (double) vy);
        double c = x * (double) x + y * (double) y;
        double time = Math.sqrt(c) / Math.max(1f, speed);
        if (Math.abs(a) < 0.001d) {
            if (b < -0.001d) time = -c / b;
        } else {
            double discriminant = b * b - 4d * a * c;
            if (discriminant >= 0d) {
                double root = Math.sqrt(discriminant);
                double first = (-b - root) / (2d * a), second = (-b + root) / (2d * a);
                if (first > 0d && second > 0d) time = Math.min(first, second);
                else if (first > 0d) time = first;
                else if (second > 0d) time = second;
            }
        }
        return (float) Math.max(0d, Math.min(2.5d, time));
    }

    static float shortest(float from, float to) {
        float delta = (to - from) % ((float) Math.PI * 2f);
        if (delta > Math.PI) delta -= (float) Math.PI * 2f;
        if (delta < -Math.PI) delta += (float) Math.PI * 2f;
        return delta;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static float unit(long value) { return (mix(value) >>> 40) * 0x1p-24f; }

    private static float angle(float x, float y) { return (float) Math.atan2(y, x); }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
    private static float distanceSquared(Vector2f a, Vector2f b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    @Override public CombatEntityAPI getTarget() { return target; }

    @Override public void setTarget(CombatEntityAPI candidate) {
        // Ignore flare spoofing and friendly targets, while allowing original engine target assignment.
        if (profile == PsychoSalvoProfile.PENELOPE && missedTarget != null) return;
        if (candidate instanceof ShipAPI ship && valid(ship)) {
            if (target != ship) resetApproach();
            target = ship; sampleTargetMotion();
        } else if (candidate == null) { target = null; resetApproach(); }
    }
}
