package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Pure whole-battle identity count: either side and repeated RX-0 models qualify. */
public final class UnicornNtdResonance {
    private UnicornNtdResonance(){ }
    public static int count(CombatEngineAPI engine){
        if(engine==null)return 0;
        Set<ShipAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());int count=0;
        for(ShipAPI ship:engine.getShips())if(ship!=null&&seen.add(ship)&&ship.isAlive()&&!ship.isHulk()
                &&!ship.wasRemoved()&&engine.isEntityInPlay(ship)&&UnicornNtdStats.supports(ship))count++;
        return count;
    }
}
