package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * RX-105 movement controller.
 *
 * Frame contract:
 * - Legs default to frame 3.
 * - Body/head default to frame 1.
 * - Shoulders and shoulder bases default to frame 1.
 * - Forward commands advance legs toward high frames; reverse commands advance
 *   toward low frames.
 */
public class Moci_RX105FullBodyAnimationController implements EveryFrameWeaponEffectPlugin {
    private static final String FRONT_SEQUENCE = "FRONT";
    private static final String BACK_SEQUENCE = "BACK";
    private static final int LEG_DEFAULT_FRAME = 3;
    private static final int BODY_DEFAULT_FRAME = 1;
    private static final int SHOULDER_DEFAULT_FRAME = 1;
    private static final int MOVEMENT_MAX_FRAME_TARGET = 7;

    private boolean initialized = false;
    private int legDefaultFrame = LEG_DEFAULT_FRAME;
    private int movementMaxFrame = MOVEMENT_MAX_FRAME_TARGET;
    private String currentSequence = null;
    private int currentFrame = LEG_DEFAULT_FRAME;
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

        if (!initialized) {
            int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
            movementMaxFrame = Math.min(MOVEMENT_MAX_FRAME_TARGET, maxFrame);
            legDefaultFrame = Math.min(LEG_DEFAULT_FRAME, movementMaxFrame);
            currentFrame = legDefaultFrame;
            initialized = true;
        }

        if (!ship.isAlive() || ship.isHulk()) {
            currentFrame = legDefaultFrame;
            setSafeFrame(weapon, currentFrame);
            syncOtherParts(ship, currentFrame);
            return;
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
                    } else if (currentFrame > legDefaultFrame) {
                        currentFrame = Math.max(currentFrame - 1, legDefaultFrame);
                    }
                } else if (BACK_SEQUENCE.equals(currentSequence)) {
                    if (isDecelerating || isBackwards) {
                        currentFrame = Math.max(currentFrame - 1, 0);
                    } else if (currentFrame < legDefaultFrame) {
                        currentFrame = Math.min(currentFrame + 1, legDefaultFrame);
                    }
                }
            }

            if (FRONT_SEQUENCE.equals(currentSequence) && currentFrame == legDefaultFrame && !isAccelerating) {
                currentSequence = null;
                timer = 0f;
            } else if (BACK_SEQUENCE.equals(currentSequence) && currentFrame == legDefaultFrame
                    && !isDecelerating && !isBackwards) {
                currentSequence = null;
                timer = 0f;
            }
        }

        if (currentSequence == null) {
            currentFrame = legDefaultFrame;
        }

        setSafeFrame(weapon, currentFrame);
        syncOtherParts(ship, currentFrame);
    }

    private void syncOtherParts(ShipAPI ship, int legFrame) {
        int bodyFrame = Math.max(0, Math.min(3, legFrame / 2));
        int shoulderFrame = Math.max(0, Math.min(5, legFrame - 2));

        if (legFrame == legDefaultFrame) {
            bodyFrame = BODY_DEFAULT_FRAME;
            shoulderFrame = SHOULDER_DEFAULT_FRAME;
        }

        updateWeaponAnimation(ship, "BODY", bodyFrame);
        updateWeaponAnimation(ship, "HEAD", bodyFrame);
        updateWeaponAnimation(ship, "SHOULDER_L", shoulderFrame);
        updateWeaponAnimation(ship, "SHOULDER_R", shoulderFrame);
        updateWeaponAnimation(ship, "SHOULDER_L_UNDER", shoulderFrame);
        updateWeaponAnimation(ship, "SHOULDER_R_UNDER", shoulderFrame);
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
