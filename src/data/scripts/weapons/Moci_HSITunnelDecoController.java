package data.scripts.weapons;

import com.fs.starfarer.api.combat.*;

public class Moci_HSITunnelDecoController implements EveryFrameWeaponEffectPlugin {
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused()) return;
    }
}