package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;

public class Moci_WingmanLifecycleListener implements AdvanceableListener {

    private ShipAPI managedShip;

    public void setManagedShip(ShipAPI ship) {
        this.managedShip = ship;
    }

    @Override
    public void advance(float amount) {
        if (managedShip == null) {
            return;
        }

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }

        if (!managedShip.isAlive() || managedShip.isHulk() || !engine.isEntityInPlay(managedShip)) {
            managedShip.removeListener(this);
            managedShip = null;
            return;
        }

        ShipAPI mothership = Moci_WingmanSupport.getTrackedMothership(managedShip);
        if (!Moci_WingmanSupport.isMothershipAlive(engine, mothership)) {
            Moci_WingmanSupport.destroyDrone(engine, managedShip);
            managedShip.removeListener(this);
            managedShip = null;
        }
    }
}
