package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;

/** Explicit target-size attenuation; does not scale the user's own twenty-second drawback. */
public final class UnicornNtdScale {
    public record Profile(float overloadSeconds, float controlSeconds, float empDamage,
                          float expirationDamage, float expirationEmp, int weaponGroups) { }
    public static final Profile SMALL = new Profile(12f, 20f, 6000f, 2400f, 6000f, 2);
    public static final Profile DESTROYER = new Profile(9f, 15f, 4500f, 1800f, 4500f, 2);
    public static final Profile CRUISER = new Profile(6f, 10f, 3000f, 1200f, 3000f, 1);
    public static final Profile CAPITAL = new Profile(3f, 4f, 1500f, 600f, 1500f, 1);
    private UnicornNtdScale() { }
    public static Profile forShip(ShipAPI ship) {
        if (ship == null || ship.getHullSize() == null) return CAPITAL;
        return switch (ship.getHullSize()) {
            case FIGHTER, FRIGATE -> SMALL;
            case DESTROYER -> DESTROYER;
            case CRUISER -> CRUISER;
            default -> CAPITAL;
        };
    }
}
