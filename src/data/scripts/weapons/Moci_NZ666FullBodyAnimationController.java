package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * NZ-666 movement controller for body, crotch and wing covers.
 */
public class Moci_NZ666FullBodyAnimationController implements EveryFrameWeaponEffectPlugin {
    private static final String FRONT_SEQUENCE = "FRONT";
    private static final String BACK_SEQUENCE = "BACK";
    private static final int LEG_DEFAULT_FRAME = 4;
    private static final int BODY_DEFAULT_FRAME = 1;
    private static final int CROTCH_DEFAULT_FRAME = 1;
    private static final int WING_COVER_DEFAULT_FRAME = 0;
    private static final int MOVEMENT_MAX_FRAME_TARGET = 8;
    private static final int WING_FORWARD_FRAME = 8;

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
        int crotchFrame = Math.max(0, Math.min(2, legFrame / 3));
        int wingCoverFrame = Math.max(0, Math.min(WING_FORWARD_FRAME, Math.round(legFrame * (WING_FORWARD_FRAME / 8f))));

        if (legFrame == legDefaultFrame) {
            bodyFrame = BODY_DEFAULT_FRAME;
            crotchFrame = CROTCH_DEFAULT_FRAME;
            wingCoverFrame = WING_COVER_DEFAULT_FRAME;
        }

        updateWeaponAnimation(ship, "BODY", bodyFrame);
        updateWeaponAnimation(ship, "CROTCH", crotchFrame);
        updateWingCoverAnimation(ship, "WING_LF", wingCoverFrame);
        updateWingCoverAnimation(ship, "WING_LB", wingCoverFrame);
        updateWingCoverAnimation(ship, "WING_RF", wingCoverFrame);
        updateWingCoverAnimation(ship, "WING_RB", wingCoverFrame);
    }

    private void updateWeaponAnimation(ShipAPI ship, String slotId, int frame) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot() != null && slotId.equals(weapon.getSlot().getId())) {
                setSafeFrame(weapon, frame);
                return;
            }
        }
    }

    private void updateWingCoverAnimation(ShipAPI ship, String moduleSlotId, int frame) {
        WeaponAPI cover = null;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot() != null && (moduleSlotId + "_COVER").equals(weapon.getSlot().getId())) {
                cover = weapon;
                break;
            }
        }
        if (cover == null || cover.getAnimation() == null) {
            return;
        }

        ShipAPI module = null;
        for (ShipAPI child : ship.getChildModulesCopy()) {
            if (child != null && child.getStationSlot() != null
                    && moduleSlotId.equals(child.getStationSlot().getId())) {
                module = child;
                break;
            }
        }

        if (module == null || !module.isAlive() || module.isHulk() || module.getHitpoints() <= 0f) {
            setSafeFrame(cover, Math.max(0, cover.getAnimation().getNumFrames() - 1));
        } else {
            setSafeFrame(cover, frame);
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
