package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;

import data.scripts.util.Moci_FluxRiftLightningUtil;

public class Moci_FluxRiftVent extends BaseHullMod {

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (ship == null || ship.isFighter() || ship.isStationModule()) {
            return;
        }
        if (!ship.hasListenerOfClass(FluxRiftVentListener.class)) {
            ship.addListener(new FluxRiftVentListener(ship));
        }
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) {
            return "" + (int) Moci_FluxRiftLightningUtil.FLUX_PER_ARC;
        }
        if (index == 1) {
            return "" + (int) Moci_FluxRiftLightningUtil.TARGET_RANGE;
        }
        if (index == 2) {
            return "" + (int) Moci_FluxRiftLightningUtil.DAMAGE;
        }
        if (index == 3) {
            return "" + (int) Moci_FluxRiftLightningUtil.EMP_DAMAGE;
        }
        return null;
    }

    public static class FluxRiftVentListener implements AdvanceableListener {
        private final ShipAPI ship;
        private final Moci_FluxRiftLightningUtil.StateData arcData = new Moci_FluxRiftLightningUtil.StateData();
        private boolean wasVenting = false;
        private float previousFlux = 0f;
        private float ventedFluxAccumulator = 0f;

        public FluxRiftVentListener(ShipAPI ship) {
            this.ship = ship;
            if (ship != null) {
                previousFlux = ship.getFluxTracker().getCurrFlux();
            }
        }

        @Override
        public void advance(float amount) {
            if (ship == null || !ship.isAlive() || ship.isHulk()) {
                return;
            }

            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused()) {
                return;
            }

            float currentFlux = ship.getFluxTracker().getCurrFlux();
            boolean isVenting = ship.getFluxTracker().isVenting();

            if (!isVenting) {
                wasVenting = false;
                ventedFluxAccumulator = 0f;
                previousFlux = currentFlux;
                return;
            }

            float fluxDropped = Math.max(0f, previousFlux - currentFlux);
            previousFlux = currentFlux;

            if (!wasVenting) {
                wasVenting = true;
            }

            ventedFluxAccumulator += fluxDropped;
            while (ventedFluxAccumulator >= Moci_FluxRiftLightningUtil.FLUX_PER_ARC) {
                ventedFluxAccumulator -= Moci_FluxRiftLightningUtil.FLUX_PER_ARC;
                Moci_FluxRiftLightningUtil.fireOnce(engine, ship, arcData);
            }
        }
    }
}
