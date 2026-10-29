package xi.arsenal;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;

/** This new Xi skin only. Native thrust, braking, turning and original Mod callbacks remain authoritative. */
public final class XiAgilityHullmod extends BaseHullMod {
    public static final float SPEED=220f, ACCELERATION=260f, DECELERATION=360f,
            TURN_RATE=80f, TURN_ACCELERATION=240f;
    private static final String MODIFIER="xi_arsenal_high_mobility";

    @Override public void applyEffectsBeforeShipCreation(ShipAPI.HullSize size, MutableShipStatsAPI stats, String id) {
        baseline(stats.getMaxSpeed(),SPEED);
        baseline(stats.getAcceleration(),ACCELERATION);
        baseline(stats.getDeceleration(),DECELERATION);
        baseline(stats.getMaxTurnRate(),TURN_RATE);
        baseline(stats.getTurnAcceleration(),TURN_ACCELERATION);
        // Public per-ship framework refit bonuses; never modify the shared captain's skill limits.
        stats.getDynamic().getMod("host.refit.maxVents").modifyFlat(MODIFIER, 20f);
        stats.getDynamic().getMod("host.refit.maxCapacitors").modifyFlat(MODIFIER, 20f);
    }
    private static void baseline(MutableStat stat,float target) {
        // Add this skin's explicit change over its inherited base. Never clear another hullmod or skill modifier.
        stat.modifyFlat(MODIFIER,Math.max(0f,target-stat.getBaseValue()));
    }
    @Override public boolean isApplicableToShip(ShipAPI ship) { return false; }
    @Override public String getUnapplicableReason(ShipAPI ship) { return "仅为柯西精神力武装型的固定内置机动组件"; }
}
