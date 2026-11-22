package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import java.util.List;

/**
 * NZ-666 movement controller for body, crotch and wing covers.
 */
public class Moci_NZ666FullBodyAnimationController implements EveryFrameWeaponEffectPlugin {
    private static final String FRONT_SEQUENCE = "FRONT";
    private static final String BACK_SEQUENCE = "BACK";
    static final int NEUTRAL_MOVEMENT_FRAME = 4;
    private static final int BODY_DEFAULT_FRAME = 1;
    private static final int CROTCH_DEFAULT_FRAME = 1;
    private static final int MOVEMENT_MAX_FRAME_TARGET = 8;

    private boolean initialized = false;
    private int legDefaultFrame = NEUTRAL_MOVEMENT_FRAME;
    private int movementMaxFrame = MOVEMENT_MAX_FRAME_TARGET;
    private String currentSequence = null;
    private int currentFrame = NEUTRAL_MOVEMENT_FRAME;
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
            legDefaultFrame = Math.min(NEUTRAL_MOVEMENT_FRAME, movementMaxFrame);
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
        int crotchFrame = Math.max(0, Math.min(2, legFrame / 3));

        if (legFrame == legDefaultFrame) {
            bodyFrame = BODY_DEFAULT_FRAME;
            crotchFrame = CROTCH_DEFAULT_FRAME;
        }

        List<ShipAPI> children = ship.getChildModulesCopy();
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot() == null) continue;
            switch (weapon.getSlot().getId()) {
                case "BODY":
                    setSafeFrame(weapon, bodyFrame);
                    break;
                case "CROTCH":
                    setSafeFrame(weapon, crotchFrame);
                    break;
                case "WING_LF_COVER":
                case "WING_LB_COVER":
                case "WING_RF_COVER":
                case "WING_RB_COVER":
                    if (weapon.getEffectPlugin() instanceof Moci_NZ666WingCoverEffect cover) {
                        cover.setMovementFrame(weapon, children, legFrame);
                    }
                    break;
            }
        }
    }

    private void setSafeFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        weapon.getAnimation().pause();
        weapon.getAnimation().setFrame(Math.max(0, Math.min(frame, maxFrame)));
    }
}
