package data.scripts.util;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.dweller.RiftLightningEffect;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;

public final class Moci_FluxRiftLightningUtil {

    public static final float FLUX_PER_ARC = 500f;
    public static final float TARGET_RANGE = 800f;
    public static final float RANDOM_POINT_MIN_RANGE = 400f;
    public static final float RANDOM_POINT_MAX_RANGE = 600f;
    public static final float DAMAGE = 400f;
    public static final float EMP_DAMAGE = 400f;

    private static final float ARC_THICKNESS = 80f;
    private static final float ARC_CORE_WIDTH = 40f;
    private static final Color ARC_COLOR = RiftLightningEffect.RIFT_LIGHTNING_COLOR;
    private static final Color ARC_CORE_COLOR = new Color(255, 255, 255, 255);
    private static final Color SPARK_COLOR = new Color(255, 80, 80, 220);

    private Moci_FluxRiftLightningUtil() {
    }

    public static class StateData {
        public final List<String> remainingSlotIds = new ArrayList<String>();
    }

    public static void fireOnce(CombatEngineAPI engine, ShipAPI ship, StateData data) {
        if (engine == null || ship == null || data == null) {
            return;
        }

        TargetResult target = findTarget(engine, ship);
        WeaponSlotAPI slot = pickSlot(ship, data, target.point);
        Vector2f from = slot == null ? new Vector2f(ship.getLocation()) : slot.computePosition(ship);

        spawnStartSpark(engine, ship, from);
        spawnRiftArc(engine, ship, from, target.point);

        if (target.entity != null && engine.isEntityInPlay(target.entity)) {
            engine.applyDamage(target.entity, target.entity.getLocation(), DAMAGE, DamageType.ENERGY,
                    EMP_DAMAGE, false, false, ship, true);
        }

        Global.getSoundPlayer().playSound("Moci_Lightting", 1f, 0.6f, from, ship.getVelocity());
    }

    private static void spawnStartSpark(CombatEngineAPI engine, ShipAPI ship, Vector2f from) {
        engine.addHitParticle(from, ship.getVelocity(), 70f, 1f, 0.12f, SPARK_COLOR);
        engine.addSmoothParticle(from, ship.getVelocity(), 35f, 0.8f, 0.08f, SPARK_COLOR);
    }

    private static void spawnRiftArc(CombatEngineAPI engine, ShipAPI ship, Vector2f from, Vector2f to) {
        float dist = Misc.getDistance(from, to);

        EmpArcParams params = new EmpArcParams();
        params.segmentLengthMult = 8f;
        params.zigZagReductionFactor = 0.15f;
        params.fadeOutDist = 50f;
        params.minFadeOutMult = 10f;
        params.flickerRateMult = 0.3f;
        params.movementDurOverride = Math.max(0.05f, dist / RiftLightningEffect.RIFT_LIGHTNING_SPEED);

        EmpArcEntityAPI arc = engine.spawnEmpArcVisual(from, ship, to, null,
                ARC_THICKNESS, ARC_COLOR, ARC_CORE_COLOR, params);
        arc.setCoreWidthOverride(ARC_CORE_WIDTH);
        arc.setRenderGlowAtStart(false);
        arc.setFadedOutAtStart(true);
        arc.setSingleFlickerMode(true);
    }

    private static WeaponSlotAPI pickSlot(final ShipAPI ship, StateData data, final Vector2f targetPoint) {
        List<WeaponSlotAPI> slots = getSystemSlots(ship);
        if (slots.isEmpty()) {
            data.remainingSlotIds.clear();
            return null;
        }

        if (data.remainingSlotIds.isEmpty()) {
            Collections.sort(slots, new Comparator<WeaponSlotAPI>() {
                @Override
                public int compare(WeaponSlotAPI a, WeaponSlotAPI b) {
                    float da = Misc.getDistance(a.computePosition(ship), targetPoint);
                    float db = Misc.getDistance(b.computePosition(ship), targetPoint);
                    return Float.compare(da, db);
                }
            });
            for (WeaponSlotAPI slot : slots) {
                data.remainingSlotIds.add(slot.getId());
            }
        }

        String id = data.remainingSlotIds.remove(0);
        for (WeaponSlotAPI slot : slots) {
            if (id.equals(slot.getId())) {
                return slot;
            }
        }
        return slots.get(0);
    }

    private static List<WeaponSlotAPI> getSystemSlots(ShipAPI ship) {
        List<WeaponSlotAPI> result = new ArrayList<WeaponSlotAPI>();
        for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
            if (slot.isSystemSlot()) {
                result.add(slot);
            }
        }
        return result;
    }

    private static TargetResult findTarget(CombatEngineAPI engine, ShipAPI ship) {
        CombatEntityAPI target = findClosestEnemyShip(engine, ship, false);
        if (target == null) {
            target = findClosestEnemyShip(engine, ship, true);
        }
        if (target == null) {
            target = findClosestEnemyMissile(engine, ship);
        }
        if (target != null) {
            return new TargetResult(target, new Vector2f(target.getLocation()));
        }
        return new TargetResult(null, randomFallbackPoint(ship));
    }

    private static ShipAPI findClosestEnemyShip(CombatEngineAPI engine, ShipAPI ship, boolean fightersOnly) {
        ShipAPI closest = null;
        float best = TARGET_RANGE * TARGET_RANGE;
        for (ShipAPI other : Moci_Util.getEnemyShipsInRange(ship.getLocation(), TARGET_RANGE, ship.getOwner())) {
            if (other == null || other == ship || !other.isAlive() || other.isHulk()) {
                continue;
            }
            if (other.isFighter() != fightersOnly) {
                continue;
            }
            float distSq = getDistanceSquared(ship.getLocation(), other.getLocation());
            if (distSq <= best) {
                best = distSq;
                closest = other;
            }
        }
        return closest;
    }

    private static MissileAPI findClosestEnemyMissile(CombatEngineAPI engine, ShipAPI ship) {
        MissileAPI closest = null;
        float best = TARGET_RANGE * TARGET_RANGE;
        for (MissileAPI missile : Moci_Util.getEnemyMissilesInRange(ship.getLocation(), TARGET_RANGE, ship.getOwner())) {
            if (missile == null || missile.isFading()) {
                continue;
            }
            float distSq = getDistanceSquared(ship.getLocation(), missile.getLocation());
            if (distSq <= best) {
                best = distSq;
                closest = missile;
            }
        }
        return closest;
    }

    private static Vector2f randomFallbackPoint(ShipAPI ship) {
        float range = RANDOM_POINT_MIN_RANGE + (RANDOM_POINT_MAX_RANGE - RANDOM_POINT_MIN_RANGE) * (float) Math.random();
        Vector2f point = Misc.getUnitVectorAtDegreeAngle((float) Math.random() * 360f);
        point.scale(range);
        Vector2f.add(ship.getLocation(), point, point);
        return point;
    }

    private static float getDistanceSquared(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    private static class TargetResult {
        final CombatEntityAPI entity;
        final Vector2f point;

        TargetResult(CombatEntityAPI entity, Vector2f point) {
            this.entity = entity;
            this.point = point == null ? new Vector2f() : point;
        }
    }
}
