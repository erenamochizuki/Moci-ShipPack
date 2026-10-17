package data.ai;

import com.fs.starfarer.api.combat.ShipAPI;
import org.magiclib.subsystems.drones.PIDController;

public abstract class Moci_BaseAIModule implements Moci_AIModule{

    public ShipAPI ship;
    public ShipAPI mothership;
    public PIDController controller;

    public Moci_BaseAIModule(ShipAPI ship,ShipAPI mothership,PIDController controller){
        this.ship = ship;
        this.mothership = mothership;
        this.controller = controller;
    }

    public abstract void advance(float amount);

    public abstract void forceEvaluateCircumstance();
}
