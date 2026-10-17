package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * AMX-004 arm controller.
 *
 * SOURCE is the visible/default weapon and may be a saber. FOLLOW is the hidden
 * alternate weapon. The arm only follows SOURCE during saber swings and never
 * writes an angle back into SOURCE while the blade swing controller owns it.
 */
public class Moci_AMX004ArmDecoWeaponEffect implements EveryFrameWeaponEffectPlugin {
    private static final int SOURCE_FRAME = 1;
    private static final int FOLLOW_FRAME = 0;

    private boolean init = false;
    private ShipAPI ship;
    private WeaponAPI source;
    private WeaponAPI follow;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        if (!init) {
            init = true;
            ship = weapon.getShip();
            initializeWeapons(weapon);
            initializeHiddenFollow(weapon);
        }

        hideFollowWeapon();

        boolean sourceSelected = isSelected(source);
        boolean followSelected = isSelected(follow);
        applyExclusiveFiringControl(sourceSelected, followSelected);

        if (Moci_MechDecoWeaponMutexControl.isBladeSwingActive(weapon)) {
            if (source != null) {
                weapon.setCurrAngle(source.getCurrAngle());
                syncFollowToSource(weapon);
            }
            disableWeapon(follow);
            setModeFrame(weapon, SOURCE_FRAME);
            return;
        }

        if (shouldFollowDrive(sourceSelected, followSelected)) {
            syncFollowFirePointToArm(weapon);
            weapon.setCurrAngle(follow.getCurrAngle());
            if (source != null) {
                source.setCurrAngle(follow.getCurrAngle());
            }
        } else if (source != null) {
            weapon.setCurrAngle(source.getCurrAngle());
            syncFollowToSource(weapon);
        }

