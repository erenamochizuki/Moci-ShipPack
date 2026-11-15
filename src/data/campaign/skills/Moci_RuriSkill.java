package data.campaign.skills;

import java.awt.Color;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.ShipSkillEffect;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.util.Misc;

import data.scripts.util.Moci_TextLoader;

/**
 * 星野琉璃专属被动技能「电子妖精」。
 *
 * <p>基础：提高电子战评级与自动开火瞄准精度。<br>
 * 精英：周期性入侵附近幅能最高的敌舰，短时间压低其武器射速与机动。</p>
 */
public class Moci_RuriSkill {
    public static final String SKILL_ID = "Moci_RuriSkill";
    private static final String TEXT_ID = SKILL_ID;
    private static final Logger LOG = Global.getLogger(Moci_RuriSkill.class);
    private static final String LOG_PREFIX = "[MOCI-SKILL] ";

    public static final float ECM_BONUS = 5f;
    public static final float AUTOFIRE_ACCURACY_BONUS = 0.5f;

    /** 精英：入侵间隔、范围、持续时间，以及对目标射速/机动的降低幅度（%）。 */
    public static final float HACK_INTERVAL = 20f;
    public static final float HACK_RANGE = 1200f;
    public static final float HACK_DURATION = 5f;
    public static final float HACK_PENALTY = 30f;

    public static class Level1 implements ShipSkillEffect {
        @Override
        public void apply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id, float level) {
            stats.getDynamic().getMod(Stats.ELECTRONIC_WARFARE_FLAT).modifyFlat(id, ECM_BONUS);
            stats.getAutofireAimAccuracy().modifyFlat(id, AUTOFIRE_ACCURACY_BONUS);
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            stats.getDynamic().getMod(Stats.ELECTRONIC_WARFARE_FLAT).unmodify(id);
            stats.getAutofireAimAccuracy().unmodify(id);
        }

        @Override
        public String getEffectDescription(float level) {
            return Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.level1",
                    Moci_TextLoader.mapOf(
                            "ecm", String.valueOf(Math.round(ECM_BONUS)),
                            "accuracy", String.valueOf(Math.round(AUTOFIRE_ACCURACY_BONUS * 100f))));
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
                HackListener.attach((ShipAPI) stats.getEntity());
            }
        }

        @Override
        public void unapply(MutableShipStatsAPI stats, ShipAPI.HullSize hullSize, String id) {
            if (stats.getEntity() instanceof ShipAPI) {
                HackListener.detach((ShipAPI) stats.getEntity());
            }
        }

        @Override
        public String getEffectDescription(float level) {
            return Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.level2",
                    Moci_TextLoader.mapOf(
                            "interval", String.valueOf(Math.round(HACK_INTERVAL)),
                            "range", String.valueOf(Math.round(HACK_RANGE)),
                            "duration", String.valueOf(Math.round(HACK_DURATION)),
                            "penalty", String.valueOf(Math.round(HACK_PENALTY))));
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

    private static final class HackListener implements AdvanceableListener {
        private static final Color TEXT_COLOR = new Color(140, 220, 255, 255);

        private final ShipAPI ship;
        private final String id;
        /** 首次入侵稍微提前，避免开战后要干等一整个间隔。 */
        private float untilNextHack = HACK_INTERVAL * 0.5f;
        private ShipAPI hackedTarget;
        private float hackRemaining;

        static void attach(ShipAPI ship) {
            if (ship == null || ship.hasListenerOfClass(HackListener.class)) return;
            ship.addListener(new HackListener(ship));
        }

        static void detach(ShipAPI ship) {
            if (ship == null) return;
            for (HackListener listener : ship.getListeners(HackListener.class)) {
                listener.releaseTarget();
            }
            ship.removeListenerOfClass(HackListener.class);
        }

        private HackListener(ShipAPI ship) {
            this.ship = ship;
            this.id = "Moci_RuriSkill_" + ship.getId();
        }

        @Override
        public void advance(float amount) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused()) return;

            // 宿主阵亡或离场后引擎不会替我们摘修饰符，必须在这里自己放掉目标。
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
                releaseTarget();
                return;
            }

            if (hackedTarget != null) {
                hackRemaining -= amount;
                if (hackRemaining <= 0f || !hackedTarget.isAlive() || hackedTarget.isHulk()) {
                    releaseTarget();
                }
            }

            untilNextHack -= amount;
            if (untilNextHack > 0f) return;

            ShipAPI target = pickTarget(engine);
            if (target == null) {
                // 附近没有目标：稍后再找，而不是空转整个间隔。
                untilNextHack = 1f;
                return;
            }
            untilNextHack = HACK_INTERVAL;
            hack(engine, target);
        }

        private ShipAPI pickTarget(CombatEngineAPI engine) {
            ShipAPI best = null;
            float bestFlux = -1f;
            float rangeSq = HACK_RANGE * HACK_RANGE;
            for (ShipAPI other : engine.getShips()) {
                if (other == null || other == ship || other.getOwner() == ship.getOwner()) continue;
                if (other.getOwner() == 100 || !other.isAlive() || other.isHulk()) continue;
                if (other.isFighter() || other.isShuttlePod() || other.isPhased()) continue;
                if (Misc.getDistanceSq(ship.getLocation(), other.getLocation()) > rangeSq) continue;
                float flux = other.getFluxLevel();
                if (flux > bestFlux) {
                    bestFlux = flux;
                    best = other;
                }
            }
            return best;
        }

        private void hack(CombatEngineAPI engine, ShipAPI target) {
            releaseTarget();
            hackedTarget = target;
            hackRemaining = HACK_DURATION;

            float mult = 1f - HACK_PENALTY / 100f;
            MutableShipStatsAPI stats = target.getMutableStats();
            stats.getBallisticRoFMult().modifyMult(id, mult);
            stats.getEnergyRoFMult().modifyMult(id, mult);
            stats.getMissileRoFMult().modifyMult(id, mult);
            stats.getAcceleration().modifyMult(id, mult);
            stats.getDeceleration().modifyMult(id, mult);
            stats.getMaxTurnRate().modifyMult(id, mult);
            stats.getTurnAcceleration().modifyMult(id, mult);

            engine.addFloatingText(target.getLocation(),
                    Moci_TextLoader.getText(TEXT_ID, "status.hacked", "系统被入侵"),
                    24f, TEXT_COLOR, target, 0.5f, 1.5f);
            LOG.info(LOG_PREFIX + "电子妖精入侵 host=" + ship.getId() + " target=" + target.getId()
                    + " flux=" + Math.round(target.getFluxLevel() * 100f) + "%");
        }

        private void releaseTarget() {
            if (hackedTarget == null) return;
            MutableShipStatsAPI stats = hackedTarget.getMutableStats();
            stats.getBallisticRoFMult().unmodify(id);
            stats.getEnergyRoFMult().unmodify(id);
            stats.getMissileRoFMult().unmodify(id);
            stats.getAcceleration().unmodify(id);
            stats.getDeceleration().unmodify(id);
            stats.getMaxTurnRate().unmodify(id);
            stats.getTurnAcceleration().unmodify(id);
            hackedTarget = null;
            hackRemaining = 0f;
        }
    }
}
