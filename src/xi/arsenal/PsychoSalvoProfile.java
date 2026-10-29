package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Source-hull-owned timing and guidance; weapon aliases alone never select a ship's profile. */
public enum PsychoSalvoProfile {
    XI("xi", 0.58f, 0.60f, 6000f, 1200f, 0.18f, 0.08f),
    PENELOPE("penelope", 0.70f, 0.70f, 2100f, 140f, 0.38f, 0.12f),
    LEGACY("legacy", 1.88f, 1.93f, 6000f, 1200f, 0.18f, 0.08f);

    public final String id;
    public final float minimumWindow, maximumWindow, steerAcceleration, turnRate;
    public final float targetCheckMinimum, targetCheckSpread;

    /** This hull releases its magazine in three synchronized waves. */
    public boolean waveSalvo() { return this == PENELOPE; }

    PsychoSalvoProfile(String id, float minimumWindow, float maximumWindow,
                       float steerAcceleration, float turnRate,
                       float targetCheckMinimum, float targetCheckSpread) {
        this.id = id; this.minimumWindow = minimumWindow; this.maximumWindow = maximumWindow;
        this.steerAcceleration = steerAcceleration; this.turnRate = turnRate;
        this.targetCheckMinimum = targetCheckMinimum; this.targetCheckSpread = targetCheckSpread;
    }

    public static PsychoSalvoProfile forShip(ShipAPI ship) {
        return forHull(ship == null ? null : ship.getHullSpec());
    }

    public static PsychoSalvoProfile forHull(ShipHullSpecAPI spec) {
        if (spec == null) return LEGACY;
        Set<ShipHullSpecAPI> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ShipHullSpecAPI> queue = new ArrayDeque<>(); queue.add(spec);
        // Exact owned IDs, bounded traversal and identity cycle detection; no prefix/name tests.
        for (int count = 0; count < 8 && !queue.isEmpty(); count++) {
            ShipHullSpecAPI current = queue.removeFirst();
            if (!seen.add(current)) continue;
            PsychoSalvoProfile match = ownedId(current.getHullId());
            if (match == null) match = ownedId(current.getBaseHullId());
            if (match == null) match = ownedId(current.getDParentHullId());
            if (match != null) return match;
            ShipHullSpecAPI base = current.getBaseHull(), parent = current.getDParentHull();
            if (base != null && !seen.contains(base)) queue.addLast(base);
            if (parent != null && !seen.contains(parent)) queue.addLast(parent);
        }
        return LEGACY;
    }

    private static PsychoSalvoProfile ownedId(String id) {
        if ("Moci_RX_105_framework_arsenal".equals(id)) return XI;
        if ("Moci_RX_104FF_framework_arsenal".equals(id)) return PENELOPE;
        return null;
    }

    static boolean ownsWeapon(String id) {
        return "Moci_fw_xi_psycho_missile".equals(id) || "Moci_fw_psycho_missile_5".equals(id)
                || "Moci_fw_psycho_missile_6".equals(id)
                || "Moci_fw_penelope_psycho_missile_5".equals(id)
                || "Moci_fw_penelope_psycho_missile_6".equals(id)
                || "Moci_fw_penelope_psycho_missile_8".equals(id);
    }
}
