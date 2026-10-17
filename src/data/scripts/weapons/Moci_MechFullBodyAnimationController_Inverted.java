package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Full-body movement animation controller for hulls whose leg frames run
 * opposite to the older Moci_MechFullBodyAnimationController contract.
 */
public class Moci_MechFullBodyAnimationController_Inverted implements EveryFrameWeaponEffectPlugin {
    private static final String FRONT_SEQUENCE = "FRONT";
    private static final String BACK_SEQUENCE = "BACK";
    private static final int DEFAULT_FRAME_TARGET = 4;
    private static final int MOVEMENT_MAX_FRAME_TARGET = 7;

    private boolean initialized = false;
    private int defaultFrame = DEFAULT_FRAME_TARGET;
    private int movementMaxFrame = MOVEMENT_MAX_FRAME_TARGET;
    private String currentSequence = null;
    private int currentFrame = DEFAULT_FRAME_TARGET;
    private float timer = 0f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null || weapon.getAnimation() == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }

        weapon.getAnimation().pause();

        if (!ship.isAlive() || ship.isHulk()) {
            setSafeFrame(weapon, defaultFrame);
            return;
        }

        if (!initialized) {
            int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
            movementMaxFrame = Math.min(MOVEMENT_MAX_FRAME_TARGET, maxFrame);
            defaultFrame = Math.min(DEFAULT_FRAME_TARGET, movementMaxFrame);
            currentFrame = defaultFrame;
            initialized = true;
        }

        boolean isAccelerating = ship.getEngineController().isAccelerating();
        boolean isDecelerating = ship.getEngineController().isDecelerating();
        boolean isBackwards = ship.getEngineController().isAcceleratingBackwards();

        if (currentSequence == null) {
            if (isAccelerating) {
                currentSequence = FRONT_SEQUENCE;
            } else if (isDecelerating || isBackwards) {
                currentSequence = BACK_SEQUENCE;
            }
            timer = 0f;
        }

        if (currentSequence != null) {
            timer += amount * Math.max(0f, weapon.getAnimation().getFrameRate());
            while (timer >= 1f) {
                timer -= 1f;
                if (FRONT_SEQUENCE.equals(currentSequence)) {
                    if (isAccelerating) {
                        currentFrame = Math.min(currentFrame + 1, movementMaxFrame);
                    } else if (currentFrame > defaultFrame) {
                        currentFrame = Math.max(currentFrame - 1, defaultFrame);
                    }
                } else if (BACK_SEQUENCE.equals(currentSequence)) {
                    if (isDecelerating || isBackwards) {
                        currentFrame = Math.max(currentFrame - 1, 0);
                    } else if (currentFrame < defaultFrame) {
                        currentFrame = Math.min(currentFrame + 1, defaultFrame);
                    }
                }
            }

            if (FRONT_SEQUENCE.equals(currentSequence) && currentFrame == defaultFrame && !isAccelerating) {
                currentSequence = null;
                timer = 0f;
            } else if (BACK_SEQUENCE.equals(currentSequence) && currentFrame == defaultFrame
                    && !isDecelerating && !isBackwards) {
                currentSequence = null;
                timer = 0f;
            }
        }

        if (currentSequence == null) {
            currentFrame = defaultFrame;
        }

        setSafeFrame(weapon, currentFrame);
        syncOtherParts(ship, currentFrame);
    }

    private void syncOtherParts(ShipAPI ship, int legFrame) {
        int bodyFrame = legFrame / 2;
        updateWeaponAnimation(ship, "BODY", bodyFrame);
        updateWeaponAnimation(ship, "HEAD", bodyFrame);
        updateWeaponAnimation(ship, "WING_L", legFrame);
        updateWeaponAnimation(ship, "WING_R", legFrame);
        updateWeaponAnimation(ship, "WINGS", legFrame);
        updateWeaponAnimation(ship, "SHOULDER_L", legFrame);
        updateWeaponAnimation(ship, "SHOULDER_R", legFrame);
    }

    private void updateWeaponAnimation(ShipAPI ship, String slotId, int frame) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot() != null && slotId.equals(weapon.getSlot().getId())) {
                setSafeFrame(weapon, frame);
                return;
            }
        }
    }

    private void setSafeFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        weapon.getAnimation().setFrame(Math.max(0, Math.min(frame, maxFrame)));
    }
}
