package data.scripts.weapons;

import org.lazywizard.lazylib.MathUtils;
import org.magiclib.util.MagicAnim;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Qubeley body-side blade linkage. AMX-004 uses FOLLOW slots for its sabers, so
 * this controller only handles the saber swing posture and leaves leg animation
 * on the leg weapon's existing controller.
 */
public class Moci_AMX004BodyBladeController implements EveryFrameWeaponEffectPlugin {
    private static final String BLADE_SWING_ACTIVE_LEFT_KEY = "MOCI_BLADE_SWING_ACTIVE_LEFT";
    private static final String BLADE_SWING_ACTIVE_RIGHT_KEY = "MOCI_BLADE_SWING_ACTIVE_RIGHT";

    private static final float BASE_ARM_OFFSET = 16f;
    private static final float BASE_SHOULDER_OFFSET = 8f;
    private static final float LPAULDRONOFFSET = -5f;
    private static final float RPAULDRONOFFSET = 5f;

    private boolean init = false;
    private ShipAPI ship;
    private WeaponAPI armL;
    private WeaponAPI armR;
    private WeaponAPI shoulderL;
    private WeaponAPI shoulderR;
    private WeaponAPI shoulderLF;
    private WeaponAPI shoulderLB;
    private WeaponAPI shoulderRF;
    private WeaponAPI shoulderRB;
    private WeaponAPI bladeL;
    private WeaponAPI bladeR;

    private float originalArmLPos = 0f;
    private float originalArmRPos = 0f;
    private float originalShoulderLPos = 0f;
    private float originalShoulderRPos = 0f;
    private float originalShoulderLFPos = 0f;
    private float originalShoulderLBPos = 0f;
    private float originalShoulderRFPos = 0f;
    private float originalShoulderRBPos = 0f;
    private float originalBladeLY = 0f;
    private float originalBladeRY = 0f;
    private float offsetMultiplier = 1f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        ship = weapon.getShip();
        if (ship == null) {
            return;
        }

        if (!init) {
            init = true;
            initialize();
        }

        if (!ship.isAlive() || ship.isHulk()) {
            setBladeSwingActive(false, false);
            return;
        }

