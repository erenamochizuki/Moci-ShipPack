package data.shipsystems.scripts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.impl.combat.dweller.RiftLightningEffect;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;

import data.scripts.util.Moci_Util;

public class Moci_FluxRiftLightningSystem extends BaseShipSystemScript {

    private static final float FLUX_PER_ARC = 500f;
    private static final float ARC_INTERVAL = 0.2f;
    private static final float TARGET_RANGE = 800f;
    private static final float RANDOM_POINT_MIN_RANGE = 400f;
    private static final float RANDOM_POINT_MAX_RANGE = 600f;
    private static final float DAMAGE = 400f;
    private static final float EMP_DAMAGE = 400f;

    private static final float ARC_THICKNESS = 80f;
    private static final float ARC_CORE_WIDTH = 40f;
    private static final Color ARC_COLOR = RiftLightningEffect.RIFT_LIGHTNING_COLOR;
    private static final Color ARC_CORE_COLOR = new Color(255, 255, 255, 255);
    private static final Color SPARK_COLOR = new Color(255, 80, 80, 220);
    private static final Vector2f ZERO = new Vector2f();

    private static final String DATA_PREFIX = "moci_flux_rift_lightning_";

    private static class SystemStateData {
        float elapsed = ARC_INTERVAL;
        final List<String> remainingSlotIds = new ArrayList<String>();
    }

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        if (!(stats.getEntity() instanceof ShipAPI)) {
            return;
        }

        ShipAPI ship = (ShipAPI) stats.getEntity();
        CombatEngineAPI engine = Global.getCombatEngine();
        if (ship == null || engine == null || engine.isPaused()) {
            return;
        }

        boolean active = state == State.IN || state == State.ACTIVE || state == State.OUT;
        if (!active) {
            resetData(engine, ship);
            return;
        }

        if (!ship.isAlive() || ship.isHulk() || ship.getFluxTracker().getCurrFlux() < FLUX_PER_ARC) {
            deactivate(ship);
            return;
        }

        SystemStateData data = getData(engine, ship);
        data.elapsed += engine.getElapsedInLastFrame();
        while (data.elapsed >= ARC_INTERVAL) {
            data.elapsed -= ARC_INTERVAL;
            if (!fireOnce(engine, ship, data)) {
                deactivate(ship);
                return;
            }
        }
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        if (stats.getEntity() instanceof ShipAPI && Global.getCombatEngine() != null) {
            resetData(Global.getCombatEngine(), (ShipAPI) stats.getEntity());
        }
    }

    private boolean fireOnce(CombatEngineAPI engine, ShipAPI ship, SystemStateData data) {
        if (ship.getFluxTracker().getCurrFlux() < FLUX_PER_ARC) {
            return false;
        }

        TargetResult target = findTarget(engine, ship);
        WeaponSlotAPI slot = pickSlot(ship, data, target.point);
        Vector2f from = slot == null ? new Vector2f(ship.getLocation()) : slot.computePosition(ship);
        Vector2f to = target.point;

        ship.getFluxTracker().decreaseFlux(FLUX_PER_ARC);
        spawnStartSpark(engine, ship, from);
        spawnRiftArc(engine, ship, from, to);

        if (target.entity != null && engine.isEntityInPlay(target.entity)) {
            engine.applyDamage(target.entity, target.entity.getLocation(), DAMAGE, DamageType.ENERGY,
                    EMP_DAMAGE, false, false, ship, true);
        }

        Global.getSoundPlayer().playSound("Moci_Lightting", 1f, 0.6f, from, ship.getVelocity());
        return true;
    }

    private void spawnStartSpark(CombatEngineAPI engine, ShipAPI ship, Vector2f from) {
        engine.addHitParticle(from, ship.getVelocity(), 70f, 1f, 0.12f, SPARK_COLOR);
        engine.addSmoothParticle(from, ship.getVelocity(), 35f, 0.8f, 0.08f, SPARK_COLOR);
    }

    private void spawnRiftArc(CombatEngineAPI engine, ShipAPI ship, Vector2f from, Vector2f to) {
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

    private WeaponSlotAPI pickSlot(final ShipAPI ship, SystemStateData data, final Vector2f targetPoint) {
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

    private List<WeaponSlotAPI> getSystemSlots(ShipAPI ship) {
        List<WeaponSlotAPI> result = new ArrayList<WeaponSlotAPI>();
        for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
            if (slot.isSystemSlot()) {
                result.add(slot);
            }
        }
        return result;
    }

    private TargetResult findTarget(CombatEngineAPI engine, ShipAPI ship) {
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

    private ShipAPI findClosestEnemyShip(CombatEngineAPI engine, ShipAPI ship, boolean fightersOnly) {
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

    private MissileAPI findClosestEnemyMissile(CombatEngineAPI engine, ShipAPI ship) {
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

    private Vector2f randomFallbackPoint(ShipAPI ship) {
        float range = RANDOM_POINT_MIN_RANGE + (RANDOM_POINT_MAX_RANGE - RANDOM_POINT_MIN_RANGE) * (float) Math.random();
        Vector2f point = Misc.getUnitVectorAtDegreeAngle((float) Math.random() * 360f);
        point.scale(range);
        Vector2f.add(ship.getLocation(), point, point);
        return point;
    }

    private float getDistanceSquared(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    private SystemStateData getData(CombatEngineAPI engine, ShipAPI ship) {
        Map<String, Object> custom = engine.getCustomData();
        String key = getDataKey(ship);
        Object existing = custom.get(key);
        if (existing instanceof SystemStateData) {
            return (SystemStateData) existing;
        }
        SystemStateData data = new SystemStateData();
        custom.put(key, data);
        return data;
    }

    private void resetData(CombatEngineAPI engine, ShipAPI ship) {
        engine.getCustomData().remove(getDataKey(ship));
    }

    private String getDataKey(ShipAPI ship) {
        return DATA_PREFIX + ship.getId();
    }

    private void deactivate(ShipAPI ship) {
        ShipSystemAPI system = ship.getSystem();
        if (system != null && system.isActive()) {
            system.deactivate();
        }
    }

    @Override
    public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
        return ship != null && ship.isAlive() && !ship.isHulk() &&
                !ship.isShipSystemDisabled() &&
                ship.getFluxTracker().getCurrFlux() >= FLUX_PER_ARC;
    }

    @Override
    public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        if (ship.getFluxTracker().getCurrFlux() < FLUX_PER_ARC) {
            return "LOW FLUX";
        }
        return null;
    }

    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (index == 0) {
            return new StatusData("每0.2秒消耗500幅能释放裂隙电弧", false);
        }
        if (index == 1) {
            return new StatusData("400能量伤害 / 400 EMP", false);
        }
        return null;
    }

    private static class TargetResult {
        final CombatEntityAPI entity;
        final Vector2f point;

        TargetResult(CombatEntityAPI entity, Vector2f point) {
            this.entity = entity;
            this.point = point == null ? new Vector2f(ZERO) : point;
        }
    }
}
