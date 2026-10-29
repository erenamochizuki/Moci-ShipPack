package xi.arsenal;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import data.subsystems.Moci_MobilitySubsystem;
import java.util.List;
import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

/** Exact original hullmod registration replacement; never install or advance a second backpack. */
public final class SmoothMobilityHullmod extends BaseHullMod {
    @Override public void applyEffectsAfterShipCreation(ShipAPI ship,String id){
        List<MagicSubsystem> existing=MagicSubsystemsManager.getSubsystemsForShipCopy(ship);
        if(existing!=null&&existing.stream().anyMatch(s->s instanceof Moci_MobilitySubsystem))return;
        MagicSubsystemsManager.addSubsystemToShip(ship,new SmoothMobilitySubsystem(ship));
    }
}