        updateBladeLinkage(amount, weapon);
    }

    private void initialize() {
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w == null || w.getSlot() == null) {
                continue;
            }
            String slotId = w.getSlot().getId();
            if ("ARM_L".equals(slotId)) {
                armL = w;
                originalArmLPos = getSpriteCenterY(w);
            } else if ("ARM_R".equals(slotId)) {
                armR = w;
                originalArmRPos = getSpriteCenterY(w);
            } else if ("SHOULDER_L".equals(slotId)) {
                shoulderL = w;
                originalShoulderLPos = getSpriteCenterY(w);
            } else if ("SHOULDER_R".equals(slotId)) {
                shoulderR = w;
                originalShoulderRPos = getSpriteCenterY(w);
            } else if ("SHOULDER_L_F".equals(slotId)) {
                shoulderLF = w;
                originalShoulderLFPos = getSpriteCenterY(w);
            } else if ("SHOULDER_L_B".equals(slotId)) {
                shoulderLB = w;
                originalShoulderLBPos = getSpriteCenterY(w);
            } else if ("SHOULDER_R_F".equals(slotId)) {
                shoulderRF = w;
                originalShoulderRFPos = getSpriteCenterY(w);
            } else if ("SHOULDER_R_B".equals(slotId)) {
                shoulderRB = w;
                originalShoulderRBPos = getSpriteCenterY(w);
            } else if ("ARM_L_SOURCE".equals(slotId) && isBladeWeapon(w)) {
                bladeL = w;
                originalBladeLY = getSpriteCenterY(w);
            } else if ("ARM_R_SOURCE".equals(slotId) && isBladeWeapon(w)) {
                bladeR = w;
                originalBladeRY = getSpriteCenterY(w);
            } else if ("ARM_L_FOLLOW".equals(slotId) && bladeL == null && isBladeWeapon(w)) {
                bladeL = w;
                originalBladeLY = getSpriteCenterY(w);
            } else if ("ARM_R_FOLLOW".equals(slotId) && bladeR == null && isBladeWeapon(w)) {
                bladeR = w;
                originalBladeRY = getSpriteCenterY(w);
            }
        }
        offsetMultiplier = calculateOffsetMultiplier(ship);
    }

    private float getSpriteCenterY(WeaponAPI weapon) {
        return weapon != null && weapon.getSprite() != null ? weapon.getSprite().getCenterY() : 0f;
    }

    private float calculateOffsetMultiplier(ShipAPI ship) {
        if (ship == null) {
            return 1f;
        }
        switch (ship.getHullSize()) {
            case FIGHTER:
            case FRIGATE:
                return 0.35f;
            case DESTROYER:
            case CRUISER:
            case CAPITAL_SHIP:
            default:
                return 1f;
        }
    }

    private void updateBladeLinkage(float amount, WeaponAPI torso) {
        boolean leftBladeActive = isBladeActive(bladeL);
        boolean rightBladeActive = isBladeActive(bladeR);
        setBladeSwingActive(leftBladeActive, rightBladeActive);

        if (leftBladeActive && armL != null && bladeL != null) {
            updateArmControl(armL, bladeL, shoulderL, originalArmLPos, originalShoulderLPos, false, torso);
        } else {
            restoreArmAndBlade(armL, shoulderL, bladeL, originalArmLPos, originalShoulderLPos, originalBladeLY, false);
        }

        if (rightBladeActive && armR != null && bladeR != null) {
            updateArmControl(armR, bladeR, shoulderR, originalArmRPos, originalShoulderRPos, true, torso);
        } else {
            restoreArmAndBlade(armR, shoulderR, bladeR, originalArmRPos, originalShoulderRPos, originalBladeRY, true);
        }

        WeaponAPI activeBlade = rightBladeActive ? bladeR : (leftBladeActive ? bladeL : null);
        if (activeBlade != null) {
            updateTorsoControl(torso, activeBlade, rightBladeActive);
            updateAIBehavior(activeBlade);
        }
    }

    private boolean isBladeActive(WeaponAPI blade) {
        if (blade == null || blade.isDisabled() || blade.getSpec() == null) {
            return false;
        }
        return isBladeWeapon(blade) && blade.getChargeLevel() > 0f;
    }

    private boolean isBladeWeapon(WeaponAPI weapon) {
        if (weapon == null || weapon.getSpec() == null) {
            return false;
        }
        String weaponId = weapon.getSpec().getWeaponId();
        return weaponId != null && weaponId.endsWith("_Sabre");
    }

    private void updateArmControl(
            WeaponAPI arm,
            WeaponAPI blade,
            WeaponAPI shoulder,
            float originalArmPos,
            float originalShoulderPos,
            boolean rightHand,
            WeaponAPI torso
    ) {
        float sineA;
        float sineB;
        if (blade.getChargeLevel() < 1f) {
            sineA = MagicAnim.smoothNormalizeRange(blade.getChargeLevel(), 0.3f, 0.9f);
            sineB = MagicAnim.smoothNormalizeRange(blade.getChargeLevel(), 0.3f, 1f);
        } else {
            sineA = 1f;
            sineB = 1f;
        }

        arm.setCurrAngle(blade.getCurrAngle());
        float armOffset = BASE_ARM_OFFSET * offsetMultiplier * sineB;
        setSpriteCenterY(arm, originalArmPos - armOffset);
        moveBladeWeapon(blade, armOffset);

        if (shoulder != null) {
            if (torso != null) {
                float shoulderOffset = rightHand ? RPAULDRONOFFSET : LPAULDRONOFFSET;
                shoulder.setCurrAngle(
                        torso.getCurrAngle() + shoulderOffset * sineA
                                + MathUtils.getShortestRotation(torso.getCurrAngle(), arm.getCurrAngle()) * 0.70f
                );
            }
            float shoulderYOffset = BASE_SHOULDER_OFFSET * offsetMultiplier * sineB;
            setSpriteCenterY(shoulder, originalShoulderPos - shoulderYOffset);
            moveShoulderParts(rightHand, shoulderYOffset);
        }
    }

    private void updateTorsoControl(WeaponAPI torso, WeaponAPI activeBlade, boolean rightHand) {
        if (torso == null || activeBlade == null || ship == null) {
            return;
        }
        if (torso.getChargeLevel() == 0f
                && (ship.getSelectedGroupAPI() == null || !ship.getSelectedGroupAPI().getWeaponsCopy().contains(torso))) {
            float sineA = activeBlade.getChargeLevel() < 1f
                    ? MagicAnim.smoothNormalizeRange(activeBlade.getChargeLevel(), 0.3f, 0.9f)
                    : 1f;
            float aim = MathUtils.getShortestRotation(ship.getFacing(), activeBlade.getCurrAngle());
            torso.setCurrAngle(ship.getFacing() + aim * 0.3f * sineA);
        }
    }

    private void updateAIBehavior(WeaponAPI activeBlade) {
        if (activeBlade == null || ship == null || ship.getShipTarget() == null) {
            return;
        }
        if (ship.getAIFlags().hasFlag(ShipwideAIFlags.AIFlags.BACKING_OFF)
                || ship.getAIFlags().hasFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE)) {
            return;
        }
        if (MathUtils.getDistanceSquared(ship.getLocation(), ship.getShipTarget().getLocation()) < 500f * 500f && !activeBlade.isDisabled() && ship.getAI() != null) {
            ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
            Global.getCombatEngine().headInDirectionWithoutTurning(ship, activeBlade.getCurrAngle(), ship.getMaxSpeed());
        }
    }

    private void restoreArmAndBlade(
            WeaponAPI arm,
            WeaponAPI shoulder,
            WeaponAPI blade,
            float originalArmPos,
            float originalShoulderPos,
            float originalBladePos,
            boolean rightHand
    ) {
        setSpriteCenterY(arm, originalArmPos);
        setSpriteCenterY(shoulder, originalShoulderPos);
        setSpriteCenterY(blade, originalBladePos);
        restoreShoulderParts(rightHand);
    }

    private void moveBladeWeapon(WeaponAPI blade, float yOffset) {
        if (blade == null) {
            return;
        }
        float originalY = blade == bladeL ? originalBladeLY : originalBladeRY;
        setSpriteCenterY(blade, originalY - yOffset);
    }

    private void setSpriteCenterY(WeaponAPI weapon, float value) {
        if (weapon != null && weapon.getSprite() != null) {
            weapon.getSprite().setCenterY(value);
        }
    }

    private void moveShoulderParts(boolean rightHand, float yOffset) {
        if (rightHand) {
            setSpriteCenterY(shoulderRF, originalShoulderRFPos - yOffset);
            setSpriteCenterY(shoulderRB, originalShoulderRBPos - yOffset);
        } else {
            setSpriteCenterY(shoulderLF, originalShoulderLFPos - yOffset);
            setSpriteCenterY(shoulderLB, originalShoulderLBPos - yOffset);
        }
    }

    private void restoreShoulderParts(boolean rightHand) {
        if (rightHand) {
            setSpriteCenterY(shoulderRF, originalShoulderRFPos);
            setSpriteCenterY(shoulderRB, originalShoulderRBPos);
        } else {
            setSpriteCenterY(shoulderLF, originalShoulderLFPos);
            setSpriteCenterY(shoulderLB, originalShoulderLBPos);
        }
    }

    private void setBladeSwingActive(boolean leftActive, boolean rightActive) {
        if (ship == null) {
            return;
        }
        ship.setCustomData(BLADE_SWING_ACTIVE_LEFT_KEY, leftActive);
        ship.setCustomData(BLADE_SWING_ACTIVE_RIGHT_KEY, rightActive);
    }
}
