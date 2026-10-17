package data.hullmods;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier;
import com.fs.starfarer.api.impl.combat.dweller.RiftLightningEffect;
import com.fs.starfarer.api.util.Misc;

import data.scripts.util.Moci_Util;

public class Moci_ChainLightningCapacitor extends BaseHullMod {

    public static final String ID = "Moci_chain_lightning_capacitor";
    public static final String OVERLOAD_ID = "Moci_chain_lightning_overload";
    public static final String BRANCHING_ID = "Moci_chain_lightning_branching";

    public static final float MAX_CHARGE = 100f;
    public static final float BASE_CHARGE_PER_SECOND = 1f;
    public static final float MAX_DAMAGE_CHARGE_PER_SECOND = 25f;
    public static final float OVERLOAD_CHARGE_PER_SECOND_MULT = 1.5f;
    public static final float BASE_DAMAGE_THRESHOLD = 150f;
    public static final float OVERLOAD_DAMAGE_THRESHOLD_BONUS = 50f;
    public static final float BRANCHING_DAMAGE_THRESHOLD_BONUS = 75f;

    public static final float PRIMARY_RANGE = 800f;
    public static final float SECONDARY_RANGE = 400f;
    public static final int BASE_BOUNCES = 4;
    public static final int OVERLOAD_BOUNCE_PENALTY = 2;
    public static final int BRANCHING_BOUNCE_BONUS = 1;
    public static final float BASE_DAMAGE_DECAY = 0.5f;
    public static final float BRANCHING_DAMAGE_DECAY = 0.25f;
    public static final float BASE_DAMAGE = 400f;
    public static final float BASE_EMP_DAMAGE = 400f;
    public static final float OVERLOAD_DAMAGE_MULT = 1.5f;

    private static final String LISTENER_KEY = "moci_chain_lightning_capacitor_listener";
    private static final String RENDER_KEY = "moci_chain_lightning_capacitor_renderer";
    private static final String STATUS_KEY = "moci_chain_lightning_capacitor_status";

    private static final float ARC_THICKNESS = 80f;
    private static final float ARC_CORE_WIDTH = 40f;
    private static final Color ARC_COLOR = RiftLightningEffect.RIFT_LIGHTNING_COLOR;
    private static final Color ARC_CORE_COLOR = new Color(255, 255, 255, 255);
    private static final Color SPARK_COLOR = new Color(255, 80, 80, 220);
    private static final Color BAR_BACK_COLOR = new Color(20, 14, 16, 180);
    private static final Color BAR_BORDER_COLOR = new Color(255, 150, 130, 220);
    private static final Color BAR_FILL_COLOR = new Color(255, 80, 80, 230);
    private static final Color BAR_GLOW_COLOR = new Color(255, 220, 190, 180);

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (ship == null || ship.isFighter() || ship.isStationModule()) {
            return;
        }

