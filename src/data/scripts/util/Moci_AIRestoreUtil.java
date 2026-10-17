package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 统一处理 fighter化机体的 AI 恢复。
 */
public final class Moci_AIRestoreUtil {

    public static final String ORIGINAL_HULL_SIZE_KEY = "Moci_OriginalHullSize";
    public static final String COMBAT_HULL_SIZE_KEY = "Moci_CombatHullSize";

    private Moci_AIRestoreUtil() {
    }

    public static ShipAPI.HullSize getBaselineHullSize(ShipAPI ship) {
        if (ship == null) {
            return null;
        }

        Object stored = ship.getCustomData().get(ORIGINAL_HULL_SIZE_KEY);
        if (stored instanceof ShipAPI.HullSize) {
            return (ShipAPI.HullSize) stored;
        }

        if (ship.getVariant() != null && ship.getVariant().getHullSize() != null) {
            return ship.getVariant().getHullSize();
        }

        if (ship.getHullSpec() != null) {
            return ship.getHullSpec().getHullSize();
        }

        return ship.getHullSize();
    }

    public static ShipAPI.HullSize getDesiredCombatHullSize(ShipAPI ship) {
        if (ship == null) {
            return null;
        }

        Object stored = ship.getCustomData().get(COMBAT_HULL_SIZE_KEY);
        if (stored instanceof ShipAPI.HullSize) {
            return (ShipAPI.HullSize) stored;
        }

        return ship.getHullSize();
    }

    public static boolean restoreDefaultAI(ShipAPI ship, ShipAIConfig fallbackConfig) {
        return restoreDefaultAI(ship, fallbackConfig, getDesiredCombatHullSize(ship));
    }

    public static boolean restoreDefaultAI(ShipAPI ship, ShipAIConfig fallbackConfig, ShipAPI.HullSize postRestoreHullSize) {
        if (ship == null) {
            return false;
        }

        ShipAPI.HullSize originalHullSize = ship.getHullSize();
        ShipAPI.HullSize baselineHullSize = getBaselineHullSize(ship);

        if (baselineHullSize != null && originalHullSize != baselineHullSize) {
            ship.setHullSize(baselineHullSize);
        }

        ship.resetDefaultAI();

        if (ship.getShipAI() == null && fallbackConfig != null) {
            ship.setShipAI(Global.getSettings().createDefaultShipAI(ship, fallbackConfig));
        }

        ShipAIPlugin restoredAI = ship.getShipAI();
        if (restoredAI != null) {
            restoredAI.forceCircumstanceEvaluation();
        }

        ShipAPI.HullSize targetHullSize = postRestoreHullSize != null ? postRestoreHullSize : originalHullSize;
        if (targetHullSize != null && ship.getHullSize() != targetHullSize) {
            ship.setHullSize(targetHullSize);
        }

        return restoredAI != null;
    }

    public static boolean restoreSavedOrDefaultAI(ShipAPI ship, Object savedAI, ShipAIConfig fallbackConfig) {
        if (ship == null) {
            return false;
        }

        if (savedAI instanceof ShipAIPlugin) {
            ship.setShipAI((ShipAIPlugin) savedAI);
            ShipAIPlugin restoredAI = ship.getShipAI();
            if (restoredAI != null) {
                restoredAI.forceCircumstanceEvaluation();
            }

            ShipAPI.HullSize desiredCombatHullSize = getDesiredCombatHullSize(ship);
            if (desiredCombatHullSize != null && ship.getHullSize() != desiredCombatHullSize) {
                ship.setHullSize(desiredCombatHullSize);
            }
            return restoredAI != null;
        }

        return restoreDefaultAI(ship, fallbackConfig);
    }
}
