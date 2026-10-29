package xi.arsenal;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Shared stats for complete RX-0-1/2/3 main bodies. The caller
 * reads the REAL Moci_NTDSubsystem and supplies its effect level once per frame.
 * This class never creates/advances a subsystem, changes animation/aim angles,
 * writes velocity, or changes the original NTD's time, charge or flux rules.
 */
public final class UnicornNtdStats implements AutoCloseable {
    public static final String MODIFIER = "xi.arsenal.unicorn.ntd.stats";
    public static final String ORIGINAL_NTD_MODIFIER = "moci_ntd_subsystem";
    public static final String HULL_ID = "Moci_RX_0_1";
    public static final Set<String> HULL_IDS = Set.of(HULL_ID,"Moci_RX_0_2","Moci_RX_0_3");
    public static final String MAGNUM_ID = "RX_0_1_arm_right_weapon_magnum";
    public static final float ACCELERATION_MULT = 30f, DECELERATION_MULT = 30f,
            TURN_RATE_MULT = 10f, TURN_ACCELERATION_MULT = 80f;
    public static final float MAGNUM_RANGE_MULT = 1.5f, MAGNUM_TURN_MULT = 5f;
    private static final float EPSILON = 0.0001f;
    private final ShipAPI ship;
    private final MutableShipStatsAPI stats;
    private final List<MagnumChange> magnums=new ArrayList<>();
    private boolean closed;

    /** Exact body/base/D-parent identities, never a prefix match that includes shields. */
    public static boolean supports(ShipAPI ship) {
        if (ship == null || ship.isStationModule() || ship.getParentStation() != null
                || ship.isFighter() || ship.isDrone() || ship.getHullSpec() == null) return false;
        Set<ShipHullSpecAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ShipHullSpecAPI> pending = new ArrayDeque<>(); pending.add(ship.getHullSpec());
        for (int count = 0; count < 8 && !pending.isEmpty(); count++) {
            ShipHullSpecAPI spec = pending.removeFirst();
            if (!seen.add(spec)) continue;
            if (member(spec.getHullId()) || member(spec.getBaseHullId())
                    || member(spec.getDParentHullId())) return true;
            ShipHullSpecAPI base = spec.getBaseHull(), parent = spec.getDParentHull();
            if (base != null && !seen.contains(base)) pending.addLast(base);
            if (parent != null && !seen.contains(parent)) pending.addLast(parent);
        }
        return false;
    }
    private static boolean member(String id){return id!=null&&HULL_IDS.contains(id);}

    public UnicornNtdStats(ShipAPI ship) {
        if (!supports(ship)) throw new IllegalArgumentException("Unicorn NTD enhancement requires a complete RX-0-1/2/3 main body");
        this.ship = ship;
        this.stats = ship.getMutableStats();
        if (stats == null) throw new IllegalArgumentException("Unicorn NTD enhancement requires native mutable ship stats");
    }

    /** Idempotent absolute modifiers; amount and time progression belong to the original subsystem. */
    public void apply(float effectLevel) {
        if (closed) return;
        float level = Float.isFinite(effectLevel) ? Math.max(0f, Math.min(1f, effectLevel)) : 0f;
        if (level <= 0f || !ship.isAlive() || ship.isHulk() || ship.wasRemoved()) { reset(); return; }
        stats.getAcceleration().modifyMult(MODIFIER, blend(ACCELERATION_MULT, level));
        stats.getDeceleration().modifyMult(MODIFIER, blend(DECELERATION_MULT, level));
        stats.getMaxTurnRate().modifyMult(MODIFIER, blend(TURN_RATE_MULT, level));
        stats.getTurnAcceleration().modifyMult(MODIFIER, blend(TURN_ACCELERATION_MULT, level));

        // Cancel exactly the current original positive NTD speed flat. Never
        // remove the original key, zero-flux boost, skill or another Mod's bonus.
        MutableStat.StatMod original = stats.getMaxSpeed().getFlatStatMod(ORIGINAL_NTD_MODIFIER);
        float speedBonus = original == null ? 0f : original.getValue();
        if (Float.isFinite(speedBonus) && speedBonus > 0f)
            stats.getMaxSpeed().modifyFlat(MODIFIER, -speedBonus);
        else stats.getMaxSpeed().unmodify(MODIFIER);
        improveAutofireAccuracy(level);

        List<WeaponAPI> current=findMagnums();
        magnums.removeIf(change->{if(!current.contains(change.weapon)||change.spec!=change.weapon.getSpec()){change.restore();return true;}return false;});
        for(WeaponAPI weapon:current){MagnumChange change=magnums.stream().filter(c->c.weapon==weapon).findFirst().orElse(null);if(change==null){change=new MagnumChange(weapon);magnums.add(change);}change.apply(level);}
    }

