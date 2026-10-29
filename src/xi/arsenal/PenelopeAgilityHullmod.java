package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import java.util.*;

/** Owned Penelope skin: heavy firepower and beam protection, less agility than Xi. */
public final class PenelopeAgilityHullmod extends BaseHullMod {
    public static final float SPEED = 220f, ACCELERATION = 200f, DECELERATION = 110f,
            TURN_RATE = 40f, TURN_ACCELERATION = 100f;
    public static final String MODIFIER = "penelope_arsenal_high_mobility";
    @Override public void applyEffectsBeforeShipCreation(ShipAPI.HullSize size, MutableShipStatsAPI stats, String id) {
        baseline(stats.getMaxSpeed(), SPEED); baseline(stats.getAcceleration(), ACCELERATION);
        baseline(stats.getDeceleration(), DECELERATION); baseline(stats.getMaxTurnRate(), TURN_RATE);
        baseline(stats.getTurnAcceleration(), TURN_ACCELERATION);
        // Native sustained beams have separate hull/armor and shield damage stats.
        // These do not include discrete BALLISTIC_AS_BEAM rifle bolts.
        stats.getBeamDamageTakenMult().modifyMult(PenelopeBeamResistance.MODIFIER, PenelopeBeamResistance.DAMAGE_MULTIPLIER);
        stats.getBeamShieldDamageTakenMult().modifyMult(PenelopeBeamResistance.MODIFIER, PenelopeBeamResistance.DAMAGE_MULTIPLIER);
    }
    @Override public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        PenelopeBeamResistance.attach(ship);
    }
    @Override public void applyEffectsAfterShipAddedToCombatEngine(ShipAPI ship, String id) {
        PenelopeBeamResistance.ensureGlobalRouter(Global.getCombatEngine());
    }
    private static void baseline(MutableStat stat, float target) {
        // The owned target may be below the inherited hull base (notably turn rate).
        // Preserve every other hullmod/skill modifier rather than clamping away a nerf.
        stat.modifyFlat(MODIFIER, target - stat.getBaseValue());
    }
    @Override public boolean isApplicableToShip(ShipAPI ship) { return false; }
    @Override public String getUnapplicableReason(ShipAPI ship) { return "佩涅罗佩精神力武装型的固定机动与光束防护组件"; }
    @Override public String getDescriptionParam(int index, ShipAPI.HullSize size) {
        return index == 0 ? "35%" : null;
    }
    public static boolean isPenelope(ShipHullSpecAPI spec) {
        if (spec == null) return false;
        Set<ShipHullSpecAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ShipHullSpecAPI> queue = new ArrayDeque<>(); queue.add(spec);
        for (int count = 0; count < 8 && !queue.isEmpty(); count++) {
            ShipHullSpecAPI current = queue.removeFirst(); if (!seen.add(current)) continue;
            if (id(current.getHullId()) || id(current.getBaseHullId()) || id(current.getDParentHullId())) return true;
            ShipHullSpecAPI base = current.getBaseHull(), parent = current.getDParentHull();
            if (base != null && !seen.contains(base)) queue.addLast(base);
            if (parent != null && !seen.contains(parent)) queue.addLast(parent);
        }
        return false;
    }
    private static boolean id(String id) { return "Moci_RX_104FF".equals(id) || "Moci_RX_104FF_framework_arsenal".equals(id); }
}