        ChainLightningListener listener = getOrCreateListener(ship);
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null && !ship.getCustomData().containsKey(RENDER_KEY)) {
            ChainLightningBarRenderer renderer = new ChainLightningBarRenderer(ship, listener);
            engine.addLayeredRenderingPlugin(renderer);
            ship.setCustomData(RENDER_KEY, renderer);
        }
    }

    private ChainLightningListener getOrCreateListener(ShipAPI ship) {
        Object existing = ship.getCustomData().get(LISTENER_KEY);
        if (existing instanceof ChainLightningListener) {
            return (ChainLightningListener) existing;
        }

        if (ship.hasListenerOfClass(ChainLightningListener.class)) {
            ChainLightningListener listener = ship.getListeners(ChainLightningListener.class).get(0);
            ship.setCustomData(LISTENER_KEY, listener);
            return listener;
        }

        ChainLightningListener listener = new ChainLightningListener(ship);
        ship.addListener(listener);
        ship.setCustomData(LISTENER_KEY, listener);
        return listener;
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) {
            return "" + (int) MAX_CHARGE;
        }
        if (index == 1) {
            return "" + (int) BASE_CHARGE_PER_SECOND;
        }
        if (index == 2) {
            return "" + (int) BASE_DAMAGE_THRESHOLD;
        }
        if (index == 3) {
            return "" + (int) PRIMARY_RANGE;
        }
        if (index == 4) {
            return "" + (int) SECONDARY_RANGE;
        }
        if (index == 5) {
            return "" + (int) BASE_DAMAGE;
        }
        if (index == 6) {
            return "" + BASE_BOUNCES;
        }
        if (index == 7) {
            return "" + (int) (BASE_DAMAGE_DECAY * 100f) + "%";
        }
        return null;
    }

    public static class ChainLightningListener implements AdvanceableListener, DamageDealtModifier {
        private final ShipAPI ship;
        private float charge = 0f;
        private float damageChargeThisSecond = 0f;
        private float damageChargeWindow = 0f;

        public ChainLightningListener(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public void advance(float amount) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused() || !isShipValid()) {
                return;
            }

            damageChargeWindow += amount;
            while (damageChargeWindow >= 1f) {
                damageChargeWindow -= 1f;
                damageChargeThisSecond = 0f;
            }

            addCharge(getChargePerSecond() * amount);

            if (ship == engine.getPlayerShip()) {
                engine.maintainStatusForPlayerShip(STATUS_KEY,
                        "graphics/icons/hullsys/quantum_disruptor.png",
                        "连锁雷暴电容器",
                        "充能 " + (int) charge + "/" + (int) MAX_CHARGE,
                        false);
            }
        }

        @Override
        public String modifyDamageDealt(Object param, CombatEntityAPI target, DamageAPI damage,
                                        Vector2f point, boolean shieldHit) {
            if (!isShipValid() || target == null || damage == null) {
                return null;
            }
            if (target.getOwner() == ship.getOwner()) {
                return null;
            }
            WeaponAPI weapon = getWeapon(param);
            if (weapon == null) {
                return null;
            }

            float amount = damage.isDps() ? Math.max(0f, damage.getDpsDuration()) : 1f;
            float dealt = Math.max(0f, damage.computeDamageDealt(amount));
            if (dealt > 0f) {
                addDamageCharge(dealt / getDamageThreshold());
            }
            return null;
        }

        public float getChargeFraction() {
            return Math.max(0f, Math.min(1f, charge / MAX_CHARGE));
        }

        private void addCharge(float amount) {
            if (amount <= 0f) {
                return;
            }
            charge += amount;
            while (charge >= MAX_CHARGE) {
                charge -= MAX_CHARGE;
                fireChain();
            }
        }

        private void addDamageCharge(float amount) {
            if (amount <= 0f || damageChargeThisSecond >= MAX_DAMAGE_CHARGE_PER_SECOND) {
                return;
            }
            float available = MAX_DAMAGE_CHARGE_PER_SECOND - damageChargeThisSecond;
            float applied = Math.min(amount, available);
            damageChargeThisSecond += applied;
            addCharge(applied);
        }

        private void fireChain() {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || !isShipValid()) {
                return;
            }

            List<CombatEntityAPI> hit = new ArrayList<CombatEntityAPI>();
            ShipAPI target = findClosestEnemyShip(ship.getLocation(), PRIMARY_RANGE, hit);
            if (target == null) {
                return;
            }

            Vector2f from = new Vector2f(ship.getLocation());
            engine.addHitParticle(from, ship.getVelocity(), 90f, 1f, 0.16f, SPARK_COLOR);
            engine.addSmoothParticle(from, ship.getVelocity(), 45f, 0.9f, 0.12f, SPARK_COLOR);

            float damage = getChainDamage();
            float empDamage = getChainEmpDamage();
            fireArcAndDamage(engine, from, ship, target, damage, empDamage);
            hit.add(target);

            Vector2f lastPoint = new Vector2f(target.getLocation());
            float retain = 1f - getDamageDecay();
            float nextDamage = damage * retain;
            float nextEmpDamage = empDamage * retain;
            for (int i = 0; i < getBounceCount(); i++) {
                ShipAPI next = findClosestEnemyShip(lastPoint, SECONDARY_RANGE, hit);
                if (next == null) {
                    break;
                }
                fireArcAndDamage(engine, lastPoint, target, next, nextDamage, nextEmpDamage);
                hit.add(next);
                target = next;
                lastPoint = new Vector2f(next.getLocation());
                nextDamage *= retain;
                nextEmpDamage *= retain;
            }

            Global.getSoundPlayer().playSound("Moci_Lightting", 1f, 0.85f, ship.getLocation(), ship.getVelocity());
        }

        private void fireArcAndDamage(CombatEngineAPI engine, Vector2f from, CombatEntityAPI fromAnchor,
                                      CombatEntityAPI target, float damage, float empDamage) {
            Vector2f to = new Vector2f(target.getLocation());
            float dist = Misc.getDistance(from, to);

            EmpArcParams params = new EmpArcParams();
            params.segmentLengthMult = 8f;
            params.zigZagReductionFactor = 0.15f;
            params.fadeOutDist = 50f;
            params.minFadeOutMult = 10f;
            params.flickerRateMult = 0.3f;
            params.movementDurOverride = Math.max(0.05f, dist / RiftLightningEffect.RIFT_LIGHTNING_SPEED);

            EmpArcEntityAPI arc = engine.spawnEmpArcVisual(from, fromAnchor, to, target,
                    ARC_THICKNESS, ARC_COLOR, ARC_CORE_COLOR, params);
            arc.setCoreWidthOverride(ARC_CORE_WIDTH);
            arc.setRenderGlowAtStart(false);
            arc.setFadedOutAtStart(true);
            arc.setSingleFlickerMode(true);

            if (engine.isEntityInPlay(target)) {
                engine.applyDamage(target, target.getLocation(), damage, DamageType.ENERGY,
                        empDamage, false, false, ship, true);
            }
        }

        private ShipAPI findClosestEnemyShip(Vector2f center, float range, List<CombatEntityAPI> excluded) {
            ShipAPI closest = null;
            float best = range * range;
            for (ShipAPI other : Moci_Util.getEnemyShipsInRange(center, range, ship.getOwner())) {
                if (other == null || other == ship || !other.isAlive() || other.isHulk()) {
                    continue;
                }
                if (excluded.contains(other)) {
                    continue;
                }
                float distSq = getDistanceSquared(center, other.getLocation());
                if (distSq <= best) {
                    best = distSq;
                    closest = other;
                }
            }
            return closest;
        }

        private boolean isShipValid() {
            return ship != null && ship.isAlive() && !ship.isHulk();
        }

        private boolean hasOverloadTuning() {
            return ship.getVariant() != null && ship.getVariant().hasHullMod(OVERLOAD_ID);
        }

        private boolean hasBranchingTuning() {
            return ship.getVariant() != null && ship.getVariant().hasHullMod(BRANCHING_ID);
        }

        private float getChargePerSecond() {
            return hasOverloadTuning() ? BASE_CHARGE_PER_SECOND * OVERLOAD_CHARGE_PER_SECOND_MULT
                    : BASE_CHARGE_PER_SECOND;
        }

        private float getDamageThreshold() {
            if (hasOverloadTuning()) {
                return BASE_DAMAGE_THRESHOLD + OVERLOAD_DAMAGE_THRESHOLD_BONUS;
            }
            if (hasBranchingTuning()) {
                return BASE_DAMAGE_THRESHOLD + BRANCHING_DAMAGE_THRESHOLD_BONUS;
            }
            return BASE_DAMAGE_THRESHOLD;
        }

        private int getBounceCount() {
            int bounces = BASE_BOUNCES;
            if (hasOverloadTuning()) {
                bounces -= OVERLOAD_BOUNCE_PENALTY;
            } else if (hasBranchingTuning()) {
                bounces += BRANCHING_BOUNCE_BONUS;
            }
            return Math.max(0, bounces);
        }

        private float getDamageDecay() {
            return hasBranchingTuning() ? BRANCHING_DAMAGE_DECAY : BASE_DAMAGE_DECAY;
        }

        private float getChainDamage() {
            return hasOverloadTuning() ? BASE_DAMAGE * OVERLOAD_DAMAGE_MULT : BASE_DAMAGE;
        }

        private float getChainEmpDamage() {
            return hasOverloadTuning() ? BASE_EMP_DAMAGE * OVERLOAD_DAMAGE_MULT : BASE_EMP_DAMAGE;
        }

        private WeaponAPI getWeapon(Object param) {
            if (param instanceof DamagingProjectileAPI) {
                return ((DamagingProjectileAPI) param).getWeapon();
            }
            if (param instanceof BeamAPI) {
                return ((BeamAPI) param).getWeapon();
            }
            return null;
        }

        private float getDistanceSquared(Vector2f a, Vector2f b) {
            float dx = a.x - b.x;
            float dy = a.y - b.y;
            return dx * dx + dy * dy;
        }
    }

    private static class ChainLightningBarRenderer extends BaseCombatLayeredRenderingPlugin {
        private final ShipAPI ship;
        private final ChainLightningListener listener;

        ChainLightningBarRenderer(ShipAPI ship, ChainLightningListener listener) {
            this.ship = ship;
            this.listener = listener;
            this.layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER;
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER || ship == null || listener == null) {
                return;
            }
            if (!ship.isAlive() || ship.isHulk() || !viewport.isNearViewport(ship.getLocation(), getRenderRadius())) {
                return;
            }

            float view = Math.max(0.1f, viewport.getViewMult());
            float width = 78f / view;
            float height = 7f / view;
            float border = 1f / view;
            float yOffset = ship.getCollisionRadius() + 24f / view;
            float x = ship.getLocation().x - width * 0.5f;
            float y = ship.getLocation().y + yOffset;
            float fill = width * listener.getChargeFraction();
            float alpha = viewport.getAlphaMult();

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            drawRect(x - border, y - border, width + border * 2f, height + border * 2f, BAR_BORDER_COLOR, alpha);
            drawRect(x, y, width, height, BAR_BACK_COLOR, alpha);
            if (fill > 0f) {
                drawRect(x, y, fill, height, BAR_FILL_COLOR, alpha);
                drawRect(x, y + height * 0.55f, fill, height * 0.45f, BAR_GLOW_COLOR, alpha);
            }

            GL11.glPopAttrib();
        }

        private void drawRect(float x, float y, float width, float height, Color color, float alphaMult) {
            GL11.glColor4f(color.getRed() / 255f,
                    color.getGreen() / 255f,
                    color.getBlue() / 255f,
                    color.getAlpha() / 255f * alphaMult);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x + width, y);
            GL11.glVertex2f(x + width, y + height);
            GL11.glVertex2f(x, y + height);
            GL11.glEnd();
        }

        @Override
        public float getRenderRadius() {
            return ship == null ? 120f : ship.getCollisionRadius() + 160f;
        }

        @Override
        public EnumSet<CombatEngineLayers> getActiveLayers() {
            return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
        }

        @Override
        public boolean isExpired() {
            return ship == null || !ship.isAlive();
        }
    }
}
