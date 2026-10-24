package data.scripts;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import data.shipsystems.scripts.Moci_MSLaunchTravelDrive;

/** 在战斗层面管理起飞预约，也覆盖没有机动战士识别卡的舰船。 */
public final class Moci_LaunchLifecycle extends BaseEveryFrameCombatPlugin {
    private static final String PLUGIN_KEY = "moci_launch_lifecycle";
    private static final String COLLISION_ID = "moci_mslaunch_fighter_collision";
    private static final String CARRIER_KEY = "moci_launch_carrier_id";
    private static final String SLOT_KEY = "moci_launch_slot_id";
    private final CombatEngineAPI engine;
    private final Map<ShipAPI, Reservation> reservations = new IdentityHashMap<>();

    private Moci_LaunchLifecycle(CombatEngineAPI engine) {
        this.engine = engine;
    }

    private static Moci_LaunchLifecycle getOrCreate(CombatEngineAPI engine) {
        Object existing = engine.getCustomData().get(PLUGIN_KEY);
        if (existing instanceof Moci_LaunchLifecycle) return (Moci_LaunchLifecycle) existing;
        Moci_LaunchLifecycle lifecycle = new Moci_LaunchLifecycle(engine);
        engine.addPlugin(lifecycle);
        engine.getCustomData().put(PLUGIN_KEY, lifecycle);
        return lifecycle;
    }

    public static boolean isLaunching(ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (ship == null || engine == null) return false;
        Object existing = engine.getCustomData().get(PLUGIN_KEY);
        return existing instanceof Moci_LaunchLifecycle
                && ((Moci_LaunchLifecycle) existing).reservations.containsKey(ship);
    }

    /** 先预约母舰的起飞容量，具体弹射槽位是否可用另行判断。 */
    public static Reservation reserve(ShipAPI ship, ShipAPI carrier) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || ship == null || carrier == null) return null;
        Moci_LaunchLifecycle lifecycle = getOrCreate(engine);
        if (lifecycle.reservations.containsKey(ship)) return null;
        String capacityKey = capacityKey(carrier.getId());
        int occupied = count(engine, capacityKey);
        if (occupied >= carrier.getHullSpec().getFighterBays()) return null;
        Reservation reservation = lifecycle.new Reservation(ship, carrier.getId());
        lifecycle.reservations.put(ship, reservation);
        engine.getCustomData().put(capacityKey, occupied + 1);
        ship.setCustomData(CARRIER_KEY, carrier.getId());
        return reservation;
    }

    private static String capacityKey(String carrierId) {
        return "moci_launchSlots" + carrierId;
    }

    private static int count(CombatEngineAPI engine, String key) {
        Object value = engine.getCustomData().get(key);
        return value instanceof Integer ? Math.max(0, (Integer) value) : 0;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine.isPaused()) return;
        for (Reservation reservation : new ArrayList<>(reservations.values())) {
            ShipAPI ship = reservation.ship;
            if (engine.isCombatOver() || !ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
                reservation.release();
                continue;
            }
            // 起飞初始化同步完成；若下一帧预约仍未启动，就释放它以免泄漏占用。
            if (!reservation.started) {
                reservation.release();
                continue;
            }
            reservation.remaining -= amount;
            if (reservation.remaining <= 0f) {
                reservation.release();
            } else {
                ship.setCustomData(Moci_MSLaunchTravelDrive.LAUNCH_TIMER_KEY, reservation.remaining);
            }
        }
        if (reservations.isEmpty()) {
            if (engine.getCustomData().get(PLUGIN_KEY) == this) engine.getCustomData().remove(PLUGIN_KEY);
            engine.removePlugin(this);
        }
    }

    public final class Reservation {
        private final ShipAPI ship;
        private final String carrierId;
        private String slotKey;
        private boolean started;
        private boolean released;
        private float remaining;

        private Reservation(ShipAPI ship, String carrierId) {
            this.ship = ship;
            this.carrierId = carrierId;
        }

        public boolean claimSlot(String slotId) {
            if (released || slotKey != null || slotId == null) return false;
            String key = capacityKey(carrierId) + "_" + slotId;
            if (engine.getCustomData().containsKey(key)) return false;
            slotKey = key;
            engine.getCustomData().put(key, this);
            ship.setCustomData(SLOT_KEY, slotId);
            return true;
        }

        public void start() {
            if (released || started) return;
            started = true;
            remaining = Moci_MSLaunchTravelDrive.LAUNCH_DURATION;
            Moci_CollisionStateManager.getInstance().setCollisionModifier(ship,
                    COLLISION_ID, 150, CollisionClass.FIGHTER);
            ship.setCustomData(Moci_MSLaunchTravelDrive.LAUNCH_TIMER_KEY, remaining);
        }

        /** 初始化失败时，也由起飞流程的 finally 分支调用此方法回收预约。 */
        public void release() {
            if (released) return;
            released = true;
            if (slotKey != null && engine.getCustomData().get(slotKey) == this) {
                engine.getCustomData().remove(slotKey);
            }
            String key = capacityKey(carrierId);
            int remainingCapacity = Math.max(0, count(engine, key) - 1);
            if (remainingCapacity == 0) engine.getCustomData().remove(key);
            else engine.getCustomData().put(key, remainingCapacity);
            Moci_CollisionStateManager.getInstance().removeCollisionModifier(ship, COLLISION_ID);
            reservations.remove(ship);
            ship.removeCustomData(CARRIER_KEY);
            ship.removeCustomData(SLOT_KEY);
            ship.removeCustomData(Moci_MSLaunchTravelDrive.LAUNCH_TIMER_KEY);
        }
    }
}
