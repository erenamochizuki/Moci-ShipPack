package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One owner per actual combat ship. Any native missile charge commits one whole-ship magazine;
 * no input key is stolen and missiles, damage, ammo regeneration and flux still use native APIs.
 * Pose timing uses the weapon's already ship-time-scaled amount exactly once per engine frame.
 */
public final class PsychoSalvoCoordinator {
    private static final String KEY = "xi.arsenal.psycho.salvoCoordinator";
    private static final int MAX_MOUNTS = 32;
    private final CombatEngineAPI engine;
    private final ShipAPI ship;
    private final PsychoSalvoProfile profile;
    private final List<Mount> mounts = new ArrayList<>();
    private String phase = "idle";
    private int sourceOwner, lastFrame = Integer.MIN_VALUE, emitted;
    private long round;
    private float localElapsed;
    private float maximumNativeCharge, maximumStep;
    private Map<String, Object> observation = new LinkedHashMap<>();

    private static final class Mount {
        final WeaponAPI weapon;
        final Map<String, Object> observation = new LinkedHashMap<>();
        boolean nativeStarted, complete;
        Mount(WeaponAPI weapon) { this.weapon = weapon; }
    }

    private PsychoSalvoCoordinator(CombatEngineAPI engine, ShipAPI ship) {
        this.engine = engine; this.ship = ship;
        this.profile = PsychoSalvoProfile.forShip(ship);
    }

    static PsychoSalvoCoordinator get(CombatEngineAPI engine, ShipAPI ship) {
        Object value = ship.getCustomData().get(KEY);
        if (value instanceof PsychoSalvoCoordinator controller) {
            if (controller.engine != engine) throw new IllegalStateException("Psycho coordinator reused across battles");
            return controller;
        }
        PsychoSalvoCoordinator controller = new PsychoSalvoCoordinator(engine, ship);
        ship.setCustomData(KEY, controller);
        return controller;
    }

    public static boolean isActive(ShipAPI ship) {
        Object value = ship.getCustomData().get(KEY);
        return value instanceof PsychoSalvoCoordinator c && c.active();
    }

    public static boolean isRifleBlocked(ShipAPI ship) {
        return isActive(ship) || FullSalvoPose.isBlockingRifle(ship);
    }

    /** Read-only observation map by convention; it contains no retained engine entities. */
    public static Map<String, Object> status(ShipAPI ship) {
        Object value = ship.getCustomData().get(KEY);
        return value instanceof PsychoSalvoCoordinator c ? c.observation : Map.of();
    }

    long roundNumber() { return round; }
    int mountOrdinal(WeaponAPI weapon) {
        for (int index=0;index<mounts.size();index++) if(mounts.get(index).weapon==weapon)return index;
        throw new IllegalArgumentException("Unregistered physical psycho mount");
    }
    private boolean active() { return "charging".equals(phase) || "firing".equals(phase); }

    void advance(float amount, WeaponAPI caller) {
        int frame = Float.floatToIntBits(engine.getTotalElapsedTime(false));
        if (frame == lastFrame) return;
        lastFrame = frame;
        maximumStep = Math.max(maximumStep, Math.min(0.25f, amount));
        if (active()) {
            localElapsed += amount;
            publishBudget();
            String invalid = invalidReason();
            if (invalid == null) for (Mount mount : mounts) {
                if (mount.weapon.isDisabled() || mount.weapon.isPermanentlyDisabled() || mount.weapon.isForceDisabled()) {
                    invalid = "weapon-disabled:" + mount.weapon.getSlot().getId(); break;
                }
            }
            if (invalid != null) cancel(invalid);
            else if (localElapsed > nativeChargeBudget() && mounts.stream().anyMatch(m -> !m.nativeStarted))
                cancel("native-charge-timeout");
            else if (localElapsed > salvoBudget()) cancel("salvo-deadline");
        }
        if (!active() && ready()) {
            for (Mount mount : mounts) {
                if (mount.weapon.getChargeLevel() > 0f) { begin(mount.weapon, false); break; }
            }
        }
        // Xi opens directly along its native charge; it has no additional
        // ship-local prefire timer. Penelope keeps its independent opening.
        FullSalvoPose.setActive(ship, active(), xiOpening() ? 0f : amount);
        suppressRifle();
        if (active()) {
            observation.put("localElapsed", localElapsed);
            if ("charging".equals(phase)) observation.put("chargeLocalElapsed", localElapsed);
            observation.put("rifleBlocked", true);
            observation.put("poseOpening", FullSalvoPose.opening(ship));
        } else if (!observation.isEmpty()) {
            observation.put("rifleBlocked", FullSalvoPose.isBlockingRifle(ship));
            observation.put("poseOpening", FullSalvoPose.opening(ship));
        }
    }

