package data.campaign.skills;

import java.awt.Color;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.ShipSkillEffect;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;

import data.scripts.util.Moci_TextLoader;

/**
 * 冲田十三专属被动技能「不屈的舰长」。
 *
 * <p>基础：舰体损失越多，受到的伤害与武器幅能消耗越低。<br>
 * 精英：每场战斗一次，舰体首次跌破阈值时清空幅能并获得短时间的大幅减伤。</p>
 */
public class Moci_OkitaSkill {
    public static final String SKILL_ID = "Moci_OkitaSkill";
    private static final String TEXT_ID = SKILL_ID;
    private static final Logger LOG = Global.getLogger(Moci_OkitaSkill.class);
    private static final String LOG_PREFIX = "[MOCI-SKILL] ";

    /** 每损失 1% 舰体降低的受到伤害（%）及其上限。 */
    public static final float DAMAGE_REDUCTION_PER_HULL_PERCENT = 0.5f;
    public static final float DAMAGE_REDUCTION_MAX = 30f;
    /** 每损失 1% 舰体降低的武器幅能消耗（%）及其上限。 */
    public static final float FLUX_COST_REDUCTION_PER_HULL_PERCENT = 0.4f;
    public static final float FLUX_COST_REDUCTION_MAX = 20f;

    /** 精英：触发阈值（舰体比例）、持续时间与期间减伤（%）。 */
    public static final float LAST_STAND_HULL_THRESHOLD = 0.3f;
    public static final float LAST_STAND_DURATION = 10f;
    public static final float LAST_STAND_DAMAGE_REDUCTION = 50f;

    private static final String ELITE_KEY = "Moci_OkitaSkill_Elite";

    public static class Level1 implements ShipSkillEffect {
        @Override
        public void apply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id, float level) {
            if (stats.getEntity() instanceof ShipAPI) {
                OkitaListener.attach((ShipAPI) stats.getEntity());
            }
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            if (stats.getEntity() instanceof ShipAPI) {
                OkitaListener.detach((ShipAPI) stats.getEntity());
            }
        }

