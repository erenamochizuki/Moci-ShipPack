package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

public class Moci_MechLegAnimationController implements EveryFrameWeaponEffectPlugin {
    private static final String FRONT_SEQUENCE = "FRONT";
    private static final String BACK_SEQUENCE = "BACK";
    private static final int DEFAULT_FRAME_TARGET = 4;

    private boolean initialized = false;
    private int maxFrame = 0;
    private int defaultFrame = 0;

    private String currentSequence = null;
    private int currentFrame = 0;
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

        syncAnimationState(weapon);

        // 这类腿部装饰帧由脚本手动控制，不能同时让内置动画继续自动播放。
        weapon.getAnimation().pause();

        if (!ship.isAlive() || ship.isHulk()) {
            resetToDefaultFrame(weapon);
            return;
        }

        boolean isAccelerating = ship.getEngineController().isAccelerating();
        boolean isDecelerating = ship.getEngineController().isDecelerating();
        boolean isBackwards = ship.getEngineController().isAcceleratingBackwards();

        String desiredSequence = getDesiredSequence(isAccelerating, isDecelerating, isBackwards);
        if (desiredSequence == null) {
            if (currentSequence == null) {
                resetToDefaultFrame(weapon);
                return;
            }
        } else if (!desiredSequence.equals(currentSequence)) {
            currentSequence = desiredSequence;
            timer = 0f;
        }

        float frameRate = Math.max(0f, weapon.getAnimation().getFrameRate());
        timer += amount * frameRate;
        while (timer >= 1f) {
            timer -= 1f;
            stepCurrentFrame(isAccelerating, isDecelerating, isBackwards);
        }

        if (shouldResetSequence(isAccelerating, isDecelerating, isBackwards)) {
            currentSequence = null;
            timer = 0f;
            currentFrame = defaultFrame;
        }

        setSafeFrame(weapon, currentFrame);
    }

    private void syncAnimationState(WeaponAPI weapon) {
        int currentMaxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        if (!initialized || currentMaxFrame != maxFrame) {
            maxFrame = currentMaxFrame;
            defaultFrame = Math.min(DEFAULT_FRAME_TARGET, maxFrame);
            currentFrame = clampFrame(currentFrame == 0 && !initialized ? defaultFrame : currentFrame);
            initialized = true;
        }
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

    private void stepCurrentFrame(boolean isAccelerating, boolean isDecelerating, boolean isBackwards) {
        if (FRONT_SEQUENCE.equals(currentSequence)) {
            if (isAccelerating) {
                currentFrame--;
            } else if (currentFrame < defaultFrame) {
                currentFrame++;
            }
        } else if (BACK_SEQUENCE.equals(currentSequence)) {
            if (isDecelerating || isBackwards) {
                currentFrame++;
            } else if (currentFrame > defaultFrame) {
                currentFrame--;
            }
        }

        currentFrame = clampFrame(currentFrame);
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

    private void resetToDefaultFrame(WeaponAPI weapon) {
        currentSequence = null;
        timer = 0f;
        currentFrame = defaultFrame;
        setSafeFrame(weapon, currentFrame);
    }

    private int clampFrame(int frame) {
        return Math.max(0, Math.min(frame, maxFrame));
    }

    private void setSafeFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        weapon.getAnimation().setFrame(clampFrame(frame));
    }
}