    void observeCharge(WeaponAPI weapon) {
        if (!active() && weapon.getChargeLevel() > 0f && ready()) begin(weapon, false);
        if (active()) suppressRifle();
    }

    void prepare(WeaponAPI weapon) {
        if (!active()) {
            // Wait for every physical mount's entire magazine, never quietly start a partial volley.
            if (!ready() || FullSalvoPose.isBlockingRifle(ship)) weapon.setForceNoFireOneFrame(true);
            return;
        }
        Mount mount = find(weapon);
        if (mount == null) { weapon.setForceNoFireOneFrame(true); return; }
        if (mount.nativeStarted) weapon.setForceNoFireOneFrame(true);
        else {
            // Complete each mount's real native charge even if the original click was brief.
            // Do not set no-fire while charging: that would reset native charge every frame.
            weapon.setForceNoFireOneFrame(false);
            weapon.setForceFireOneFrame(true);
        }
    }

    private boolean xiOpening() { return profile == PsychoSalvoProfile.XI && "charging".equals(phase); }

    /**
     * ProjectileWeapon consumes force-fire flags BEFORE effect.advance, then
     * advances its native charge AFTER this callback. Apply the corresponding
     * real arm pose here, before that native step (never in a render callback).
     * This mirrors the native charge's RoF/minRefireDelay time scaling, so a RoF
     * bonus cannot fire through closed arms or leave an extra charge after them.
     */
    void beforeNativeCharge(WeaponAPI weapon, float amount) {
        if (!xiOpening()) return;
        Mount mount = find(weapon);
        if (mount == null || mount.nativeStarted) return;
        float duration = weapon.getSpec().getChargeTime();
        if (!Float.isFinite(duration) || duration <= 0f)
            throw new IllegalStateException("Xi opening requires positive native charge duration");
        float nativeStep = amount;
        float rate = ship.getMutableStats().getMissileRoFMult().getModifiedValue();
        if (rate > 1f && weapon.isFiring()) {
            ProjectileWeaponSpecAPI spec = (ProjectileWeaponSpecAPI) weapon.getSpec();
            float cycle = weapon.isInBurst() ? spec.getBurstDelay() : spec.getRefireDelay() + duration;
            float minimum = Global.getSettings().getFloat("minRefireDelay");
            nativeStep = cycle / rate < minimum ? Math.max(amount, amount * cycle / minimum) : amount * rate;
        } else if (rate < 1f) nativeStep *= rate;
        float projected = Math.max(0f, Math.min(1f, weapon.getChargeLevel())) + nativeStep / duration;
        FullSalvoPose.followNativeOpening(ship, projected);
        observation.put("poseOpening", FullSalvoPose.opening(ship));
        observation.put("poseReady", FullSalvoPose.isFullyOpen(ship));
        if (FullSalvoPose.isFullyOpen(ship) && !observation.containsKey("poseReadyEngineTime")) {
            observation.put("poseReadyEngineTime", (double) engine.getTotalElapsedTime(false));
            observation.put("poseReadyLocalElapsed", localElapsed);
        }
    }

