package data.scripts.weapons;

import java.awt.Color;
import java.util.List;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;

import data.scripts.util.Moci_ColorData;
import data.scripts.util.Moci_Util;

public class Moci_MSZ006ShieldArmEffect implements EveryFrameWeaponEffectPlugin {
    private static final float ROTATION_SPEED = 35f;
    private static final float DEFENSE_OFFSET = -50f;

    private boolean initialized = false;
    private ShipAPI ship;
    private ShipAPI shield;
    private WeaponAPI source;
    private WeaponAPI sourceFollow;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null) {
            return;
        }

        if (!initialized) {
            initialized = true;
            bindWeaponsAndShield(weapon);
        }

        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        if (isNonMsShape(ship)) {
            weapon.setCurrAngle(ship.getFacing());
            if (source != null) {
                source.setCurrAngle(ship.getFacing());
            }
            if (sourceFollow != null) {
                sourceFollow.setCurrAngle(ship.getFacing());
                hideFollowWeapon();
            }
            faceShield(ship.getFacing());
            return;
        }

        if (Moci_MechDecoWeaponMutexControl.isBladeSwingActive(weapon)) {
            if (source != null) {
                Moci_MechDecoWeaponMutexControl.syncAngleOnly(weapon, source);
                if (sourceFollow != null) {
                    sourceFollow.setCurrAngle(source.getCurrAngle());
                    hideFollowWeapon();
                }
                faceShield(source.getCurrAngle());
            }
            return;
        }

        updateExclusiveFiringControl();

        WeaponAPI manualControlWeapon = getManualControlWeapon();
        if (manualControlWeapon != null) {
            float manualAngle = manualControlWeapon.getCurrAngle();
            weapon.setCurrAngle(manualAngle);
            if (source != null && source != manualControlWeapon) {
                source.setCurrAngle(manualAngle);
            }
            if (sourceFollow != null && sourceFollow != manualControlWeapon) {
                sourceFollow.setCurrAngle(manualAngle);
            }
            hideFollowWeapon();
            faceShield(manualAngle);
            return;
        }

        if (sourceFollow != null && source != null) {
            sourceFollow.setCurrAngle(source.getCurrAngle());
            hideFollowWeapon();
        }

        CombatEntityAPI target = findNearestThreat();
        if (target != null) {
            float currentOffset = normalizeAngle(weapon.getCurrAngle() - ship.getFacing());
            if (currentOffset < DEFENSE_OFFSET) {
                currentOffset = Math.min(currentOffset + ROTATION_SPEED * amount, DEFENSE_OFFSET);
            } else if (currentOffset > DEFENSE_OFFSET) {
                currentOffset = Math.max(currentOffset - ROTATION_SPEED * amount, DEFENSE_OFFSET);
            }

            float finalAngle = ship.getFacing() + currentOffset;
            weapon.setCurrAngle(finalAngle);
            if (source != null) {
                source.setCurrAngle(finalAngle);
            }
            if (sourceFollow != null) {
                sourceFollow.setCurrAngle(finalAngle);
                hideFollowWeapon();
            }
        } else if (source != null) {
            float sourceOffset = normalizeAngle(source.getCurrAngle() - ship.getFacing());
            weapon.setCurrAngle(ship.getFacing() + sourceOffset);
        }

        if (source != null) {
            faceShield(source.getCurrAngle());
        } else {
            faceShield(weapon.getCurrAngle());
        }
    }

    private void bindWeaponsAndShield(WeaponAPI weapon) {
        ship = weapon.getShip();
        if (ship == null || weapon.getSlot() == null) {
            return;
        }

        String slotId = weapon.getSlot().getId();
        String side = slotId.substring(slotId.lastIndexOf("_") + 1);
        String shieldSlotId = "SHIELD_" + side;
        String sourceSlotId = slotId + "_SOURCE";
        String followSlotId = slotId + "_FOLLOW";

        for (ShipAPI module : ship.getChildModulesCopy()) {
            if (module.getStationSlot() != null && shieldSlotId.equals(module.getStationSlot().getId())) {
                shield = module;
                break;
            }
        }

        for (WeaponAPI candidate : ship.getAllWeapons()) {
            if (candidate.getSlot() != null && sourceSlotId.equals(candidate.getSlot().getId())) {
                source = candidate;
            }
            if (candidate.getSlot() != null && followSlotId.equals(candidate.getSlot().getId())) {
                sourceFollow = candidate;
            }
        }

        if (sourceFollow != null) {
            initializeFollowWeapon(weapon);
        }
    }

    private CombatEntityAPI findNearestThreat() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            return null;
        }
        if ((ship.getShield() != null && ship.getShield().isOn())
                || (source != null && source.isFiring())
                || (sourceFollow != null && sourceFollow.isFiring())) {
            return null;
        }
        if (!isShieldPresent()) {
            return null;
        }

        CombatEntityAPI nearestThreat = null;
        float nearestDistance = Float.MAX_VALUE;

        for (ShipAPI enemy : engine.getShips()) {
            if (enemy.isAlive() && enemy.getOwner() != ship.getOwner()) {
                float distance = MathUtils.getDistance(ship, enemy);
                if (distance < 1000f && distance < nearestDistance) {
                    nearestThreat = enemy;
                    nearestDistance = distance;
                }
            }
        }

        List<DamagingProjectileAPI> threats = Moci_Util.getEnemyProjectilesAndMissilesWithinRange(
                ship.getLocation(), 600f, ship.getOwner());
        for (DamagingProjectileAPI threat : threats) {
            float distance = MathUtils.getDistance(ship, threat);
            if (distance < nearestDistance) {
                nearestThreat = threat;
                nearestDistance = distance;
            }
        }

        return nearestThreat;
    }

    private WeaponAPI getManualControlWeapon() {
        if (ship == null || ship.getSelectedGroupAPI() == null) {
            return null;
        }

        WeaponGroupAPI selected = ship.getSelectedGroupAPI();
        WeaponAPI activeWeapon = getActiveWeaponSafely(selected);
        if (activeWeapon == sourceFollow || (sourceFollow != null && selected.getWeaponsCopy().contains(sourceFollow))) {
            return sourceFollow;
        }
        if (activeWeapon == source || selected.getWeaponsCopy().contains(source)) {
            return source;
        }
        return null;
    }

    private WeaponAPI getActiveWeaponSafely(WeaponGroupAPI group) {
        if (group == null || group.getWeaponsCopy().isEmpty()) {
            return null;
        }
        try {
            return group.getActiveWeapon();
        } catch (IndexOutOfBoundsException ex) {
            return null;
        }
    }

    private void updateExclusiveFiringControl() {
        if (ship == null || ship.getSelectedGroupAPI() == null || sourceFollow == null) {
            return;
        }

        boolean sourceSelected = source != null && ship.getSelectedGroupAPI().getWeaponsCopy().contains(source);
        boolean followSelected = ship.getSelectedGroupAPI().getWeaponsCopy().contains(sourceFollow);
        if (sourceSelected && !followSelected) {
            sourceFollow.setForceNoFireOneFrame(true);
        } else if (followSelected && !sourceSelected && source != null) {
            source.setForceNoFireOneFrame(true);
        } else if (sourceSelected && followSelected && source != null) {
            if (source.getCooldownRemaining() > 0f) {
                source.setForceNoFireOneFrame(true);
            } else {
                sourceFollow.setForceNoFireOneFrame(true);
            }
        } else {
            sourceFollow.setForceNoFireOneFrame(true);
        }
    }

    private void initializeFollowWeapon(WeaponAPI weapon) {
        if (sourceFollow == null || weapon.getSlot() == null || sourceFollow.getSlot() == null) {
            return;
        }

        Vector2f diffFollow = new Vector2f();
        Vector2f.sub(weapon.getSlot().getLocation(), sourceFollow.getSlot().getLocation(), diffFollow);

        Vector2f decorativeFirePoint = null;
        if (weapon.getSpec() != null && !weapon.getSpec().getTurretFireOffsets().isEmpty()) {
            decorativeFirePoint = weapon.getSpec().getTurretFireOffsets().get(0);
        }
        syncFirePoint(sourceFollow, diffFollow, decorativeFirePoint);
        hideFollowWeapon();
    }

    private void hideFollowWeapon() {
        if (sourceFollow == null) {
            return;
        }

        Color transparent = Moci_ColorData.NONE;
        if (sourceFollow.getSprite() != null) {
            sourceFollow.getSprite().setColor(transparent);
        }
        if (sourceFollow.getBarrelSpriteAPI() != null) {
            sourceFollow.getBarrelSpriteAPI().setColor(transparent);
        }
        if (sourceFollow.getUnderSpriteAPI() != null) {
            sourceFollow.getUnderSpriteAPI().setColor(transparent);
        }
        if (sourceFollow.getGlowSpriteAPI() != null) {
            sourceFollow.getGlowSpriteAPI().setColor(transparent);
        }
        if (sourceFollow.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : sourceFollow.getMissileRenderData()) {
                render.getSprite().setWidth(0f);
                render.getSprite().setHeight(0f);
            }
        }
    }

    private static void syncFirePoint(WeaponAPI sync, Vector2f diff, Vector2f override) {
        if (sync == null || diff == null) {
            return;
        }
        if (override == null) {
            override = new Vector2f();
        }

        Vector2f.add(override, diff, diff);
        sync.ensureClonedSpec();
        int turretOffsets = sync.getSpec().getTurretFireOffsets().size();
        int hardpointOffsets = sync.getSpec().getHardpointFireOffsets().size();
        int hiddenOffsets = sync.getSpec().getHiddenFireOffsets().size();

        sync.getSpec().getTurretFireOffsets().clear();
        sync.getSpec().getHardpointFireOffsets().clear();
        sync.getSpec().getHiddenFireOffsets().clear();
        for (int i = 0; i < turretOffsets; i++) {
            sync.getSpec().getTurretFireOffsets().add(new Vector2f(diff));
        }
        for (int i = 0; i < hardpointOffsets; i++) {
            sync.getSpec().getHardpointFireOffsets().add(new Vector2f(diff));
        }
        for (int i = 0; i < hiddenOffsets; i++) {
            sync.getSpec().getHiddenFireOffsets().add(new Vector2f(diff));
        }
    }

    private void faceShield(float angle) {
        if (isShieldPresent()) {
            shield.setFacing(angle);
        }
    }

    private boolean isShieldPresent() {
        return shield != null && shield.isAlive() && !shield.isHulk() && shield.getHitpoints() > 0f;
    }

    private boolean isNonMsShape(ShipAPI ship) {
        if (Boolean.TRUE.equals(ship.getCustomData().get(Moci_MechTransformController.CUSTOM_PARTS_HIDDEN))
                || Boolean.TRUE.equals(ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORMED))) {
            return true;
        }
        Object state = ship.getCustomData().get(Moci_MechTransformController.CUSTOM_TRANSFORM_STATE);
        return state != null && !"NORMAL".equals(String.valueOf(state));
    }

    private float normalizeAngle(float angle) {
        while (angle > 180f) {
            angle -= 360f;
        }
        while (angle < -180f) {
            angle += 360f;
        }
        return angle;
    }
}
