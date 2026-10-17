package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Keeps NZ-666 wing covers visible only while the matching wing module exists.
 * Movement frames are driven by Moci_NZ666FullBodyAnimationController.
 */
public class Moci_NZ666WingCoverEffect implements EveryFrameWeaponEffectPlugin {
    private boolean initialized = false;
    private ShipAPI module;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null) {
            return;
        }

        if (!initialized) {
            initialized = true;
            bind(weapon);
        }

        if (weapon.getAnimation() == null) {
            return;
        }

        weapon.getAnimation().pause();
        if (module == null || !module.isAlive() || module.isHulk() || module.getHitpoints() <= 0f) {
            int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
            weapon.getAnimation().setFrame(maxFrame);
        }
    }

    private void bind(WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        if (ship == null || weapon.getSlot() == null) {
            return;
        }

        String slotId = weapon.getSlot().getId();
        String moduleSlotId = slotId.endsWith("_COVER")
                ? slotId.substring(0, slotId.length() - "_COVER".length())
                : slotId;

        for (ShipAPI child : ship.getChildModulesCopy()) {
            if (child != null && child.getStationSlot() != null
                    && moduleSlotId.equals(child.getStationSlot().getId())) {
                module = child;
                break;
            }
        }
    }
}
