package data.shipsystems.scripts.ai;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;

import data.scripts.util.Moci_FluxRiftLightningUtil;
import data.scripts.util.Moci_Util;

public class Moci_FluxRiftLightningAI implements ShipSystemAIScript {

    private static final float MIN_STARTING_FLUX = Moci_FluxRiftLightningUtil.FLUX_PER_ARC * 2f;

    private ShipAPI ship;
    private ShipSystemAPI system;
    private CombatEngineAPI engine;

    private final IntervalUtil decisionInterval = new IntervalUtil(0.1f, 0.2f);

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.engine = engine;
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (engine == null || engine.isPaused() || ship == null || system == null ||
                !ship.isAlive() || ship.isHulk()) {
            return;
        }

        decisionInterval.advance(amount);
        if (!decisionInterval.intervalElapsed()) {
            return;
        }

        boolean active = system.isOn() || system.isActive() || system.isChargeup();
        boolean hasTarget = hasValidTarget();
        float currentFlux = ship.getFluxTracker().getCurrFlux();

        if (active) {
            if (!hasTarget || currentFlux < Moci_FluxRiftLightningUtil.FLUX_PER_ARC) {
                system.deactivate();
            }
            return;
        }

        if (system.isChargedown() || system.isCoolingDown() || system.isOutOfAmmo() ||
                !system.canBeActivated()) {
            return;
        }

        if (hasTarget && currentFlux >= MIN_STARTING_FLUX) {
            ship.useSystem();
        }
    }

    private boolean hasValidTarget() {
        float range = Moci_FluxRiftLightningUtil.TARGET_RANGE;

        for (ShipAPI other : Moci_Util.getEnemyShipsInRange(ship.getLocation(), range, ship.getOwner())) {
            if (other != null && other != ship && other.isAlive() && !other.isHulk()) {
                return true;
            }
        }

        for (MissileAPI missile : Moci_Util.getEnemyMissilesInRange(ship.getLocation(), range, ship.getOwner())) {
            if (missile != null && !missile.isExpired() && !missile.isFading()) {
                return true;
            }
        }

        return false;
    }
}
