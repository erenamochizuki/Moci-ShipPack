package data.scripts.weapons;

import java.awt.Color;
import java.util.Iterator;
import java.util.LinkedList;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;

public final class Moci_WingmanSupport {

    public static final String ROOT_MOTHERSHIP_KEY = "Moci_Wingman_RootMothership";

    private static final int MAX_PARENT_DEPTH = 20;
    private static final Color DRONE_DEATH_COLOR = new Color(255, 200, 100, 255);

    private Moci_WingmanSupport() {
    }

    public static ShipAPI resolveWeaponMothership(WeaponAPI weapon) {
        if (weapon == null) {
            return null;
        }
        return resolveRootMothership(weapon.getShip());
    }

    public static ShipAPI resolveRootMothership(ShipAPI ship) {
        ShipAPI current = ship;
        int guard = 0;
        while (current != null
                && current.getParentStation() != null
                && current.getParentStation() != current
                && guard++ < MAX_PARENT_DEPTH) {
            current = current.getParentStation();
        }
        return current;
    }

    public static void linkDroneToMothership(ShipAPI drone, ShipAPI mothership) {
        if (drone == null) {
            return;
        }

        if (mothership != null) {
            drone.getAIFlags().setFlag(ShipwideAIFlags.AIFlags.DRONE_MOTHERSHIP, 100000f, mothership);
            drone.setCustomData(ROOT_MOTHERSHIP_KEY, mothership);
        }

        attachLifecycleListener(drone);
    }

    public static void attachLifecycleListener(ShipAPI drone) {
        if (drone == null || drone.hasListenerOfClass(Moci_WingmanLifecycleListener.class)) {
            return;
        }

        Moci_WingmanLifecycleListener listener = new Moci_WingmanLifecycleListener();
        listener.setManagedShip(drone);
        drone.addListener(listener);
    }

    public static ShipAPI getTrackedMothership(ShipAPI drone) {
        if (drone == null) {
            return null;
        }

        Object data = drone.getCustomData().get(ROOT_MOTHERSHIP_KEY);
        if (data instanceof ShipAPI) {
            return (ShipAPI) data;
        }
        return null;
    }

    public static boolean isMothershipAlive(CombatEngineAPI engine, ShipAPI mothership) {
        return engine != null
                && mothership != null
                && mothership.isAlive()
                && !mothership.isHulk()
                && engine.isEntityInPlay(mothership);
    }

    public static boolean destroyAllDronesIfMothershipInvalid(CombatEngineAPI engine,
                                                              LinkedList<ShipAPI> drones,
                                                              ShipAPI mothership) {
        if (drones == null || drones.isEmpty() || isMothershipAlive(engine, mothership)) {
            return false;
        }

        Iterator<ShipAPI> iterator = drones.iterator();
        while (iterator.hasNext()) {
            destroyDrone(engine, iterator.next());
            iterator.remove();
        }
        return true;
    }

    public static void destroyDrone(CombatEngineAPI engine, ShipAPI drone) {
        if (engine == null || drone == null || !engine.isEntityInPlay(drone)) {
            return;
        }

        if (drone.isAlive() && !drone.isHulk()) {
            engine.spawnExplosion(
                    drone.getLocation(),
                    drone.getVelocity(),
                    DRONE_DEATH_COLOR,
                    drone.getCollisionRadius() * 1.5f,
                    0.3f
            );
        }

        engine.removeEntity(drone);
    }
}