    private void improveAutofireAccuracy(float level) {
        MutableStat accuracy = stats.getAutofireAimAccuracy();
        accuracy.unmodify(MODIFIER);
        float current = accuracy.getModifiedValue();
        // Native MutableStat is (base + base*percent/100 + flat)*mult:
        // percent scales the base, not the flat we are solving for.
        float scale = accuracy.getMult();
        // Native Ship.getAimAccuracy uses 2-clamp(stat,0,1). A raw value over1
        // provides no extra precision. Solve only our flat while preserving all
        // foreign percent/mult modifiers. An external zero/negative multiplier
        // cannot be overridden without deleting its owner, so respect it.
        if (!Float.isFinite(current) || !Float.isFinite(scale) || scale <= EPSILON) return;
        float target = Math.max(0f, Math.min(1f, current + (1f - current) * level));
        accuracy.modifyFlat(MODIFIER, (target - current) / scale);
    }

    private List<WeaponAPI> findMagnums() {
        List<WeaponAPI> found=new ArrayList<>();
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if(weapon.getSlot()==null)continue;String slot=weapon.getSlot().getId();
            if("ARM_R_SOURCE".equals(slot)&&(MAGNUM_ID.equals(weapon.getId())||UnicornMagnumContent.PULSE.equals(weapon.getId()))
                    ||UnicornMagnumContent.CHARGE_SLOT.equals(slot)&&UnicornMagnumContent.CHARGE.equals(weapon.getId()))found.add(weapon);
        }
        return found;
    }

    private void reset() {
        stats.getAcceleration().unmodify(MODIFIER);
        stats.getDeceleration().unmodify(MODIFIER);
        stats.getMaxTurnRate().unmodify(MODIFIER);
        stats.getTurnAcceleration().unmodify(MODIFIER);
        stats.getMaxSpeed().unmodify(MODIFIER);
        stats.getAutofireAimAccuracy().unmodify(MODIFIER);
        for(MagnumChange change:magnums)change.restore();magnums.clear();
    }

    /** Restore only fields/modifiers owned here; original NTD and animation keep their normal owner. */
    @Override public void close() {
        if (closed) return;
        reset(); closed = true;
    }

    private static float blend(float target, float level) { return 1f + (target - 1f) * level; }

    private static final class MagnumChange {
        final WeaponAPI weapon;
        final WeaponSpecAPI spec;
        final float originalRange, originalTurn;
        float writtenRange, writtenTurn;
        MagnumChange(WeaponAPI weapon) {
            this.weapon = weapon;
            // Native ensureClonedSpec clones this actual pulse/beam weapon's spec;
            // native aim/range subsequently read its turnRate/maxRange each step.
            weapon.ensureClonedSpec();
            spec=weapon.getSpec();originalRange=spec.getMaxRange();originalTurn=spec.getTurnRate();
            if (!Float.isFinite(originalRange) || !Float.isFinite(originalTurn))
                throw new IllegalStateException("Magnum native range/turn must be finite");
            writtenRange = originalRange; writtenTurn = originalTurn;
        }
        void apply(float level) {
            writtenRange = originalRange * blend(MAGNUM_RANGE_MULT, level);
            writtenTurn = originalTurn * blend(MAGNUM_TURN_MULT, level);
            spec.setMaxRange(writtenRange); spec.setTurnRate(writtenTurn);
        }
        void restore() {
            // Do not erase a later writer's values if another extension took
            // ownership of the same instance field after our final write.
            if (Math.abs(spec.getMaxRange() - writtenRange) <= EPSILON) spec.setMaxRange(originalRange);
            if (Math.abs(spec.getTurnRate() - writtenTurn) <= EPSILON) spec.setTurnRate(originalTurn);
        }
    }
}