        @Override
        public String getEffectDescription(float level) {
            return Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.level1",
                    Moci_TextLoader.mapOf(
                            "damage_per", trim(DAMAGE_REDUCTION_PER_HULL_PERCENT),
                            "damage_max", trim(DAMAGE_REDUCTION_MAX),
                            "flux_per", trim(FLUX_COST_REDUCTION_PER_HULL_PERCENT),
                            "flux_max", trim(FLUX_COST_REDUCTION_MAX)));
        }

        @Override
        public String getEffectPerLevelDescription() {
            return null;
        }

        @Override
        public ScopeDescription getScopeDescription() {
            return ScopeDescription.PILOTED_SHIP;
        }
    }

    public static class Level2 implements ShipSkillEffect {
        @Override
        public void apply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id, float level) {
            if (stats.getEntity() instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) stats.getEntity();
                ship.setCustomData(ELITE_KEY, true);
                OkitaListener.attach(ship);
            }
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            if (stats.getEntity() instanceof ShipAPI) {
                ((ShipAPI) stats.getEntity()).removeCustomData(ELITE_KEY);
            }
        }

        @Override
        public String getEffectDescription(float level) {
            return Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.level2",
                    Moci_TextLoader.mapOf(
                            "threshold", trim(LAST_STAND_HULL_THRESHOLD * 100f),
                            "duration", trim(LAST_STAND_DURATION),
                            "reduction", trim(LAST_STAND_DAMAGE_REDUCTION)));
        }

        @Override
        public String getEffectPerLevelDescription() {
            return null;
        }

        @Override
        public ScopeDescription getScopeDescription() {
            return ScopeDescription.PILOTED_SHIP;
        }
    }

    private static String trim(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) {
            return String.valueOf(Math.round(value));
        }
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private static final class OkitaListener implements AdvanceableListener {
        private static final Color TEXT_COLOR = new Color(255, 210, 120, 255);

        private final ShipAPI ship;
        private final String id;
        private final String lastStandId;
        private final Object statusKey = new Object();
        private boolean lastStandUsed;
        private float lastStandRemaining;

        static void attach(ShipAPI ship) {
            if (ship == null || ship.hasListenerOfClass(OkitaListener.class)) return;
            ship.addListener(new OkitaListener(ship));
        }

        static void detach(ShipAPI ship) {
            if (ship == null) return;
            for (OkitaListener listener : ship.getListeners(OkitaListener.class)) {
                listener.clear();
            }
            ship.removeListenerOfClass(OkitaListener.class);
        }

        private OkitaListener(ShipAPI ship) {
            this.ship = ship;
            this.id = "Moci_OkitaSkill_" + ship.getId();
            this.lastStandId = id + "_last_stand";
        }

        @Override
        public void advance(float amount) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused()) return;
            if (!ship.isAlive() || ship.isHulk()) {
                clear();
                return;
            }

            float lostPercent = Math.max(0f, Math.min(1f, 1f - ship.getHullLevel())) * 100f;
            float damageReduction = Math.min(DAMAGE_REDUCTION_MAX, lostPercent * DAMAGE_REDUCTION_PER_HULL_PERCENT);
            float fluxReduction = Math.min(FLUX_COST_REDUCTION_MAX, lostPercent * FLUX_COST_REDUCTION_PER_HULL_PERCENT);

            MutableShipStatsAPI stats = ship.getMutableStats();
            float damageMult = 1f - damageReduction / 100f;
            stats.getHullDamageTakenMult().modifyMult(id, damageMult);
            stats.getArmorDamageTakenMult().modifyMult(id, damageMult);
            stats.getShieldDamageTakenMult().modifyMult(id, damageMult);
            float fluxMult = 1f - fluxReduction / 100f;
            stats.getBallisticWeaponFluxCostMod().modifyMult(id, fluxMult);
            stats.getEnergyWeaponFluxCostMod().modifyMult(id, fluxMult);
            stats.getMissileWeaponFluxCostMod().modifyMult(id, fluxMult);

            advanceLastStand(engine, amount);
        }

        private void advanceLastStand(CombatEngineAPI engine, float amount) {
            if (lastStandRemaining > 0f) {
                lastStandRemaining -= amount;
                if (lastStandRemaining <= 0f) {
                    clearLastStand();
                } else if (engine.getPlayerShip() == ship) {
                    engine.maintainStatusForPlayerShip(statusKey,
                            Global.getSettings().getSpriteName("icon", "Moci_skill1"),
                            Moci_TextLoader.getText(TEXT_ID, "status.title", "不屈的舰长"),
                            Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.last_stand",
                                    Moci_TextLoader.mapOf("seconds",
                                            String.valueOf((int) Math.ceil(lastStandRemaining)))),
                            false);
                }
                return;
            }

            if (lastStandUsed || !ship.getCustomData().containsKey(ELITE_KEY)) return;
            if (ship.getHullLevel() >= LAST_STAND_HULL_THRESHOLD) return;

            lastStandUsed = true;
            lastStandRemaining = LAST_STAND_DURATION;
            if (ship.getFluxTracker().isOverloaded()) {
                ship.getFluxTracker().stopOverload();
            }
            ship.getFluxTracker().setHardFlux(0f);
            ship.getFluxTracker().setCurrFlux(0f);

            float mult = 1f - LAST_STAND_DAMAGE_REDUCTION / 100f;
            MutableShipStatsAPI stats = ship.getMutableStats();
            stats.getHullDamageTakenMult().modifyMult(lastStandId, mult);
            stats.getArmorDamageTakenMult().modifyMult(lastStandId, mult);
            stats.getShieldDamageTakenMult().modifyMult(lastStandId, mult);

            engine.addFloatingText(ship.getLocation(),
                    Moci_TextLoader.getText(TEXT_ID, "status.last_stand_trigger", "我们还没有输！"),
                    28f, TEXT_COLOR, ship, 0.5f, 2f);
            LOG.info(LOG_PREFIX + "不屈的舰长触发 ship=" + ship.getId()
                    + " hull=" + Math.round(ship.getHullLevel() * 100f) + "%");
        }

        private void clearLastStand() {
            lastStandRemaining = 0f;
            MutableShipStatsAPI stats = ship.getMutableStats();
            stats.getHullDamageTakenMult().unmodify(lastStandId);
            stats.getArmorDamageTakenMult().unmodify(lastStandId);
            stats.getShieldDamageTakenMult().unmodify(lastStandId);
        }

        private void clear() {
            MutableShipStatsAPI stats = ship.getMutableStats();
            stats.getHullDamageTakenMult().unmodify(id);
            stats.getArmorDamageTakenMult().unmodify(id);
            stats.getShieldDamageTakenMult().unmodify(id);
            stats.getBallisticWeaponFluxCostMod().unmodify(id);
            stats.getEnergyWeaponFluxCostMod().unmodify(id);
            stats.getMissileWeaponFluxCostMod().unmodify(id);
            clearLastStand();
        }
    }
}
