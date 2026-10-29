package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.util.vector.Vector2f;

/** Per-mount native first shot plus bounded native projectile scheduling for the current magazine. */
public final class PsychoWeaponClock implements EveryFrameWeaponEffectPluginWithAdvanceAfter {
    /** Legacy schedule-test constant only. Runtime magazine size always comes from getMaxAmmo(). */
    @Deprecated public static final int SALVO_SIZE = 30;
    private static final int FRAME_BUDGET = 4;
    private static final float MAX_CARRY = 2f;
    private WeaponAPI boundWeapon;
    private PsychoSalvoCoordinator coordinator;
    private PsychoSalvoProfile profile;
    private int remaining, roundShots, capacity;
    private float carry, elapsed;
    private boolean deadlineReported;
    private long roundNumber, roundSeed;
    private float[] shotIntervals;
    private Map<String, Object> status;

    @Override public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || amount <= 0f || !Float.isFinite(amount)) return;
        bind(engine, weapon);
        // 光学收尾的所有者由这把武器自己创建，不再是全局插件。
        PsychoArmamentVisuals.ensure(engine);
        PsychoTelemetry.clock(engine, weapon, amount);
        coordinator.advance(amount, weapon);
        coordinator.prepare(weapon);
        coordinator.beforeNativeCharge(weapon, amount);
        if (remaining <= 0) return;
        String invalid = invalidReason(engine, weapon);
        if (invalid != null) { coordinator.cancel(invalid); return; }
        elapsed += amount;
        carry = Math.min(MAX_CARRY, carry + amount);
        emitPending(engine, weapon);
    }

    private void emitPending(CombatEngineAPI engine, WeaponAPI weapon) {
        int emittedThisFrame = 0;
        int budget = profile.waveSalvo() ? 8 : FRAME_BUDGET;
        while (remaining > 0 && carry + 0.000001f >= nextInterval() && emittedThisFrame < budget) {
            String invalid = invalidReason(engine, weapon);
            if (invalid != null) { coordinator.cancel(invalid); return; }
            float interval = nextInterval();
            if (!emit(engine, weapon)) return;
            carry = Math.max(0f, carry - interval);
            remaining--; roundShots++; emittedThisFrame++;
        }
        status.put("roundShots", roundShots);
        status.put("remaining", remaining);
        status.put("weaponLocalElapsed", elapsed);
        status.put("backlogSeconds", carry);
        if (remaining > 0 && carry + 0.000001f >= nextInterval())
            PsychoTelemetry.increment(engine, "salvoFrameBudgetHits");
        if (remaining > 0 && elapsed > profile.maximumWindow + 0.1f && !deadlineReported) {
            deadlineReported = true;
            PsychoTelemetry.increment(engine, "salvoLateRounds");
        }
        if (remaining == 0) {
            status.put("state", "completed");
            status.put("lastEngineTime", (double) engine.getTotalElapsedTime(false));
            PsychoTelemetry.increment(engine, "salvosCompleted");
            weapon.setRemainingCooldownTo(Math.max(weapon.getCooldownRemaining(), weapon.getCooldown()));
            carry = 0f;
        }
        coordinator.mountProgress(weapon, roundShots, remaining, remaining == 0);
    }

    @Override public void advanceAfter(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || amount <= 0f || !Float.isFinite(amount)) return;
        bind(engine, weapon);
        // Native charge advances after everyFrameEffect.advance. Observe it here so the pose
        // starts during the actual native charge, not at the first missile callback.
        coordinator.observeCharge(weapon);
    }

    void startSalvo(CombatEngineAPI engine, WeaponAPI weapon) {
        bind(engine, weapon);
        if (remaining > 0) { coordinator.cancel("unexpected-second-native-trigger"); return; }
        if (!coordinator.nativeFirstShot(weapon)) return;
        capacity = weapon.getMaxAmmo();
        if (capacity < 1 || capacity > 128) throw new IllegalStateException("Invalid psycho magazine capacity " + capacity);
        if (profile.waveSalvo() && (capacity < 3 || capacity > 8))
            throw new IllegalStateException("Three-wave psycho mount requires three to eight missiles: " + capacity);
        roundNumber = coordinator.roundNumber();
        roundSeed = seed(weapon.getSlot().getId(), roundNumber);
        shotIntervals = makeShotIntervals(weapon.getSlot().getId(), roundNumber, capacity, profile,coordinator.mountOrdinal(weapon));
        remaining = capacity - 1; roundShots = 1; carry = 0f; elapsed = 0f;
        deadlineReported = false;
        status = new LinkedHashMap<>();
        status.put("state", remaining > 0 ? "firing" : "completed");
        status.put("capacity", capacity);
        status.put("roundShots", roundShots); status.put("remaining", remaining);
        status.put("firstEngineTime", (double) engine.getTotalElapsedTime(false));
        status.put("weaponLocalElapsed", 0f); status.put("backlogSeconds", 0f);
        status.put("round", roundNumber); status.put("scheduleSeed", Long.toUnsignedString(roundSeed));
        status.put("profile", profile.id); status.put("frameBudget", profile.waveSalvo() ? 8 : FRAME_BUDGET);
        status.put("waveCount", profile.waveSalvo() ? 3 : 0);
        status.put("waveIntervalLocalSeconds", profile.waveSalvo() ? .35f : 0f);
        status.put("maximumCarrySeconds", MAX_CARRY);
        List<Float> plannedTimes = new ArrayList<>(Math.max(0, capacity - 1));
        float planned = 0f;
        for (float interval : shotIntervals) { planned += interval; plannedTimes.add(planned); }
        status.put("plannedShotTimes", plannedTimes); status.put("plannedDuration", planned);
        PsychoTelemetry.salvo(engine, weapon, status);
        PsychoTelemetry.increment(engine, "salvosStarted");
        coordinator.mountProgress(weapon, roundShots, remaining, remaining == 0);
        // The native first missile is already paid for and recorded. Complete the
        // first wave in this tick; the other two waves share the same local
        // offsets on every physical mount, rather than using random intervals.
        if (profile.waveSalvo() && remaining > 0) emitPending(engine, weapon);
    }

    private void bind(CombatEngineAPI engine, WeaponAPI weapon) {
        if (boundWeapon == null) {
            if (!PsychoSalvoProfile.ownsWeapon(weapon.getId()))
                throw new IllegalArgumentException("Psycho clock requires an exact owned weapon ID: " + weapon.getId());
            boundWeapon = weapon;
            profile = PsychoSalvoProfile.forShip(weapon.getShip());
            coordinator = PsychoSalvoCoordinator.get(engine, weapon.getShip());
        } else if (boundWeapon != weapon)
            throw new IllegalStateException("One psycho controller cannot be shared by weapon instances");
    }

    private float nextInterval() { return shotIntervals[roundShots - 1]; }

    /** Legacy overload for old deterministic schedule tests; production calls the capacity overload. */
    static float[] makeShotIntervals(String slot, long round) { return makeShotIntervals(slot, round, SALVO_SIZE); }

    /** Unrelated hulls using this pack's weapons retain the historical timing. */
    static float[] makeShotIntervals(String slot, long round, int capacity) {
        return makeShotIntervals(slot, round, capacity, PsychoSalvoProfile.LEGACY);
    }

    /** Reproducible irregular spacing, normalized to the actual source hull's local-time window. */
    static float[] makeShotIntervals(String slot, long round, int capacity, PsychoSalvoProfile profile) {
        return makeShotIntervals(slot,round,capacity,profile,0);
    }

    static float[] makeShotIntervals(String slot,long round,int capacity,PsychoSalvoProfile profile,int ordinal) {
        if (capacity < 1 || capacity > 128) throw new IllegalArgumentException("Unsupported magazine " + capacity);
        if (capacity == 1) return new float[0];
        if (profile.waveSalvo()) {
            float[] intervals=new float[capacity-1];
            int shot=0,start=Math.floorMod(ordinal+2,3);float previous=0;
            for(int wave=0;wave<3;wave++) {
                int count=capacity/3+(Math.floorMod(wave-start,3)<capacity%3?1:0);
                for(int i=0;i<count;i++,shot++)if(shot>0){float due=wave*.35f;intervals[shot-1]=due-previous;previous=due;}
            }
            return intervals;
        }
        long scheduleSeed = seed(slot, round);
        float duration = profile.minimumWindow + unit(scheduleSeed ^ 0x632BE59BD9B4E019L)
                * (profile.maximumWindow - profile.minimumWindow);
        float nominal = duration / (capacity - 1);
        float[] intervals = new float[capacity - 1];
        float previous = 0f;
        for (int index = 0; index < intervals.length; index++) {
            float due = (index + 1) * nominal;
            if (index < intervals.length - 1)
                due += (unit(scheduleSeed + index * 0x9E3779B97F4A7C15L) - 0.5f) * nominal * 0.8f;
            intervals[index] = due - previous; previous = due;
        }
        float sum = 0f;
        for (float interval : intervals) sum += interval;
        for (int index = 0; index < intervals.length; index++) intervals[index] *= duration / sum;
        return intervals;
    }

    private static long seed(String slot, long round) {
        long slotHash = 0xcbf29ce484222325L;
        for (int index = 0; index < slot.length(); index++) slotHash = (slotHash ^ slot.charAt(index)) * 0x100000001b3L;
        return mix(slotHash ^ round * 0xD1B54A32D192ED03L);
    }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
    private static float unit(long value) { return (mix(value) >>> 40) * 0x1p-24f; }

    private String invalidReason(CombatEngineAPI engine, WeaponAPI weapon) {
        String invalid = coordinator.invalidReason();
        if (invalid != null) return invalid;
        if (weapon.isDisabled() || weapon.isPermanentlyDisabled() || weapon.isForceDisabled()) return "weapon-disabled";
        if (weapon.getAmmo() < 1) return "ammo-empty";
        return null;
    }

    private boolean emit(CombatEngineAPI engine, WeaponAPI weapon) {
        ShipAPI source = weapon.getShip();
        FluxTrackerAPI flux = source.getFluxTracker();
        float cost = weapon.getFluxCostToFire();
        if (!Float.isFinite(cost) || cost < 0f) throw new IllegalStateException("Invalid native missile flux cost: " + cost);
        if (flux.getMaxFlux() - flux.getCurrFlux() + 0.0001f < cost || !flux.increaseFlux(cost, false)) {
            coordinator.cancel("insufficient-flux"); return false;
        }
        int previousAmmo = weapon.getAmmo();
        weapon.setAmmo(previousAmmo - 1);
        CombatEntityAPI projectile;
        try {
            float departureAngle = weapon.getCurrAngle() + (unit(roundSeed ^ roundShots * 0xA24BAED4963EE407L) - 0.5f) * 36f;
            projectile = engine.spawnProjectile(source, weapon, weapon.getId(),
                    new Vector2f(weapon.getFirePoint(0)), departureAngle, new Vector2f(source.getVelocity()));
        } catch (RuntimeException | Error error) {
            weapon.setAmmo(previousAmmo); flux.decreaseFlux(cost);
            coordinator.cancel("native-spawn-exception"); throw error;
        }
        if (!(projectile instanceof MissileAPI)) {
            if (projectile != null) engine.removeEntity(projectile);
            weapon.setAmmo(previousAmmo); flux.decreaseFlux(cost);
            coordinator.cancel("native-spawn-not-missile");
            throw new IllegalStateException("Native psycho spawn did not produce MissileAPI");
        }
        // Real native collision and ModPlugin AI remain authoritative. This helper belongs to
        // this content pack, not the source mod; spawnProjectile does not call weapon onFire.
        PsychoOnFire.record((DamagingProjectileAPI) projectile, weapon, engine, true);
        coordinator.scheduledShot(weapon);
        PsychoTelemetry.add(engine, "scheduledFluxSpent", cost);
        return true;
    }

    void cancelLocal(CombatEngineAPI engine, String reason) {
        if (remaining <= 0) return;
        status.put("state", "cancelled"); status.put("reason", reason);
        status.put("roundShots", roundShots); status.put("cancelledRemaining", remaining);
        status.put("remaining", 0); status.put("lastEngineTime", (double) engine.getTotalElapsedTime(false));
        PsychoTelemetry.increment(engine, "salvosCancelled");
        remaining = 0; carry = 0f;
    }
}
