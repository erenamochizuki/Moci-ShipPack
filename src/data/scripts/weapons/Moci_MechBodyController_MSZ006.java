package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * MSZ-006 leg animation controller.
 *
 * Mounted on LEGS. Frame 4 is the neutral MS pose; frame 8 is blank and is only
 * used by the transform flow. Walking animation is disabled in all WR/transition
 * states so it does not fight the backpack transform controller.
 */
public class Moci_MechBodyController_MSZ006 implements EveryFrameWeaponEffectPlugin {
    private static final String FRONT_SEQUENCE = "FRONT";
    private static final String BACK_SEQUENCE = "BACK";

    private static final int DEFAULT_FRAME_TARGET = 4;
    private static final int TRANSFORM_READY_FRAME = 0;
    private static final int BLANK_FRAME_TARGET = 8;

    private static final float DEFAULT_WALK_FRAME_RATE = 12f;
    private static final float TRANSFORM_FRAME_RATE = 18f;

    private boolean initialized = false;
    private int maxFrame = 0;
    private int maxWalkFrame = 0;
    private int defaultFrame = 0;
    private int blankFrame = 0;

    private String currentSequence = null;
    private int currentFrame = 0;
    private float timer = 0f;
    private boolean transformBlanked = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null || weapon.getAnimation() == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }

        syncAnimationState(weapon);
        weapon.getAnimation().pause();

        if (!ship.isAlive() || ship.isHulk()) {
            resetToDefaultFrame(weapon);
            return;
        }

        String transformState = getTransformState(ship);
        if (isTransformPreparing(ship) && "NORMAL".equals(transformState)) {
            updatePrepareToTransform(amount, weapon);
            return;
        }
        if ("TRANSFORMING".equals(transformState)) {
            if (!areTransformPartsHidden(ship)) {
                updatePrepareToTransform(amount, weapon);
                return;
            }
            updateTransformOut(amount, weapon);
            return;
        }
        if ("REVERTING".equals(transformState) && !areTransformPartsHidden(ship)) {
            resetToDefaultFrame(weapon);
            return;
        }
        if (isWaveRiderOrTransition(transformState)) {
            currentSequence = null;
            timer = 0f;
            currentFrame = blankFrame;
            transformBlanked = true;
            setSafeFrame(weapon, currentFrame);
            return;
        }

        if (transformBlanked || currentFrame > maxWalkFrame) {
            currentFrame = defaultFrame;
            timer = 0f;
            currentSequence = null;
        }
        transformBlanked = false;
        updateWalkAnimation(amount, ship, weapon);
    }

    private void syncAnimationState(WeaponAPI weapon) {
        int currentMaxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        if (!initialized || currentMaxFrame != maxFrame) {
            maxFrame = currentMaxFrame;
            blankFrame = Math.min(BLANK_FRAME_TARGET, maxFrame);
            maxWalkFrame = Math.max(0, blankFrame - 1);
            defaultFrame = Math.min(DEFAULT_FRAME_TARGET, maxWalkFrame);
            currentFrame = initialized ? clampFrame(currentFrame) : defaultFrame;
            initialized = true;
        }
    }

    private void updateTransformOut(float amount, WeaponAPI weapon) {
        currentSequence = null;

        if (transformBlanked) {
            currentFrame = blankFrame;
            setSafeFrame(weapon, currentFrame);
            return;
        }

        int readyFrame = Math.min(TRANSFORM_READY_FRAME, maxWalkFrame);
        if (currentFrame != readyFrame) {
            timer += amount * TRANSFORM_FRAME_RATE;
            while (timer >= 1f && currentFrame != readyFrame) {
                timer -= 1f;
                currentFrame += currentFrame < readyFrame ? 1 : -1;
                currentFrame = clampFrame(currentFrame);
            }
            setSafeFrame(weapon, currentFrame);
            return;
        }

        transformBlanked = true;
        timer = 0f;
        currentFrame = blankFrame;
        setSafeFrame(weapon, currentFrame);
    }

    private void updatePrepareToTransform(float amount, WeaponAPI weapon) {
        currentSequence = null;
        transformBlanked = false;

        int readyFrame = Math.min(TRANSFORM_READY_FRAME, maxWalkFrame);
        if (currentFrame == readyFrame) {
            timer = 0f;
            setSafeFrame(weapon, currentFrame);
            return;
        }

        float frameRate = weapon.getAnimation().getFrameRate();
        if (frameRate <= 0f) {
            frameRate = DEFAULT_WALK_FRAME_RATE;
        }

        timer += amount * frameRate;
        while (timer >= 1f && currentFrame != readyFrame) {
            timer -= 1f;
            currentFrame += currentFrame < readyFrame ? 1 : -1;
            currentFrame = clampWalkFrame(currentFrame);
        }
        setSafeFrame(weapon, currentFrame);
    }

    private void updateWalkAnimation(float amount, ShipAPI ship, WeaponAPI weapon) {
        boolean isAccelerating = ship.getEngineController().isAccelerating();
        boolean isDecelerating = ship.getEngineController().isDecelerating();
        boolean isBackwards = ship.getEngineController().isAcceleratingBackwards();

        String desiredSequence = getDesiredSequence(isAccelerating, isDecelerating, isBackwards);
        if (desiredSequence != null && !desiredSequence.equals(currentSequence)) {
            currentSequence = desiredSequence;
            timer = 0f;
        }

        if (currentSequence == null) {
            currentFrame = defaultFrame;
            setSafeFrame(weapon, currentFrame);
            return;
        }

        float frameRate = weapon.getAnimation().getFrameRate();
        if (frameRate <= 0f) {
            frameRate = DEFAULT_WALK_FRAME_RATE;
        }

        timer += amount * frameRate;
        while (timer >= 1f) {
            timer -= 1f;
            stepWalkFrame(isAccelerating, isDecelerating, isBackwards);
        }

        if (shouldResetSequence(isAccelerating, isDecelerating, isBackwards)) {
            currentSequence = null;
            timer = 0f;
            currentFrame = defaultFrame;
        }

        setSafeFrame(weapon, currentFrame);
    }

    private String getDesiredSequence(boolean isAccelerating, boolean isDecelerating, boolean isBackwards) {
        if (isDecelerating || isBackwards) {
            return BACK_SEQUENCE;
        }
        if (isAccelerating) {
            return FRONT_SEQUENCE;
        }
        return null;
    }

    private void stepWalkFrame(boolean isAccelerating, boolean isDecelerating, boolean isBackwards) {
        if (FRONT_SEQUENCE.equals(currentSequence)) {
            if (isAccelerating) {
                currentFrame++;
            } else if (currentFrame > defaultFrame) {
                currentFrame--;
            }
        } else if (BACK_SEQUENCE.equals(currentSequence)) {
            if (isDecelerating || isBackwards) {
                currentFrame--;
            } else if (currentFrame < defaultFrame) {
                currentFrame++;
            }
        }
        currentFrame = clampWalkFrame(currentFrame);
    }

    private boolean shouldResetSequence(boolean isAccelerating, boolean isDecelerating, boolean isBackwards) {
        if (FRONT_SEQUENCE.equals(currentSequence)) {
            return currentFrame == defaultFrame && !isAccelerating;
        }
        if (BACK_SEQUENCE.equals(currentSequence)) {
            return currentFrame == defaultFrame && !isDecelerating && !isBackwards;
        }
        return true;
    }

    private boolean isWaveRiderOrTransition(String transformState) {
        if (transformState == null || "NORMAL".equals(transformState)) {
            return false;
        }
        return true;
    }

    private String getTransformState(ShipAPI ship) {
        Object state = ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORM_STATE);
        return state instanceof String ? (String) state : "NORMAL";
    }

    private boolean isTransformPreparing(ShipAPI ship) {
        Object preparing = ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORM_PREPARE);
        return preparing instanceof Boolean && (Boolean) preparing;
    }

    private boolean areTransformPartsHidden(ShipAPI ship) {
        Object hidden = ship.getCustomData().get(Moci_MechTransformController.CUSTOM_PARTS_HIDDEN);
        return hidden instanceof Boolean && (Boolean) hidden;
    }

    private void resetToDefaultFrame(WeaponAPI weapon) {
        currentSequence = null;
        timer = 0f;
        transformBlanked = false;
        currentFrame = defaultFrame;
        setSafeFrame(weapon, currentFrame);
    }

    private int clampFrame(int frame) {
        return Math.max(0, Math.min(frame, maxFrame));
    }

    private int clampWalkFrame(int frame) {
        return Math.max(0, Math.min(frame, maxWalkFrame));
    }

    private void setSafeFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        weapon.getAnimation().setFrame(clampFrame(frame));
    }
}
