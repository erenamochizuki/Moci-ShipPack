package data.shipsystems.scripts.ai;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DroneLauncherShipSystemAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

public class Moci_DefenseDroneAI implements ShipSystemAIScript {
    private ShipAPI ship;
    private ShipSystemAPI system;
    private ShipwideAIFlags flags;
    private CombatEngineAPI engine;
    private boolean phaseCloak = false;
    private static final String SYSTEM_ID = "Moci_defense_drone";

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.flags = flags;
        this.engine = engine;
        if (system != null && SYSTEM_ID.equals(system.getId())) {
            this.system = system;
        } else if (ship.getPhaseCloak() != null && SYSTEM_ID.equals(ship.getPhaseCloak().getId())) {
            this.system = ship.getPhaseCloak();
            phaseCloak = true;
        }
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (engine == null || engine.isPaused() || system == null) return;

        if (system.getCooldownRemaining() > 0) {
            return;
        }

        if (system instanceof DroneLauncherShipSystemAPI) {
            DroneLauncherShipSystemAPI drone = (DroneLauncherShipSystemAPI) system;
            if (drone.getDroneOrders() == DroneLauncherShipSystemAPI.DroneOrders.RECALL) {
                toggle();
            }
        } else {
            if (!system.isActive() && system.getAmmo() > 0) {
                toggle();
            }
        }
    }

    private void toggle() {
        if (phaseCloak) {
                    ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0);
        } else {
            ship.useSystem();
        }
    }
}