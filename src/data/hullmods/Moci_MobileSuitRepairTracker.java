package data.hullmods;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 机动兵器整备/起飞阶段状态跟踪器。
 */
public class Moci_MobileSuitRepairTracker {

    public static final String DATA_KEY = "Moci_MobileSuitRepairTracker";
    public static final float REFIT_COOLDOWN_SECONDS = 3f;

    public enum State {
        IDLE,
        RETURNING,
        APPROACHING,
        REPAIRING,
        TAKEOFF_PENDING,
        TAKING_OFF,
        EMERGENCY_RELEASE,
        COMPLETED,
        ABORTED
    }

    private final ShipAPI ship;
    private State state = State.IDLE;
    private ShipAPI carrier;
    private Moci_RepairBayScript.Moci_RepairBay bay;
    private Vector2f lastKnownLocation;
    private float stateElapsed = 0f;
    private float totalElapsed = 0f;
    private float cooldownUntil = 0f;
    private boolean emergencyRelease = false;

    public Moci_MobileSuitRepairTracker(ShipAPI ship) {
        this.ship = ship;
    }

    public static Moci_MobileSuitRepairTracker get(ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        Object existing = ship.getCustomData().get(DATA_KEY);
        return existing instanceof Moci_MobileSuitRepairTracker
                ? (Moci_MobileSuitRepairTracker) existing
                : null;
    }

    public static Moci_MobileSuitRepairTracker getOrCreate(ShipAPI ship) {
        Moci_MobileSuitRepairTracker tracker = get(ship);
        if (tracker == null && ship != null) {
            tracker = new Moci_MobileSuitRepairTracker(ship);
            ship.setCustomData(DATA_KEY, tracker);
        }
        return tracker;
    }

    public static void clear(ShipAPI ship) {
        if (ship != null) {
            ship.removeCustomData(DATA_KEY);
        }
    }

    public void advance(float amount) {
        stateElapsed += amount;
        totalElapsed += amount;
    }

    public void beginReturn(ShipAPI carrier, Moci_RepairBayScript.Moci_RepairBay bay) {
        updateState(State.RETURNING, carrier, bay);
    }

    public void beginApproach(ShipAPI carrier, Moci_RepairBayScript.Moci_RepairBay bay) {
        emergencyRelease = false;
        updateState(State.APPROACHING, carrier, bay);
    }

    public void beginRepair(ShipAPI carrier, Moci_RepairBayScript.Moci_RepairBay bay) {
        updateState(State.REPAIRING, carrier, bay);
    }

    public void beginTakeoff(ShipAPI carrier, Moci_RepairBayScript.Moci_RepairBay bay) {
        updateState(State.TAKEOFF_PENDING, carrier, bay);
    }

    public void markTakingOff() {
        updateState(State.TAKING_OFF, carrier, bay);
    }

    public void markEmergencyRelease() {
        emergencyRelease = true;
        updateState(State.EMERGENCY_RELEASE, carrier, bay);
    }

    public void markCompleted() {
        if (state == State.COMPLETED || state == State.ABORTED) return;
        updateState(State.COMPLETED, carrier, bay);
        startCooldown();
    }

    public void markAborted() {
        if (state == State.COMPLETED || state == State.ABORTED) return;
        updateState(State.ABORTED, carrier, bay);
        startCooldown();
    }

    public boolean isActive() {
        return state == State.RETURNING || state == State.APPROACHING || state == State.REPAIRING
                || state == State.TAKEOFF_PENDING || state == State.TAKING_OFF
                || state == State.EMERGENCY_RELEASE;
    }

    public boolean wasEmergencyRelease() {
        return emergencyRelease;
    }

    public float getCooldownRemaining() {
        return Math.max(0f, cooldownUntil - combatTime());
    }

    private void startCooldown() {
        cooldownUntil = combatTime() + REFIT_COOLDOWN_SECONDS;
    }

    private float combatTime() {
        return Global.getCombatEngine() != null
                ? Global.getCombatEngine().getTotalElapsedTime(false) : totalElapsed;
    }

    public boolean isRepairing() {
        return state == State.REPAIRING;
    }

    public static boolean isShipRepairing(ShipAPI ship) {
        Moci_MobileSuitRepairTracker tracker = get(ship);
        return tracker != null && tracker.isRepairing();
    }

    public State getState() {
        return state;
    }

    public ShipAPI getCarrier() {
        return carrier;
    }

    public Moci_RepairBayScript.Moci_RepairBay getBay() {
        return bay;
    }

    public Vector2f getLastKnownLocation() {
        return lastKnownLocation != null ? new Vector2f(lastKnownLocation) : null;
    }

    public float getStateElapsed() {
        return stateElapsed;
    }

    public float getTotalElapsed() {
        return totalElapsed;
    }

    private void updateState(State newState, ShipAPI carrier, Moci_RepairBayScript.Moci_RepairBay bay) {
        this.state = newState;
        this.stateElapsed = 0f;
        this.carrier = carrier;
        this.bay = bay;
        if (bay != null && bay.getLocation() != null) {
            this.lastKnownLocation = new Vector2f(bay.getLocation());
        } else if (carrier != null && carrier.getLocation() != null) {
            this.lastKnownLocation = new Vector2f(carrier.getLocation());
        }
    }
}
