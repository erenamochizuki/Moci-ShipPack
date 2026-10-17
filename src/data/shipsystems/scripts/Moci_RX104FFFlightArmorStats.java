package data.shipsystems.scripts;

import java.util.List;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.Misc;

import data.hullmods.Moci_MobileSuitsIDcard;
import data.scripts.Moci_CollisionStateManager;
import data.scripts.weapons.Moci_RX104FFLegAnimationController;

/**
 * RX-104FF flight-armor drift. Handles lateral impulse, body animation,
 * temporary collision suppression, and hiding exposed MS parts.
 */
public class Moci_RX104FFFlightArmorStats extends BaseShipSystemScript {
    private static final String COLLISION_MODIFIER_ID = "moci_rx104ff_flight_armor_collision";
    private static final int COLLISION_PRIORITY = 400;
    private static final float SYSTEM_DURATION = 0.75f;
    private static final int LEG_BLANK_FRAME = 8;

    private static final String[] TWO_FRAME_BLANK_SLOTS = {
            "ARM_L", "ARM_R", "ARM_R_SOURCE", "BODY_ARMOR", "HEAD"
    };

    private static class Params {
        float thrustAccel = 3000f;
        float thrustRateMult = 2f;
        float maxSpeedBoost = 600f;
        float brakingDecelMult = 2f;
    }

    private final Params p = new Params();
    private boolean pluginCreated = false;

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        if (!(stats.getEntity() instanceof ShipAPI)) {
            return;
        }

        ShipAPI ship = (ShipAPI) stats.getEntity();
        if (effectLevel >= 1f && !pluginCreated) {
            boolean driftLeft = shouldDriftLeft(ship);
            float thrustAngle = driftLeft ? ship.getFacing() + 90f : ship.getFacing() - 90f;
            ship.setCustomData(Moci_MobileSuitsIDcard.ENGINE_RENDER_SUPPRESSED_KEY, true);
            Global.getCombatEngine().addPlugin(createFlightArmorPlugin(ship, thrustAngle, driftLeft));
            pluginCreated = true;
        }

