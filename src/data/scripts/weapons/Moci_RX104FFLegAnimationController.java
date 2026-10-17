package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * RX-104FF leg-only movement controller. The body is reserved for the
 * flight-armor ship system, and frame 8 is a blank transform frame.
 */
public class Moci_RX104FFLegAnimationController implements EveryFrameWeaponEffectPlugin {
    public static final String FLIGHT_ARMOR_ACTIVE_KEY = "moci_rx104ff_flight_armor_active";

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

        if (Boolean.TRUE.equals(ship.getCustomData().get(FLIGHT_ARMOR_ACTIVE_KEY))) {
            return;
        }

        if (!initialized) {
            int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
            movementMaxFrame = Math.min(MOVEMENT_MAX_FRAME_TARGET, maxFrame);
            defaultFrame = Math.min(DEFAULT_FRAME_TARGET, movementMaxFrame);
            currentFrame = defaultFrame;
            initialized = true;
        }

        if (!ship.isAlive() || ship.isHulk()) {
            setSafeFrame(weapon, defaultFrame);
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
    }

    private void setSafeFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        weapon.getAnimation().setFrame(Math.max(0, Math.min(frame, maxFrame)));
    }
}
