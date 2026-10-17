package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * MSN-02 backpack LineBit cover.
 *
 * <p>Combines the ModuleCover2 slot convention with backpack LineBit facing
 * sync. BACKPACK_WPL_COVER follows BACKPACK_L, BACKPACK_WPR_COVER follows
 * BACKPACK_R, so backpack LineBits keep their own 360-degree aiming.</p>
 */
public class Moci_MSN02BackpackLineBitCoverEffect implements EveryFrameWeaponEffectPlugin {
    private boolean init = false;
    private ShipAPI module = null;
    private WeaponAPI lineBitWeapon = null;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null || weapon.getShip() == null) {
            return;
        }

        if (!init) {
            init = true;
            initialize(weapon);
        }

        updateCoverFrame(weapon);
        syncFacing(weapon);
    }

    private void initialize(WeaponAPI weapon) {
        String slotId = weapon.getSlot() != null ? weapon.getSlot().getId() : null;
        if (slotId == null) {
            return;
        }

        String moduleSlotId = slotId;
        if (slotId.endsWith("_COVER")) {
            moduleSlotId = slotId.substring(0, slotId.length() - "_COVER".length());
        }

        String lineBitSlotId = null;
        if ("BACKPACK_WPL".equals(moduleSlotId)) {
            lineBitSlotId = "BACKPACK_L";
        } else if ("BACKPACK_WPR".equals(moduleSlotId)) {
            lineBitSlotId = "BACKPACK_R";
        }

        ShipAPI ship = weapon.getShip();
        for (ShipAPI child : ship.getChildModulesCopy()) {
            if (child != null && child.getStationSlot() != null
                    && moduleSlotId.equals(child.getStationSlot().getId())) {
                module = child;
                break;
            }
        }

        for (WeaponAPI other : ship.getAllWeapons()) {
            if (other == null || other.getSlot() == null) {
                continue;
            }
            String otherSlotId = other.getSlot().getId();
            if (lineBitSlotId != null && lineBitSlotId.equals(otherSlotId)) {
                lineBitWeapon = other;
            }
        }

        weapon.setSuspendAutomaticTurning(true);
        if (lineBitWeapon != null) {
            lineBitWeapon.setSuspendAutomaticTurning(true);
        }
    }

    private void updateCoverFrame(WeaponAPI weapon) {
        if (weapon.getAnimation() == null) {
            return;
        }

        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        if (module != null && module.isAlive() && module.getStationSlot() != null) {
            weapon.getAnimation().setFrame(0);
        } else {
            weapon.getAnimation().setFrame(Math.min(1, maxFrame));
        }
    }

    private void syncFacing(WeaponAPI weapon) {
        if (lineBitWeapon == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        float guideAngle = normalize360(lineBitWeapon.getCurrAngle());
        float relativeAngle = normalizeAngle(guideAngle - ship.getFacing());

        weapon.setCurrAngle(guideAngle);

        if (module != null && module.isAlive() && module.getStationSlot() != null) {
            module.ensureClonedStationSlotSpec();
            module.getStationSlot().setAngle(relativeAngle);
        }
    }

    private static float normalizeAngle(float angle) {
        while (angle > 180f) angle -= 360f;
        while (angle < -180f) angle += 360f;
        return angle;
    }

    private static float normalize360(float angle) {
        while (angle >= 360f) angle -= 360f;
        while (angle < 0f) angle += 360f;
        return angle;
    }
}
