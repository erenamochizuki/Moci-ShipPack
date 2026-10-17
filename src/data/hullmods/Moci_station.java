package data.hullmods;  
  
import com.fs.starfarer.api.combat.CombatEntityAPI;  
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import org.lwjgl.util.vector.Vector2f;  
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipCommand;
  
public class Moci_station extends BaseHullMod {  

    public void setLocation(CombatEntityAPI entity, Vector2f location) {  
        Vector2f dif = new Vector2f(location);  
        Vector2f.sub(location, entity.getLocation(), dif);  
        Vector2f.add(entity.getLocation(), dif, entity.getLocation());
        Vector2f.sub(entity.getVelocity(), entity.getVelocity(), entity.getVelocity());
    }  
  
    @Override  
    public void advanceInCombat(ShipAPI ship, float amount) {  
        if(ship.isAlive() && !ship.getEngineController().isFlamedOut()){
            setLocation(ship, new Vector2f());  
            ship.getEngineController().forceFlameout(true);
            for(ShipEngineAPI e : ship.getEngineController().getShipEngines()){
                e.disable(true);
            }
        }
        ship.giveCommand(ShipCommand.DECELERATE, ship, 0);
    }  
}
