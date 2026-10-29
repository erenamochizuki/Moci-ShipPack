package data.hullmods;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
/** Moci content restriction remains local; the standalone API has no faction/tag restriction. */
public class Moci_GNShield extends BaseHullMod implements org.moci.filmshield.api.FilmShieldProvider {
    @Override public void applyEffectsAfterShipCreation(ShipAPI ship,String id) { Moci_GNShield_Script.getInstance(ship); }
    @Override public boolean isApplicableToShip(ShipAPI ship) {
        return ship!=null&&ship.getShield()!=null&&ship.getHullSpec().hasTag("moci_ms");
    }
}