        setModeFrame(weapon, getModeFrame(sourceSelected, followSelected));
    }

    private void initializeWeapons(WeaponAPI weapon) {
        if (ship == null) {
            return;
        }

        String slotId = weapon.getSlot().getId();
        String sourceId = slotId + "_SOURCE";
        String followId = slotId + "_FOLLOW";

        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w == null || w.getSlot() == null) {
                continue;
            }
            String otherSlotId = w.getSlot().getId();
            if (sourceId.equals(otherSlotId)) {
                source = w;
            } else if (followId.equals(otherSlotId)) {
                follow = w;
            }
        }
    }

    private void initializeHiddenFollow(WeaponAPI arm) {
        if (source == null || follow == null || source.getSlot() == null || follow.getSlot() == null) {
            return;
        }

        syncFollowToSource(arm);
        hideFollowWeapon();
    }

    private boolean shouldFollowDrive(boolean sourceSelected, boolean followSelected) {
        if (follow == null) {
            return false;
        }
        if (isWeaponActive(source)) {
            return false;
        }
        if (sourceSelected && followSelected) {
            return false;
        }
        return followSelected || (isWeaponActive(follow) && !isWeaponActive(source));
    }

    private int getModeFrame(boolean sourceSelected, boolean followSelected) {
        boolean sourceFiring = isWeaponFiringSignal(source);
        boolean followFiring = isWeaponFiringSignal(follow);

        if (sourceSelected && followSelected) {
            if (sourceFiring || isWeaponActive(source)) {
                return SOURCE_FRAME;
            }
            if (followFiring || isWeaponActive(follow)) {
                return FOLLOW_FRAME;
            }
            return SOURCE_FRAME;
        }
        if (sourceSelected) {
            return SOURCE_FRAME;
        }
        if (followSelected) {
            return FOLLOW_FRAME;
        }
        if (sourceFiring || isWeaponActive(source)) {
            return SOURCE_FRAME;
        }
        if (followFiring || isWeaponActive(follow)) {
            return FOLLOW_FRAME;
        }
        return FOLLOW_FRAME;
    }

    private boolean isSelected(WeaponAPI weapon) {
        return weapon != null
                && weapon.getShip() != null
                && weapon.getShip().getSelectedGroupAPI() != null
                && weapon.getShip().getSelectedGroupAPI().getWeaponsCopy().contains(weapon);
    }

    private void applyExclusiveFiringControl(boolean sourceSelected, boolean followSelected) {
        if (source == null && follow == null) {
            return;
        }

        if (isWeaponActive(source)) {
            disableWeapon(follow);
            return;
        }

        if (sourceSelected && followSelected) {
            disableWeapon(follow);
            return;
        }

        if (sourceSelected) {
            disableWeapon(follow);
        } else if (followSelected) {
            disableWeapon(source);
        } else {
            disableWeapon(follow);
        }
    }

    private boolean isWeaponActive(WeaponAPI weapon) {
        return weapon != null && (weapon.isFiring() || weapon.getChargeLevel() > 0f || weapon.isInBurst());
    }

    private boolean isWeaponFiringSignal(WeaponAPI weapon) {
        if (weapon == null || weapon.isDisabled() || !weapon.isFiring()) {
            return false;
        }
        return weapon.getChargeLevel() > 0f || weapon.isInBurst() || weapon.getCooldownRemaining() <= 0f;
    }

    private void disableWeapon(WeaponAPI weapon) {
        if (weapon != null) {
            weapon.setForceNoFireOneFrame(true);
        }
    }

    private void syncFollowToSource(WeaponAPI arm) {
        if (source != null && follow != null) {
            follow.setCurrAngle(source.getCurrAngle());
            syncFollowSpriteCenterToSource();
            syncFollowFirePointToArm(arm);
        }
    }

    private void syncFollowSpriteCenterToSource() {
        if (source == null || follow == null || source.getSprite() == null || follow.getSprite() == null) {
            return;
        }
        follow.getSprite().setCenterX(source.getSprite().getCenterX());
        follow.getSprite().setCenterY(source.getSprite().getCenterY());
        if (source.getGlowSpriteAPI() != null && follow.getGlowSpriteAPI() != null) {
            follow.getGlowSpriteAPI().setCenterX(source.getGlowSpriteAPI().getCenterX());
            follow.getGlowSpriteAPI().setCenterY(source.getGlowSpriteAPI().getCenterY());
        }
    }

    private void syncFollowFirePointToArm(WeaponAPI arm) {
        if (arm == null || follow == null || arm.getSlot() == null || follow.getSlot() == null) {
            return;
        }

        Vector2f diff = new Vector2f();
        Vector2f.sub(arm.getSlot().getLocation(), follow.getSlot().getLocation(), diff);
        syncFirePoint(follow, diff, getPrimaryFirePoint(arm));
    }

    private Vector2f getPrimaryFirePoint(WeaponAPI weapon) {
        if (weapon == null || weapon.getSpec() == null) {
            return null;
        }
        if (!weapon.getSpec().getTurretFireOffsets().isEmpty()) {
            return weapon.getSpec().getTurretFireOffsets().get(0);
        }
        if (!weapon.getSpec().getHardpointFireOffsets().isEmpty()) {
            return weapon.getSpec().getHardpointFireOffsets().get(0);
        }
        if (!weapon.getSpec().getHiddenFireOffsets().isEmpty()) {
            return weapon.getSpec().getHiddenFireOffsets().get(0);
        }
        return null;
    }

    private void setModeFrame(WeaponAPI arm, int frame) {
        setSafeFrame(arm, frame);
        setSafeFrame(source, frame);
        setSafeFrame(follow, frame);
    }

    private void setSafeFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        weapon.getAnimation().setFrame(Math.max(0, Math.min(frame, maxFrame)));
        weapon.getAnimation().pause();
    }

    private void hideFollowWeapon() {
        if (follow == null) {
            return;
        }

        Color transparent = new Color(0f, 0f, 0f, 0f);
        if (follow.getSprite() != null) {
            follow.getSprite().setColor(transparent);
        }
        if (follow.getBarrelSpriteAPI() != null) {
            follow.getBarrelSpriteAPI().setColor(transparent);
        }
        if (follow.getUnderSpriteAPI() != null) {
            follow.getUnderSpriteAPI().setColor(transparent);
        }
        if (follow.getGlowSpriteAPI() != null) {
            follow.getGlowSpriteAPI().setColor(transparent);
        }
        if (follow.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : follow.getMissileRenderData()) {
                render.getSprite().setWidth(0f);
                render.getSprite().setHeight(0f);
            }
        }
    }

    public static void syncFirePoint(WeaponAPI sync, Vector2f diff, Vector2f override) {
        if (sync == null || diff == null) {
            return;
        }
        if (override == null) {
            override = new Vector2f();
        }

        Vector2f.add(override, diff, diff);
        sync.ensureClonedSpec();
        int t = sync.getSpec().getTurretFireOffsets().size();
        int h = sync.getSpec().getHardpointFireOffsets().size();
        int hd = sync.getSpec().getHiddenFireOffsets().size();

        sync.getSpec().getTurretFireOffsets().clear();
        sync.getSpec().getHardpointFireOffsets().clear();
        sync.getSpec().getHiddenFireOffsets().clear();

        for (int i = 0; i < t; i++) {
            sync.getSpec().getTurretFireOffsets().add(new Vector2f(diff));
        }
        for (int i = 0; i < h; i++) {
            sync.getSpec().getHardpointFireOffsets().add(new Vector2f(diff));
        }
        for (int i = 0; i < hd; i++) {
            sync.getSpec().getHiddenFireOffsets().add(new Vector2f(diff));
        }

        if (sync.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : sync.getMissileRenderData()) {
                render.getSprite().setWidth(0f);
                render.getSprite().setHeight(0f);
            }
        }
    }
}
