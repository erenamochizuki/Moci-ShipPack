package data.hullmods;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import org.moci.filmshield.api.FilmShield;
/** Moci content restriction remains local; the standalone API has no faction/tag restriction. */
public class Moci_GNShield extends BaseHullMod {
    @Override public void applyEffectsAfterShipCreation(ShipAPI ship,String id) { Moci_GNShield_Script.getInstance(ship); }
    @Override public void advanceInCombat(ShipAPI ship,float amount) {
        FilmShield.ensureCombatServices();
        if(ship.isShipWithModules()) for(ShipAPI module:ship.getChildModulesCopy())
            if(module.isAlive()&&!module.isHulk()&&FilmShield.find(module)==null&&FilmShield.visual(module)==null)
                Moci_GNShield_Script.getInstance(module);
    }
    @Override public boolean isApplicableToShip(ShipAPI ship) {
        return ship!=null&&ship.getShield()!=null&&ship.getHullSpec().hasTag("moci_ms");
    }
}
