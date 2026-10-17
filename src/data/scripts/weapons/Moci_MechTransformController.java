package data.scripts.weapons;

import org.lwjgl.input.Keyboard;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import data.scripts.util.Moci_VariableFrameWeaponModeUtil;

/**
 * MSZ-006 backpack transform controller.
 *
 * Mounted on the BACKPACK decorative weapon. The backpack sprite sheet is the
 * transform animation; the other visible mech parts are switched to their blank
 * frames while the ship is in wave-rider form.
 */
public class Moci_MechTransformController implements EveryFrameWeaponEffectPlugin {
    private static final String HULL_ID = "Moci_MSZ_006";
    private static final String BACKPACK_SLOT = "BACKPACK";

    private static final String STATUS_KEY = "MOCI_MSZ_006_TRANSFORM_STATUS";
    private static final String STAT_ID = "MOCI_MSZ_006_TRANSFORM_STATS";
    public static final String CUSTOM_TRANSFORMED = "MOCI_MSZ_006_TRANSFORMED";
    public static final String CUSTOM_TRANSFORM_STATE = "MOCI_MSZ_006_TRANSFORM_STATE";
    public static final String CUSTOM_TRANSFORM_PREPARE = "MOCI_MSZ_006_TRANSFORM_PREPARE";
    public static final String CUSTOM_PARTS_HIDDEN = "MOCI_MSZ_006_PARTS_HIDDEN";

    private static final float HOLD_TIME = 0.75f;
    private static final float COOLDOWN_TIME = 4f;
    private static final float TRANSFORM_ANIM_TIME = 0.55f;
    private static final float REVERT_ANIM_TIME = 0.55f;

    private static final int NORMAL_FRAME = 0;
    private static final int RESTORE_PARTS_FRAME = 1;
    private static final int BLANK_FRAME = 1;
    private static final int BACKPACK_TRANSFORMED_FRAME = 11;

    private static final float NORMAL_MANEUVER_BONUS = 35f;
    private static final float TRANSFORMED_SPEED_BONUS = 45f;

    private static final String[] TWO_FRAME_PART_SLOTS = {
            "BODY",
            "HEAD",
            "HEAD_SOURCE",
            "ARM_L",
            "ARM_R",
            "ARM_L_SOURCE",
            "ARM_R_SOURCE",
            "SHIELD_L_COVER",
            "SHOULDER_L",
            "SHOULDER_R"
    };

    private static final String[] MS_COMBAT_WEAPON_SLOTS = {
            "HEAD_SOURCE",
            "ARM_L_SOURCE",
            "ARM_L_FOLLOW",
            "ARM_R_SOURCE"
    };

    private enum State {
        NORMAL,
        TRANSFORMING,
        TRANSFORMED,
        REVERTING,
        REVERT_COMPLETE
    }