    private void discover() {
        if (!mounts.isEmpty()) return;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!PsychoSalvoProfile.ownsWeapon(weapon.getId())
                    || !(weapon.getEffectPlugin() instanceof PsychoWeaponClock)) continue;
            if (mounts.size() >= MAX_MOUNTS) throw new IllegalStateException("Too many psycho mounts on one ship");
            mounts.add(new Mount(weapon));
        }
        mounts.sort(Comparator.comparing(m -> m.weapon.getSlot().getId()));
    }

    private boolean ready() {
        discover();
        // A cancellation's current native input was already captured before our
        // callback. Let its queued no-fire and closing pose settle before any
        // newly observed charge can commit another round.
        if (!active() && FullSalvoPose.isBlockingRifle(ship)) return false;
        if (mounts.isEmpty() || invalidReason() != null) return false;
        for (Mount mount : mounts) {
            WeaponAPI weapon = mount.weapon;
            if (weapon.getMaxAmmo() < 1 || weapon.getAmmo() < weapon.getMaxAmmo()
                    || weapon.getCooldownRemaining() > 0.0001f || weapon.isDisabled()
                    || weapon.isPermanentlyDisabled() || weapon.isForceDisabled()) return false;
        }
        return true;
    }

    private void begin(WeaponAPI trigger, boolean nativeFallback) {
        discover();
        phase = "charging"; sourceOwner = ship.getOwner(); round++; emitted = 0;
        // Charge was advanced by the native weapon before advanceAfter observed it. Include
        // that measured initial progress instead of pretending the charge began a frame later.
        float initialCharge = Math.max(0f, Math.min(1f, trigger.getChargeLevel())) * trigger.getSpec().getChargeTime();
        localElapsed = initialCharge;
        observation = new LinkedHashMap<>();
        observation.put("state", phase); observation.put("shipId", ship.getId());
        observation.put("hullId", ship.getHullSpec().getHullId()); observation.put("round", round);
        observation.put("profile", profile.id);
        observation.put("chargeStartEngineTime", (double) engine.getTotalElapsedTime(false));
        observation.put("initialNativeChargeSeconds", initialCharge);
        observation.put("chargeLocalElapsed", localElapsed); observation.put("localElapsed", localElapsed);
        observation.put("nativeCallbackFallback", nativeFallback);
        observation.put("emittedShots", emitted); observation.put("rifleBlocked", true);
        observation.put("cannonsUnmodified", true);
        Map<String, Map<String, Object>> slots = new LinkedHashMap<>();
        int expected = 0; maximumNativeCharge = 0f;
        for (Mount mount : mounts) {
            mount.nativeStarted = false; mount.complete = false; mount.observation.clear();
            int capacity = mount.weapon.getMaxAmmo(); expected += capacity;
            mount.observation.put("capacity", capacity); mount.observation.put("roundShots", 0);
            mount.observation.put("remaining", capacity); mount.observation.put("nativeStarted", false);
            float charge = mount.weapon.getSpec().getChargeTime();
            if (!Float.isFinite(charge) || charge < 0f)
                throw new IllegalStateException("Invalid native psycho charge time: " + charge);
            maximumNativeCharge = Math.max(maximumNativeCharge, charge);
            mount.observation.put("nativeChargeSeconds", charge);
            slots.put(mount.weapon.getSlot().getId(), mount.observation);
        }
        observation.put("expectedShots", expected); observation.put("slots", slots);
        publishBudget();
        PsychoTelemetry.shipSalvo(engine, ship, observation);
        PsychoTelemetry.increment(engine, "shipSalvosStarted");
        FullSalvoPose.setActive(ship, true, profile == PsychoSalvoProfile.XI ? 0f : initialCharge);
        if (profile == PsychoSalvoProfile.XI)
            FullSalvoPose.followNativeOpening(ship, trigger.getChargeLevel());
        suppressRifle();
        // Other native mounts may not have been advanced yet this frame. Force only the
        // pending first shot, never an extra native shot from a mount already in its schedule.
        for (Mount mount : mounts) prepare(mount.weapon);
    }

    boolean nativeFirstShot(WeaponAPI weapon) {
        if (!active()) {
            // Defensive observation for unusual native ordering. Normal tests must see false
            // here: native charge should already have been seen in advanceAfter.
            begin(weapon, true);
        }
        Mount mount = find(weapon);
        if (mount == null || mount.nativeStarted) { cancel("unexpected-native-shot"); return false; }
        if (profile == PsychoSalvoProfile.XI && !FullSalvoPose.isFullyOpen(ship)) {
            // A changed native ordering/spec is a visible contract violation,
            // never permission to schedule the rest through an unopened pose.
            cancel("native-first-shot-before-pose-ready"); return false;
        }
        mount.nativeStarted = true;
        mount.observation.put("nativeStarted", true);
        mount.observation.put("firstShotPoseOpening", FullSalvoPose.opening(ship));
        mount.observation.put("firstShotPoseReady", FullSalvoPose.isFullyOpen(ship));
        mount.observation.put("firstFireLocalElapsed", localElapsed);
        if ("charging".equals(phase)) {
            phase = "firing"; observation.put("state", phase);
            observation.put("firstFireEngineTime", (double) engine.getTotalElapsedTime(false));
            observation.put("firstFireLocalElapsed", localElapsed);
            observation.put("chargeLocalElapsed", localElapsed);
        }
        shot();
        weapon.setForceNoFireOneFrame(true);
        return true;
    }

    void scheduledShot(WeaponAPI weapon) { if (active()) shot(); }

    private float nativeChargeBudget() { return maximumNativeCharge + 0.6f + 2f * maximumStep; }
    private float salvoBudget() { return nativeChargeBudget() + profile.maximumWindow + 0.8f + 4f * maximumStep; }
    private void publishBudget() {
        observation.put("maximumNativeChargeSeconds", maximumNativeCharge);
        observation.put("nativeChargeBudgetSeconds", nativeChargeBudget());
        observation.put("salvoWindowUpperSeconds", profile.maximumWindow);
        observation.put("salvoBudgetSeconds", salvoBudget());
        observation.put("maximumWeaponStepSeconds", maximumStep);
    }
    private void shot() {
        emitted++;
        observation.put("emittedShots", emitted);
        observation.put("lastFireEngineTime", (double) engine.getTotalElapsedTime(false));
        observation.put("lastFireLocalElapsed", localElapsed);
    }

    void mountProgress(WeaponAPI weapon, int count, int remaining, boolean complete) {
        Mount mount = find(weapon);
        if (mount == null || !active()) return;
        mount.observation.put("roundShots", count); mount.observation.put("remaining", remaining);
        mount.complete = complete;
        if (mounts.stream().allMatch(m -> m.nativeStarted && m.complete)) {
            phase = "completed"; observation.put("state", phase);
            observation.put("completedEngineTime", (double) engine.getTotalElapsedTime(false));
            PsychoTelemetry.increment(engine, "shipSalvosCompleted");
            FullSalvoPose.setActive(ship, false, 0f);
        }
    }

    private Mount find(WeaponAPI weapon) {
        for (Mount mount : mounts) if (mount.weapon == weapon) return mount;
        return null;
    }

    String invalidReason() {
        if (ship == null || !ship.isAlive() || ship.isHulk()) return "ship-dead";
        if (ship.wasRemoved() || ship.isExpired() || !engine.isEntityInPlay(ship)) return "ship-removed";
        if (active() && ship.getOwner() != sourceOwner) return "owner-changed";
        if (ship.isPhased()) return "ship-phased";
        if (ship.getFluxTracker().isOverloadedOrVenting()) return "overloaded-or-venting";
        if (ship.isHoldFire() || ship.isHoldFireOneFrame()) return "ship-hold-fire";
        return null;
    }

    void cancel(String reason) {
        if (!active()) return;
        phase = "cancelled";
        observation.put("state", phase); observation.put("reason", reason);
        observation.put("cancelledEngineTime", (double) engine.getTotalElapsedTime(false));
        for (Mount mount : mounts) {
            mount.weapon.setForceNoFireOneFrame(true); mount.weapon.stopFiring();
            if (mount.weapon.getEffectPlugin() instanceof PsychoWeaponClock clock) clock.cancelLocal(engine, reason);
        }
        PsychoTelemetry.increment(engine, "shipSalvosCancelled");
        FullSalvoPose.setActive(ship, false, 0f);
    }

    private void suppressRifle() {
        if (!isRifleBlocked(ship)) return;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot() != null && "ARM_R_SOURCE".equals(weapon.getSlot().getId())) {
                weapon.setForceNoFireOneFrame(true);
                // Stop an already running native rifle burst as the arms leave their aiming pose.
                weapon.stopFiring();
            }
        }
    }
}
