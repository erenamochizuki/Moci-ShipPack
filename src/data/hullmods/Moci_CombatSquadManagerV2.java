package data.hullmods;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;

import java.util.ArrayList;
import java.util.List;

public class Moci_CombatSquadManagerV2 implements AdvanceableListener {

    private ShipAPI ship;
    private List<ShipAPI> squad = new ArrayList<>();

    @Override
    public void advance(float amount) {

    }
}