    private State state = State.NORMAL;
    private boolean initialized = false;
    private boolean keyWasDown = false;
    private boolean partsHidden = false;
    private float holdTimer = 0f;
    private float cooldownTimer = 0f;
    private float animationTimer = 0f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || ship.getHullSpec() == null || !HULL_ID.equals(ship.getHullSpec().getHullId())) {
            return;
        }

        if (weapon.getSlot() == null || !BACKPACK_SLOT.equals(weapon.getSlot().getId())) {
            return;
        }

        if (!ship.isAlive() || ship.isHulk()) {
            clearStats(ship);
            restoreNormalVisuals(ship, weapon);
            ship.removeCustomData(CUSTOM_TRANSFORM_STATE);
            ship.removeCustomData(CUSTOM_TRANSFORM_PREPARE);
            return;
        }

        if (!initialized) {
            initialized = true;
            setFrame(weapon, NORMAL_FRAME);
            showParts(ship);
        }

        if (cooldownTimer > 0f) {
            cooldownTimer = Math.max(0f, cooldownTimer - amount);
        }

        handleInput(amount, engine, ship);
        updateState(amount, ship, weapon);
        updateWeaponMode(ship);
        ship.setCustomData(CUSTOM_TRANSFORM_STATE, state.name());
        applyStats(ship);
        updatePlayerStatus(engine, ship);
    }

    private void handleInput(float amount, CombatEngineAPI engine, ShipAPI ship) {
        if (engine.getPlayerShip() != ship || ship.getShipAI() != null || !canStartTransform()) {
            keyWasDown = false;
            holdTimer = 0f;
            ship.removeCustomData(CUSTOM_TRANSFORM_PREPARE);
            return;
        }

        boolean keyDown = Keyboard.isKeyDown(Keyboard.KEY_X);
        if (keyDown) {
            if (!keyWasDown) {
                keyWasDown = true;
                holdTimer = 0f;
            }
            ship.setCustomData(CUSTOM_TRANSFORM_PREPARE, Boolean.TRUE);
            returnAllWeaponsToDefaultAngle(ship);
            holdTimer += amount;
            if (holdTimer >= HOLD_TIME) {
                beginTransition();
                keyWasDown = false;
                holdTimer = 0f;
                ship.removeCustomData(CUSTOM_TRANSFORM_PREPARE);
            }
        } else {
            keyWasDown = false;
            holdTimer = 0f;
            ship.removeCustomData(CUSTOM_TRANSFORM_PREPARE);
        }
    }

    private boolean canStartTransform() {
        return cooldownTimer <= 0f && (state == State.NORMAL || state == State.TRANSFORMED);
    }

    private void beginTransition() {
        animationTimer = 0f;
        state = state == State.TRANSFORMED ? State.REVERTING : State.TRANSFORMING;
    }

    private void returnAllWeaponsToDefaultAngle(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon == null || weapon.getSlot() == null) {
                continue;
            }
            float targetAngle = ship.getFacing() + weapon.getSlot().getAngle();
            weapon.setCurrAngle(approachAngle(weapon.getCurrAngle(), targetAngle, 5f));
        }
    }

    private float approachAngle(float current, float target, float maxStep) {
        float diff = getShortestRotation(current, target);
        if (Math.abs(diff) <= maxStep) {
            return target;
        }
        return current + Math.signum(diff) * maxStep;
    }

    private float getShortestRotation(float from, float to) {
        float diff = (to - from) % 360f;
        if (diff > 180f) {
            diff -= 360f;
        } else if (diff < -180f) {
            diff += 360f;
        }
        return diff;
    }

    private void updateState(float amount, ShipAPI ship, WeaponAPI backpack) {
        switch (state) {
            case TRANSFORMING:
                animationTimer += amount;
                returnAllWeaponsToDefaultAngle(ship);
                setBackpackFrame(backpack, 0, getBackpackMidFrame(backpack), animationTimer / TRANSFORM_ANIM_TIME);
                if (isBackpackPastNormalFrame(backpack)) {
                    hideParts(ship);
                } else {
                    showParts(ship);
                }
                if (animationTimer >= TRANSFORM_ANIM_TIME) {
                    state = State.TRANSFORMED;
                    animationTimer = 0f;
                    cooldownTimer = COOLDOWN_TIME;
                    setFrame(backpack, getBackpackMidFrame(backpack));
                    ship.setCustomData(CUSTOM_TRANSFORMED, Boolean.TRUE);
                }
                break;
            case TRANSFORMED:
                hideParts(ship);
                setFrame(backpack, getBackpackMidFrame(backpack));
                ship.setCustomData(CUSTOM_TRANSFORMED, Boolean.TRUE);
                break;
            case REVERTING:
                animationTimer += amount;
                returnAllWeaponsToDefaultAngle(ship);
                setBackpackFrame(backpack, getBackpackMidFrame(backpack), getBackpackLastFrame(backpack),
                        animationTimer / REVERT_ANIM_TIME);
                if (isBackpackAtRestorePartsFrame(backpack)) {
                    showParts(ship);
                } else {
                    hideParts(ship);
                }
                if (animationTimer >= REVERT_ANIM_TIME) {
                    state = State.REVERT_COMPLETE;
                    animationTimer = 0f;
                    setFrame(backpack, getBackpackLastFrame(backpack));
                }
                break;
            case REVERT_COMPLETE:
                state = State.NORMAL;
                cooldownTimer = COOLDOWN_TIME;
                restoreNormalVisuals(ship, backpack);
                ship.removeCustomData(CUSTOM_TRANSFORMED);
                break;
            case NORMAL:
            default:
                showParts(ship);
                setFrame(backpack, NORMAL_FRAME);
                ship.removeCustomData(CUSTOM_TRANSFORMED);
                ship.removeCustomData(CUSTOM_TRANSFORM_PREPARE);
                break;
        }
    }

    private void setBackpackFrame(WeaponAPI backpack, int startFrame, int endFrame, float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        int frame = startFrame + Math.round((endFrame - startFrame) * clamped);
        setFrame(backpack, frame);
    }

    private boolean isBackpackPastNormalFrame(WeaponAPI backpack) {
        if (backpack == null || backpack.getAnimation() == null) {
            return false;
        }
        return backpack.getAnimation().getFrame() > NORMAL_FRAME;
    }

    private boolean isBackpackAtRestorePartsFrame(WeaponAPI backpack) {
        if (backpack == null || backpack.getAnimation() == null) {
            return false;
        }
        return backpack.getAnimation().getFrame() <= RESTORE_PARTS_FRAME;
    }

    private void hideParts(ShipAPI ship) {
        if (!partsHidden) {
            partsHidden = true;
            ship.setCustomData(CUSTOM_PARTS_HIDDEN, Boolean.TRUE);
        }

        for (int i = 0; i < TWO_FRAME_PART_SLOTS.length; i++) {
            setSlotFrame(ship, TWO_FRAME_PART_SLOTS[i], BLANK_FRAME);
        }
    }

    private void showParts(ShipAPI ship) {
        if (!partsHidden) {
            return;
        }

        partsHidden = false;
        ship.removeCustomData(CUSTOM_PARTS_HIDDEN);
        for (int i = 0; i < TWO_FRAME_PART_SLOTS.length; i++) {
            setSlotFrame(ship, TWO_FRAME_PART_SLOTS[i], NORMAL_FRAME);
        }
    }

    private void restoreNormalVisuals(ShipAPI ship, WeaponAPI backpack) {
        showParts(ship);
        setFrame(backpack, NORMAL_FRAME);
        ship.removeCustomData(CUSTOM_TRANSFORMED);
    }

    private void applyStats(ShipAPI ship) {
        clearStats(ship);
        if (state == State.TRANSFORMED || state == State.REVERTING || state == State.REVERT_COMPLETE) {
            ship.getMutableStats().getMaxSpeed().modifyPercent(STAT_ID, TRANSFORMED_SPEED_BONUS);
            ship.getMutableStats().getAcceleration().modifyPercent(STAT_ID, TRANSFORMED_SPEED_BONUS);
            ship.getMutableStats().getDeceleration().modifyPercent(STAT_ID, TRANSFORMED_SPEED_BONUS);
        } else {
            ship.getMutableStats().getAcceleration().modifyPercent(STAT_ID, NORMAL_MANEUVER_BONUS);
            ship.getMutableStats().getDeceleration().modifyPercent(STAT_ID, NORMAL_MANEUVER_BONUS);
            ship.getMutableStats().getTurnAcceleration().modifyPercent(STAT_ID, NORMAL_MANEUVER_BONUS);
            ship.getMutableStats().getMaxTurnRate().modifyPercent(STAT_ID, NORMAL_MANEUVER_BONUS);
        }
    }

    private void clearStats(ShipAPI ship) {
        ship.getMutableStats().getMaxSpeed().unmodify(STAT_ID);
        ship.getMutableStats().getAcceleration().unmodify(STAT_ID);
        ship.getMutableStats().getDeceleration().unmodify(STAT_ID);
        ship.getMutableStats().getTurnAcceleration().unmodify(STAT_ID);
        ship.getMutableStats().getMaxTurnRate().unmodify(STAT_ID);
    }

    private void updatePlayerStatus(CombatEngineAPI engine, ShipAPI ship) {
        if (engine.getPlayerShip() != ship) {
            return;
        }

        String title = "Z高达变形系统";
        String data;
        boolean isDebuff = false;

        if (state == State.TRANSFORMING) {
            data = "变形中";
        } else if (state == State.REVERTING || state == State.REVERT_COMPLETE) {
            data = "复归中";
        } else if (cooldownTimer > 0f) {
            data = String.format("冷却 %.1f秒", cooldownTimer);
            isDebuff = true;
        } else if (state == State.TRANSFORMED) {
            data = String.format("Wave Rider: 速度 +%.0f%%，长按X复归", TRANSFORMED_SPEED_BONUS);
        } else if (holdTimer > 0f) {
            data = String.format("变形准备 %.0f%%", Math.min(1f, holdTimer / HOLD_TIME) * 100f);
        } else {
            data = String.format("MS形态: 机动 +%.0f%%，长按X变形", NORMAL_MANEUVER_BONUS);
        }

        engine.maintainStatusForPlayerShip(STATUS_KEY,
                "graphics/icons/hullsys/maneuvering_jets.png",
                title,
                data,
                isDebuff);
    }

    private void setSlotFrame(ShipAPI ship, String slotId, int frame) {
        for (WeaponAPI part : ship.getAllWeapons()) {
            if (part.getSlot() != null && slotId.equals(part.getSlot().getId())) {
                setFrame(part, frame);
                return;
            }
        }
    }

    private void setFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        weapon.getAnimation().pause();
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        weapon.getAnimation().setFrame(Math.max(0, Math.min(frame, maxFrame)));
    }

    private int getBackpackMidFrame(WeaponAPI backpack) {
        return Math.min(BACKPACK_TRANSFORMED_FRAME, getBackpackLastFrame(backpack));
    }

    private int getBackpackLastFrame(WeaponAPI backpack) {
        if (backpack == null || backpack.getAnimation() == null) {
            return 0;
        }
        return Math.max(0, backpack.getAnimation().getNumFrames() - 1);
    }

    private void updateWeaponMode(ShipAPI ship) {
        boolean wrMode = state == State.TRANSFORMED;
        boolean suppressFire = state == State.TRANSFORMING
                || state == State.REVERTING
                || state == State.REVERT_COMPLETE;
        Moci_VariableFrameWeaponModeUtil.applyWeaponMode(ship, wrMode, suppressFire, MS_COMBAT_WEAPON_SLOTS);
    }
}