        if (state == State.COOLDOWN && pluginCreated) {
            pluginCreated = false;
        }
    }

    private boolean shouldDriftLeft(ShipAPI ship) {
        boolean strafeLeft = ship.getEngineController().isStrafingLeft();
        boolean strafeRight = ship.getEngineController().isStrafingRight();
        if (strafeLeft && !strafeRight) {
            return true;
        }
        if (strafeRight && !strafeLeft) {
            return false;
        }

        boolean turnLeft = ship.getEngineController().isTurningLeft();
        boolean turnRight = ship.getEngineController().isTurningRight();
        if (turnLeft && !turnRight) {
            return true;
        }
        if (turnRight && !turnLeft) {
            return false;
        }

        Vector2f velocity = ship.getVelocity();
        if (velocity.length() < 10f) {
            return true;
        }

        float velocityAngle = (float) Math.toDegrees(Math.atan2(velocity.y, velocity.x));
        float turnDir = Misc.getClosestTurnDirection(velocityAngle, ship.getFacing());
        return turnDir < 0f;
    }

    private EveryFrameCombatPlugin createFlightArmorPlugin(final ShipAPI ship, final float thrustAngle,
            final boolean driftLeft) {
        return new BaseEveryFrameCombatPlugin() {
            private float elapsed = 0f;
            private float thrustTime = 0f;
            private boolean thrusting = true;
            private boolean partsBlanked = false;
            private boolean cleanedUp = false;

            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                if (Global.getCombatEngine().isPaused()) {
                    return;
                }

                if (ship == null || !ship.isAlive() || ship.isHulk()) {
                    cleanup();
                    Global.getCombatEngine().removePlugin(this);
                    return;
                }

                ship.setCustomData(Moci_RX104FFLegAnimationController.FLIGHT_ARMOR_ACTIVE_KEY, true);
                ship.setCustomData(Moci_MobileSuitsIDcard.ENGINE_RENDER_SUPPRESSED_KEY, true);
                Moci_CollisionStateManager.getInstance().setCollisionModifier(ship, COLLISION_MODIFIER_ID,
                        COLLISION_PRIORITY, CollisionClass.NONE);

                float progress = Math.min(1f, elapsed / SYSTEM_DURATION);
                int bodyFrame = getBodyFrame(progress, driftLeft);
                setSlotFrame(ship, "BODY", bodyFrame);

                int firstBlankTriggerFrame = driftLeft ? 7 : 1;
                if (!partsBlanked && bodyFrame == firstBlankTriggerFrame) {
                    partsBlanked = true;
                }
                if (partsBlanked) {
                    setBlankFrames(ship);
                }

                applyLateralThrust(ship, amount);

                elapsed += amount;
                if (elapsed >= SYSTEM_DURATION) {
                    cleanup();
                    Global.getCombatEngine().removePlugin(this);
                }
            }

            private void applyLateralThrust(ShipAPI ship, float amount) {
                String modId = "moci_rx104ff_flight_armor";
                ship.getMutableStats().getMaxSpeed().unmodifyFlat(modId);
                float maxSpeedWithoutBonus = ship.getMutableStats().getMaxSpeed().getModifiedValue();

                if (thrusting) {
                    thrustTime += amount * p.thrustRateMult;
                    float mag = (1f - thrustTime) * (1f - thrustTime);
                    if (mag > 0f) {
                        mag = (float) Math.sqrt(mag);
                    }

                    Vector2f acc = Misc.getUnitVectorAtDegreeAngle(thrustAngle);
                    acc.scale(p.thrustAccel * mag * amount);
                    Vector2f.add(ship.getVelocity(), acc, ship.getVelocity());

                    ship.getMutableStats().getMaxSpeed().modifyFlat(modId, p.maxSpeedBoost);
                    float currentSpeed = ship.getVelocity().length();
                    if (currentSpeed > maxSpeedWithoutBonus) {
                        float excessSpeed = currentSpeed - maxSpeedWithoutBonus;
                        ship.getMutableStats().getDeceleration().modifyFlat(modId, excessSpeed);
                    }

                    ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                    ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
                    ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                    ship.getEngineController().extendFlame(this, 1f, 1f, 1f);

                    if (thrustTime >= 1f) {
                        thrusting = false;
                    }
                    return;
                }

                float currentSpeed = ship.getVelocity().length();
                if (currentSpeed > maxSpeedWithoutBonus) {
                    float excessSpeed = currentSpeed - maxSpeedWithoutBonus;
                    ship.getMutableStats().getDeceleration().modifyFlat(modId, p.brakingDecelMult * excessSpeed);
                }

                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
                ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                ship.getEngineController().extendFlame(this, 0.5f, 0.5f, 0.5f);
            }

            private int getBodyFrame(float progress, boolean driftLeft) {
                int index = Math.min(7, MathUtils.clamp((int) (progress * 8f), 0, 7));
                if (driftLeft) {
                    return new int[] { 7, 6, 5, 4, 3, 2, 1, 0 }[index];
                }
                return new int[] { 1, 2, 3, 4, 5, 6, 7, 0 }[index];
            }

            private void setBlankFrames(ShipAPI ship) {
                for (String slotId : TWO_FRAME_BLANK_SLOTS) {
                    setSlotFrame(ship, slotId, 1);
                    forceNoFire(ship, slotId);
                }
                setSlotFrame(ship, "LEGS", LEG_BLANK_FRAME);
            }

            private void restoreVisibleFrames(ShipAPI ship) {
                setSlotFrame(ship, "BODY", 0);
                for (String slotId : TWO_FRAME_BLANK_SLOTS) {
                    setSlotFrame(ship, slotId, 0);
                }
                setSlotFrame(ship, "LEGS", 4);
            }

            private void setSlotFrame(ShipAPI ship, String slotId, int frame) {
                for (WeaponAPI weapon : ship.getAllWeapons()) {
                    if (weapon.getSlot() != null && slotId.equals(weapon.getSlot().getId())) {
                        if (weapon.getAnimation() != null) {
                            weapon.getAnimation().pause();
                            int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
                            weapon.getAnimation().setFrame(Math.max(0, Math.min(frame, maxFrame)));
                        }
                        return;
                    }
                }
            }

            private void forceNoFire(ShipAPI ship, String slotId) {
                for (WeaponAPI weapon : ship.getAllWeapons()) {
                    if (weapon.getSlot() != null && slotId.equals(weapon.getSlot().getId())) {
                        weapon.setForceNoFireOneFrame(true);
                        return;
                    }
                }
            }

            private void cleanup() {
                if (cleanedUp) {
                    return;
                }
                cleanedUp = true;
                ship.removeCustomData(Moci_RX104FFLegAnimationController.FLIGHT_ARMOR_ACTIVE_KEY);
                ship.removeCustomData(Moci_MobileSuitsIDcard.ENGINE_RENDER_SUPPRESSED_KEY);
                restoreVisibleFrames(ship);
                Moci_CollisionStateManager.getInstance().removeCollisionModifier(ship, COLLISION_MODIFIER_ID);
                ship.getMutableStats().getMaxSpeed().unmodify("moci_rx104ff_flight_armor");
                ship.getMutableStats().getDeceleration().unmodify("moci_rx104ff_flight_armor");
            }
        };
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        if (stats.getEntity() instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) stats.getEntity();
            ship.removeCustomData(Moci_MobileSuitsIDcard.ENGINE_RENDER_SUPPRESSED_KEY);
            Moci_CollisionStateManager.getInstance().removeCollisionModifier(ship, COLLISION_MODIFIER_ID);
        }
    }

    @Override
    public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
        if (ship != null && (ship.getEngineController().isFlamedOut() || ship.getEngineController().isFlamingOut())) {
            return false;
        }
        return super.isUsable(system, ship);
    }
}
